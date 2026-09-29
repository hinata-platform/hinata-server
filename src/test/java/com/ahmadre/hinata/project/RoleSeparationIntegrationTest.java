package com.ahmadre.hinata.project;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleAccess;
import com.ahmadre.hinata.article.ArticleService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.RelativeDate;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.migration.MigrationMarkers;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.search.SearchResponse;
import com.ahmadre.hinata.search.SearchService;
import com.ahmadre.hinata.setup.AdminSettingsController;
import com.ahmadre.hinata.setup.OrgSettingsController;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.team.KnowledgeAccess;
import com.ahmadre.hinata.team.ProjectAccess;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.team.TeamRole;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * HIN-129 against a real MongoDB: organisational access and content are separate.
 *
 * <p>An administrator runs the platform and reads nobody's project, team, page or
 * search hit through that role. An organisation admin runs working time and
 * absences and edits the organisation's settings. A Team-Admin runs the settings of
 * the team's projects. Knowledge-base pages are opened through projects and teams,
 * and a page without either belongs to its author alone.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.project-templates.enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class RoleSeparationIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private UserRepository users;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private ProjectService projectService;
	@Autowired
	private TeamRepository teams;
	@Autowired
	private TeamService teamService;
	@Autowired
	private IssueService issues;
	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private ArticleService articles;
	@Autowired
	private ArticleAccess articleAccess;
	@Autowired
	private SearchService search;
	@Autowired
	private OrgSettingsController orgSettings;
	@Autowired
	private AdminSettingsController adminSettings;
	@Autowired
	private SettingsService settings;
	@Autowired
	private ProjectTemplatePolicy templates;
	@Autowired
	private com.ahmadre.hinata.migration.OrgAdminRoleBackfill backfill;

	@MockitoBean
	private CurrentUser currentUser;

	private User admin;
	private User orgAdmin;
	private User lead;
	private User teamAdmin;
	private User member;
	private User outsider;
	private Project project;
	private Team team;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "projects", "teams", "issues", "articles",
				"issue_activities", "server_settings", MigrationMarkers.COLLECTION)) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		settings.save(new ServerSettings());
		admin = user("admin", Role.ADMIN);
		orgAdmin = user("orgadmin", Role.ORG_ADMIN);
		lead = user("lead");
		teamAdmin = user("teamadmin");
		member = user("member");
		outsider = user("outsider");
		project = projects.save(Project.builder().key("SEC").name("Secret plans")
				.leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId())))
				.workflowStates(new ArrayList<>(List.of(
						Project.WorkflowState.builder().id("s1").name("Open").build(),
						Project.WorkflowState.builder().id("s2").name("Done").build())))
				.resolvedStates(new ArrayList<>(List.of("Done")))
				.build());
		team = teams.save(Team.builder().key("ORG").name("Orga")
				.projectIds(new ArrayList<>(List.of(project.getId())))
				.members(new ArrayList<>(List.of(
						TeamMembership.builder().userId(teamAdmin.getId()).role(TeamRole.ADMIN)
								.access(ProjectAccess.all()).knowledge(KnowledgeAccess.all()).build(),
						TeamMembership.builder().userId(member.getId()).role(TeamRole.MEMBER)
								.access(ProjectAccess.none()).build())))
				.build());
	}

	// --- administrators read nothing through the role ------------------------

	@Test
	void anAdministratorSeesNoProjectTheyAreNotIn() {
		issue("Secret issue");

		assertThat(projectService.visibleTo(admin)).isEmpty();
		assertThatThrownBy(() -> projectService.assertMember(project, admin)).isInstanceOf(ApiException.class);
		assertThat(projectService.canManage(project, admin)).isFalse();
		assertThat(teamService.visibleTo(admin)).isEmpty();
		assertThat(teamService.canManage(team, admin)).isFalse();
	}

	@Test
	void searchFindsOnlyWhatTheCallerReaches() {
		issue("Secret issue");

		assertThat(hits(search.search(admin, "Secret", "ISSUES", false))).isZero();
		assertThat(hits(search.search(outsider, "Secret", "PROJECTS", false))).isZero();
		assertThat(hits(search.search(member, "Secret", "ISSUES", false))).isEqualTo(1);
		assertThat(search.search(admin, "", null, false).counts().get("ISSUES")).isZero();
	}

	// --- Team-Admins run the settings of their team's projects ----------------

	@Test
	void aTeamAdminOfAnOwningTeamManagesTheProjectButDoesNotLeadIt() {
		assertThat(projectService.canManage(project, teamAdmin)).isTrue();
		Project renamed = projectService.applyUpdate(project.getId(), new ProjectUpdateRequest(null,
				"Renamed", null, null, null, null, null, null, null, null, null, null, null, null),
				teamAdmin);
		assertThat(renamed.getName()).isEqualTo("Renamed");

		assertThatThrownBy(() -> projectService.assertLead(project, teamAdmin)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> projectService.applyUpdate(project.getId(), new ProjectUpdateRequest(null,
				"Nope", null, null, null, null, null, null, null, null, null, null, null, null), member))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void aProjectCountsDeadlinesItsOwnWayOrTheOrganisations() {
		Project updated = projectService.applyUpdate(project.getId(), new ProjectUpdateRequest(null, null, null,
				null, null, null, null, null, null, null, null, null, null, RelativeDate.Basis.WORKING, null, null),
				lead);
		assertThat(updated.getDeadlineBasis()).isEqualTo(RelativeDate.Basis.WORKING);

		Project cleared = projectService.applyUpdate(project.getId(), new ProjectUpdateRequest(null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, true, null), lead);
		assertThat(cleared.getDeadlineBasis()).isNull();
	}

	// --- one deadline for several issues --------------------------------------

	@Test
	void aBulkDeadlineWritesEveryIssueOrNone() {
		Issue a = issue("First");
		Issue b = issue("Second");
		LocalDate due = LocalDate.of(2026, 12, 1);

		assertThatThrownBy(() -> issues.updateAll(List.of(a.getId(), b.getId()),
				issue -> issue.setDueDate(due), outsider)).isInstanceOf(ApiException.class);
		assertThat(issueRepository.findById(a.getId()).orElseThrow().getDueDate()).isNull();

		List<Issue> updated = issues.updateAll(List.of(a.getId(), b.getId(), a.getId()),
				issue -> issue.setDueDate(due), member);
		assertThat(updated).extracting(Issue::getDueDate).containsOnly(due);
		assertThat(issueRepository.findById(b.getId()).orElseThrow().getDueDate()).isEqualTo(due);
	}

	@Test
	void aBulkChangeIsBounded() {
		List<String> ids = IntStream.range(0, IssueService.MAX_BULK + 1).mapToObj(i -> "id-" + i).toList();
		assertThatThrownBy(() -> issues.updateAll(ids, issue -> { }, member))
				.isInstanceOf(ApiException.class).hasMessageContaining("bulkTooMany");
	}

	// --- knowledge base follows projects and teams ---------------------------

	@Test
	void aPageWithoutProjectOrTeamIsPrivateToItsAuthor() {
		Article mine = page(lead, null, null, null, "My notes");

		assertThat(articleAccess.canSee(mine, lead)).isTrue();
		assertThat(articleAccess.canSee(mine, member)).isFalse();
		assertThat(articleAccess.canSee(mine, admin)).isFalse();
	}

	@Test
	void aTeamOpensExactlyThePagesItGrantsWithEverythingBelowThem() {
		Article handbook = page(teamAdmin, null, team.getId(), null, "Handbook");
		Article chapter = page(teamAdmin, null, null, handbook.getId(), "Chapter");
		Article budget = page(teamAdmin, null, team.getId(), null, "Budget");

		assertThat(chapter.getTeamId()).isEqualTo(team.getId());
		assertThat(articleAccess.canSee(handbook, member)).isFalse();

		teamService.updateMembership(teams.findById(team.getId()).orElseThrow(), teamAdmin, member.getId(),
				null, null, KnowledgeAccess.some(List.of(handbook.getId())));

		assertThat(articles.list(member, false, null)).extracting(Article::getTitle)
				.containsExactlyInAnyOrder("Handbook", "Chapter");
		assertThat(articleAccess.canSee(budget, member)).isFalse();
		assertThat(articles.list(admin, false, null)).isEmpty();
		assertThat(articles.list(teamAdmin, false, null)).hasSize(3);
	}

	@Test
	void aTeamCanOnlyOpenItsOwnPages() {
		Article foreign = page(lead, project.getId(), null, null, "Project page");

		assertThatThrownBy(() -> teamService.updateMembership(teams.findById(team.getId()).orElseThrow(),
				teamAdmin, member.getId(), null, null, KnowledgeAccess.some(List.of(foreign.getId()))))
				.isInstanceOf(ApiException.class).hasMessageContaining("pagesNotOfTeam");
	}

	@Test
	void movingAPageTakesItsSubtreeAlong() {
		Article root = page(lead, null, null, null, "Draft");
		Article child = page(lead, null, null, root.getId(), "Draft detail");

		articles.place(root.getId(), project.getId(), null, lead);

		assertThat(articleAccess.canSee(child, member)).isFalse(); // stale object
		Article reloaded = mongo.findById(child.getId(), Article.class);
		assertThat(reloaded.getProjectId()).isEqualTo(project.getId());
		assertThat(articleAccess.canSee(reloaded, member)).isTrue();
		assertThatThrownBy(() -> articles.place(child.getId(), null, null, lead))
				.isInstanceOf(ApiException.class).hasMessageContaining("placeFollowsParent");
		assertThatThrownBy(() -> articles.place(root.getId(), null, null, member))
				.isInstanceOf(ApiException.class).hasMessageContaining("privateIsAuthorOnly");
	}

	@Test
	void aPageCannotBeFiledIntoAPlaceTheAuthorCannotReach() {
		assertThatThrownBy(() -> page(outsider, project.getId(), null, null, "Planted"))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> page(outsider, null, team.getId(), null, "Planted"))
				.isInstanceOf(ApiException.class);
	}

	// --- the organisation's settings -----------------------------------------

	@Test
	void onlyOrganisationAdminsReachTheOrganisationSettings() {
		when(currentUser.require()).thenReturn(admin);
		assertThatThrownBy(() -> orgSettings.get()).isInstanceOf(ApiException.class);

		when(currentUser.require()).thenReturn(orgAdmin);
		OrgSettingsController.OrgSettings saved = orgSettings.update(
				new OrgSettingsController.OrgSettingsUpdate(null, RelativeDate.Basis.WORKING, null));
		assertThat(saved.defaultDeadlineBasis()).isEqualTo(RelativeDate.Basis.WORKING);
		assertThat(templates.defaultBasis()).isEqualTo(RelativeDate.Basis.WORKING);

		OrgSettingsController.OrgSettings cleared = orgSettings.update(
				new OrgSettingsController.OrgSettingsUpdate(null, null, true));
		assertThat(cleared.defaultDeadlineBasis()).isNull();
		assertThat(cleared.effectiveDeadlineBasis()).isEqualTo(RelativeDate.Basis.CALENDAR);
	}

	@Test
	void anAdministratorSaveLeavesTheOrganisationsBlocksAlone() {
		when(currentUser.require()).thenReturn(orgAdmin);
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setCurrency("CHF");
		orgSettings.update(new OrgSettingsController.OrgSettingsUpdate(block, RelativeDate.Basis.WORKING, null));

		when(currentUser.require()).thenReturn(admin);
		ServerSettings body = adminSettings.get();
		body.getTimeTracking().setCurrency("USD");
		body.getProjectTemplates().setDefaultBasis(RelativeDate.Basis.CALENDAR);
		adminSettings.update(body);

		ServerSettings stored = settings.get();
		assertThat(stored.getTimeTracking().getCurrency()).isEqualTo("CHF");
		assertThat(stored.getProjectTemplates().getDefaultBasis()).isEqualTo(RelativeDate.Basis.WORKING);
	}

	@Test
	void theHandOverMakesEveryAdministratorAnOrganisationAdminOnce() {
		backfill.run(new DefaultApplicationArguments());

		assertThat(users.findById(admin.getId()).orElseThrow().isOrgAdmin()).isTrue();
		assertThat(users.findById(lead.getId()).orElseThrow().isOrgAdmin()).isFalse();

		// Taken away on purpose afterwards, it stays taken away.
		User separated = users.findById(admin.getId()).orElseThrow();
		separated.setRoles(Set.of(Role.ADMIN, Role.MEMBER));
		users.save(separated);
		backfill.run(new DefaultApplicationArguments());
		assertThat(users.findById(admin.getId()).orElseThrow().isOrgAdmin()).isFalse();
	}

	// --- helpers ----------------------------------------------------------------

	private static int hits(SearchResponse response) {
		return response.groups().stream().mapToInt(group -> group.items().size()).sum();
	}

	private Issue issue(String title) {
		long number = issueRepository.count() + 1;
		return issueRepository.save(Issue.builder().projectId(project.getId()).title(title)
				.readableId("SEC-" + number).numberInProject(number).state("Open")
				.type(Issue.Type.TASK).build());
	}

	private Article page(User author, String projectId, String teamId, String parentId, String title) {
		return articles.create(author, new ArticleService.Draft(title, RichText.EMPTY, projectId, teamId,
				parentId, null, null, null, null));
	}

	private User user(String name, Role... extra) {
		Set<Role> roles = new java.util.HashSet<>(Set.of(Role.MEMBER));
		roles.addAll(List.of(extra));
		return users.save(User.builder().username(name).email(name + "@example.org")
				.displayName(name).roles(roles).build());
	}
}
