package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.billing.BillingRate;
import com.ahmadre.hinata.billing.BillingRateService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The money budget (HIN-155): valued like a billing report, seen by an administrator always and by
 * a lead only as revenue, set as a cost budget only by an administrator, refused without billing,
 * and heard by the project's own leads alone.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true",
		"hinata.time-tracking.alerts-enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class MoneyBudgetIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SettingsService settings;
	@Autowired
	private UserRepository users;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private TimeTrackingService timeTracking;
	@Autowired
	private BillingRateService rates;
	@Autowired
	private TimeUsageService usage;
	@Autowired
	private TimeUsageCache cache;
	@Autowired
	private ProjectTimeSettingsController controller;
	@Autowired
	private TimeBudgetLiveAlerts live;
	@Autowired
	private NotificationRepository notifications;
	@MockitoBean
	private CurrentUser currentUser;

	private User admin;
	private User lead;
	private User member;
	private Project project;

	@BeforeEach
	void seed() {
		for (String collection : List.of("projects", "users", "work_items", "billing_rates", "server_settings",
				"teams", "project_time_settings", "notifications", "time_marks")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		cache.forgetAll();
		billing(true);
		admin = user("admin", Role.ORG_ADMIN);
		lead = user("lead", Role.MEMBER);
		member = user("member", Role.MEMBER);
		// The administrator leads the project too: project settings are for the people who run a
		// project, and the administrator role alone does not open them (ProjectService.canManage).
		project = projects.save(Project.builder().key("HIN").name("Hinata").leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId(), admin.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), admin.getId(), member.getId()))).build());
		LocalDate day = LocalDate.now().minusDays(1);
		rates.create(admin, new BillingRateService.Draft(BillingRate.Kind.BILLABLE, BillingRate.Scope.PROJECT,
				project.getId(), null, 10_000L, day.minusDays(30), null));
		rates.create(admin, new BillingRateService.Draft(BillingRate.Kind.COST, BillingRate.Scope.DEFAULT,
				null, null, 6_000L, day.minusDays(30), null));
		entry(member, day, 120, true);
		entry(member, day, 60, false);
	}

	@Test
	void revenueCountsBillableEntriesAndCostCountsAll() {
		save(admin, 100_000L, ProjectTimeSettings.RateKind.BILLABLE);
		assertThat(money(admin).usedCents()).isEqualTo(20_000);
		assertThat(money(admin).currency()).isEqualTo("EUR");

		save(admin, 100_000L, ProjectTimeSettings.RateKind.COST);
		assertThat(money(admin).usedCents()).isEqualTo(18_000);
	}

	@Test
	void aLeadSeesRevenueButNeverCostAndAMemberSeesNoMoney() {
		save(admin, 100_000L, ProjectTimeSettings.RateKind.BILLABLE);
		assertThat(money(lead).budgetCents()).isEqualTo(100_000);
		assertThat(money(member).budgetCents()).isNull();
		assertThat(money(member).usedCents()).isNull();

		save(admin, 100_000L, ProjectTimeSettings.RateKind.COST);
		assertThat(money(lead).usedCents()).isNull();
		when(currentUser.require()).thenReturn(member);
		assertThat(controller.get(project.getId()).budgetCents()).isNull();
	}

	@Test
	void onlyAnAdministratorSwitchesToACostBudgetAndALeadMaySaveItBack() {
		assertThatThrownBy(() -> save(lead, 100_000L, ProjectTimeSettings.RateKind.COST))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
		save(admin, 100_000L, ProjectTimeSettings.RateKind.COST);
		save(lead, 100_000L, ProjectTimeSettings.RateKind.COST);
	}

	@Test
	void aLeadCannotChangeOrRemoveACostBudgetItDoesNotSee() {
		save(admin, 100_000L, ProjectTimeSettings.RateKind.COST);
		when(currentUser.require()).thenReturn(lead);
		assertThat(controller.get(project.getId()).budgetCents()).isNull();

		save(lead, 1L, ProjectTimeSettings.RateKind.COST);
		save(lead, null, null);

		assertThat(stored().getBudgetCents()).isEqualTo(100_000L);
		assertThat(stored().getBudgetRateKind()).isEqualTo(ProjectTimeSettings.RateKind.COST);
	}

	@Test
	void aTeamAdministratorWhoIsNoLeadRunsTheProjectButNotItsMoney() {
		User teamAdmin = user("teamadmin", Role.MEMBER);
		mongo.insert(com.ahmadre.hinata.team.Team.builder().name("Team").key("TEAM")
				.projectIds(new ArrayList<>(List.of(project.getId())))
				.members(new ArrayList<>(List.of(com.ahmadre.hinata.team.TeamMembership.builder()
						.userId(teamAdmin.getId()).role(com.ahmadre.hinata.team.TeamRole.ADMIN)
						.access(com.ahmadre.hinata.team.ProjectAccess.all()).build())))
				.build());
		assertThatThrownBy(() -> save(teamAdmin, 5_000L, ProjectTimeSettings.RateKind.BILLABLE))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

		save(lead, 5_000L, ProjectTimeSettings.RateKind.BILLABLE);
		when(currentUser.require()).thenReturn(teamAdmin);
		assertThat(controller.get(project.getId()).budgetCents()).isNull();
		// Saving the rest of the block keeps the revenue budget the team administrator does not see.
		save(teamAdmin, null, null);
		assertThat(stored().getBudgetCents()).isEqualTo(5_000L);
	}

	@Test
	void aMemberDoesNotLearnFromTheReportThatAProjectHasAMoneyBudget() {
		save(admin, 100_000L, ProjectTimeSettings.RateKind.BILLABLE);
		assertThat(usage.projectsWithBudgetFor(member, null)).isEmpty();
		assertThat(usage.projectsWithBudgetFor(lead, null)).containsExactly(project.getId());
	}

	private ProjectTimeSettings stored() {
		return mongo.findOne(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("projectId").is(project.getId())),
				ProjectTimeSettings.class);
	}

	@Test
	void aBlockSavedBackAsItWasKeepsItsMoneyBudgetAfterBillingWentOff() {
		save(lead, 100_000L, null);
		billing(false);

		save(lead, 100_000L, null);

		assertThat(mongo.findOne(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("projectId").is(project.getId())),
				ProjectTimeSettings.class).getBudgetCents()).isEqualTo(100_000L);
	}

	@Test
	void withoutBillingThereIsNoMoneyBudget() {
		billing(false);
		assertThatThrownBy(() -> save(lead, 100_000L, null))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
	}

	@Test
	void theProjectsLeadsHearOfRevenueAndNobodyOfCost() {
		save(admin, 25_000L, ProjectTimeSettings.RateKind.BILLABLE);
		live.measure(new TimeEntryWrites.Touched(Set.of(project.getId()), Set.of()));
		assertThat(alerts(lead)).singleElement().asString().contains("80 %").contains("€");
		assertThat(alerts(member)).isEmpty();

		mongo.getCollection("notifications").deleteMany(new Document());
		mongo.getCollection("time_marks").deleteMany(new Document());
		save(admin, 25_000L, ProjectTimeSettings.RateKind.COST);
		live.measure(new TimeEntryWrites.Touched(Set.of(project.getId()), Set.of()));
		assertThat(alerts(lead)).isEmpty();
	}

	private TimeUsageService.ProjectUsage money(User viewer) {
		return usage.withMoney(usage.ofProjects(List.of(project.getId())), viewer).getFirst();
	}

	private void save(User by, Long cents, ProjectTimeSettings.RateKind kind) {
		when(currentUser.require()).thenReturn(by);
		ProjectTimeSettingsController.ProjectTimeSettingsRequest request =
				new ProjectTimeSettingsController.ProjectTimeSettingsRequest();
		request.setBudgetCents(cents);
		request.setBudgetRateKind(kind);
		controller.put(project.getId(), request);
	}

	private List<String> alerts(User person) {
		return notifications.findAll().stream()
				.filter(sent -> person.getId().equals(sent.getUserId())
						&& sent.getType() == Notification.Type.TIME_BUDGET_ALERT)
				.map(Notification::getBody).toList();
	}

	private void billing(boolean on) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		block.setBillingEnabled(on);
		block.setAlertsEnabled(true);
		current.setTimeTracking(block);
		settings.save(current);
	}

	private User user(String name, Role role) {
		return users.save(User.builder().email(name + "@example.org").username(name).displayName(name)
				.roles(role == Role.MEMBER ? Set.of(Role.MEMBER) : Set.of(Role.MEMBER, role)).active(true)
				.timezone("UTC").locale("en").build());
	}

	private void entry(User owner, LocalDate day, int minutes, boolean billable) {
		timeTracking.create(new TimeTrackingService.NewEntry(project.getId(), null, minutes, day, null, "work",
				null, null, List.of(), billable), WorkItem.Source.APP, owner);
	}
}
