package com.ahmadre.hinata.git;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Receives real provider webhooks (GitHub / GitLab / Bitbucket), verifies the
 * per-project signing secret, and turns push / pull-request / CI events into
 * live {@link GitDevInfo} — branches, commits, PR/MRs and builds linked to any
 * issue key referenced in the branch name, commit message or PR title. Push
 * commit messages additionally drive smart commits, and PR open/merge events
 * apply the project's automation. Nothing is emulated: an event only lands when
 * its signature verifies against the secret stored when the repo was connected.
 */
@Service
@RequiredArgsConstructor
public class GitWebhookService {

	private static final Logger log = LoggerFactory.getLogger(GitWebhookService.class);
	private static final Pattern ISSUE_KEY = Pattern.compile("[A-Z][A-Z0-9]+-\\d+");
	private static final String BRANCH_PREFIX = "refs/heads/";
	/** GitLab sends this all-zero SHA as {@code before} when a branch is first created. */
	private static final String NULL_SHA = "0000000000000000000000000000000000000000";
	private static final int MAX_COMMITS = 30;
	private static final int MAX_BUILDS = 20;

	private final ProjectRepository projects;
	private final GitDevInfoRepository devInfos;
	private final IssueService issues;
	private final GitService gitService;
	private final GitOAuthClient api;
	private final GitCommitLedger ledger;
	private final TokenCipher cipher;
	private final UserRepository users;
	// Self-contained mapper (the app registers no ObjectMapper bean to inject).
	private final ObjectMapper mapper = new ObjectMapper();

	// ─────────────────────────── GitHub ───────────────────────────

	public void handleGithub(byte[] body, String event, String signature) {
		JsonNode payload = parse(body);
		String[] or = splitOwnerRepo(payload.path("repository").path("full_name").asText(""));
		if (or == null) {
			return;
		}
		Matched m = resolve("github", or[0], or[1], g -> verifyGithub(body, signature, g));
		if (m == null) {
			return;
		}
		User actor = actor(m.repo());
		switch (event == null ? "" : event) {
			case "push" -> githubPush(m, payload, actor);
			case "create" -> githubCreate(m, payload, actor);
			case "pull_request" -> githubPr(m, payload, actor);
			case "workflow_run" -> githubBuild(m, payload);
			default -> { /* event we don't act on */ }
		}
	}

	private void githubPush(Matched m, JsonNode payload, User actor) {
		String branch = stripRef(payload.path("ref").asText(""));
		boolean created = payload.path("created").asBoolean(false);
		Instant now = Instant.now();
		recordBranch(m, branch, now);
		if (created) {
			applyPushAutomation(m.project(), keysIn(branch), PushRule.BRANCH, actor);
		}
		for (JsonNode c : payload.path("commits")) {
			String message = c.path("message").asText("");
			String sha = c.path("id").asText("");
			Instant at = instant(c.path("timestamp").asText(null), now);
			recordCommit(m, sha, message, at,
					c.path("verification").path("verified").asBoolean(false));
			applyCommitEffects(m, sha, message, actor);
		}
	}

	/** GitHub fires a dedicated {@code create} event when a branch/tag is created (e.g. via the API). */
	private void githubCreate(Matched m, JsonNode payload, User actor) {
		if (!"branch".equals(payload.path("ref_type").asText(""))) {
			return;
		}
		String branch = payload.path("ref").asText("");
		recordBranch(m, branch, Instant.now());
		applyPushAutomation(m.project(), keysIn(branch), PushRule.BRANCH, actor);
	}

	private void githubPr(Matched m, JsonNode payload, User actor) {
		JsonNode pr = payload.path("pull_request");
		String action = payload.path("action").asText("");
		boolean merged = pr.path("merged").asBoolean(false);
		boolean draft = pr.path("draft").asBoolean(false);
		String state = merged ? "MERGED"
				: draft ? "DRAFT"
				: "closed".equals(pr.path("state").asText("")) ? "CLOSED" : "OPEN";
		String src = pr.path("head").path("ref").asText("");
		GitDevInfo.PullRequest dto = GitDevInfo.PullRequest.builder()
				.number(pr.path("number").asInt())
				.title(pr.path("title").asText(""))
				.state(state)
				.sourceBranch(src)
				.targetBranch(pr.path("base").path("ref").asText(""))
				.comments(pr.path("comments").asInt(0))
				.provider(m.repo().getProvider())
				.repo(slug(m.repo()))
				.at(Instant.now())
				.build();
		Set<String> keys = keysIn(pr.path("title").asText(""), src);
		for (String key : keys) {
			upsert(m, key, dev -> putPr(dev, dto));
		}
		boolean openEvent = action.equals("opened") || action.equals("reopened")
				|| action.equals("ready_for_review");
		boolean mergeEvent = action.equals("closed") && merged;
		applyPrAutomation(m.project(), keys, openEvent, mergeEvent, actor);
	}

	private void githubBuild(Matched m, JsonNode payload) {
		JsonNode run = payload.path("workflow_run");
		String branch = run.path("head_branch").asText("");
		String name = run.path("name").asText("CI");
		GitDevInfo.Build build = GitDevInfo.Build.builder()
				.name(name)
				.workflow(run.path("path").asText(name))
				.branch(branch)
				.status(buildStatus(run.path("status").asText(""), run.path("conclusion").asText("")))
				.provider(m.repo().getProvider())
				.repo(slug(m.repo()))
				.at(Instant.now())
				.build();
		for (String key : keysIn(branch)) {
			upsert(m, key, dev -> putBuild(dev, build));
		}
	}

	private boolean verifyGithub(byte[] body, String signature, Project.Git repo) {
		String secret = secret(repo);
		if (secret == null || signature == null || !signature.startsWith("sha256=")) {
			return false;
		}
		return constantEquals("sha256=" + hmacSha256Hex(secret, body), signature);
	}

	// ─────────────────────────── GitLab ───────────────────────────

	public void handleGitlab(byte[] body, String event, String token) {
		JsonNode payload = parse(body);
		String[] or = splitOwnerRepo(payload.path("project").path("path_with_namespace").asText(""));
		if (or == null) {
			return;
		}
		Matched m = resolve("gitlab", or[0], or[1], g -> token != null && token.equals(secret(g)));
		if (m == null) {
			return;
		}
		User actor = actor(m.repo());
		switch (event == null ? "" : event) {
			case "Push Hook" -> gitlabPush(m, payload, actor);
			case "Merge Request Hook" -> gitlabMr(m, payload, actor);
			case "Pipeline Hook" -> gitlabPipeline(m, payload);
			default -> { /* ignore */ }
		}
	}

	private void gitlabPush(Matched m, JsonNode payload, User actor) {
		String branch = stripRef(payload.path("ref").asText(""));
		boolean created = NULL_SHA.equals(payload.path("before").asText(""));
		Instant now = Instant.now();
		recordBranch(m, branch, now);
		if (created) {
			applyPushAutomation(m.project(), keysIn(branch), PushRule.BRANCH, actor);
		}
		for (JsonNode c : payload.path("commits")) {
			String message = c.path("message").asText("");
			String sha = c.path("id").asText("");
			recordCommit(m, sha, message,
					instant(c.path("timestamp").asText(null), now), false);
			applyCommitEffects(m, sha, message, actor);
		}
	}

	private void gitlabMr(Matched m, JsonNode payload, User actor) {
		JsonNode a = payload.path("object_attributes");
		String glState = a.path("state").asText("");
		boolean draft = a.path("work_in_progress").asBoolean(false) || a.path("draft").asBoolean(false);
		String state = switch (glState) {
			case "merged" -> "MERGED";
			case "closed" -> "CLOSED";
			default -> draft ? "DRAFT" : "OPEN";
		};
		String src = a.path("source_branch").asText("");
		GitDevInfo.PullRequest dto = GitDevInfo.PullRequest.builder()
				.number(a.path("iid").asInt())
				.title(a.path("title").asText(""))
				.state(state)
				.sourceBranch(src)
				.targetBranch(a.path("target_branch").asText(""))
				.provider(m.repo().getProvider())
				.repo(slug(m.repo()))
				.at(Instant.now())
				.build();
		Set<String> keys = keysIn(a.path("title").asText(""), src);
		for (String key : keys) {
			upsert(m, key, dev -> putPr(dev, dto));
		}
		String action = a.path("action").asText("");
		boolean openEvent = action.equals("open") || action.equals("reopen");
		boolean mergeEvent = action.equals("merge");
		applyPrAutomation(m.project(), keys, openEvent, mergeEvent, actor);
	}

	private void gitlabPipeline(Matched m, JsonNode payload) {
		JsonNode a = payload.path("object_attributes");
		String branch = a.path("ref").asText("");
		GitDevInfo.Build build = GitDevInfo.Build.builder()
				.name("pipeline")
				.workflow(".gitlab-ci.yml")
				.branch(branch)
				.status(pipelineStatus(a.path("status").asText("")))
				.provider(m.repo().getProvider())
				.repo(slug(m.repo()))
				.at(Instant.now())
				.build();
		for (String key : keysIn(branch)) {
			upsert(m, key, dev -> putBuild(dev, build));
		}
	}

	// ─────────────────────────── Bitbucket ───────────────────────────

	public void handleBitbucket(byte[] body, String eventKey, String secretParam) {
		JsonNode payload = parse(body);
		String[] or = splitOwnerRepo(payload.path("repository").path("full_name").asText(""));
		if (or == null) {
			return;
		}
		Matched m = resolve("bitbucket", or[0], or[1], g -> secretParam != null && secretParam.equals(secret(g)));
		if (m == null) {
			return;
		}
		User actor = actor(m.repo());
		String key = eventKey == null ? "" : eventKey;
		if (key.equals("repo:push")) {
			bitbucketPush(m, payload, actor);
		}
		else if (key.startsWith("pullrequest:")) {
			bitbucketPr(m, payload, actor, key);
		}
	}

	private void bitbucketPush(Matched m, JsonNode payload, User actor) {
		Instant now = Instant.now();
		for (JsonNode change : payload.path("push").path("changes")) {
			JsonNode target = change.path("new");
			if (!"branch".equals(target.path("type").asText(""))) {
				continue;
			}
			String branch = target.path("name").asText("");
			boolean created = change.path("old").isMissingNode() || change.path("old").isNull();
			recordBranch(m, branch, now);
			if (created) {
				applyPushAutomation(m.project(), keysIn(branch), PushRule.BRANCH, actor);
			}
			for (JsonNode c : change.path("commits")) {
				String message = c.path("message").asText("");
				String sha = c.path("hash").asText("");
				recordCommit(m, sha, message,
						instant(c.path("date").asText(null), now), false);
				applyCommitEffects(m, sha, message, actor);
			}
		}
	}

	private void bitbucketPr(Matched m, JsonNode payload, User actor, String eventKey) {
		JsonNode pr = payload.path("pullrequest");
		String bbState = pr.path("state").asText("");
		String state = switch (bbState) {
			case "MERGED" -> "MERGED";
			case "DECLINED", "SUPERSEDED" -> "CLOSED";
			default -> "OPEN";
		};
		String src = pr.path("source").path("branch").path("name").asText("");
		GitDevInfo.PullRequest dto = GitDevInfo.PullRequest.builder()
				.number(pr.path("id").asInt())
				.title(pr.path("title").asText(""))
				.state(state)
				.sourceBranch(src)
				.targetBranch(pr.path("destination").path("branch").path("name").asText(""))
				.comments(pr.path("comment_count").asInt(0))
				.provider(m.repo().getProvider())
				.repo(slug(m.repo()))
				.at(Instant.now())
				.build();
		Set<String> keys = keysIn(pr.path("title").asText(""), src);
		for (String key : keys) {
			upsert(m, key, dev -> putPr(dev, dto));
		}
		boolean mergeEvent = eventKey.equals("pullrequest:fulfilled");
		boolean openEvent = eventKey.equals("pullrequest:created");
		applyPrAutomation(m.project(), keys, openEvent, mergeEvent, actor);
	}

	// ─────────────────────────── shared recording ───────────────────────────

	private void recordBranch(Matched m, String branch, Instant at) {
		if (branch.isBlank()) {
			return;
		}
		for (String key : keysIn(branch)) {
			upsert(m, key, dev -> {
				dev.getBranches().removeIf(b -> b.getName().equalsIgnoreCase(branch));
				dev.getBranches().add(GitDevInfo.Branch.builder()
						.name(branch).base(m.repo().getDefaultBranch())
						.ahead(0).behind(0).updatedAt(at)
						.provider(m.repo().getProvider()).repo(slug(m.repo()))
						.build());
			});
		}
	}

	private void recordCommit(Matched m, String sha, String message,
			Instant at, boolean verified) {
		if (sha.isBlank()) {
			return;
		}
		// Link a commit only to the issue key(s) in its own message — not the
		// branch's key. The branch is linked separately (recordBranch), so a
		// commit never surfaces on an issue just because of the branch it rides on.
		Set<String> keys = keysIn(message);
		if (keys.isEmpty()) {
			return; // no linked issue → skip recording (and the stats API call)
		}
		String first = firstLine(message);
		// Push payloads don't include per-commit line counts, so fetch them from
		// the provider API (best-effort: zeros when unavailable).
		GitOAuthClient.CommitStats stats = commitStats(m.repo(), sha);
		for (String key : keys) {
			upsert(m, key, dev -> {
				if (dev.getCommits().stream().anyMatch(c -> sha.equals(c.getSha()))) {
					return;
				}
				dev.getCommits().add(0, GitDevInfo.Commit.builder()
						.sha(sha).message(first).at(at).verified(verified)
						.additions(stats.additions()).deletions(stats.deletions())
						.provider(m.repo().getProvider()).repo(slug(m.repo()))
						.build());
				trim(dev.getCommits(), MAX_COMMITS);
			});
		}
	}

	/** A commit's line stats via the provider API; zeros when unavailable (best-effort). */
	private GitOAuthClient.CommitStats commitStats(Project.Git repo, String sha) {
		return api.commitStats(repo.getProvider(), cipher.decrypt(repo.getEncryptedToken()),
				repo.getOwner(), repo.getRepo(), sha);
	}

	private static void putPr(GitDevInfo dev, GitDevInfo.PullRequest pr) {
		dev.getPrs().removeIf(p -> p.getNumber() == pr.getNumber());
		dev.getPrs().add(pr);
	}

	private static void putBuild(GitDevInfo dev, GitDevInfo.Build build) {
		dev.getBuilds().removeIf(b -> b.getBranch() != null
				&& b.getBranch().equals(build.getBranch())
				&& b.getWorkflow() != null && b.getWorkflow().equals(build.getWorkflow()));
		dev.getBuilds().add(0, build);
		trim(dev.getBuilds(), MAX_BUILDS);
	}

	/** Loads (or creates) the issue's dev-info, applies the mutation, persists it. */
	private void upsert(Matched m, String key, Consumer<GitDevInfo> mutation) {
		Project project = m.project();
		Issue issue;
		try {
			issue = issues.get(key);
		}
		catch (ApiException notFound) {
			return; // key references no real issue
		}
		if (!project.getId().equals(issue.getProjectId())) {
			return; // key belongs to a different project than this repo
		}
		// Concurrent webhooks (push + PR + workflow_run) hit the same dev-info doc;
		// @Version turns a racing whole-document save into an optimistic-lock
		// failure, so re-read + re-apply the mutation instead of silently dropping
		// the other writer's activity. Bounded to avoid an unbounded spin.
		for (int attempt = 0; attempt < 5; attempt++) {
			GitDevInfo dev = devInfos.findByIssueKeyIgnoreCase(issue.getReadableId())
					.orElseGet(() -> GitDevInfo.builder()
							.issueKey(issue.getReadableId())
							.projectId(project.getId())
							.build());
			mutation.accept(dev);
			dev.setUpdatedAt(Instant.now());
			try {
				devInfos.save(dev);
				return;
			}
			catch (org.springframework.dao.OptimisticLockingFailureException
					| org.springframework.dao.DuplicateKeyException retry) {
				// Another delivery won the race (version bump, or concurrent insert
				// of the first document) — reload and re-apply on the next attempt.
			}
		}
		log.warn("Gave up upserting git dev-info for {} after contention", issue.getReadableId());
	}

	private void applyPrAutomation(Project project, Set<String> keys, boolean openEvent,
			boolean mergeEvent, User actor) {
		if (actor == null || (!openEvent && !mergeEvent)) {
			return;
		}
		for (String key : keys) {
			try {
				Issue issue = issues.get(key);
				if (project.getId().equals(issue.getProjectId())) {
					gitService.applyPrRule(project, issue, mergeEvent, actor);
				}
			}
			catch (RuntimeException e) {
				log.warn("[git] PR automation for {} skipped: {}", key, e.getMessage());
			}
		}
	}

	/** Which push-time rule to apply — a branch was created, or a commit was pushed. */
	private enum PushRule { BRANCH, COMMIT }

	/**
	 * Applies the project's branch-created / commit-pushed automation to every
	 * referenced issue that belongs to this repo's project. Idempotent: the
	 * transition is a no-op when the issue is already in the target state.
	 */
	private void applyPushAutomation(Project project, Set<String> keys, PushRule kind, User actor) {
		if (actor == null || keys.isEmpty()) {
			return;
		}
		for (String key : keys) {
			try {
				Issue issue = issues.get(key);
				if (!project.getId().equals(issue.getProjectId())) {
					continue;
				}
				if (kind == PushRule.BRANCH) {
					gitService.applyBranchRule(project, issue, actor);
				}
				else {
					gitService.applyCommitRule(project, issue, actor);
				}
			}
			catch (RuntimeException e) {
				log.warn("[git] {} automation for {} skipped: {}", kind, key, e.getMessage());
			}
		}
	}

	/**
	 * Applies a commit's side effects — smart commits + the commit-pushed
	 * transition — <strong>exactly once</strong> per commit. Guarded by the
	 * {@link GitCommitLedger} so a redelivered or re-listed commit (e.g. the same
	 * SHA re-appearing when a feature branch is merged into the default branch)
	 * never re-posts a comment, re-logs time or re-runs a transition. Linking is
	 * by the issue key(s) in the commit <em>message</em> only — the branch is
	 * linked separately via {@link #recordBranch}, so a commit never leaks onto
	 * an issue merely because it sits on that issue's branch.
	 */
	private void applyCommitEffects(Matched m, String sha, String message, User actor) {
		if (!ledger.firstSight(m.repo().getProvider(), slug(m.repo()), sha)) {
			return; // already processed — idempotent
		}
		if (actor != null) {
			gitService.applySmartCommits(message, actor);
		}
		applyPushAutomation(m.project(), keysIn(message), PushRule.COMMIT, actor);
	}

	// ─────────────────────────── helpers ───────────────────────────

	/** A verified inbound webhook: the owning project + the exact connected repo it hit. */
	private record Matched(Project project, Project.Git repo) {
	}

	/**
	 * Finds the project + specific connection an inbound webhook targets, across a
	 * project's primary and additional repos, verifying that repo's own signing
	 * secret. Returns {@code null} for an unknown repo (ignored, 200); throws when
	 * the repo is known but no connection's secret verifies.
	 */
	private Matched resolve(String provider, String owner, String repo, Predicate<Project.Git> verifier) {
		Set<Project> candidates = new LinkedHashSet<>();
		candidates.addAll(projects.findByGit_ProviderAndGit_OwnerIgnoreCaseAndGit_RepoIgnoreCase(provider, owner, repo));
		candidates.addAll(projects
				.findByExtraRepos_ProviderAndExtraRepos_OwnerIgnoreCaseAndExtraRepos_RepoIgnoreCase(provider, owner, repo));
		if (candidates.isEmpty()) {
			return null; // unknown repo → silently ignored (200)
		}
		for (Project p : candidates) {
			for (Project.Git g : p.allRepos()) {
				if (repoMatches(g, provider, owner, repo) && verifier.test(g)) {
					return new Matched(p, g);
				}
			}
		}
		throw ApiException.unauthorized("error.git.webhookSignature");
	}

	private static boolean repoMatches(Project.Git g, String provider, String owner, String repo) {
		return g != null && provider.equalsIgnoreCase(g.getProvider())
				&& owner.equalsIgnoreCase(g.getOwner()) && repo.equalsIgnoreCase(g.getRepo());
	}

	private static String slug(Project.Git repo) {
		return repo.getOwner() + "/" + repo.getRepo();
	}

	private String secret(Project.Git repo) {
		return repo == null ? null : cipher.decrypt(repo.getEncryptedWebhookSecret());
	}

	/** Actor for automation/smart-commits: the user who connected the repo that fired. */
	private User actor(Project.Git repo) {
		String id = repo == null ? null : repo.getConnectedBy();
		return id == null ? null : users.findById(id).orElse(null);
	}

	private Set<String> keysIn(String... texts) {
		Set<String> keys = new LinkedHashSet<>();
		for (String text : texts) {
			if (text == null) {
				continue;
			}
			Matcher m = ISSUE_KEY.matcher(text);
			while (m.find()) {
				keys.add(m.group());
			}
		}
		return keys;
	}

	private JsonNode parse(byte[] body) {
		try {
			return body == null || body.length == 0 ? mapper.createObjectNode() : mapper.readTree(body);
		}
		catch (IOException e) {
			throw ApiException.badRequest("error.git.apiFailed");
		}
	}

	private static String[] splitOwnerRepo(String fullName) {
		if (fullName == null || fullName.isBlank() || !fullName.contains("/")) {
			return null;
		}
		int slash = fullName.lastIndexOf('/');
		return new String[] { fullName.substring(0, slash), fullName.substring(slash + 1) };
	}

	private static String stripRef(String ref) {
		return ref.startsWith(BRANCH_PREFIX) ? ref.substring(BRANCH_PREFIX.length()) : ref;
	}

	private static String firstLine(String message) {
		if (message == null) {
			return "";
		}
		int nl = message.indexOf('\n');
		return (nl >= 0 ? message.substring(0, nl) : message).trim();
	}

	private static <T> void trim(List<T> list, int max) {
		if (list.size() > max) {
			list.subList(max, list.size()).clear();
		}
	}

	private static Instant instant(String iso, Instant fallback) {
		if (iso == null || iso.isBlank()) {
			return fallback;
		}
		try {
			return OffsetDateTime.parse(iso).toInstant();
		}
		catch (RuntimeException e1) {
			try {
				return Instant.parse(iso);
			}
			catch (RuntimeException e2) {
				return fallback;
			}
		}
	}

	private static String buildStatus(String status, String conclusion) {
		if (!"completed".equals(status)) {
			return "in_progress".equals(status) ? "running" : "pending";
		}
		return switch (conclusion) {
			case "success" -> "passing";
			case "failure", "timed_out", "cancelled", "startup_failure" -> "failing";
			default -> "pending";
		};
	}

	private static String pipelineStatus(String status) {
		return switch (status) {
			case "success" -> "passing";
			case "failed" -> "failing";
			case "running" -> "running";
			default -> "pending";
		};
	}

	private static String hmacSha256Hex(String secret, byte[] body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] digest = mac.doFinal(body == null ? new byte[0] : body);
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (GeneralSecurityException e) {
			throw ApiException.badRequest("error.git.apiFailed");
		}
	}

	private static boolean constantEquals(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
}
