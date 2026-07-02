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
	private static final int MAX_COMMITS = 30;
	private static final int MAX_BUILDS = 20;

	private final ProjectRepository projects;
	private final GitDevInfoRepository devInfos;
	private final IssueService issues;
	private final GitService gitService;
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
		Project project = resolveProject("github", or[0], or[1], p -> verifyGithub(body, signature, p));
		if (project == null) {
			return;
		}
		User actor = actor(project);
		switch (event == null ? "" : event) {
			case "push" -> githubPush(project, payload, actor);
			case "pull_request" -> githubPr(project, payload, actor);
			case "workflow_run" -> githubBuild(project, payload);
			default -> { /* event we don't act on */ }
		}
	}

	private void githubPush(Project project, JsonNode payload, User actor) {
		String branch = stripRef(payload.path("ref").asText(""));
		Instant now = Instant.now();
		recordBranch(project, branch, now);
		for (JsonNode c : payload.path("commits")) {
			String message = c.path("message").asText("");
			Instant at = instant(c.path("timestamp").asText(null), now);
			recordCommit(project, branch, c.path("id").asText(""), message, at,
					c.path("verification").path("verified").asBoolean(false));
			smartCommit(message, actor);
		}
	}

	private void githubPr(Project project, JsonNode payload, User actor) {
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
				.at(Instant.now())
				.build();
		Set<String> keys = keysIn(pr.path("title").asText(""), src);
		for (String key : keys) {
			upsert(project, key, dev -> putPr(dev, dto));
		}
		boolean openEvent = action.equals("opened") || action.equals("reopened")
				|| action.equals("ready_for_review");
		boolean mergeEvent = action.equals("closed") && merged;
		applyPrAutomation(project, keys, openEvent, mergeEvent, actor);
	}

	private void githubBuild(Project project, JsonNode payload) {
		JsonNode run = payload.path("workflow_run");
		String branch = run.path("head_branch").asText("");
		String name = run.path("name").asText("CI");
		GitDevInfo.Build build = GitDevInfo.Build.builder()
				.name(name)
				.workflow(run.path("path").asText(name))
				.branch(branch)
				.status(buildStatus(run.path("status").asText(""), run.path("conclusion").asText("")))
				.at(Instant.now())
				.build();
		for (String key : keysIn(branch)) {
			upsert(project, key, dev -> putBuild(dev, build));
		}
	}

	private boolean verifyGithub(byte[] body, String signature, Project project) {
		String secret = secret(project);
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
		Project project = resolveProject("gitlab", or[0], or[1],
				p -> token != null && token.equals(secret(p)));
		if (project == null) {
			return;
		}
		User actor = actor(project);
		switch (event == null ? "" : event) {
			case "Push Hook" -> gitlabPush(project, payload, actor);
			case "Merge Request Hook" -> gitlabMr(project, payload, actor);
			case "Pipeline Hook" -> gitlabPipeline(project, payload);
			default -> { /* ignore */ }
		}
	}

	private void gitlabPush(Project project, JsonNode payload, User actor) {
		String branch = stripRef(payload.path("ref").asText(""));
		Instant now = Instant.now();
		recordBranch(project, branch, now);
		for (JsonNode c : payload.path("commits")) {
			String message = c.path("message").asText("");
			recordCommit(project, branch, c.path("id").asText(""), message,
					instant(c.path("timestamp").asText(null), now), false);
			smartCommit(message, actor);
		}
	}

	private void gitlabMr(Project project, JsonNode payload, User actor) {
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
				.at(Instant.now())
				.build();
		Set<String> keys = keysIn(a.path("title").asText(""), src);
		for (String key : keys) {
			upsert(project, key, dev -> putPr(dev, dto));
		}
		String action = a.path("action").asText("");
		boolean openEvent = action.equals("open") || action.equals("reopen");
		boolean mergeEvent = action.equals("merge");
		applyPrAutomation(project, keys, openEvent, mergeEvent, actor);
	}

	private void gitlabPipeline(Project project, JsonNode payload) {
		JsonNode a = payload.path("object_attributes");
		String branch = a.path("ref").asText("");
		GitDevInfo.Build build = GitDevInfo.Build.builder()
				.name("pipeline")
				.workflow(".gitlab-ci.yml")
				.branch(branch)
				.status(pipelineStatus(a.path("status").asText("")))
				.at(Instant.now())
				.build();
		for (String key : keysIn(branch)) {
			upsert(project, key, dev -> putBuild(dev, build));
		}
	}

	// ─────────────────────────── Bitbucket ───────────────────────────

	public void handleBitbucket(byte[] body, String eventKey, String secretParam) {
		JsonNode payload = parse(body);
		String[] or = splitOwnerRepo(payload.path("repository").path("full_name").asText(""));
		if (or == null) {
			return;
		}
		Project project = resolveProject("bitbucket", or[0], or[1],
				p -> secretParam != null && secretParam.equals(secret(p)));
		if (project == null) {
			return;
		}
		User actor = actor(project);
		String key = eventKey == null ? "" : eventKey;
		if (key.equals("repo:push")) {
			bitbucketPush(project, payload, actor);
		}
		else if (key.startsWith("pullrequest:")) {
			bitbucketPr(project, payload, actor, key);
		}
	}

	private void bitbucketPush(Project project, JsonNode payload, User actor) {
		Instant now = Instant.now();
		for (JsonNode change : payload.path("push").path("changes")) {
			JsonNode target = change.path("new");
			if (!"branch".equals(target.path("type").asText(""))) {
				continue;
			}
			String branch = target.path("name").asText("");
			recordBranch(project, branch, now);
			for (JsonNode c : change.path("commits")) {
				String message = c.path("message").asText("");
				recordCommit(project, branch, c.path("hash").asText(""), message,
						instant(c.path("date").asText(null), now), false);
				smartCommit(message, actor);
			}
		}
	}

	private void bitbucketPr(Project project, JsonNode payload, User actor, String eventKey) {
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
				.at(Instant.now())
				.build();
		Set<String> keys = keysIn(pr.path("title").asText(""), src);
		for (String key : keys) {
			upsert(project, key, dev -> putPr(dev, dto));
		}
		boolean mergeEvent = eventKey.equals("pullrequest:fulfilled");
		boolean openEvent = eventKey.equals("pullrequest:created");
		applyPrAutomation(project, keys, openEvent, mergeEvent, actor);
	}

	// ─────────────────────────── shared recording ───────────────────────────

	private void recordBranch(Project project, String branch, Instant at) {
		if (branch.isBlank()) {
			return;
		}
		for (String key : keysIn(branch)) {
			upsert(project, key, dev -> {
				dev.getBranches().removeIf(b -> b.getName().equalsIgnoreCase(branch));
				dev.getBranches().add(GitDevInfo.Branch.builder()
						.name(branch).base(project.getGit().getDefaultBranch())
						.ahead(0).behind(0).updatedAt(at).build());
			});
		}
	}

	private void recordCommit(Project project, String branch, String sha, String message,
			Instant at, boolean verified) {
		if (sha.isBlank()) {
			return;
		}
		String first = firstLine(message);
		Set<String> keys = keysIn(message, branch);
		for (String key : keys) {
			upsert(project, key, dev -> {
				if (dev.getCommits().stream().anyMatch(c -> sha.equals(c.getSha()))) {
					return;
				}
				dev.getCommits().add(0, GitDevInfo.Commit.builder()
						.sha(sha).message(first).at(at).verified(verified).build());
				trim(dev.getCommits(), MAX_COMMITS);
			});
		}
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
	private void upsert(Project project, String key, Consumer<GitDevInfo> mutation) {
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
		GitDevInfo dev = devInfos.findByIssueKeyIgnoreCase(issue.getReadableId())
				.orElseGet(() -> GitDevInfo.builder()
						.issueKey(issue.getReadableId())
						.projectId(project.getId())
						.build());
		mutation.accept(dev);
		dev.setUpdatedAt(Instant.now());
		devInfos.save(dev);
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

	private void smartCommit(String message, User actor) {
		if (actor != null) {
			gitService.applySmartCommits(message, actor);
		}
	}

	// ─────────────────────────── helpers ───────────────────────────

	private Project resolveProject(String provider, String owner, String repo, Predicate<Project> verifier) {
		List<Project> candidates = projects
				.findByGit_ProviderAndGit_OwnerIgnoreCaseAndGit_RepoIgnoreCase(provider, owner, repo);
		if (candidates.isEmpty()) {
			return null; // unknown repo → silently ignored (200)
		}
		for (Project p : candidates) {
			if (verifier.test(p)) {
				return p;
			}
		}
		throw ApiException.unauthorized("error.git.webhookSignature");
	}

	private String secret(Project project) {
		Project.Git git = project.getGit();
		return git == null ? null : cipher.decrypt(git.getEncryptedWebhookSecret());
	}

	/** Actor for automation/smart-commits: the user who connected the repo. */
	private User actor(Project project) {
		String id = project.getGit() == null ? null : project.getGit().getConnectedBy();
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
