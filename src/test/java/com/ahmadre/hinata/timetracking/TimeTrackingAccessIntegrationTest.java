package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.audit.AuditLogRepository;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.report.ReportService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
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
import static org.assertj.core.api.Assertions.entry;

/**
 * Who may read and write logged time — the three access holes HIN-82 closes,
 * pinned in both directions: what the outsider is refused, the member still
 * gets.
 *
 * <p>Against a real MongoDB rather than mocks, because the rules are only half
 * Java: the timesheet's user and project filters have to reach the query
 * <em>together</em> (a mock would happily answer a query that ANDs nothing),
 * the cross-project time report is a visibility set turned into an {@code $in},
 * and "a refused write leaves the counter alone" is a statement about what is
 * in the database afterwards.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class TimeTrackingAccessIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimeTrackingService timeTracking;
	@Autowired
	private ReportService reports;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;
	@Autowired
	private AuditLogRepository auditLog;

	/** A member of the project, and the owner of the seeded entry. */
	private User member;
	/** A second member of the same project — a peer, with no power over the first one's entries. */
	private User peer;
	/** Member of no project at all. */
	private User outsider;
	/** Lead of the project. */
	private User lead;
	private User admin;

	private Project project;
	/** A project nobody under test is a member of except the outsider. */
	private Project foreign;
	private Issue issue;
	private Issue foreignIssue;

	private static final LocalDate DAY = LocalDate.now().minusDays(1);

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "projects", "teams", "users", "work_items",
				"notifications", "issue_activities", "audit_log")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		member = user("member", Role.MEMBER);
		peer = user("peer", Role.MEMBER);
		outsider = user("outsider", Role.MEMBER);
		lead = user("lead", Role.MEMBER);
		admin = user("admin", Role.ADMIN);
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadId(lead.getId()).leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(member.getId(), peer.getId(), lead.getId())))
				.build());
		foreign = projects.save(Project.builder().key("OTH").name("Other")
				.leadId(outsider.getId()).leadIds(new ArrayList<>(List.of(outsider.getId())))
				.memberIds(new ArrayList<>(List.of(outsider.getId())))
				.build());
		issue = issue(project, "HIN-1");
		foreignIssue = issue(foreign, "OTH-1");
	}

	private User user(String name, Role role) {
		return users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(role)).active(true)
				// Fixed so that "today" is the same day for every actor here; the
				// zone-dependent rules have their own test.
				.timezone("UTC").build());
	}

	private Issue issue(Project in, String key) {
		return issueRepository.save(Issue.builder().projectId(in.getId()).readableId(key)
				.numberInProject(1).title("Login bug").state("Open").spentMinutes(0)
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build());
	}

	private TimeTrackingService.NewWorkItem draft(int minutes) {
		return new TimeTrackingService.NewWorkItem(minutes, DAY, "Development", "work",
				null, null, null, null);
	}

	private WorkItem logged(User owner, int minutes) {
		return timeTracking.add(issue.getId(), draft(minutes), WorkItem.Source.APP, owner);
	}

	private int spentMinutesOf(Issue on) {
		return issueRepository.findById(on.getId()).orElseThrow().getSpentMinutes();
	}

	/** Asserts the call is refused with 403 — the status is part of the contract, not decoration. */
	private void assertForbidden(ThrowingCallable call) {
		assertThatThrownBy(call)
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	// --- reading an issue's entries -------------------------------------------

	@Test
	void anOutsiderCannotListAnIssuesEntriesWhileEveryMemberStillCan() {
		logged(member, 30);

		assertForbidden(() -> timeTracking.list(issue.getId(), outsider));

		assertThat(timeTracking.list(issue.getId(), member)).hasSize(1);
		assertThat(timeTracking.list(issue.getId(), peer)).hasSize(1);
		assertThat(timeTracking.list(issue.getId(), lead)).hasSize(1);
		assertThat(timeTracking.list(issue.getId(), admin)).hasSize(1);
	}

	@Test
	void anOutsiderCannotPageAnIssuesEntriesWhileEveryMemberStillCan() {
		logged(member, 30);

		assertForbidden(() -> timeTracking.page(issue.getId(), 0, 20, outsider));

		assertThat(timeTracking.page(issue.getId(), 0, 20, member).getTotalElements()).isEqualTo(1);
		assertThat(timeTracking.page(issue.getId(), 0, 20, lead).getTotalElements()).isEqualTo(1);
		assertThat(timeTracking.page(issue.getId(), 0, 20, admin).getTotalElements()).isEqualTo(1);
	}

	/** The readable id is the same door; it must not be a way around the lock. */
	@Test
	void theReadableIdIsNoBackDoor() {
		assertForbidden(() -> timeTracking.list("HIN-1", outsider));

		assertThat(timeTracking.list("HIN-1", member)).isEmpty();
	}

	// --- writing ---------------------------------------------------------------

	@Test
	void anOutsiderCannotLogTimeAndTheCounterDoesNotMove() {
		assertForbidden(() -> timeTracking.add(issue.getId(), draft(45), WorkItem.Source.APP,
				outsider));

		assertThat(workItems.count()).as("nothing was written").isZero();
		assertThat(spentMinutesOf(issue)).as("a refused write leaves spentMinutes alone").isZero();

		// The other direction: the same call from a member goes through.
		assertThat(logged(member, 45).getId()).isNotNull();
		assertThat(spentMinutesOf(issue)).isEqualTo(45);
	}

	@Test
	void aMemberOfAnotherProjectCannotLogTimeOnThisOnesIssue() {
		// The outsider is a full member of `foreign` — membership somewhere is not
		// membership here.
		assertThat(timeTracking.add(foreignIssue.getId(), draft(20), WorkItem.Source.APP, outsider))
				.isNotNull();

		assertForbidden(() -> timeTracking.add(issue.getId(), draft(20), WorkItem.Source.APP,
				outsider));
		assertThat(spentMinutesOf(issue)).isZero();
	}

	// --- editing and deleting one entry ----------------------------------------

	private TimeTrackingService.WorkItemPatch minutes(int value) {
		return new TimeTrackingService.WorkItemPatch(value, null, null, null, false, null, false,
				null, null, null);
	}

	@Test
	void theOwnerEditsTheirOwnEntry() {
		WorkItem entry = logged(member, 30);

		WorkItem updated = timeTracking.update(entry.getId(), minutes(75), member);

		assertThat(updated.getDurationMinutes()).isEqualTo(75);
		assertThat(spentMinutesOf(issue)).isEqualTo(75);
		assertThat(auditLog.findAll()).as("editing one's own entry is ordinary use, not an incident")
				.noneMatch(entry2 -> entry2.getAction() == AuditAction.TIME_ENTRY_UPDATED);
	}

	@Test
	void aPeerMemberCannotEditSomeoneElsesEntryButTheLeadCan() {
		WorkItem entry = logged(member, 30);

		assertForbidden(() -> timeTracking.update(entry.getId(), minutes(75), peer));
		assertThat(workItems.findById(entry.getId()).orElseThrow().getDurationMinutes())
				.as("the refused edit changed nothing").isEqualTo(30);

		assertThat(timeTracking.update(entry.getId(), minutes(75), lead).getDurationMinutes())
				.isEqualTo(75);
		assertThat(timeTracking.update(entry.getId(), minutes(90), admin).getDurationMinutes())
				.isEqualTo(90);
		assertThat(spentMinutesOf(issue)).isEqualTo(90);
	}

	@Test
	void aForeignEditIsAudited() {
		WorkItem entry = logged(member, 30);

		timeTracking.update(entry.getId(), minutes(75), lead);

		List<AuditLog> written = auditLog.findAll().stream()
				.filter(row -> row.getAction() == AuditAction.TIME_ENTRY_UPDATED).toList();
		assertThat(written).hasSize(1);
		assertThat(written.getFirst().getActorId()).isEqualTo(lead.getId());
		assertThat(written.getFirst().getTargetId()).as("the person whose record was touched")
				.isEqualTo(member.getId());
		assertThat(written.getFirst().getMetadata()).containsEntry("workItem", entry.getId());
	}

	@Test
	void aPeerMemberCannotDeleteSomeoneElsesEntryButTheLeadCan() {
		WorkItem entry = logged(member, 30);

		assertForbidden(() -> timeTracking.delete(entry.getId(), peer));
		assertThat(workItems.findById(entry.getId())).as("still there").isPresent();
		assertThat(spentMinutesOf(issue)).isEqualTo(30);

		timeTracking.delete(entry.getId(), lead);

		assertThat(workItems.findById(entry.getId())).isEmpty();
		assertThat(spentMinutesOf(issue)).isZero();
		assertThat(auditLog.findAll())
				.anyMatch(row -> row.getAction() == AuditAction.TIME_ENTRY_DELETED
						&& lead.getId().equals(row.getActorId()));
	}

	@Test
	void anOutsiderCanNeitherEditNorDeleteAnEntryOfAProjectTheyCannotSee() {
		WorkItem entry = logged(member, 30);

		assertForbidden(() -> timeTracking.update(entry.getId(), minutes(75), outsider));
		assertForbidden(() -> timeTracking.delete(entry.getId(), outsider));

		assertThat(workItems.findById(entry.getId()).orElseThrow().getDurationMinutes())
				.isEqualTo(30);
	}

	/** The MCP door is narrower than the app's: a token never inherits lead powers. */
	@Test
	void deleteOwnRefusesTheLeadTheAppWouldAllow() {
		WorkItem entry = logged(member, 30);

		assertForbidden(() -> timeTracking.deleteOwn(entry.getId(), lead));
		assertThat(workItems.findById(entry.getId())).isPresent();

		timeTracking.deleteOwn(entry.getId(), member);
		assertThat(workItems.findById(entry.getId())).isEmpty();
	}

	// --- the timesheet ----------------------------------------------------------

	private List<TimeTrackingService.TimesheetRow> sheet(String userId, String projectId,
			User requester) {
		return timeTracking.timesheet(DAY.minusDays(3), DAY.plusDays(3), userId, projectId,
				requester);
	}

	/**
	 * The bug this pins: {@code isAdmin ? userId : (projectId == null ? me : userId)}
	 * handed a non-admin any user's hours as soon as a project id was in the query
	 * string.
	 */
	@Test
	void aProjectFilterDoesNotUnlockAnotherUsersHours() {
		logged(member, 30);
		logged(peer, 60);

		assertForbidden(() -> sheet(member.getId(), project.getId(), peer));
		assertForbidden(() -> sheet(member.getId(), null, peer));

		// … and the peer's own rows are still there, with and without the filter.
		assertThat(sheet(null, project.getId(), peer)).singleElement()
				.satisfies(row -> {
					assertThat(row.userId()).isEqualTo(peer.getId());
					assertThat(row.totalMinutes()).isEqualTo(60);
				});
		assertThat(sheet(peer.getId(), null, peer)).singleElement()
				.satisfies(row -> assertThat(row.totalMinutes()).isEqualTo(60));
	}

	@Test
	void withoutAUserFilterANonAdminOnlySeesTheirOwnRows() {
		logged(member, 30);
		logged(peer, 60);

		assertThat(sheet(null, null, member)).singleElement()
				.satisfies(row -> {
					assertThat(row.userId()).isEqualTo(member.getId());
					assertThat(row.totalMinutes()).isEqualTo(30);
				});
	}

	@Test
	void aProjectTheCallerCannotSeeIsRefusedRatherThanSilentlyIgnored() {
		timeTracking.add(foreignIssue.getId(), draft(15), WorkItem.Source.APP, outsider);

		assertForbidden(() -> sheet(null, foreign.getId(), member));

		// The outsider, who is a member there, gets the same query answered.
		assertThat(sheet(null, foreign.getId(), outsider)).singleElement()
				.satisfies(row -> assertThat(row.totalMinutes()).isEqualTo(15));
	}

	@Test
	void anAdminStillSeesEveryoneAndMayNarrowByUserOrProject() {
		logged(member, 30);
		logged(peer, 60);
		timeTracking.add(foreignIssue.getId(), draft(15), WorkItem.Source.APP, outsider);

		assertThat(sheet(null, null, admin)).hasSize(3);
		assertThat(sheet(member.getId(), null, admin)).singleElement()
				.satisfies(row -> assertThat(row.totalMinutes()).isEqualTo(30));
		assertThat(sheet(null, foreign.getId(), admin)).singleElement()
				.satisfies(row -> assertThat(row.userId()).isEqualTo(outsider.getId()));
	}

	/** Both filters have to reach the query, or a project filter would widen a user filter. */
	@Test
	void bothFiltersApplyTogether() {
		logged(member, 30);
		timeTracking.add(foreignIssue.getId(), draft(15), WorkItem.Source.APP, admin);

		assertThat(sheet(admin.getId(), project.getId(), admin))
				.as("the admin logged nothing on this project").isEmpty();
		assertThat(sheet(admin.getId(), foreign.getId(), admin)).singleElement()
				.satisfies(row -> assertThat(row.totalMinutes()).isEqualTo(15));
	}

	// --- reports ------------------------------------------------------------------

	@Test
	void everyProjectReportRefusesANonMemberAndAnswersAMember() {
		logged(member, 30);

		assertForbidden(() -> reports.issuesByState(project.getId(), outsider));
		assertForbidden(() -> reports.issuesByAssignee(project.getId(), outsider));
		assertForbidden(() -> reports.issuesByPriority(project.getId(), outsider));
		assertForbidden(() -> reports.createdVsResolved(project.getId(), 30, outsider));
		assertForbidden(() -> reports.timePerActivity(project.getId(), DAY.minusDays(3),
				DAY.plusDays(3), outsider));

		assertThat(reports.issuesByState(project.getId(), member)).containsEntry("Open", 1L);
		assertThat(reports.issuesByAssignee(project.getId(), member)).containsEntry("unassigned", 1L);
		assertThat(reports.issuesByPriority(project.getId(), member)).isNotEmpty();
		assertThat(reports.createdVsResolved(project.getId(), 30, member)).hasSize(30);
		assertThat(reports.timePerActivity(project.getId(), DAY.minusDays(3), DAY.plusDays(3),
				member)).containsEntry("Development", 30);
	}

	/**
	 * {@code /time-per-project} used to sum the whole instance. It now sums what
	 * the caller can see — and that is asserted from both ends: the foreign
	 * project is absent, the caller's own is present with the right number.
	 */
	@Test
	void timePerProjectCoversOnlyTheProjectsTheCallerCanSee() {
		logged(member, 30);
		timeTracking.add(foreignIssue.getId(), draft(15), WorkItem.Source.APP, outsider);

		assertThat(reports.timePerProject(DAY.minusDays(3), DAY.plusDays(3), member))
				.containsExactly(entry(project.getId(), 30));

		assertThat(reports.timePerProject(DAY.minusDays(3), DAY.plusDays(3), outsider))
				.containsExactly(entry(foreign.getId(), 15));

		assertThat(reports.timePerProject(DAY.minusDays(3), DAY.plusDays(3), admin))
				.as("an admin sees the whole instance, as before")
				.containsOnlyKeys(project.getId(), foreign.getId());
	}

	@Test
	void aUserWhoCanSeeNoProjectGetsAnEmptyTimeReportRatherThanEverything() {
		logged(member, 30);
		User nobody = user("nobody", Role.MEMBER);

		assertThat(reports.timePerProject(DAY.minusDays(3), DAY.plusDays(3), nobody)).isEmpty();
	}
}
