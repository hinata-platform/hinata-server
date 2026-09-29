package com.ahmadre.hinata.project;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleAccess;
import com.ahmadre.hinata.article.ArticleService;
import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditFeed;
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

	@Autowired
	private com.ahmadre.hinata.audit.AuditService audit;
	@Autowired
	private AuditFeed auditFeed;
	@Autowired
	private com.ahmadre.hinata.admin.AdminUserService adminUsers;
	@Autowired
	private com.ahmadre.hinata.timeoff.TimeOffAccess timeOffAccess;
	@Autowired
	private com.ahmadre.hinata.availability.AvailabilityAccess availabilityAccess;
	@Autowired
	private com.ahmadre.hinata.team.TeamController teamController;
	@Autowired
	private com.ahmadre.hinata.article.TeamPagesController teamPages;
	@Autowired
	private com.ahmadre.hinata.audit.OrgAuditController orgAudit;
	@Autowired
	private com.ahmadre.hinata.auth.PasswordResetService passwordResets;

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
				"issue_activities", "server_settings", "audit_log", "team_activity", MigrationMarkers.COLLECTION)) {
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
	void aTeamAdminCannotAppointThemselvesLead() {
		assertThatThrownBy(() -> projectService.applyUpdate(project.getId(), new ProjectUpdateRequest(null, null,
				null, null, List.of(teamAdmin.getId()), null, null, null, null, null, null, null, null, null),
				teamAdmin)).isInstanceOf(ApiException.class).hasMessageContaining("notLead");

		// The same leads sent back, as a settings screen does, are no change.
		Project saved = projectService.applyUpdate(project.getId(), new ProjectUpdateRequest(null, "Same leads",
				null, null, List.of(lead.getId()), null, null, null, null, null, null, null, null, null), teamAdmin);
		assertThat(saved.getLeadIds()).containsExactly(lead.getId());
	}

	@Test
	void aProjectFollowsTheOrganisationUntilItSaysOtherwise() {
		assertThat(templates.effectiveBasis(project)).isEqualTo(RelativeDate.Basis.CALENDAR);
		when(currentUser.require()).thenReturn(orgAdmin);
		orgSettings.update(new OrgSettingsController.OrgSettingsUpdate(null, RelativeDate.Basis.WORKING, null));
		assertThat(templates.effectiveBasis(projects.findById(project.getId()).orElseThrow()))
				.isEqualTo(RelativeDate.Basis.WORKING);
	}

	@Test
	void aProjectSetsAndClearsItsOwnDeadlineBasis() {
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

		// Answered like an issue that does not exist: an outsider learns nothing.
		assertThatThrownBy(() -> issues.updateAll(List.of(a.getId(), b.getId()),
				issue -> issue.setDueDate(due), outsider)).isInstanceOf(ApiException.class)
				.hasMessageContaining("notFound");
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
		// The block is the organisation's: an administrator is not even shown it.
		assertThat(body.getTimeTracking()).isNull();
		ServerSettings.TimeTracking attempt = new ServerSettings.TimeTracking();
		attempt.setCurrency("USD");
		body.setTimeTracking(attempt);
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

	// --- HIN-129 review round -------------------------------------------------

	@Test
	void thePlatformAuditFeedLeavesOutTheOrganisationsRecordsAndContentDetails() {
		audit.event(AuditAction.TIME_OFF_SICK_REPORTED).actor(member).target(member).meta("from", "2026-10-01").log();
		audit.event(AuditAction.ISSUE_DELETED).actor(lead).meta("issue", "SEC-1").log();
		audit.event(AuditAction.LOGIN_SUCCESS).actor(member).log();
		AuditFeed.Filter all = new AuditFeed.Filter(null, null, null, null, null, null, null, null, 1, 50);

		List<AuditFeed.AuditEntryResponse> platform = auditFeed.page(all, AuditFeed.Scope.PLATFORM).items();
		assertThat(platform).extracting(AuditFeed.AuditEntryResponse::action)
				.contains("ISSUE_DELETED", "LOGIN_SUCCESS").doesNotContain("TIME_OFF_SICK_REPORTED");
		assertThat(platform).filteredOn(row -> row.action().equals("ISSUE_DELETED"))
				.allSatisfy(row -> assertThat(row.metadata()).isEmpty());

		assertThat(auditFeed.page(all, AuditFeed.Scope.ORGANISATION).items())
				.extracting(AuditFeed.AuditEntryResponse::action).containsExactly("TIME_OFF_SICK_REPORTED");
	}

	@Test
	void anAdministratorCannotGrantThemselvesTheOrganisationRoleNorRemoveTheLastOne() {
		when(currentUser.requireId()).thenReturn(admin.getId());
		assertThatThrownBy(() -> adminUsers.setOrgAdmin(List.of(admin.getId()), true))
				.isInstanceOf(ApiException.class).hasMessageContaining("cannotGrantOwnOrgRole");
		assertThatThrownBy(() -> adminUsers.setOrgAdmin(List.of(orgAdmin.getId()), false))
				.isInstanceOf(ApiException.class).hasMessageContaining("cannotRemoveLastOrgAdmin");

		adminUsers.setOrgAdmin(List.of(member.getId()), true);
		assertThat(users.findById(member.getId()).orElseThrow().isOrgAdmin()).isTrue();
	}

	@Test
	void anAddressChangedByAnAdministratorBlocksAResetForADay() {
		when(currentUser.requireId()).thenReturn(admin.getId());
		adminUsers.updateDetails(member.getId(), null, null, "elsewhere@example.org");

		assertThatThrownBy(() -> adminUsers.sendPasswordReset(List.of(member.getId())))
				.isInstanceOf(ApiException.class).hasMessageContaining("resetAfterEmailChange");
		assertThat(mongo.count(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("action").is("USER_EMAIL_CHANGED")),
				"audit_log")).isEqualTo(1);
	}

	@Test
	void readingAPageIsNotEnoughToTakeItAway() {
		Article plan = page(lead, project.getId(), null, null, "Plan");
		Article mine = page(member, null, null, null, "Mine");

		assertThatThrownBy(() -> articles.place(plan.getId(), null, team.getId(), member))
				.isInstanceOf(ApiException.class).hasMessageContaining("moveNotAllowed");
		Article reloaded = mongo.findById(plan.getId(), Article.class);
		assertThatThrownBy(() -> articles.save(reloaded, mine.getId(), member))
				.isInstanceOf(ApiException.class);
		assertThat(mongo.findById(plan.getId(), Article.class).getProjectId()).isEqualTo(project.getId());
	}

	@Test
	void aPageFiledUnderAnotherPlaceStaysWhereItIsWhenItsOldParentMoves() {
		Article root = page(lead, null, null, null, "Draft");
		// Filed before places were kept together: a team page under a private one.
		Article teamPage = mongo.save(Article.builder().title("Team notes").teamId(team.getId())
				.parentId(root.getId()).authorId(teamAdmin.getId()).build());

		articles.place(root.getId(), project.getId(), null, lead);

		Article after = mongo.findById(teamPage.getId(), Article.class);
		assertThat(after.getTeamId()).isEqualTo(team.getId());
		assertThat(after.getProjectId()).isNull();
		assertThat(after.getParentId()).isNull();
	}

	@Test
	void namedAbsenceManagersNarrowWhoSeesIllness() {
		assertThat(timeOffAccess.isKeeper(orgAdmin)).isTrue();

		when(currentUser.require()).thenReturn(orgAdmin);
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAbsenceManagers(List.of(member.getId()));
		orgSettings.update(new OrgSettingsController.OrgSettingsUpdate(block, null, null));

		assertThat(timeOffAccess.isKeeper(orgAdmin)).isFalse();
		assertThat(timeOffAccess.isKeeper(member)).isTrue();
		assertThat(availabilityAccess.of(orgAdmin, lead.getId()))
				.isEqualTo(com.ahmadre.hinata.availability.AvailabilityAccess.Sight.TYPE_AND_SPAN);
	}

	@Test
	void aMemberSeesHowManyPagesColleaguesWereOpenedButNotWhich() {
		Article handbook = page(teamAdmin, null, team.getId(), null, "Handbook");
		teamService.updateMembership(teams.findById(team.getId()).orElseThrow(), teamAdmin, member.getId(),
				null, null, KnowledgeAccess.some(List.of(handbook.getId())));
		User other = user("other");
		teamService.addMembers(teams.findById(team.getId()).orElseThrow(), teamAdmin, List.of(other.getId()),
				TeamRole.MEMBER, ProjectAccess.none());

		when(currentUser.require()).thenReturn(other);
		TeamMembership seen = teamController.get(team.getId()).membership(member.getId());
		assertThat(seen.knowledgeOrNone().getArticleIds()).isEmpty();
		assertThat(seen.knowledgeOrNone().getCount()).isEqualTo(1);

		when(currentUser.require()).thenReturn(member);
		assertThat(teamPages.outline(team.getId(), "hand").getBody())
				.extracting(ArticleService.PageRef::title).containsExactly("Handbook");
	}

	@Test
	void theHandOverIsOnRecordForEveryPerson() {
		backfill.run(new DefaultApplicationArguments());
		assertThat(mongo.count(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("action").is("USER_ROLE_CHANGED")
						.and("targetId").is(admin.getId())), "audit_log")).isEqualTo(1);
	}

	// --- HIN-129 review round 2 -------------------------------------------------

	@Test
	void anotherPersonsPrivatePageNeverTravelsWithMine() {
		Article mine = page(lead, null, null, null, "Old wiki");
		// Filed under it back when pages without a place were everybody's.
		Article theirs = mongo.save(Article.builder().title("Their notes").parentId(mine.getId())
				.authorId(member.getId()).build());

		articles.place(mine.getId(), project.getId(), null, lead);

		Article after = mongo.findById(theirs.getId(), Article.class);
		assertThat(after.getProjectId()).isNull();
		assertThat(after.getParentId()).isNull();
		assertThat(articleAccess.canSee(after, lead)).isFalse();
		assertThat(articleAccess.canSee(after, member)).isTrue();
	}

	@Test
	void aPageOfAnArchivedProjectDoesNotOpenByIdEither() {
		Article plan = page(lead, project.getId(), null, null, "Plan");
		project.setArchived(true);
		projects.save(project);
		assertThat(articleAccess.canSee(mongo.findById(plan.getId(), Article.class), member)).isFalse();
	}

	@Test
	void theForgotPasswordRouteWaitsOutAnAddressAnAdministratorJustSet() {
		when(currentUser.requireId()).thenReturn(admin.getId());
		adminUsers.updateDetails(member.getId(), null, null, "taken@example.org");
		User changed = users.findById(member.getId()).orElseThrow();
		changed.setEmailVerified(true);
		changed.setPasswordHash("hash");
		users.save(changed);

		passwordResets.requestByEmail("taken@example.org");

		assertThat(users.findById(member.getId()).orElseThrow().getPasswordResetTokenHash()).isNull();
	}

	@Test
	void anOrganisationAdminOutsideANamedCircleDoesNotReadAbsencesInTheLog() {
		audit.event(AuditAction.TIME_OFF_SICK_REPORTED).actor(member).target(member).log();
		audit.event(AuditAction.TIMESHEET_APPROVED).actor(lead).target(member).log();
		when(currentUser.require()).thenReturn(orgAdmin);
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAbsenceManagers(List.of(member.getId()));
		orgSettings.update(new OrgSettingsController.OrgSettingsUpdate(block, null, null));

		assertThat(orgAudit.list(null, null, null, null, null, null, null, null, 1, 50).items())
				.extracting(AuditFeed.AuditEntryResponse::action)
				.contains("TIMESHEET_APPROVED").doesNotContain("TIME_OFF_SICK_REPORTED");
	}

	@Test
	void theOrganisationsAuditSwitchesAreTheOrganisationAdmins() {
		when(currentUser.require()).thenReturn(orgAdmin);
		orgSettings.update(new OrgSettingsController.OrgSettingsUpdate(null, null, null,
				java.util.Map.of("TIME_ENTRY_CREATED", true, "LOGIN_SUCCESS", false)));
		assertThat(settings.get().getAudit().getEvents()).containsEntry("TIME_ENTRY_CREATED", true)
				.doesNotContainKey("LOGIN_SUCCESS");

		when(currentUser.require()).thenReturn(admin);
		ServerSettings body = adminSettings.get();
		body.getAudit().setEnabled(false);
		body.getAudit().getEvents().put("TIME_ENTRY_CREATED", false);
		adminSettings.update(body);

		assertThat(settings.get().getAudit().getEvents()).containsEntry("TIME_ENTRY_CREATED", true);
		// The platform's master switch does not silence the organisation's records.
		audit.event(AuditAction.TIME_ENTRY_CREATED).actor(member).log();
		assertThat(mongo.count(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("action").is("TIME_ENTRY_CREATED")),
				"audit_log")).isEqualTo(1);
	}

	@Test
	void aSelectionThatIncludesTheAdministratorChangesNobody() {
		when(currentUser.requireId()).thenReturn(admin.getId());
		assertThatThrownBy(() -> adminUsers.setOrgAdmin(List.of(member.getId(), admin.getId()), true))
				.isInstanceOf(ApiException.class);
		assertThat(users.findById(member.getId()).orElseThrow().isOrgAdmin()).isFalse();
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
