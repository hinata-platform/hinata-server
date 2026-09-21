package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The time reports against a real database (HIN-93): the visibility matrix in both directions,
 * rounding per entry, day buckets that never move with a zone, the approval filter and the
 * workload report's gate and order.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class TimeReportIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimeReportService reports;
	@Autowired
	private TimeWorkloadReport workload;
	@Autowired
	private UserRepository users;
	@Autowired
	private SettingsService settings;
	@Autowired
	private WorkItemIndexMigration indexes;

	private User admin;
	private User lead;
	private User member;
	private User outsider;
	private Project apollo;
	private Project zeus;

	@BeforeEach
	void seed() {
		for (Class<?> type : List.of(User.class, Project.class, Issue.class, WorkItem.class,
				TimesheetApproval.class)) {
			mongo.remove(new Query(), type);
		}
		settings.save(new ServerSettings());
		admin = person("Ada Admin", Role.ADMIN);
		lead = person("Lena Lead", Role.MEMBER);
		member = person("Mia Member", Role.MEMBER);
		outsider = person("Otto Outsider", Role.MEMBER);
		apollo = project("APO", lead, member);
		zeus = project("ZEU", outsider, outsider);
		entry(lead, apollo, DAY, 60);
		entry(member, apollo, DAY, 120);
		entry(member, zeus, DAY, 30);
		entry(outsider, zeus, DAY, 240);
		entry(member, null, DAY, 15);
		entry(outsider, null, DAY, 45);
	}

	// --- the matrix ---------------------------------------------------------

	@Test
	void aMemberSeesTheirOwnEntriesAndTheTotalsOfTheirProjectsWithoutPeople() {
		TimeReportService.Summary byProject = summary(member, TimeReportService.GroupBy.PROJECT);
		assertThat(minutesByKey(byProject)).containsOnly(Map.entry(apollo.getId(), 180L),
				Map.entry(zeus.getId(), 30L), Map.entry("none", 15L));

		TimeReportService.Summary byPerson = summary(member, TimeReportService.GroupBy.USER);
		assertThat(minutesByKey(byPerson)).containsOnly(Map.entry(member.getId(), 165L));
		assertThat(byPerson.people()).isFalse();

		assertThat(detailed(member)).extracting(TimeReportService.EntryRow::userId).containsOnly(member.getId());
		// Naming a colleague narrows to the entries whose people the reader sees: none of theirs.
		assertThat(reports.summary(member, filter(Map.of("userIds", List.of(lead.getId()))),
				TimeReportService.GroupBy.PROJECT, 0, 50).totals().minutes()).isZero();
	}

	@Test
	void aLeadWithoutThePolicyIsAMember() {
		assertThat(minutesByKey(summary(lead, TimeReportService.GroupBy.USER)))
				.containsOnly(Map.entry(lead.getId(), 60L));
		assertThat(detailed(lead)).extracting(TimeReportService.EntryRow::userId).containsOnly(lead.getId());
	}

	@Test
	void aLeadWithThePolicySeesThePeopleOfTheirProjectsAndNothingElseOfThem() {
		policy(block -> block.setLeadsSeeMemberEntries(true));

		assertThat(minutesByKey(summary(lead, TimeReportService.GroupBy.USER)))
				.containsOnly(Map.entry(lead.getId(), 60L), Map.entry(member.getId(), 120L));
		assertThat(detailed(lead)).extracting(TimeReportService.EntryRow::projectId)
				.containsOnly(apollo.getId());
		assertThat(summary(lead, TimeReportService.GroupBy.USER).groups().getContent())
				.extracting(TimeReportService.Group::label).containsExactly("Lena Lead", "Mia Member");
	}

	@Test
	void anAdministratorSeesEverythingAndEntriesWithoutAProjectStayTheirOwners() {
		assertThat(summary(admin, TimeReportService.GroupBy.PROJECT).totals().minutes()).isEqualTo(510);
		assertThat(minutesByKey(summary(outsider, TimeReportService.GroupBy.PROJECT)))
				.containsOnly(Map.entry(zeus.getId(), 270L), Map.entry("none", 45L));
		assertThat(detailed(lead)).extracting(TimeReportService.EntryRow::projectId).doesNotContainNull();
	}

	// --- rounding and buckets -----------------------------------------------

	@Test
	void roundingFoldsEachEntryBeforeAnythingIsAddedUp() {
		mongo.remove(new Query(), WorkItem.class);
		for (int i = 0; i < 3; i++) {
			entry(member, apollo, DAY, 8);
		}
		TimeReportFilter nearest = TimeReportFilter.of(DAY, DAY, null, null, null, null, null, null, null, null,
				TimePolicy.Rounding.NEAREST, 15, null, null, java.time.ZoneOffset.UTC);

		TimeReportService.Totals totals = reports.summary(member, nearest, TimeReportService.GroupBy.PROJECT, 0, 50)
				.totals();

		// 8 rounds to 15 three times; rounding the 24 once would have made it 30.
		assertThat(totals.minutes()).isEqualTo(45);
		assertThat(totals.filedMinutes()).isEqualTo(24);
		assertThat(reports.detailed(member, nearest, 0, 50).getContent())
				.extracting(TimeReportService.EntryRow::roundedMinutes).containsOnly(15);
	}

	@Test
	void anEntryAcrossMidnightStaysOnItsDayInEveryZone() {
		mongo.remove(new Query(), WorkItem.class);
		LocalDate first = LocalDate.of(2026, 3, 1);
		WorkItem late = entry(member, apollo, first, 60);
		late.setStartedAt(Instant.parse("2026-03-01T22:30:00Z"));
		late.setEndedAt(Instant.parse("2026-03-01T23:30:00Z"));
		mongo.save(late);

		for (String zone : List.of("Europe/Berlin", "Asia/Tokyo", "America/Los_Angeles")) {
			TimeReportFilter filter = filter(Map.of("tz", zone, "from", LocalDate.of(2026, 2, 1)));
			assertThat(keys(reports.summary(member, filter, TimeReportService.GroupBy.DAY, 0, 50))).as(zone)
					.containsExactly("2026-03-01");
			assertThat(keys(reports.summary(member, filter, TimeReportService.GroupBy.MONTH, 0, 50))).as(zone)
					.containsExactly("2026-03-01");
			assertThat(keys(reports.summary(member, filter, TimeReportService.GroupBy.WEEK, 0, 50))).as(zone)
					.containsExactly("2026-02-23");
		}
	}

	// --- approval -----------------------------------------------------------

	@Test
	void theApprovalFilterReadsTheStoredPeriods() {
		mongo.insert(TimesheetApproval.builder().userId(member.getId()).projectId(apollo.getId())
				.periodStart(DAY.minusDays(1)).periodEnd(DAY.plusDays(5)).status(TimesheetApproval.Status.APPROVED)
				.submittedAt(Instant.now()).build());

		TimeReportFilter approved = filter(Map.of("approval", Set.of(TimeReportFilter.Approval.APPROVED)));
		TimeReportFilter open = filter(Map.of("approval", Set.of(TimeReportFilter.Approval.OPEN)));

		assertThat(detailed(admin, approved)).extracting(TimeReportService.EntryRow::minutes).containsOnly(120);
		assertThat(detailed(admin, open)).extracting(TimeReportService.EntryRow::minutes)
				.containsExactlyInAnyOrder(60, 30, 240, 15, 45);
	}

	// --- workload -----------------------------------------------------------

	@Test
	void workloadIsAbsentWithoutItsPolicyAndForbiddenToAMember() {
		assertThatThrownBy(() -> workload.workload(admin, filter(Map.of()), null, null, 0, 50))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
		policy(block -> {
			block.setWorkloadReportsEnabled(true);
			block.setLeadsSeeMemberEntries(true);
		});
		assertThatThrownBy(() -> workload.workload(member, filter(Map.of()), null, null, 0, 50))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getStatus().value()).isEqualTo(403));
	}

	@Test
	void workloadListsTheLeadsPeopleByNameWithTheirTimeOnTheLeadsProjects() {
		policy(block -> {
			block.setWorkloadReportsEnabled(true);
			block.setLeadsSeeMemberEntries(true);
		});

		TimeWorkloadReport.Workload seen = workload.workload(lead, filter(Map.of()), null, apollo.getId(), 0, 50);

		assertThat(seen.people().getContent()).extracting(TimeWorkloadReport.Row::name)
				.containsExactly("Lena Lead", "Mia Member");
		// Mia booked 120 on Apollo and 30 on Zeus; the lead sees Apollo.
		assertThat(seen.people().getContent().get(1).bookedMinutes()).isEqualTo(120);
		assertThat(seen.people().getContent().get(1).capacityMinutes()).isPositive();
		assertThat(seen.bookedInLedProjects()).isTrue();
	}

	// --- the covering index -------------------------------------------------

	@Test
	void theRetiredWindowIndexIsDroppedAndItsSuccessorCoversTheMinutes() {
		mongo.getCollection("work_items").createIndex(new org.bson.Document("date", 1).append("userId", 1)
				.append("projectId", 1), new com.mongodb.client.model.IndexOptions().name(WorkItemIndexMigration.RETIRED));

		assertThat(indexes.drop()).isTrue();
		assertThat(indexes.drop()).isFalse();
		List<String> names = new ArrayList<>();
		mongo.getCollection("work_items").listIndexes().forEach(index -> names.add(index.getString("name")));
		assertThat(names).contains(WorkItem.DATE_USER_PROJECT_MINUTES_INDEX)
				.doesNotContain(WorkItemIndexMigration.RETIRED);
	}

	// --- fixtures -----------------------------------------------------------

	private TimeReportService.Summary summary(User viewer, TimeReportService.GroupBy groupBy) {
		return reports.summary(viewer, filter(Map.of()), groupBy, 0, 50);
	}

	private List<TimeReportService.EntryRow> detailed(User viewer) {
		return detailed(viewer, filter(Map.of()));
	}

	private List<TimeReportService.EntryRow> detailed(User viewer, TimeReportFilter filter) {
		return reports.detailed(viewer, filter, 0, 100).getContent();
	}

	@SuppressWarnings("unchecked")
	private TimeReportFilter filter(Map<String, Object> values) {
		LocalDate from = (LocalDate) values.getOrDefault("from", DAY.minusDays(7));
		return TimeReportFilter.of(from, DAY.plusDays(7), null,
				(List<String>) values.get("userIds"), null, null, null, null, null,
				(Set<TimeReportFilter.Approval>) values.get("approval"), null, null, null,
				(String) values.get("tz"), java.time.ZoneOffset.UTC);
	}

	private static Map<String, Long> minutesByKey(TimeReportService.Summary summary) {
		return summary.groups().getContent().stream().collect(Collectors.toMap(
				group -> group.key() == null ? "none" : group.key(), TimeReportService.Group::minutes));
	}

	private static List<String> keys(TimeReportService.Summary summary) {
		return summary.groups().getContent().stream().map(TimeReportService.Group::key).toList();
	}

	private void policy(java.util.function.Consumer<ServerSettings.TimeTracking> change) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = current.getTimeTracking() != null ? current.getTimeTracking()
				: new ServerSettings.TimeTracking();
		change.accept(block);
		current.setTimeTracking(block);
		settings.save(current);
	}

	private User person(String name, Role role) {
		return users.save(User.builder().username(name.toLowerCase().replace(' ', '.')).displayName(name)
				.email(name.replace(' ', '.') + "@example.test").roles(Set.of(role)).active(true).locale("en")
				.build());
	}

	private Project project(String key, User leadOf, User memberOf) {
		return mongo.insert(Project.builder().key(key).name(key).leadId(leadOf.getId())
				.leadIds(new ArrayList<>(List.of(leadOf.getId())))
				.memberIds(new ArrayList<>(List.of(leadOf.getId(), memberOf.getId()))).build());
	}

	/**
	 * Every project gets one issue and every entry on a project is booked on it: the workload
	 * roster counts only time recorded on an issue that was never moved.
	 */
	private WorkItem entry(User owner, Project project, LocalDate day, int minutes) {
		return mongo.insert(WorkItem.builder().userId(owner.getId())
				.projectId(project == null ? null : project.getId())
				.issueId(project == null ? null : issueOf(project).getId()).date(day).durationMinutes(minutes)
				.source(WorkItem.Source.APP).build());
	}

	private Issue issueOf(Project project) {
		Issue found = mongo.findOne(Query.query(org.springframework.data.mongodb.core.query.Criteria
				.where("projectId").is(project.getId())), Issue.class);
		return found != null ? found : mongo.insert(Issue.builder().projectId(project.getId())
				.readableId(project.getKey() + "-1").numberInProject(1).title("Work").state("Open").build());
	}
}
