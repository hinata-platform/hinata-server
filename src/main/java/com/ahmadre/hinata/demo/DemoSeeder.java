package com.ahmadre.hinata.demo;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleRepository;
import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.AgileBoardRepository;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.git.GitDevInfo;
import com.ahmadre.hinata.git.GitDevInfoRepository;
import com.ahmadre.hinata.git.GitService;
import com.ahmadre.hinata.git.TokenCipher;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueLink;
import com.ahmadre.hinata.issue.IssueLinkRepository;
import com.ahmadre.hinata.issue.IssueLinkType;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.space.Space;
import com.ahmadre.hinata.space.SpaceRepository;
import com.ahmadre.hinata.team.ProjectAccess;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.team.TeamRole;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.timetracking.WorkItemRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seeds a realistic, English demo workspace for a fresh local dev cluster so
 * every developer gets the same populated environment to click through and to
 * capture marketing / store screenshots against.
 *
 * <p>Enabled with {@code hinata.demo.seed=true} (env {@code HINATA_DEMO_SEED=true}).
 * Idempotent: it does nothing once any project exists, so it is safe to leave on.
 * It also completes first-run setup (organization + admin) when needed, so a
 * brand-new database becomes immediately login-ready.</p>
 *
 * <p>For repeatable tests, set {@code hinata.demo.reset=true}
 * (env {@code HINATA_DEMO_RESET=true}) to wipe the workspace and re-seed the same
 * deterministic dataset on every boot.</p>
 *
 * <p>Never runs in production: the bean is {@code @Profile("!prod")}, so even if
 * {@code HINATA_DEMO_SEED=true} leaks into a prod environment it is simply not
 * created.</p>
 *
 * <p>Login after seeding: {@code admin} / {@code hinata-demo-2026} (admin).</p>
 */
@Slf4j
@Component
@Profile("!prod")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hinata.demo.seed", havingValue = "true")
public class DemoSeeder {

	/** Shared password for every demo account (>= UserService.MIN_PASSWORD_LENGTH). */
	public static final String DEMO_PASSWORD = "hinata-demo-2026";

	private final HinataProperties properties;
	private final SettingsService settings;
	private final UserService userService;
	private final UserRepository users;
	private final ProjectRepository projects;
	private final AgileBoardRepository boards;
	private final SprintRepository sprints;
	private final IssueRepository issues;
	private final IssueLinkRepository issueLinks;
	private final WorkItemRepository workItems;
	private final TeamRepository teams;
	private final ArticleRepository articleRepo;
	private final SpaceRepository spaceRepo;
	private final MongoTemplate mongo;
	private final GitService gitService;
	private final GitDevInfoRepository gitDevInfos;
	private final TokenCipher tokenCipher;

	private final Map<String, Long> counters = new LinkedHashMap<>();

	@EventListener(ApplicationReadyEvent.class)
	public void seed() {
		if (properties.getDemo().isReset()) {
			log.warn("[demo] reset=true — wiping existing workspace data before re-seeding");
			resetWorkspace();
		}
		if (projects.count() > 0) {
			log.info("[demo] projects already present — skipping demo seed");
			return;
		}
		log.info("[demo] seeding demo workspace …");

		// --- people ---------------------------------------------------------
		User admin = ensureAdmin();
		User tomas = person("tomas@hinata.dev", "tomas", "Tomáš Horák", "Backend Engineer");
		User lena = person("lena@hinata.dev", "lena", "Lena Vogt", "Product Design");
		User amara = person("amara@hinata.dev", "amara", "Amara Okafor", "Frontend Engineer");
		User mei = person("mei@hinata.dev", "mei", "Mei Lin", "QA Engineer");
		User jonas = person("jonas@hinata.dev", "jonas", "Jonas Becker", "DevOps Engineer");
		List<User> everyone = List.of(admin, tomas, lena, amara, mei, jonas);

		// --- projects -------------------------------------------------------
		Project hin = project("HIN", "Hinata Platform",
				"Self-hosted project & issue tracking — the product itself.",
				"#D9A032", admin, everyone);
		Project mob = project("MOB", "Mobile App",
				"Flutter clients for iOS, Android, macOS and the web.",
				"#AEC6F4", amara, List.of(admin, amara, lena, mei));
		Project inf = project("INF", "Infrastructure",
				"Self-host bundle, CI/CD, observability and the deploy story.",
				"#9BE0C7", jonas, List.of(admin, jonas, tomas));

		// --- teams ----------------------------------------------------------
		Team core = team("CORE", "Core Platform", "hexagon", 70, admin,
				List.of(hin, inf), List.of(admin, tomas, jonas));
		Team dsgn = team("DSGN", "Design & Mobile", "palette", 300, lena,
				List.of(mob), List.of(lena, amara, mei));

		// --- scrum board + sprints for HIN ---------------------------------
		AgileBoard board = board(hin, admin);
		LocalDate today = LocalDate.now();
		Sprint s22 = sprint(board, "Sprint 22", "Stabilize attachments & SSE sync",
				today.minusDays(35), today.minusDays(21), 36, true);
		Sprint s23 = sprint(board, "Sprint 23", "Teams, project access & search palette",
				today.minusDays(21), today.minusDays(7), 38, true);
		Sprint s24 = sprint(board, "Sprint 24", "Board views v2 & the honey-amber redesign",
				today.minusDays(7), today.plusDays(7), 40, false);
		board.setActiveSprintId(s24.getId());
		boards.save(board);

		// --- issues ---------------------------------------------------------
		seedHinIssues(hin, board, s22, s23, s24, admin, tomas, lena, amara, mei, jonas);
		seedSideIssues(mob, amara, lena, mei, admin);
		seedSideIssues(inf, jonas, tomas, admin, mei);

		// Per-project Git integration: each project links to its OWN repo (mixed
		// providers → exercises provider-adaptive PR/MR) with auto-linked branches,
		// commits, pull/merge requests and builds on a spread of its issues.
		seedGitIntegration(hin, mob, inf, admin, everyone);

		// Give a slice of HIN issues real start/due dates (+ a dependency chain)
		// so the Gantt / Timeline surface renders bars instead of an empty state.
		scheduleTimeline(hin);

		// Wire a spread of Jira-style issue links so the "Verknüpfte Vorgänge"
		// panel renders real relationships out of the box.
		seedIssueLinks(hin);

		// --- this week's tracked work for the admin ------------------------
		seedTracker(admin, hin);

		// --- knowledge base (real articles, cross-linked to issues/people) --
		seedKnowledge(core, dsgn, hin, inf, admin, tomas, lena, amara, mei, jonas);

		// --- security audit log (real, back-dated events) ------------------
		seedAuditLog(admin, tomas, lena, amara, mei, jonas);

		log.info("[demo] done — {} users, {} projects, {} issues, {} articles. Login: admin / {}",
				users.count(), projects.count(), issues.count(), articleRepo.count(), DEMO_PASSWORD);
	}

	/**
	 * Drops every collection the seeder owns and re-opens first-run setup, so the
	 * idempotent guard passes and {@link #seed()} rebuilds a pristine workspace.
	 * Safe because the bean is {@code @Profile("!prod")} — this never runs in prod.
	 */
	private void resetWorkspace() {
		mongo.dropCollection(com.ahmadre.hinata.audit.AuditLog.class);
		mongo.dropCollection(Space.class);
		mongo.dropCollection(Article.class);
		mongo.dropCollection(WorkItem.class);
		mongo.dropCollection(GitDevInfo.class);
		mongo.dropCollection(IssueLink.class);
		mongo.dropCollection(Issue.class);
		mongo.dropCollection(Sprint.class);
		mongo.dropCollection(AgileBoard.class);
		mongo.dropCollection(Team.class);
		mongo.dropCollection(Project.class);
		mongo.dropCollection(User.class);
		counters.clear();
		// Re-open setup so ensureAdmin() recreates a clean demo admin.
		ServerSettings s = settings.get();
		s.setSetupCompleted(false);
		settings.save(s);
	}

	// ---- builders ----------------------------------------------------------

	private User ensureAdmin() {
		ServerSettings s = settings.get();
		User admin;
		if (s.isSetupCompleted()) {
			admin = users.findAll().stream().filter(User::isAdmin).findFirst()
					.orElseGet(() -> userService.createLocal("admin@hinata.dev", "admin",
							"admin", DEMO_PASSWORD, Set.of(Role.ADMIN, Role.MEMBER)));
		} else {
			admin = userService.createLocal("admin@hinata.dev", "admin", "admin",
					DEMO_PASSWORD, Set.of(Role.ADMIN, Role.MEMBER));
			s.setOrganizationName("Hinata");
			s.setSetupCompleted(true);
			settings.save(s);
		}
		admin.setTitle("Maintainer");
		applyAccountDefaults(admin);
		return users.save(admin);
	}

	private User person(String email, String username, String displayName, String title) {
		User u = userService.createLocal(email, username, displayName, DEMO_PASSWORD,
				Set.of(Role.MEMBER));
		u.setTitle(title);
		applyAccountDefaults(u);
		return users.save(u);
	}

	/**
	 * Stamps the self-service account fields the {@code /me} surface reads, so
	 * demo accounts render as fully-onboarded, verified members (not still-pending
	 * invites): verified email, a sensible password-change / join date, and the
	 * default notification matrix. 2FA is intentionally left off so the demo login
	 * ({@code admin / } {@value #DEMO_PASSWORD}) stays single-step.
	 */
	private void applyAccountDefaults(User u) {
		Instant joined = u.getCreatedAt() != null ? u.getCreatedAt() : Instant.now();
		u.setEmailVerified(true);
		u.setJoinedAt(joined);
		u.setPasswordChangedAt(joined);
		if (u.getNotificationPreferences() == null) {
			u.setNotificationPreferences(NotificationPreferences.defaults());
		}
	}

	private Project project(String key, String name, String description, String color,
			User lead, List<User> members) {
		List<String> memberIds = members.stream().map(User::getId).toList();
		Project p = Project.builder()
				.key(key)
				.name(name)
				.description(description)
				.color(color)
				.leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(memberIds))
				.labels(new ArrayList<>(List.of(
						Project.Label.builder().id(Project.newId()).name("design").hue(300).build(),
						Project.Label.builder().id(Project.newId()).name("performance").hue(70).build(),
						Project.Label.builder().id(Project.newId()).name("security").hue(15).build(),
						Project.Label.builder().id(Project.newId()).name("good-first-issue").hue(155).build())))
				.build();
		return projects.save(p);
	}

	private Team team(String key, String name, String icon, int hue, User creator,
			List<Project> teamProjects, List<User> members) {
		List<String> projectIds = teamProjects.stream().map(Project::getId).toList();
		List<TeamMembership> memberships = new ArrayList<>();
		for (User u : members) {
			memberships.add(TeamMembership.builder()
					.userId(u.getId())
					.role(u.getId().equals(creator.getId()) ? TeamRole.ADMIN : TeamRole.MEMBER)
					.access(ProjectAccess.all())
					.build());
		}
		return teams.save(Team.builder()
				.key(key).name(name).icon(icon).colorHue(hue)
				.createdBy(creator.getId())
				.projectIds(new ArrayList<>(projectIds))
				.members(memberships)
				.build());
	}

	private AgileBoard board(Project project, User owner) {
		return boards.save(AgileBoard.builder()
				.name(project.getName() + " Board")
				.type(AgileBoard.Type.SCRUM)
				.projectIds(new ArrayList<>(List.of(project.getId())))
				.ownerId(owner.getId())
				.columns(new ArrayList<>(List.of(
						AgileBoard.Column.builder().name("To Do")
								.states(new ArrayList<>(List.of("Open"))).build(),
						AgileBoard.Column.builder().name("In Progress")
								.states(new ArrayList<>(List.of("In Progress"))).wipLimit(4).build(),
						AgileBoard.Column.builder().name("In Review")
								.states(new ArrayList<>(List.of("In Review"))).wipLimit(3).build(),
						AgileBoard.Column.builder().name("Done")
								.states(new ArrayList<>(List.of("Done"))).build())))
				.build());
	}

	private Sprint sprint(AgileBoard board, String name, String goal,
			LocalDate start, LocalDate end, int capacity, boolean archived) {
		return sprints.save(Sprint.builder()
				.boardId(board.getId())
				.name(name).goal(goal)
				.startDate(start).endDate(end)
				.capacityPoints(capacity)
				.archived(archived)
				.build());
	}

	// ---- issue seeding -----------------------------------------------------

	/** Curated HIN backlog: the dense board/dashboard/sprint/report surface. */
	private void seedHinIssues(Project p, AgileBoard board, Sprint s22, Sprint s23, Sprint s24,
			User admin, User tomas, User lena, User amara, User mei, User jonas) {
		// --- epics: the strategic buckets the work below rolls up into ------
		Issue epicBoard = epic(p, "Agile board redesign v2", lena, 9);
		Issue epicDeploy = epic(p, "Self-host & deployment hardening", jonas, 8);
		Issue epicSearch = epic(p, "Search & navigation", admin, 7);

		// --- active sprint (Sprint 24): in-flight work ---------------------
		Issue boardRedesign = issue(p, "Redesign the agile board with calmer column rhythm",
				Issue.Type.STORY, Issue.Priority.MAJOR, "In Progress", lena, s24, 5, 240, 95,
				List.of("design"), null, 4, epicBoard.getId());
		issue(p, "Card drag introduces 120ms jank on large sprints", Issue.Type.BUG,
				Issue.Priority.CRITICAL, "In Progress", amara, s24, 3, 180, 130,
				List.of("performance"), null, 1, epicBoard.getId());
		issue(p, "Issue detail: inline edit for estimate & spent", Issue.Type.STORY,
				Issue.Priority.NORMAL, "In Progress", admin, s24, 3, 150, 70,
				List.of(), null, 7);
		issue(p, "Adaptive icon clips on Android 13 themed mode", Issue.Type.BUG,
				Issue.Priority.MAJOR, "Open", mei, s24, 2, 90, 0,
				List.of(), -1, 0);
		Issue deployScript = issue(p, "Blue-green deploy script for the self-host bundle",
				Issue.Type.TASK, Issue.Priority.NORMAL, "Open", jonas, s24, 5, 300, 0,
				List.of("security"), null, 0, epicDeploy.getId());
		issue(p, "Sprint header shows wrong remaining capacity", Issue.Type.BUG,
				Issue.Priority.NORMAL, "In Review", tomas, s24, 2, 60, 55,
				List.of(), null, 0, epicBoard.getId());
		issue(p, "People filter on board ignores unassigned column", Issue.Type.BUG,
				Issue.Priority.MINOR, "In Review", amara, s24, 1, 45, 40,
				List.of(), null, 0, epicBoard.getId());
		issue(p, "Backlog ordering should persist across reloads", Issue.Type.TASK,
				Issue.Priority.NORMAL, "Open", admin, s24, 2, 90, 0,
				List.of(), -2, 0);
		issue(p, "Add story-point capacity bar to sprint planning", Issue.Type.STORY,
				Issue.Priority.NORMAL, "Open", lena, s24, 3, 120, 0,
				List.of("design"), null, 0, epicBoard.getId());

		// --- sub-tasks: the granular breakdown of two in-flight stories ----
		subtask(p, "Audit column spacing tokens", lena, "In Review", boardRedesign);
		subtask(p, "Rebuild the column header component", lena, "In Progress", boardRedesign);
		subtask(p, "Migration guide for custom board layouts", amara, "Open", boardRedesign);
		subtask(p, "Write the health-check probe", jonas, "In Progress", deployScript);
		subtask(p, "Document the rollback procedure", jonas, "Open", deployScript);
		// A few more on the admin's plate so the dashboard "Today's focus" fills.
		issue(p, "Restore drag-handle focus ring after a drop", Issue.Type.BUG,
				Issue.Priority.CRITICAL, "Open", admin, s24, 2, 60, 0, List.of(), -1, 0);
		issue(p, "Sprint capacity overflow not flagged in header", Issue.Type.BUG,
				Issue.Priority.MAJOR, "Open", admin, s24, 2, 75, 15,
				List.of("performance"), -2, 0);
		issue(p, "Wire ⌘K palette to project quick-switch", Issue.Type.STORY,
				Issue.Priority.MAJOR, "In Progress", admin, s24, 3, 120, 45,
				List.of(), 1, 3, epicSearch.getId());

		// --- resolved this sprint / last 7d (feeds completion + ranking) ---
		resolved(p, "Glass filter popup overflows on narrow windows", Issue.Type.BUG,
				"Done", amara, s24, 2, 60, 60, daysAgo(2));
		resolved(p, "Timeline view: snap drag to day grid", Issue.Type.FEATURE,
				"Done", lena, s24, 3, 150, 140, daysAgo(3));
		resolved(p, "Keyboard shortcut ⌘K opens command palette", Issue.Type.FEATURE,
				"Done", admin, s24, 2, 80, 75, daysAgo(1));
		resolved(p, "Fix flaky team-access integration test", Issue.Type.TASK,
				"Done", tomas, s24, 1, 45, 50, daysAgo(4));
		resolved(p, "Empty-state illustration for backlog", Issue.Type.TASK,
				"Done", lena, s24, 1, 40, 35, daysAgo(5));

		// --- Sprint 23 (closed): velocity history --------------------------
		resolved(p, "Per-member project access gating", Issue.Type.FEATURE,
				"Done", tomas, s23, 8, 480, 520, daysAgo(9));
		resolved(p, "Global ⌘K search & command palette", Issue.Type.FEATURE,
				"Done", admin, s23, 5, 300, 290, daysAgo(10));
		resolved(p, "Colored labels & workflow states editor", Issue.Type.FEATURE,
				"Done", lena, s23, 5, 260, 250, daysAgo(11));
		resolved(p, "Liquid-glass overlays across modals", Issue.Type.FEATURE,
				"Done", amara, s23, 3, 180, 175, daysAgo(12));
		resolved(p, "SSO authz-request store fixes ngrok flow", Issue.Type.BUG,
				"Done", tomas, s23, 3, 150, 160, daysAgo(13));
		resolved(p, "Attachments: presigned S3 URLs + live sync", Issue.Type.FEATURE,
				"Done", jonas, s23, 8, 420, 400, daysAgo(14));

		// --- Sprint 22 (closed): older velocity ----------------------------
		resolved(p, "Issue activity stream & comments", Issue.Type.FEATURE,
				"Done", admin, s22, 5, 300, 310, daysAgo(24));
		resolved(p, "Burndown & velocity report endpoints", Issue.Type.FEATURE,
				"Done", tomas, s22, 5, 280, 270, daysAgo(25));
		resolved(p, "Gantt dependencies & critical path", Issue.Type.FEATURE,
				"Done", amara, s22, 8, 460, 480, daysAgo(27));
		resolved(p, "Theme-aware dark mode tokens", Issue.Type.FEATURE,
				"Done", lena, s22, 3, 160, 150, daysAgo(28));

		// --- backlog (no sprint): future work ------------------------------
		backlog(p, "Recurring issues & templates", Issue.Type.FEATURE, lena, 5, List.of("design"));
		backlog(p, "Webhooks for issue lifecycle events", Issue.Type.FEATURE, tomas, 5, List.of());
		backlog(p, "Bulk edit from the issues table", Issue.Type.FEATURE, amara, 3, List.of());
		backlog(p, "Audit log export for compliance", Issue.Type.TASK, jonas, 3, List.of("security"));
		backlog(p, "Markdown paste handling in comments", Issue.Type.BUG, mei, 2,
				List.of("good-first-issue"));
		backlog(p, "Saved board filters per user", Issue.Type.FEATURE, admin, 3, List.of());
	}

	/** Lighter, generic fill for the secondary projects. */
	private void seedSideIssues(Project p, User lead, User a, User b, User qa) {
		issue(p, "Wire up project picker on the connect screen", Issue.Type.FEATURE,
				Issue.Priority.NORMAL, "In Progress", lead, null, 3, 150, 60, List.of(), null, 2);
		issue(p, "Offline cache for the dashboard", Issue.Type.FEATURE,
				Issue.Priority.NORMAL, "Open", a, null, 5, 240, 0, List.of(), null, 0);
		issue(p, "Crash on rotate during sprint planning", Issue.Type.BUG,
				Issue.Priority.MAJOR, "Open", qa, null, 2, 90, 0, List.of(), -1, 0);
		resolved(p, "Bottom-nav liquid-glass refinement", Issue.Type.FEATURE,
				"Done", a, null, 3, 150, 150, daysAgo(3));
		resolved(p, "Set up release lane in fastlane", Issue.Type.TASK,
				"Done", b, null, 2, 120, 110, daysAgo(6));
		resolved(p, "Pin base images by digest", Issue.Type.TASK,
				"Done", lead, null, 1, 60, 55, daysAgo(8));
		backlog(p, "Localize push notification copy", Issue.Type.TASK, qa, 2, List.of());
		backlog(p, "Add staging environment to CI", Issue.Type.TASK, b, 3, List.of());
	}

	// ---- issue helpers -----------------------------------------------------

	private Issue issue(Project p, String title, Issue.Type type, Issue.Priority priority,
			String state, User assignee, Sprint sprint, int points, int estimate, int spent,
			List<String> tags, Integer dueOffsetDays, int rank) {
		return issue(p, title, type, priority, state, assignee, sprint, points, estimate, spent,
				tags, dueOffsetDays, rank, null);
	}

	/** Top-level epic that groups the standard issues seeded under it. */
	private Issue epic(Project p, String title, User assignee, int rank) {
		return issue(p, title, Issue.Type.EPIC, Issue.Priority.MAJOR, "In Progress", assignee,
				null, 0, 0, 0, List.of(), null, rank, null);
	}

	/** Sub-task under a standard [parent] issue (same project, no estimate). */
	private void subtask(Project p, String title, User assignee, String state, Issue parent) {
		issue(p, title, Issue.Type.SUBTASK, Issue.Priority.NORMAL, state, assignee, null,
				0, 0, 0, List.of(), null, 0, parent.getId());
	}

	private Issue issue(Project p, String title, Issue.Type type, Issue.Priority priority,
			String state, User assignee, Sprint sprint, int points, int estimate, int spent,
			List<String> tags, Integer dueOffsetDays, int rank, String parentId) {
		long number = counters.merge(p.getKey(), 1L, Long::sum);
		LocalDate due = dueOffsetDays == null ? null : LocalDate.now().plusDays(dueOffsetDays);
		Issue i = Issue.builder()
				.projectId(p.getId())
				.numberInProject(number)
				.readableId(p.getKey() + "-" + number)
				.title(title)
				.type(type)
				.priority(priority)
				.state(state)
				.assigneeId(assignee.getId())
				.assigneeIds(new java.util.ArrayList<>(java.util.List.of(assignee.getId())))
				.reporterId(p.getLeadId())
				.parentId(parentId)
				.sprintId(sprint == null ? null : sprint.getId())
				.storyPoints(points)
				.estimateMinutes(estimate)
				.spentMinutes(spent)
				.dueDate(due)
				.tags(new ArrayList<>(tags))
				.rank(rank)
				.build();
		i = issues.save(i);
		bumpCounter(p, number);
		return i;
	}

	private void resolved(Project p, String title, Issue.Type type, String state, User assignee,
			Sprint sprint, int points, int estimate, int spent, Instant resolvedAt) {
		Issue i = issue(p, title, type, Issue.Priority.NORMAL, state, assignee, sprint,
				points, estimate, spent, List.of(), null, 0);
		// Backdate creation a few days before resolution for realistic cycle time.
		Instant created = resolvedAt.minus(4, ChronoUnit.DAYS);
		mongo.updateFirst(Query.query(Criteria.where("_id").is(i.getId())),
				new Update().set("resolvedAt", resolvedAt).set("createdAt", created), Issue.class);
	}

	private void backlog(Project p, String title, Issue.Type type, User assignee, int points,
			List<String> tags) {
		issue(p, title, type, Issue.Priority.NORMAL, "Backlog", assignee, null,
				points, points * 60, 0, tags, null, 0);
	}

	private void bumpCounter(Project p, long number) {
		mongo.updateFirst(Query.query(Criteria.where("_id").is(p.getId())),
				new Update().max("issueCounter", number), Project.class);
	}

	private Instant daysAgo(int days) {
		return LocalDate.now().minusDays(days).atTime(14, 30).toInstant(ZoneOffset.UTC);
	}

	// ---- git integration ---------------------------------------------------

	/**
	 * Links each project to its <em>own</em> repository (mixed providers, so the
	 * Development panel exercises both "PR" and "MR") and seeds auto-linked
	 * branches / commits / pull-or-merge-requests / builds on a spread of each
	 * project's issues. Automation is pre-enabled on the demo projects to show the
	 * wiring; the remaining issues stay unlinked to exercise the empty state.
	 */
	private void seedGitIntegration(Project hin, Project mob, Project inf, User admin, List<User> everyone) {
		// HIN → GitHub (OAuth): branch-created, PR-opened and PR-merged all wired.
		Project hinGit = connectDemo(hin, "github", "hinata-platform", "hinata-app", "oauth",
				userById(everyone, hin.getLeadId(), admin), 9, 2, true, true, true);
		// MOB → GitLab (OAuth): shows "Merge request / MR" everywhere.
		Project mobGit = connectDemo(mob, "gitlab", "hinata-platform", "hinata-mobile", "oauth",
				userById(everyone, mob.getLeadId(), admin), 6, 11, true, true, true);
		// INF → GitHub via URL + access token: only PR-merged automation enabled.
		Project infGit = connectDemo(inf, "github", "hinata-platform", "hinata-infra", "token",
				userById(everyone, inf.getLeadId(), admin), 3, 60, false, false, true);

		seedDevInfoFor(hinGit, everyone);
		seedDevInfoFor(mobGit, everyone);
		seedDevInfoFor(infGit, everyone);
	}

	private Project connectDemo(Project project, String provider, String owner, String repo, String method,
			User connectedBy, int connectedDaysAgo, int syncMinutesAgo,
			boolean branchOn, boolean prOpenedOn, boolean prMergedOn) {
		Project.Automation automation = gitService.defaultAutomation(project);
		automation.getBranchCreated().setOn(branchOn);
		automation.getPrOpened().setOn(prOpenedOn);
		automation.getPrMerged().setOn(prMergedOn);
		Instant now = Instant.now();
		// Re-fetch so we don't write the stale in-memory issueCounter (0) back over
		// the value bumped in the DB while seeding this project's issues.
		Project fresh = projects.findById(project.getId()).orElse(project);
		fresh.setGit(Project.Git.builder()
				.id(Project.newId())
				.provider(provider)
				.owner(owner)
				.repo(repo)
				.defaultBranch("main")
				.connectedBy(connectedBy.getId())
				.connectedAt(now.minus(Duration.ofDays(connectedDaysAgo)))
				.lastSyncAt(now.minus(Duration.ofMinutes(syncMinutesAgo)))
				.method(method)
				.branchTemplate("{key}-{summary}")
				.automation(automation)
				.encryptedToken(tokenCipher.encrypt(provider + "-demo-token-" + project.getKey()))
				.build());
		return projects.save(fresh);
	}

	private void seedDevInfoFor(Project project, List<User> people) {
		boolean gitlab = "gitlab".equals(project.getGit().getProvider());
		List<Issue> pool = issues
				.findByProjectId(project.getId(), PageRequest.of(0, 40, Sort.by("numberInProject")))
				.getContent().stream()
				.filter(i -> i.getType() != Issue.Type.EPIC && i.getType() != Issue.Type.SUBTASK)
				.limit(3)
				.toList();
		Instant now = Instant.now();
		for (int idx = 0; idx < pool.size(); idx++) {
			Issue issue = pool.get(idx);
			String key = issue.getReadableId();
			String branchName = key + "-" + gitSlug(issue.getTitle());
			String authorId = primaryUser(issue, people);
			// idx 0 → open PR, 1 → draft PR, 2 → merged PR (with fewer categories).
			String prState = switch (idx) {
				case 0 -> "OPEN";
				case 1 -> "DRAFT";
				default -> "MERGED";
			};
			String checks = idx == 1 ? "failing" : "passing";

			List<GitDevInfo.Commit> commits = new ArrayList<>();
			commits.add(GitDevInfo.Commit.builder()
					.sha(fakeSha(key, 1)).message(key + " " + issue.getTitle())
					.authorId(authorId).at(now.minus(Duration.ofHours(2))).additions(142).deletions(38)
					.verified(true).build());
			if (idx <= 1) {
				commits.add(GitDevInfo.Commit.builder()
						.sha(fakeSha(key, 2)).message(key + " Add regression coverage and tidy edge cases")
						.authorId(authorId).at(now.minus(Duration.ofHours(6))).additions(64).deletions(12)
						.verified(true).build());
			}
			if (idx == 0 && project.getGit().getAutomation().isSmartCommits()) {
				// A real smart-commit message — parsed by SmartCommitParser / applied by
				// GitService.applySmartCommits on a push webhook (see §5).
				commits.add(GitDevInfo.Commit.builder()
						.sha(fakeSha(key, 3))
						.message(key + " #time 2h 30m #comment Verified against the design redline")
						.authorId(authorId).at(now.minus(Duration.ofHours(9))).additions(22).deletions(9)
						.verified(false).build());
			}

			GitDevInfo.PullRequest pr = GitDevInfo.PullRequest.builder()
					.number((gitlab ? 50 : 120) + idx)
					.title(key + " " + issue.getTitle())
					.state(prState)
					.authorId(authorId)
					.reviewerIds(pickReviewers(authorId, people, idx == 1 ? 0 : (idx == 2 ? 1 : 2)))
					.approvals(idx == 2 ? 2 : (idx == 0 ? 1 : 0))
					.changesRequested(0)
					.comments(idx + 2)
					.sourceBranch(branchName).targetBranch("main")
					.at(now.minus(Duration.ofHours(1L + idx)))
					.checks(checks)
					.build();

			List<GitDevInfo.Branch> branches = new ArrayList<>();
			List<GitDevInfo.Build> builds = new ArrayList<>();
			if (idx <= 1) {
				branches.add(GitDevInfo.Branch.builder()
						.name(branchName).base("main")
						.ahead(idx == 0 ? 6 : 3).behind(idx == 0 ? 2 : 0)
						.updatedAt(now.minus(Duration.ofHours(2L + idx))).authorId(authorId).build());
				builds.add(GitDevInfo.Build.builder()
						.name(gitlab ? "pipeline · test" : "CI · build & test")
						.workflow(gitlab ? ".gitlab-ci.yml" : "ci.yml")
						.branch(branchName)
						.status(checks)
						.duration(idx == 1 ? "3m 09s" : "2m 41s")
						.at(now.minus(Duration.ofHours(1L + idx))).build());
			}

			gitDevInfos.save(GitDevInfo.builder()
					.issueKey(key)
					.projectId(project.getId())
					.branches(branches)
					.commits(commits)
					.prs(new ArrayList<>(List.of(pr)))
					.builds(builds)
					.updatedAt(now)
					.build());
		}
	}

	private static String primaryUser(Issue issue, List<User> people) {
		if (issue.getAssigneeId() != null) {
			return issue.getAssigneeId();
		}
		if (issue.getReporterId() != null) {
			return issue.getReporterId();
		}
		return people.get(0).getId();
	}

	private static List<String> pickReviewers(String authorId, List<User> people, int count) {
		List<String> reviewers = new ArrayList<>();
		for (User u : people) {
			if (reviewers.size() >= count) {
				break;
			}
			if (!u.getId().equals(authorId)) {
				reviewers.add(u.getId());
			}
		}
		return reviewers;
	}

	private static User userById(List<User> people, String id, User fallback) {
		return people.stream().filter(u -> u.getId().equals(id)).findFirst().orElse(fallback);
	}

	/** Branch-name slug: lowercase, non-alphanumerics → '-', first five words. */
	private static String gitSlug(String title) {
		String cleaned = title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		String[] words = cleaned.split("-");
		StringBuilder slug = new StringBuilder();
		for (int i = 0; i < words.length && i < 5; i++) {
			if (slug.length() > 0) {
				slug.append('-');
			}
			slug.append(words[i]);
		}
		return slug.toString();
	}

	/** Deterministic, sha-like 7-char hex for a seeded commit. */
	private static String fakeSha(String key, int n) {
		String hex = Integer.toHexString((key + ":" + n).hashCode() & 0x0fffffff);
		while (hex.length() < 7) {
			hex = "0" + hex;
		}
		return hex.substring(0, 7);
	}

	// ---- security audit log ------------------------------------------------

	/**
	 * Seeds a realistic spread of security-audit records so the admin "Audit log"
	 * surface renders real history out of the box. Written straight to Mongo and
	 * back-dated (the live {@code AuditService} path is gated on request context),
	 * mirroring exactly what the instrumented services would have recorded.
	 */
	private void seedAuditLog(User admin, User tomas, User lena, User amara, User mei, User jonas) {
		// hoursAgo, action, severity?, outcome?, actor, target, ip, ua, meta…
		auditEvent(2, AuditAction.LOGIN_SUCCESS, admin, null, "203.0.113.xx.xx",
				"hinata app", "mfa", "totp");
		auditEvent(3, AuditAction.SETTINGS_CHANGED, admin, null, "203.0.113.xx.xx",
				"Chrome", "auditEnabled", "true");
		auditEvent(6, AuditAction.LOGIN_FAILURE, null, null, "198.51.100.xx.xx",
				"Firefox", "reason", "invalidCredentials").setActorLabel("tomas@hinata.dev");
		auditEvent(6, AuditAction.LOGIN_FAILURE, null, null, "198.51.100.xx.xx",
				"Firefox", "reason", "invalidCredentials").setActorLabel("tomas@hinata.dev");
		auditEvent(7, AuditAction.LOGIN_BLOCKED, null, null, "198.51.100.xx.xx",
				"Firefox", "reason", "tooManyAttempts").setActorLabel("tomas@hinata.dev");
		auditEvent(11, AuditAction.LOGIN_SUCCESS, tomas, null, "192.0.2.xx.xx", "Chrome");
		auditEvent(14, AuditAction.TWO_FACTOR_ENABLED, lena, null, "192.0.2.xx.xx", "Safari");
		auditEvent(18, AuditAction.PASSWORD_CHANGED, amara, null, "192.0.2.xx.xx", "hinata app");
		auditEvent(22, AuditAction.USER_ROLE_CHANGED, admin, tomas, "203.0.113.xx.xx",
				"Chrome", "from", "USER", "to", "ADMIN");
		auditEvent(24, AuditAction.USER_INVITED, admin, mei, "203.0.113.xx.xx",
				"Chrome", "role", "MEMBER");
		auditEvent(27, AuditAction.MFA_FAILURE, jonas, null, "192.0.2.xx.xx", "hinata app");
		auditEvent(30, AuditAction.SESSION_REVOKED, jonas, null, "192.0.2.xx.xx",
				"Safari", "scope", "others");
		auditEvent(34, AuditAction.EMAIL_CHANGE_REQUESTED, amara, null, "192.0.2.xx.xx",
				"hinata app", "newEmail", "amara.okafor@hinata.dev");
		auditEvent(38, AuditAction.USER_DEACTIVATED, admin, mei, "203.0.113.xx.xx", "Chrome");
		auditEvent(41, AuditAction.SSO_LOGIN, lena, null, "192.0.2.xx.xx",
				"Chrome", "provider", "OIDC");
		auditEvent(45, AuditAction.DATA_EXPORT_REQUESTED, jonas, null, "192.0.2.xx.xx", "hinata app");
		log.info("[demo] seeded {} audit-log records", mongo.count(new Query(), AuditLog.class));
	}

	/**
	 * Builds, persists and back-dates one {@link AuditLog} record. Trailing
	 * {@code meta} varargs are key/value pairs. Returns the saved entry so the
	 * caller can tweak it (e.g. set an anonymous actor label).
	 */
	private AuditLog auditEvent(int hoursAgo, AuditAction action, User actor, User target,
			String ip, String userAgent, String... meta) {
		Map<String, String> metadata = new LinkedHashMap<>();
		for (int i = 0; i + 1 < meta.length; i += 2) {
			metadata.put(meta[i], meta[i + 1]);
		}
		AuditLog.Outcome outcome = switch (action) {
			case LOGIN_FAILURE, LOGIN_BLOCKED, MFA_FAILURE -> AuditLog.Outcome.FAILURE;
			default -> AuditLog.Outcome.SUCCESS;
		};
		AuditLog entry = AuditLog.builder()
				.action(action)
				.category(action.category())
				.severity(action.defaultSeverity())
				.outcome(outcome)
				.actorId(actor == null ? null : actor.getId())
				.actorLabel(actor == null ? null : actor.getDisplayName())
				.targetId(target == null ? null : target.getId())
				.targetLabel(target == null ? null : target.getDisplayName())
				.ip(ip)
				.userAgent(userAgent)
				.metadata(metadata.isEmpty() ? null : metadata)
				.build();
		AuditLog saved = mongo.save(entry);
		Instant when = Instant.now().minus(hoursAgo, ChronoUnit.HOURS);
		mongo.updateFirst(Query.query(Criteria.where("_id").is(saved.getId())),
				new Update().set("timestamp", when), AuditLog.class);
		saved.setTimestamp(when);
		return saved;
	}

	/** Stagger start/due dates across ~3 weeks for the first dozen HIN issues,
	 * chaining a few dependencies so the timeline shows links. */
	private void scheduleTimeline(Project p) {
		List<Issue> list = mongo.find(Query.query(Criteria.where("projectId").is(p.getId()))
				.with(Sort.by("numberInProject")).limit(12), Issue.class);
		LocalDate base = LocalDate.now().minusDays(6);
		String prev = null;
		int offset = 0;
		for (Issue i : list) {
			LocalDate start = base.plusDays(offset);
			int points = i.getStoryPoints() == null ? 2 : i.getStoryPoints();
			LocalDate due = start.plusDays(Math.clamp(points, 2, 6));
			Update u = new Update().set("startDate", start).set("dueDate", due);
			if (prev != null && offset % 4 == 0) {
				u.set("dependsOnIds", List.of(prev));
			}
			mongo.updateFirst(Query.query(Criteria.where("_id").is(i.getId())), u, Issue.class);
			prev = i.getId();
			offset += 2;
		}
	}

	/**
	 * Seeds a representative spread of {@link IssueLink}s across the first standard
	 * HIN issues — one of most link types — so the "Verknüpfte Vorgänge" panel and
	 * its grouped inward/outward verbs render real data on first run.
	 */
	private void seedIssueLinks(Project p) {
		List<Issue> standard = mongo.find(Query.query(Criteria.where("projectId").is(p.getId())
						.and("type").in(Issue.Type.TASK, Issue.Type.STORY, Issue.Type.BUG,
								Issue.Type.FEATURE))
				.with(Sort.by("numberInProject")), Issue.class);
		if (standard.size() < 6) return;
		// source "verb" target — stored once, rendered from both ends.
		link(IssueLinkType.BLOCKS, standard.get(0), standard.get(1));
		link(IssueLinkType.BLOCKS, standard.get(2), standard.get(3));
		link(IssueLinkType.RELATES, standard.get(1), standard.get(4));
		link(IssueLinkType.DUPLICATES, standard.get(5), standard.get(2));
		link(IssueLinkType.TESTS, standard.get(4), standard.get(0));
		if (standard.size() >= 8) {
			link(IssueLinkType.CLONES, standard.get(6), standard.get(7));
			link(IssueLinkType.SPLITS, standard.get(0), standard.get(6));
		}
		log.info("[demo] seeded {} issue links", issueLinks.count());
	}

	private void link(IssueLinkType type, Issue source, Issue target) {
		issueLinks.save(IssueLink.builder()
				.type(type)
				.sourceId(source.getId())
				.targetId(target.getId())
				.build());
	}

	// ---- time tracking -----------------------------------------------------

	/** A believable "focus this week" for the admin: ~26h30m across the week. */
	private void seedTracker(User user, Project p) {
		String issueId = issues.findByProjectId(p.getId(),
				org.springframework.data.domain.PageRequest.of(0, 1)).getContent().get(0).getId();
		LocalDate monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
		int[] minutes = {255, 300, 210, 345, 270, 0, 0}; // Mon–Sun
		String[] activities = {"Development", "Development", "Code Review", "Development",
				"Testing", "Development", "Development"};
		for (int d = 0; d < 7; d++) {
			if (minutes[d] == 0) continue;
			workItems.save(WorkItem.builder()
					.issueId(issueId)
					.projectId(p.getId())
					.userId(user.getId())
					.date(monday.plusDays(d))
					.durationMinutes(minutes[d])
					.activityType(activities[d])
					.description("Demo tracked work")
					.build());
		}
	}

	// ---- knowledge base ----------------------------------------------------

	/**
	 * Seeds a small, organisation-wide knowledge base whose markdown bodies
	 * cross-link to the *real* seeded issues ({@code {{issue:HIN-n}}}), people
	 * ({@code {{user:<id>}}}) and other articles ({@code {{doc:<id>}}}). Mirrors
	 * the spaces/articles the frontend KB renders — but as genuine backend data.
	 */
	private void seedKnowledge(Team core, Team dsgn, Project hin, Project inf,
			User admin, User tomas, User lena, User amara, User mei, User jonas) {
		String u = "{{user:" + admin.getId() + "}}";

		// --- spaces (persisted; carry their own icon/hue/description) ----------
		space("Engineering", "code-xml", 250, 0, admin,
				"Architecture, services, release & on-call runbooks.");
		space("Product", "compass", 155, 1, admin,
				"Specs, workflow rules and decision records.");
		space("Design", "palette", 300, 2, admin,
				"Brand, motion and the Hive design system.");
		space("Operations", "server-cog", 200, 3, admin,
				"Infra, deploy and observability.");

		// --- Engineering — team-wide (CORE team) ---------------------------
		Article engHome = teamArticle(core, "Engineering", "code-xml", "Engineering handbook", null,
				admin, List.of("handbook"), 0,
				"# Engineering handbook\n\nHow we build, ship and run Hinata. Start with the "
						+ "**Release checklist** before any deploy.\n\n:::info\nEvery production change "
						+ "goes through review, CI and a tagged release.\n:::\n\nMaintained by " + u + ".");
		Article release = teamArticle(core, "Engineering", "rocket", "Release checklist & version gating",
				engHome.getId(), jonas, List.of("release", "ci"), 0,
				"## Release checklist\n\nBefore cutting a release:\n\n- [ ] All blocking issues closed\n"
						+ "- [ ] CI green on `main`\n- [ ] Version bumped & tagged\n\nThe self-host deploy "
						+ "is automated in {{issue:HIN-5}}. Owner: {{user:" + jonas.getId() + "}}.\n\n"
						+ "### Version gating\n\nThe `/meta` endpoint advertises the minimum client "
						+ "version; older apps show an upgrade gate.");
		teamArticle(core, "Engineering", "key-round", "Auth & token refresh", engHome.getId(),
				tomas, List.of("auth", "api"), 1,
				"## Auth & token refresh\n\nAccess + refresh tokens, rotated on use.\n\n:::warn\nWhen two "
						+ "requests refresh at once, the second can present a used refresh token. Tracked in "
						+ "{{issue:HIN-3}} — owner {{user:" + tomas.getId() + "}}.\n:::\n\n```ts\nlet inflight: "
						+ "Promise<Tokens> | null = null;\nasync function refresh() { /* dedupe */ }\n```\n\n"
						+ "See also the release flow in {{doc:" + release.getId() + "}}.");

		// --- Product — project-scoped (HIN project) ------------------------
		Article prodHome = projectArticle(hin, "Product", "compass", "Product principles", null, lena,
				List.of("product"), 0,
				"# Product principles\n\nCalm, fast, keyboard-first. We bias toward fewer, sharper "
						+ "surfaces over many shallow ones.\n\nRoadmap themes live with the board redesign "
						+ "{{issue:HIN-1}}.");
		projectArticle(hin, "Product", "sparkles", "Board views v2 spec", prodHome.getId(), lena,
				List.of("board", "spec"), 0,
				"## Board views v2\n\nBoard · Backlog · Timeline, with a people filter and a calmer "
						+ "column rhythm.\n\nDriven by {{issue:HIN-1}}; the drag performance work is "
						+ "{{issue:HIN-2}} ({{user:" + amara.getId() + "}}).\n\n| View | Groups by |\n"
						+ "| --- | --- |\n| Board | State |\n| Backlog | Sprint |\n| Timeline | Dates |");

		// --- Design — team-wide (DSGN team) --------------------------------
		teamArticle(dsgn, "Design", "palette", "The Hive design system", null, lena,
				List.of("design", "tokens"), 0,
				"# The Hive design system\n\nNavy rail, warm-paper canvas, honey-amber accents.\n\n"
						+ "Inline editing on the issue detail is {{issue:HIN-3}}. Reviewed by " + u + ".");

		// --- Operations — project-scoped (INF project) ---------------------
		Article ops = projectArticle(inf, "Operations", "server-cog", "Self-hosting guide", null, jonas,
				List.of("infra", "deploy"), 0,
				"# Self-hosting guide\n\nReverse proxy, TLS, MongoDB and the bundled app.\n\n"
						+ "Blue-green deploys are scripted in {{issue:HIN-5}}.");
		projectArticle(inf, "Operations", "database-backup", "Backups & restore", ops.getId(), jonas,
				List.of("infra", "backup"), 0,
				"## Backups & restore\n\nNightly `mongodump`, encrypted off-site. Test the restore "
						+ "monthly.\n\nOn-call: {{user:" + mei.getId() + "}}.");
	}

	private Space space(String name, String icon, int hue, int sortOrder, User author, String desc) {
		return spaceRepo.save(Space.builder()
				.name(name)
				.icon(icon)
				.hue(hue)
				.description(desc)
				.sortOrder(sortOrder)
				.authorId(author.getId())
				.build());
	}

	private Article teamArticle(Team team, String space, String icon, String title, String parentId,
			User author, List<String> tags, int sortOrder, String body) {
		return article(null, team.getId(), space, icon, title, parentId, author, tags, sortOrder, body);
	}

	private Article projectArticle(Project project, String space, String icon, String title,
			String parentId, User author, List<String> tags, int sortOrder, String body) {
		return article(project.getId(), null, space, icon, title, parentId, author, tags, sortOrder,
				body);
	}

	private Article article(String projectId, String teamId, String space, String icon, String title,
			String parentId, User author, List<String> tags, int sortOrder, String body) {
		return articleRepo.save(Article.builder()
				.projectId(projectId)
				.teamId(teamId)
				.parentId(parentId)
				.space(space)
				.icon(icon)
				.title(title)
				.content(body)
				.tags(new ArrayList<>(tags))
				.authorId(author.getId())
				.sortOrder(sortOrder)
				.build());
	}
}
