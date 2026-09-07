package com.ahmadre.hinata.git;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.timetracking.TimeTrackingService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Git integration — <strong>per project</strong>. Each project owns its provider
 * + repository connection and its own automation rules, expressed against that
 * project's own {@link Project.WorkflowState} ids. Nothing here is global.
 *
 * <p>OAuth is brokered server-side: the client never receives a provider token
 * (see {@link TokenCipher} + {@code @JsonIgnore} on {@link Project.Git}). Until
 * real provider app credentials + a public webhook URL are configured, the
 * account graph (owners/repos) and development information are emulated /
 * seeded; the endpoint surface, storage and client behaviour are identical, so
 * wiring real credentials later is a pure config change.
 */
@Service
@RequiredArgsConstructor
public class GitService {

	private static final Logger log = LoggerFactory.getLogger(GitService.class);

	private static final Set<String> PROVIDERS = Set.of("github", "gitlab", "bitbucket");
	private static final String DEFAULT_TEMPLATE = "{key}-{summary}";

	private final ProjectService projects;
	private final IssueService issues;
	private final RichTextService richText;
	private final GitDevInfoRepository devInfos;
	private final TokenCipher cipher;
	private final GitIntegrationSettings config;
	private final GitOAuthClient api;
	private final GitOAuthSessionRepository oauthSessions;
	// #time logs a work item for the commit author, resolved by e-mail; the
	// author's zone decides which day a commit timestamp falls on.
	private final TimeTrackingService timeTracking;
	private final UserService users;
	private final AuditService audit;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	// ─────────────────────────── connection (per project) ───────────────────────────

	/**
	 * Kicks off the real OAuth Authorization-Code flow: records a pending session
	 * keyed by an unguessable {@code state} and returns the provider authorize URL
	 * to open. When no provider app is configured, {@code available} is false and
	 * the client routes to the URL + token method instead (no emulation).
	 */
	public OAuthStart oauthStart(String projectId, String provider, User user) {
		Project project = requireLead(projectId, user);
		validateProvider(provider);
		if (!config.configured(provider)) {
			return new OAuthStart(null, null, false);
		}
		String state = newState();
		oauthSessions.save(GitOAuthSession.builder()
				.state(state)
				.projectId(project.getId())
				.provider(provider)
				.userId(user.getId())
				.status(GitOAuthSession.Status.PENDING)
				.createdAt(Instant.now())
				.build());
		return new OAuthStart(buildAuthorizeUrl(provider, state), state, true);
	}

	/**
	 * Public OAuth callback — the browser lands here after the provider consent.
	 * Exchanges the {@code code} for an access token and parks it (encrypted) on
	 * the session; the wizard polls {@link #sessionStatus} to continue. Returns a
	 * small self-contained HTML page for the browser.
	 */
	public String handleCallback(String code, String state, String error) {
		GitOAuthSession session = isBlank(state) ? null : oauthSessions.findById(state).orElse(null);
		if (session == null) {
			return callbackPage(false, "This authorization link is invalid or has expired.");
		}
		if (!isBlank(error)) {
			return failSession(session, error, "Access was declined — you can close this window.");
		}
		if (isBlank(code)) {
			return failSession(session, "missing_code", "No authorization code was returned.");
		}
		try {
			String token = api.exchangeCode(session.getProvider(), code, config.oauthRedirectUri());
			session.setEncryptedToken(cipher.encrypt(token));
			session.setStatus(GitOAuthSession.Status.AUTHORIZED);
			session.setError(null);
			oauthSessions.save(session);
			return callbackPage(true,
					"You're connected. This tab closes automatically — if it stays open, "
							+ "use the button below and pick your repository back in Hinata.");
		}
		catch (RuntimeException e) {
			log.warn("[git] OAuth callback exchange for {} failed: {}", session.getProvider(), e.getMessage());
			return failSession(session, "exchange_failed", "Could not complete the connection. Please try again.");
		}
	}

	/** Polled by the wizard after opening the authorize URL. */
	public SessionStatus sessionStatus(String state, User user) {
		GitOAuthSession session = oauthSessions.findById(state)
				.filter(s -> s.getUserId().equals(user.getId()))
				.orElseThrow(() -> ApiException.notFound("git.oauthSession"));
		return new SessionStatus(session.getStatus().name(), session.getProvider(), session.getError());
	}

	/** Owners (org / group / workspace) the authorized account exposes. */
	public List<OwnerDto> owners(String projectId, String provider, String state, User user) {
		requireLead(projectId, user);
		validateProvider(provider);
		String token = sessionToken(state, projectId, provider, user);
		return api.listOwners(provider, token);
	}

	/** Repositories under {@code owner}, filtered by an optional query. */
	public List<RepoDto> repos(String projectId, String provider, String owner, String query, String state, User user) {
		requireLead(projectId, user);
		validateProvider(provider);
		if (isBlank(owner)) {
			throw ApiException.badRequest("error.git.repoRequired");
		}
		String token = sessionToken(state, projectId, provider, user);
		return api.listRepos(provider, token, owner, query);
	}

	/** Binds the chosen repo to the project, moving the OAuth token onto it. */
	public Project connect(String projectId, String provider, String owner, String repo, String state, User user) {
		Project project = requireLead(projectId, user);
		validateProvider(provider);
		if (isBlank(owner) || isBlank(repo)) {
			throw ApiException.badRequest("error.git.repoRequired");
		}
		GitOAuthSession session = requireAuthorizedSession(state, projectId, provider, user);
		Instant now = Instant.now();
		Project.Git connection = Project.Git.builder()
				.id(newRepoId())
				.provider(provider)
				.owner(owner.trim())
				.repo(repo.trim())
				.defaultBranch("main")
				.connectedBy(user.getId())
				.connectedAt(now)
				.lastSyncAt(now)
				.method("oauth")
				.branchTemplate(DEFAULT_TEMPLATE)
				.automation(defaultAutomation(project))
				.encryptedToken(session.getEncryptedToken())
				.build();
		addConnection(project, connection);
		registerWebhook(connection, cipher.decrypt(session.getEncryptedToken()));
		Project saved = projects.save(project);
		oauthSessions.deleteById(session.getState());
		return saved;
	}

	/** Self-managed fallback — store repo URL + PAT (encrypted, server-side). */
	public Project connectToken(String projectId, String repoUrl, String token, User user) {
		Project project = requireLead(projectId, user);
		if (isBlank(repoUrl) || isBlank(token)) {
			throw ApiException.badRequest("error.git.tokenRequired");
		}
		String provider = detectProvider(repoUrl);
		String[] ownerRepo = ownerRepoFromUrl(repoUrl, project.getKey());
		Instant now = Instant.now();
		Project.Git connection = Project.Git.builder()
				.id(newRepoId())
				.provider(provider)
				.owner(ownerRepo[0])
				.repo(ownerRepo[1])
				.defaultBranch("main")
				.connectedBy(user.getId())
				.connectedAt(now)
				.lastSyncAt(now)
				.method("token")
				.branchTemplate(DEFAULT_TEMPLATE)
				.automation(defaultAutomation(project))
				.encryptedToken(cipher.encrypt(token))
				.build();
		addConnection(project, connection);
		registerWebhook(connection, token);
		return projects.save(project);
	}

	/**
	 * Removes a connected repository. {@code repoId} null/blank targets the primary
	 * connection. When the primary is removed its shared automation + branch
	 * template are carried onto the next repo so project-wide rules survive.
	 */
	public Project disconnect(String projectId, String repoId, User user) {
		Project project = requireLead(projectId, user);
		Project.Git target = findRepo(project, repoId);
		if (target == null) {
			return project;
		}
		if (!isBlank(target.getWebhookId())) {
			api.deleteWebhook(target.getProvider(), cipher.decrypt(target.getEncryptedToken()),
					target.getOwner(), target.getRepo(), target.getWebhookId());
		}
		removeConnection(project, target);
		return projects.save(project);
	}

	/** Bumps {@code lastSyncAt}; {@code repoId} null/blank syncs every connection. */
	public Project resync(String projectId, String repoId, User user) {
		Project project = requireLead(projectId, user);
		requireConnected(project);
		Instant now = Instant.now();
		if (isBlank(repoId)) {
			project.allRepos().forEach(g -> g.setLastSyncAt(now));
		}
		else {
			Project.Git target = findRepo(project, repoId);
			if (target != null) {
				target.setLastSyncAt(now);
			}
		}
		return projects.save(project);
	}

	private String newRepoId() {
		return Project.newId();
	}

	/** Adds a connection as the primary (if none yet) or as an additional repo; rejects duplicates. */
	private void addConnection(Project project, Project.Git connection) {
		boolean duplicate = project.allRepos().stream().anyMatch(g ->
				connection.getProvider().equalsIgnoreCase(g.getProvider())
						&& connection.getOwner().equalsIgnoreCase(g.getOwner())
						&& connection.getRepo().equalsIgnoreCase(g.getRepo()));
		if (duplicate) {
			throw ApiException.conflict("error.git.alreadyConnected");
		}
		if (project.getGit() == null) {
			project.setGit(connection);
		}
		else {
			if (project.getExtraRepos() == null) {
				project.setExtraRepos(new java.util.ArrayList<>());
			}
			project.getExtraRepos().add(connection);
		}
	}

	/** Finds a connection by id; a null/blank id resolves to the primary. */
	private Project.Git findRepo(Project project, String repoId) {
		if (isBlank(repoId)) {
			return project.getGit();
		}
		return project.allRepos().stream()
				.filter(g -> repoId.equals(g.getId()))
				.findFirst().orElse(null);
	}

	/**
	 * The connection a PR belongs to — matched by the PR's own provider + slug so
	 * the right token/owner/repo is used in a multi-repo project. Falls back to the
	 * primary repo when the PR carries no attribution (e.g. older/demo data).
	 */
	private Project.Git repoFor(Project project, GitDevInfo.PullRequest pr) {
		String slug = pr.getRepo();
		if (pr.getProvider() != null && slug != null) {
			for (Project.Git g : project.allRepos()) {
				if (g != null && pr.getProvider().equalsIgnoreCase(g.getProvider())
						&& slug.equalsIgnoreCase(g.getOwner() + "/" + g.getRepo())) {
					return g;
				}
			}
		}
		return project.getGit();
	}

	/** Removes a connection; if it was the primary, promotes the next repo and keeps shared config. */
	private void removeConnection(Project project, Project.Git target) {
		if (target == project.getGit()) {
			List<Project.Git> extras = project.getExtraRepos();
			if (extras != null && !extras.isEmpty()) {
				Project.Git promoted = extras.remove(0);
				promoted.setAutomation(target.getAutomation());
				promoted.setBranchTemplate(target.getBranchTemplate());
				project.setGit(promoted);
			}
			else {
				project.setGit(null);
			}
		}
		else if (project.getExtraRepos() != null) {
			project.getExtraRepos().removeIf(g -> g == target);
		}
	}

	public Project setAutomation(String projectId, Project.Automation incoming, User user) {
		Project project = requireLead(projectId, user);
		requireConnected(project);
		Project.Automation current = project.getGit().getAutomation();
		Project.Automation merged = Project.Automation.builder()
				.branchCreated(orElse(incoming == null ? null : incoming.getBranchCreated(), current.getBranchCreated()))
				.commitPushed(orElse(incoming == null ? null : incoming.getCommitPushed(), current.getCommitPushed()))
				.prOpened(orElse(incoming == null ? null : incoming.getPrOpened(), current.getPrOpened()))
				.prMerged(orElse(incoming == null ? null : incoming.getPrMerged(), current.getPrMerged()))
				.smartCommits(incoming != null && incoming.isSmartCommits())
				.build();
		validateAutomation(project, merged);
		project.getGit().setAutomation(merged);
		return projects.save(project);
	}

	public Project setBranchTemplate(String projectId, String template, User user) {
		Project project = requireLead(projectId, user);
		requireConnected(project);
		String cleaned = isBlank(template) ? DEFAULT_TEMPLATE : template.trim();
		project.getGit().setBranchTemplate(cleaned);
		return projects.save(project);
	}

	/**
	 * Default automation seeded on connect: all rules <em>off</em>, smart commits
	 * on, and each rule's target pre-pointed at a sensible state <em>in this
	 * project's workflow</em> (In Progress for branch/commit, In Review for a PR
	 * opened, the first resolved state for a PR merged) so enabling a rule already
	 * points somewhere valid.
	 */
	public Project.Automation defaultAutomation(Project project) {
		return Project.Automation.builder()
				.branchCreated(Project.Rule.builder().on(false).toStateId(stateIdContaining(project, "progress")).build())
				.commitPushed(Project.Rule.builder().on(false).toStateId(stateIdContaining(project, "progress")).build())
				.prOpened(Project.Rule.builder().on(false).toStateId(stateIdContaining(project, "review")).build())
				.prMerged(Project.Rule.builder().on(false).toStateId(resolvedStateId(project)).build())
				.smartCommits(true)
				.build();
	}

	// ─────────────────────────── development info (per issue) ───────────────────────────

	public DevInfoResponse devInfo(String issueKey, User user) {
		Issue issue = issues.get(issueKey);
		Project project = projects.get(issue.getProjectId());
		projects.assertMember(project, user);
		if (project.getGit() == null) {
			return DevInfoResponse.notConnected();
		}
		GitDevInfo info = devInfos.findByIssueKeyIgnoreCase(issue.getReadableId()).orElse(null);
		return DevInfoResponse.connected(project.getGit(), info);
	}

	/** Merge a PR/MR from the Development panel; applies the {@code prMerged} rule. */
	public PrActionResponse mergePr(String issueKey, int number, User user) {
		return transitionPr(issueKey, number, "MERGED", user);
	}

	/** Mark a draft PR/MR ready for review; applies the {@code prOpened} rule. */
	public PrActionResponse readyPr(String issueKey, int number, User user) {
		return transitionPr(issueKey, number, "OPEN", user);
	}

	private PrActionResponse transitionPr(String issueKey, int number, String newState, User user) {
		Issue issue = issues.get(issueKey);
		Project project = projects.get(issue.getProjectId());
		projects.assertMember(project, user);
		requireConnected(project);
		GitDevInfo info = devInfos.findByIssueKeyIgnoreCase(issue.getReadableId())
				.orElseThrow(() -> ApiException.notFound("git.devInfo"));
		GitDevInfo.PullRequest pr = info.getPrs().stream()
				.filter(p -> p.getNumber() == number)
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("git.pullRequest"));
		// A merge must actually happen on the provider before we record it or move
		// the issue — otherwise the UI shows a merge that never occurred. On any
		// provider failure this throws, so nothing below runs and the client rolls
		// back its optimistic state.
		if ("MERGED".equals(newState)) {
			Project.Git repo = repoFor(project, pr);
			api.mergePr(repo.getProvider(), cipher.decrypt(repo.getEncryptedToken()),
					repo.getOwner(), repo.getRepo(), number);
		}
		pr.setState(newState);
		info.setUpdatedAt(Instant.now());
		devInfos.save(info);

		Issue updated = issue;
		Project.Automation auto = project.getGit().getAutomation();
		if (auto != null) {
			if ("MERGED".equals(newState) && isOn(auto.getPrMerged())) {
				updated = applyTransition(project, issue, auto.getPrMerged().getToStateId(), user);
			}
			else if ("OPEN".equals(newState) && isOn(auto.getPrOpened())) {
				updated = applyTransition(project, issue, auto.getPrOpened().getToStateId(), user);
			}
		}
		return new PrActionResponse(DevInfoResponse.connected(project.getGit(), info), updated);
	}

	// ─────────────────────────── smart commits (§5) ───────────────────────────

	/** Longest description a {@code #time} entry gets: the sha and the first line, cut. */
	static final int TIME_DESCRIPTION_MAX = 200;

	/**
	 * One pushed commit as far as smart commits are concerned: what it says,
	 * who wrote it and when. {@code authorEmail} and {@code committedAt} come
	 * from the push payload (every provider carries both); either may be null
	 * when the payload did not.
	 */
	public record SmartCommit(String sha, String message, String authorEmail, Instant committedAt) {
	}

	/**
	 * Applies every smart-commit command found in a commit message, honouring the
	 * per-project {@code smartCommits} toggle. This is the single real code path a
	 * push webhook drives — {@code #comment} adds a comment, {@code #time} logs
	 * work, any other {@code #word} transitions the issue.
	 *
	 * <p>{@code actor} — the user who connected the repository — is who comments
	 * and transitions are attributed to. Logged time is <em>not</em>: a
	 * {@code #time} is the commit author's work, so it is booked to the account
	 * behind the commit's author e-mail, and skipped with a warning when that
	 * address belongs to no active member of the issue's project. The connector
	 * is never credited with someone else's hours.
	 *
	 * <p>Being credited is not the same as being authorized. An author line is
	 * free text in the commit, so it says who <em>should</em> get the hours, never
	 * who may write them: {@code actor} still has to reach the issue, exactly as
	 * a comment or a transition does, and a booking onto anyone else's account is
	 * audited under both names.
	 */
	public void applySmartCommits(SmartCommit commit, User actor) {
		for (SmartCommitParser.Command command : SmartCommitParser.parse(commit.message())) {
			Issue issue;
			try {
				issue = issues.get(command.issueKey());
			}
			catch (ApiException unknownKey) {
				continue; // key referenced in the commit points at no issue — ignore
			}
			Project project = projects.get(issue.getProjectId());
			Project.Git git = project.getGit();
			if (git == null || git.getAutomation() == null || !git.getAutomation().isSmartCommits()) {
				continue;
			}
			try {
				switch (command.type()) {
					// A smart-commit comment is plain text from a commit message; it goes
					// through the same converter as every other markdown source.
					case COMMENT -> issues.addComment(issue.getId(),
							richText.fromMarkdown(command.value()), actor);
					case TIME -> logTime(commit, command, issue, actor);
					case TRANSITION -> {
						String state = matchState(project, command.value());
						if (state != null) {
							issues.update(issue.getId(), i -> i.setState(state), actor);
						}
					}
				}
			}
			catch (RuntimeException e) {
				log.warn("[git] smart-commit '{}' on {} skipped: {}",
						command.type(), command.issueKey(), e.getMessage());
			}
		}
	}

	/**
	 * {@code #time} as a work item of the commit author. Nothing is written when
	 * the push cannot reach the issue, when the author cannot be resolved to an
	 * active account, or when that account is not a member of the issue's
	 * project — the time is skipped and logged, in the same voice as every other
	 * skipped command, rather than booked to the wrong person.
	 *
	 * <p>Two identities meet here and they are checked differently on purpose.
	 * {@code actor} is the push: it comes from a webhook signed with the
	 * repository's secret, so it is the identity that has to pass the ACL, and
	 * it passes the same one a {@code #comment} passes. The author e-mail is
	 * plain text in the commit object — anyone who can push can write any name
	 * into it — so it only decides <em>who gets the hours</em>, never whether
	 * they may be written. When the two differ, the entry is audited under both,
	 * because a working-time record credited to someone who did not create it is
	 * exactly the thing that has to be findable afterwards.
	 */
	private void logTime(SmartCommit commit, SmartCommitParser.Command command, Issue issue,
			User actor) {
		int minutes = SmartCommitParser.minutes(command.value());
		if (minutes <= 0) {
			return;
		}
		if (!issues.canAccess(issue, actor)) {
			log.warn("[git] smart-commit 'TIME' on {} skipped: the push cannot reach that issue",
					command.issueKey());
			return;
		}
		User author = users.findActiveByEmail(commit.authorEmail()).orElse(null);
		if (author == null) {
			log.warn("[git] smart-commit 'TIME' on {} skipped: commit author {} matches no active account",
					command.issueKey(), commit.authorEmail());
			return;
		}
		if (!issues.canAccess(issue, author)) {
			log.warn("[git] smart-commit 'TIME' on {} skipped: commit author {} is not a member of the project",
					command.issueKey(), commit.authorEmail());
			return;
		}
		ZoneId zone = timeTracking.zoneOf(author);
		LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
		LocalDate day = commit.committedAt() == null ? today
				: LocalDate.ofInstant(commit.committedAt(), zone);
		// A commit timestamp is the author's clock, which may run a little ahead
		// of ours; a day that has not begun here yet is booked to today.
		if (day.isAfter(today)) {
			day = today;
		}
		WorkItem entry = timeTracking.add(issue.getId(),
				new TimeTrackingService.NewWorkItem(minutes, day, null,
						timeDescription(commit), null, null, null, null),
				WorkItem.Source.SMART_COMMIT, author);
		if (!author.getId().equals(actor.getId())) {
			audit.event(AuditAction.TIME_ENTRY_CREATED_FOR).actor(actor).target(author)
					.meta("workItem", entry.getId())
					.meta("issue", issue.getReadableId())
					.meta("commit", shortSha(commit))
					.meta("minutes", String.valueOf(minutes))
					.meta("date", String.valueOf(day)).log();
		}
	}

	/** "Smart commit &lt;sha7&gt;: &lt;first line&gt;", capped at {@link #TIME_DESCRIPTION_MAX}. */
	static String timeDescription(SmartCommit commit) {
		String sha7 = shortSha(commit);
		String message = commit.message() == null ? "" : commit.message();
		int newline = message.indexOf('\n');
		String firstLine = (newline >= 0 ? message.substring(0, newline) : message).trim();
		String text = "Smart commit " + sha7 + ": " + firstLine;
		return text.length() > TIME_DESCRIPTION_MAX ? text.substring(0, TIME_DESCRIPTION_MAX) : text;
	}

	/** The first seven characters of a commit's sha, or "" when it carries none. */
	static String shortSha(SmartCommit commit) {
		String sha = commit.sha() == null ? "" : commit.sha();
		return sha.length() > 7 ? sha.substring(0, 7) : sha;
	}

	// ─────────────────────────── internals ───────────────────────────

	private Issue applyTransition(Project project, Issue issue, String toStateId, User actor) {
		if (isBlank(toStateId)) {
			return issue;
		}
		String name = stateNameById(project, toStateId);
		if (name == null || name.equals(issue.getState())) {
			return issue; // rule points at a state that no longer exists, or a no-op
		}
		// Automation only advances an issue FORWARD in the workflow, never
		// backward, so a late commit can't drag an in-review / done issue back to
		// in-progress. An issue whose state isn't in the workflow is still moved.
		List<String> order = project.workflowStateNames();
		int from = order.indexOf(issue.getState());
		int to = order.indexOf(name);
		if (to < 0 || (from >= 0 && to <= from)) {
			return issue;
		}
		return issues.update(issue.getId(), i -> i.setState(name), actor);
	}

	private Project requireLead(String projectId, User user) {
		Project project = projects.get(projectId);
		projects.assertLeadOrAdmin(project, user);
		return project;
	}

	private void requireConnected(Project project) {
		if (project.getGit() == null) {
			throw ApiException.badRequest("error.git.notConnected");
		}
	}

	private void validateProvider(String provider) {
		if (provider == null || !PROVIDERS.contains(provider)) {
			throw ApiException.badRequest("error.git.unknownProvider", String.valueOf(provider));
		}
	}

	private void validateAutomation(Project project, Project.Automation automation) {
		Set<String> ids = project.getWorkflowStates().stream()
				.map(Project.WorkflowState::getId)
				.collect(Collectors.toSet());
		checkRule(automation.getBranchCreated(), ids);
		checkRule(automation.getCommitPushed(), ids);
		checkRule(automation.getPrOpened(), ids);
		checkRule(automation.getPrMerged(), ids);
	}

	private void checkRule(Project.Rule rule, Set<String> stateIds) {
		if (rule == null) {
			return;
		}
		if (!isBlank(rule.getToStateId()) && !stateIds.contains(rule.getToStateId())) {
			throw ApiException.badRequest("error.git.unknownState");
		}
		if (rule.isOn() && isBlank(rule.getToStateId())) {
			throw ApiException.badRequest("error.git.ruleNeedsState");
		}
	}

	private String stateIdContaining(Project project, String needle) {
		for (Project.WorkflowState s : project.getWorkflowStates()) {
			if (s.getName().toLowerCase().contains(needle)) {
				return s.getId();
			}
		}
		return project.getWorkflowStates().isEmpty() ? null : project.getWorkflowStates().get(0).getId();
	}

	private String resolvedStateId(Project project) {
		List<String> resolved = project.getResolvedStates();
		if (resolved != null && !resolved.isEmpty()) {
			for (Project.WorkflowState s : project.getWorkflowStates()) {
				if (s.getName().equalsIgnoreCase(resolved.get(0))) {
					return s.getId();
				}
			}
		}
		return stateIdContaining(project, "done");
	}

	private String stateNameById(Project project, String id) {
		for (Project.WorkflowState s : project.getWorkflowStates()) {
			if (s.getId().equals(id)) {
				return s.getName();
			}
		}
		return null;
	}

	/** Matches a smart-commit transition word against a workflow state name. */
	private String matchState(Project project, String word) {
		String needle = word.replaceAll("[^a-z0-9]", "");
		for (Project.WorkflowState s : project.getWorkflowStates()) {
			if (normalize(s.getName()).equals(needle)) {
				return s.getName();
			}
		}
		for (Project.WorkflowState s : project.getWorkflowStates()) {
			if (normalize(s.getName()).contains(needle)) {
				return s.getName();
			}
		}
		return null;
	}

	private static String normalize(String s) {
		return s.toLowerCase().replaceAll("[^a-z0-9]", "");
	}

	private String buildAuthorizeUrl(String provider, String state) {
		String redirect = config.oauthRedirectUri();
		return switch (provider) {
			case "github" -> "https://github.com/login/oauth/authorize?client_id=" + enc(config.githubClientId())
					+ "&redirect_uri=" + enc(redirect) + "&scope=" + enc("repo read:org")
					+ "&state=" + enc(state);
			case "gitlab" -> "https://gitlab.com/oauth/authorize?client_id=" + enc(config.gitlabClientId())
					+ "&redirect_uri=" + enc(redirect) + "&response_type=code"
					+ "&scope=" + enc("api read_repository write_repository") + "&state=" + enc(state);
			case "bitbucket" -> "https://bitbucket.org/site/oauth2/authorize?client_id=" + enc(config.bitbucketClientId())
					+ "&response_type=code&state=" + enc(state);
			default -> throw ApiException.badRequest("error.git.unknownProvider", provider);
		};
	}

	/** Resolves + decrypts the provider token from an authorized OAuth session. */
	private String sessionToken(String state, String projectId, String provider, User user) {
		GitOAuthSession session = requireAuthorizedSession(state, projectId, provider, user);
		String token = cipher.decrypt(session.getEncryptedToken());
		if (isBlank(token)) {
			throw ApiException.badRequest("error.git.oauthNotAuthorized");
		}
		return token;
	}

	private GitOAuthSession requireAuthorizedSession(String state, String projectId, String provider, User user) {
		if (isBlank(state)) {
			throw ApiException.badRequest("error.git.oauthNotAuthorized");
		}
		GitOAuthSession session = oauthSessions.findById(state)
				.orElseThrow(() -> ApiException.badRequest("error.git.oauthNotAuthorized"));
		if (!session.getUserId().equals(user.getId()) || !session.getProjectId().equals(projectId)
				|| !session.getProvider().equals(provider)
				|| session.getStatus() != GitOAuthSession.Status.AUTHORIZED
				|| isBlank(session.getEncryptedToken())) {
			throw ApiException.badRequest("error.git.oauthNotAuthorized");
		}
		return session;
	}

	private String failSession(GitOAuthSession session, String error, String userMessage) {
		session.setStatus(GitOAuthSession.Status.ERROR);
		session.setError(error);
		oauthSessions.save(session);
		return callbackPage(false, userMessage);
	}

	private String newState() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Best-effort webhook registration; records the hook id + secret on the connection. */
	private void registerWebhook(Project.Git git, String plainToken) {
		if (git == null || isBlank(plainToken)) {
			return;
		}
		try {
			String secret = newState();
			String callback = config.publicApiBase() + "/git/webhooks/" + git.getProvider();
			String hookId = api.registerWebhook(git.getProvider(), plainToken, git.getOwner(),
					git.getRepo(), callback, secret);
			git.setWebhookId(hookId);
			git.setEncryptedWebhookSecret(cipher.encrypt(secret));
		}
		catch (RuntimeException e) {
			log.warn("[git] webhook registration for {}/{} failed: {}",
					git.getOwner(), git.getRepo(), e.getMessage());
		}
	}

	/** Applies the project's PR/MR automation on an inbound webhook PR event. */
	public Issue applyPrRule(Project project, Issue issue, boolean merged, User actor) {
		if (project.getGit() == null || project.getGit().getAutomation() == null) {
			return issue;
		}
		Project.Rule rule = merged
				? project.getGit().getAutomation().getPrMerged()
				: project.getGit().getAutomation().getPrOpened();
		return applyRule(project, issue, rule, actor);
	}

	/** Applies the project's branch-created automation on an inbound push/create webhook. */
	public Issue applyBranchRule(Project project, Issue issue, User actor) {
		Project.Automation auto = project.getGit() == null ? null : project.getGit().getAutomation();
		return applyRule(project, issue, auto == null ? null : auto.getBranchCreated(), actor);
	}

	/** Applies the project's commit-pushed automation on an inbound push webhook. */
	public Issue applyCommitRule(Project project, Issue issue, User actor) {
		Project.Automation auto = project.getGit() == null ? null : project.getGit().getAutomation();
		return applyRule(project, issue, auto == null ? null : auto.getCommitPushed(), actor);
	}

	private Issue applyRule(Project project, Issue issue, Project.Rule rule, User actor) {
		return isOn(rule) ? applyTransition(project, issue, rule.getToStateId(), actor) : issue;
	}

	/**
	 * Minimal self-contained page shown to the browser after the provider
	 * redirect. It tries to close its own tab automatically, but providers such
	 * as GitHub send a {@code Cross-Origin-Opener-Policy} header that severs the
	 * opener relationship, so Chrome blocks the scripted close. The prominent
	 * button closes reliably under the user gesture (via the self-reopen trick),
	 * revealing the original Hinata tab where the wizard has already advanced to
	 * repository selection through polling.
	 */
	private static String callbackPage(boolean ok, String message) {
		String title = ok ? "Connected to Hinata" : "Connection failed";
		String accent = ok ? "#B9831F" : "#B4443B";
		// Honey-amber hex mark, matching the app brand.
		String hex = "<svg width=\"30\" height=\"30\" viewBox=\"0 0 24 24\" fill=\"none\" "
				+ "xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M12 2l8.66 5v10L12 22l-8.66-5V7L12 2z\" "
				+ "fill=\"" + accent + "\"/></svg>";
		String button = ok
				? "<button onclick=\"hnClose()\" style=\"margin-top:22px;border:0;cursor:pointer;"
						+ "background:#1C2438;color:#fff;font-size:14px;font-weight:600;padding:12px 22px;"
						+ "border-radius:11px;font-family:inherit\">Close this tab &amp; return to Hinata</button>"
				: "";
		return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
				+ "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
				+ "<title>" + title + "</title></head>"
				+ "<body style=\"margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
				+ "font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#F6F2EA;color:#2A2723\">"
				+ "<div style=\"max-width:360px;text-align:center;padding:28px\">"
				+ "<div style=\"width:56px;height:56px;margin:0 auto 18px;border-radius:16px;background:#1C2438;"
				+ "display:flex;align-items:center;justify-content:center\">" + hex + "</div>"
				+ "<div style=\"display:inline-flex;align-items:center;gap:7px;font-size:13px;font-weight:600;"
				+ "color:" + accent + ";margin-bottom:10px\"><span style=\"font-size:16px\">"
				+ (ok ? "&#10003;" : "!") + "</span>" + title + "</div>"
				+ "<p style=\"font-size:14px;line-height:1.55;color:#6B6459;margin:0\">" + escapeHtml(message) + "</p>"
				+ button
				+ "</div>"
				+ "<script>"
				+ "function hnClose(){try{window.open('','_self');}catch(e){}try{window.close();}catch(e){}}"
				// On success, drive the same robust self-reopen close the button
				// uses (not a bare window.close(), which the browser blocks after
				// the OAuth redirect chain). Retry a few times because some
				// browsers only honour the close a tick or two after load. The
				// button stays as the fallback when the browser refuses outright.
				+ (ok
						? "hnClose();setTimeout(hnClose,150);setTimeout(hnClose,600);"
								+ "setTimeout(hnClose,1500);"
						: "")
				+ "</script></body></html>";
	}

	private static String escapeHtml(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String detectProvider(String url) {
		String u = url.toLowerCase();
		if (u.contains("gitlab")) {
			return "gitlab";
		}
		if (u.contains("bitbucket")) {
			return "bitbucket";
		}
		return "github";
	}

	private static String[] ownerRepoFromUrl(String url, String projectKey) {
		String cleaned = url.replaceAll("^https?://", "").replaceAll("\\.git$", "");
		String[] parts = cleaned.split("/");
		String repo = parts.length > 0 ? parts[parts.length - 1] : projectKey.toLowerCase();
		String owner = parts.length > 2 ? parts[parts.length - 2] : "self-managed";
		return new String[] { owner, repo };
	}

	private static boolean isOn(Project.Rule rule) {
		return rule != null && rule.isOn();
	}

	private static Project.Rule orElse(Project.Rule value, Project.Rule fallback) {
		return value != null ? value : (fallback != null ? fallback : Project.Rule.off());
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static String enc(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	// ─────────────────────────── DTOs ───────────────────────────

	/**
	 * @param authorizeUrl provider consent URL to open (null when unavailable)
	 * @param state        OAuth {@code state} to poll the session with
	 * @param available    whether a provider app is configured (real OAuth possible)
	 */
	public record OAuthStart(String authorizeUrl, String state, boolean available) {
	}

	public record SessionStatus(String status, String provider, String error) {
	}

	public record OwnerDto(String id, String name, String kind, int repos) {
	}

	public record RepoDto(String name, boolean priv, String lang, String langColor, String updated) {
	}

	public record PrActionResponse(DevInfoResponse devInfo, Issue issue) {
	}

	/** What the client reads for an issue's Development summary. */
	public record DevInfoResponse(boolean connected, String provider, String owner, String repo,
			List<GitDevInfo.Branch> branches, List<GitDevInfo.Commit> commits,
			List<GitDevInfo.PullRequest> prs, List<GitDevInfo.Build> builds) {

		static DevInfoResponse notConnected() {
			return new DevInfoResponse(false, null, null, null,
					List.of(), List.of(), List.of(), List.of());
		}

		static DevInfoResponse connected(Project.Git git, GitDevInfo info) {
			if (info == null) {
				return new DevInfoResponse(true, git.getProvider(), git.getOwner(), git.getRepo(),
						List.of(), List.of(), List.of(), List.of());
			}
			return new DevInfoResponse(true, git.getProvider(), git.getOwner(), git.getRepo(),
					info.getBranches(), info.getCommits(), info.getPrs(), info.getBuilds());
		}
	}
}
