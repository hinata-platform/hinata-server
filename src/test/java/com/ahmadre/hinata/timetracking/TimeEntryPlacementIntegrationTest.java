package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLogRepository;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Filing an existing entry somewhere else after the fact: a timer entry that ran unfiled, an
 * entry typed without an issue, an entry on the wrong ticket.
 *
 * <p>Against a real MongoDB, because most of what can go wrong is in the database afterwards —
 * the derived {@code spentMinutes} of both issues, the billable flag the new project's default
 * implies, an entry that must not have moved when the move was refused.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class TimeEntryPlacementIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final LocalDate DAY = LocalDate.now().minusDays(1);

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimeTrackingService timeTracking;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private IssueRepository issues;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;
	@Autowired
	private SettingsService settings;
	@Autowired
	private TimeLocks locks;
	@Autowired
	private AuditLogRepository auditLog;

	/** Member of both projects, owner of the entries. */
	private User member;
	/** Lead of {@link #hinata} only, member of nothing else. */
	private User lead;
	/** Member of {@link #foreign} only. */
	private User outsider;
	private User admin;

	private Project hinata;
	private Project billing;
	private Project foreign;
	private Issue login;
	private Issue invoice;
	private Issue foreignIssue;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "projects", "teams", "users", "work_items",
				"project_time_settings", "audit_log", "server_settings", "time_tags",
				"timesheet_approvals")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		ServerSettings fresh = new ServerSettings();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		block.setLeadsSeeMemberEntries(true);
		block.setDefaultBillable(false);
		fresh.setTimeTracking(block);
		settings.save(fresh);
		member = user("member", Role.MEMBER);
		lead = user("lead", Role.MEMBER);
		outsider = user("outsider", Role.MEMBER);
		admin = user("admin", Role.ORG_ADMIN);
		hinata = project("HIN", lead, member, lead);
		billing = project("BIL", member, member);
		foreign = project("OTH", outsider, outsider);
		login = issue(hinata, "HIN-1");
		invoice = issue(billing, "BIL-1");
		foreignIssue = issue(foreign, "OTH-1");
		// The project the hours are worth something in.
		mongo.save(ProjectTimeSettings.builder().projectId(billing.getId()).defaultBillable(true).build());
		locks.forgetProjectLocks();
	}

	private User user(String name, Role role) {
		return users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(role)).active(true).timezone("UTC").build());
	}

	private Project project(String key, User leader, User... members) {
		List<String> memberIds = new ArrayList<>();
		for (User member : members) {
			memberIds.add(member.getId());
		}
		return projects.save(Project.builder().key(key).name(key)
				.leadId(leader.getId()).leadIds(new ArrayList<>(List.of(leader.getId())))
				.memberIds(memberIds).build());
	}

	private Issue issue(Project in, String key) {
		return issues.save(Issue.builder().projectId(in.getId()).readableId(key)
				.numberInProject(Integer.parseInt(key.substring(key.indexOf('-') + 1))).title(key).state("Open").spentMinutes(0)
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build());
	}

	private WorkItem logged(String projectId, String issueId, int minutes, User owner) {
		return timeTracking.create(new TimeTrackingService.NewEntry(projectId, issueId, minutes, DAY,
				null, "work", null, null, null, null), WorkItem.Source.APP, owner);
	}

	private static TimeTrackingService.WorkItemPatch place(boolean projectSet, String projectId,
			boolean issueSet, String issueId) {
		return new TimeTrackingService.WorkItemPatch(null, null, null, null, false, null, false, null,
				null, null, projectSet, projectId, issueSet, issueId);
	}

	private static TimeTrackingService.WorkItemPatch toIssue(String issueId) {
		return place(false, null, true, issueId);
	}

	private int spentOn(Issue issue) {
		return issues.findById(issue.getId()).orElseThrow().getSpentMinutes();
	}

	private WorkItem reread(WorkItem item) {
		return workItems.findById(item.getId()).orElseThrow();
	}

	private static void assertRefused(ThrowingCallable call, HttpStatus status) {
		assertThatThrownBy(call).isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(status);
	}

	private static void assertRefused(ThrowingCallable call, HttpStatus status, String messageKey) {
		assertThatThrownBy(call).isInstanceOf(ApiException.class).hasMessage(messageKey)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(status);
	}

	// --- assigning --------------------------------------------------------------

	@Test
	void anUnfiledEntryIsFiledOnAnIssueAndTheIssueCountsIt() {
		WorkItem entry = logged(null, null, 45, member);

		WorkItem filed = timeTracking.update(entry.getId(), toIssue(login.getId()), member);

		assertThat(filed.getIssueId()).isEqualTo(login.getId());
		assertThat(filed.getProjectId()).as("the issue brings its project").isEqualTo(hinata.getId());
		assertThat(spentOn(login)).isEqualTo(45);
	}

	@Test
	void anIssueNamedByItsReadableIdIsStoredByItsId() {
		WorkItem entry = logged(null, null, 30, member);

		WorkItem filed = timeTracking.update(entry.getId(), toIssue("HIN-1"), member);

		assertThat(filed.getIssueId()).isEqualTo(login.getId());
	}

	@Test
	void movingBetweenIssuesMovesTheMinutesWithIt() {
		WorkItem entry = logged(null, login.getId(), 60, member);
		assertThat(spentOn(login)).isEqualTo(60);

		timeTracking.update(entry.getId(), toIssue(invoice.getId()), member);

		assertThat(spentOn(login)).isZero();
		assertThat(spentOn(invoice)).isEqualTo(60);
		assertThat(reread(entry).getProjectId()).isEqualTo(billing.getId());
	}

	@Test
	void aMoveAndANewLengthInOneEditCountOnBothSides() {
		WorkItem entry = logged(null, login.getId(), 60, member);

		timeTracking.update(entry.getId(), new TimeTrackingService.WorkItemPatch(90, null, null, null,
				false, null, false, null, null, null, false, null, true, invoice.getId()), member);

		assertThat(spentOn(login)).isZero();
		assertThat(spentOn(invoice)).isEqualTo(90);
	}

	@Test
	void namingOnlyAProjectLetsGoOfTheIssueOfTheOldOne() {
		WorkItem entry = logged(null, login.getId(), 20, member);

		WorkItem moved = timeTracking.update(entry.getId(), place(true, billing.getId(), false, null),
				member);

		assertThat(moved.getProjectId()).isEqualTo(billing.getId());
		assertThat(moved.getIssueId()).isNull();
		assertThat(spentOn(login)).isZero();
	}

	@Test
	void takingTheIssueAwayKeepsTheProject() {
		WorkItem entry = logged(null, login.getId(), 20, member);

		WorkItem detached = timeTracking.update(entry.getId(), toIssue(null), member);

		assertThat(detached.getIssueId()).isNull();
		assertThat(detached.getProjectId()).isEqualTo(hinata.getId());
		assertThat(spentOn(login)).isZero();
	}

	@Test
	void takingTheProjectAwayUnfilesTheEntry() {
		WorkItem entry = logged(null, login.getId(), 20, member);

		WorkItem unfiled = timeTracking.update(entry.getId(), place(true, null, true, null), member);

		assertThat(unfiled.getProjectId()).isNull();
		assertThat(unfiled.getIssueId()).isNull();
		assertThat(spentOn(login)).isZero();
	}

	@Test
	void aBodyWhoseIssueAndProjectDisagreeIsRefusedAndNothingMoves() {
		WorkItem entry = logged(null, null, 20, member);

		assertThatThrownBy(() -> timeTracking.update(entry.getId(),
				place(true, billing.getId(), true, login.getId()), member))
				.isInstanceOf(ApiException.class).hasMessage("error.time.issueProjectMismatch");
		assertThat(reread(entry).getProjectId()).isNull();
	}

	@Test
	void anEditThatLeavesThePlacementAloneStillWorksAfterTheOwnerLeftTheProject() {
		WorkItem entry = logged(null, login.getId(), 20, member);
		hinata.getMemberIds().remove(member.getId());
		projects.save(hinata);

		// The client repeats the placement it loaded; nothing about it changes.
		WorkItem updated = timeTracking.update(entry.getId(), new TimeTrackingService.WorkItemPatch(25,
				null, null, null, false, null, false, null, null, null, true, hinata.getId(), true,
				login.getId()), member);

		assertThat(updated.getDurationMinutes()).isEqualTo(25);
		assertThat(spentOn(login)).isEqualTo(25);
	}

	// --- who may file it where ----------------------------------------------------

	@Test
	void nobodyFilesTheirEntryOnAProjectOrIssueTheyCannotReach() {
		WorkItem entry = logged(null, login.getId(), 30, member);

		assertRefused(() -> timeTracking.update(entry.getId(), place(true, foreign.getId(), false, null),
				member), HttpStatus.FORBIDDEN);
		// An issue the caller cannot see answers like one that does not exist.
		assertRefused(() -> timeTracking.update(entry.getId(), toIssue(foreignIssue.getId()), member),
				HttpStatus.NOT_FOUND, "error.notFound");
		assertRefused(() -> timeTracking.update(entry.getId(), toIssue("OTH-1"), member),
				HttpStatus.NOT_FOUND, "error.notFound");
		assertRefused(() -> timeTracking.update(entry.getId(), toIssue("does-not-exist"), member),
				HttpStatus.NOT_FOUND, "error.notFound");

		WorkItem stored = reread(entry);
		assertThat(stored.getIssueId()).isEqualTo(login.getId());
		assertThat(spentOn(login)).isEqualTo(30);
		assertThat(spentOn(foreignIssue)).isZero();
	}

	@Test
	void somebodyElsesEntryCannotBeMovedByAPeer() {
		WorkItem entry = logged(null, invoice.getId(), 30, member);

		assertRefused(() -> timeTracking.update(entry.getId(), toIssue(foreignIssue.getId()), outsider),
				HttpStatus.FORBIDDEN);
		assertThat(reread(entry).getIssueId()).isEqualTo(invoice.getId());
	}

	@Test
	void aLeadDoesNotPushAMembersHoursIntoAProjectTheyHaveNoSayIn() {
		WorkItem entry = logged(null, login.getId(), 30, member);
		// The lead may reach the billing project, but leads nothing there.
		billing.getMemberIds().add(lead.getId());
		projects.save(billing);

		assertRefused(() -> timeTracking.update(entry.getId(), toIssue(invoice.getId()), lead),
				HttpStatus.FORBIDDEN, "error.time.editOwnOnly");
		assertThat(reread(entry).getIssueId()).isEqualTo(login.getId());
		assertThat(spentOn(login)).isEqualTo(30);
	}

	@Test
	void anAdministratorMovesAnEntryOnlyWhereItsOwnerCanSeeIt() {
		WorkItem entry = logged(null, login.getId(), 30, member);
		// Reaching an issue takes membership, for an administrator as on a create.
		for (Project joined : List.of(billing, foreign)) {
			joined.getMemberIds().add(admin.getId());
			projects.save(joined);
		}

		assertRefused(() -> timeTracking.update(entry.getId(), toIssue(foreignIssue.getId()), admin),
				HttpStatus.FORBIDDEN, "error.time.ownerOutOfReach");
		WorkItem moved = timeTracking.update(entry.getId(), toIssue(invoice.getId()), admin);
		assertThat(moved.getIssueId()).isEqualTo(invoice.getId());
	}

	// --- what stays frozen ----------------------------------------------------------

	@Test
	void anInvoicedEntryDoesNotMove() {
		WorkItem entry = logged(null, login.getId(), 30, member);
		mongo.updateFirst(Query.query(Criteria.where("_id").is(entry.getId())),
				new Update().set("invoiceId", "inv-1"), WorkItem.class);

		assertRefused(() -> timeTracking.update(entry.getId(), toIssue(invoice.getId()), member),
				HttpStatus.FORBIDDEN);
		assertThat(spentOn(login)).isEqualTo(30);
		assertThat(spentOn(invoice)).isZero();
	}

	@Test
	void aRequiredProjectCannotBeTakenOffButAnOldUnfiledEntryStaysEditable() {
		WorkItem entry = logged(null, login.getId(), 30, member);
		WorkItem unfiled = logged(null, null, 15, member);
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking.RequiredFields required = new ServerSettings.TimeTracking.RequiredFields();
		required.setProject(true);
		current.getTimeTracking().setRequiredFields(required);
		settings.save(current);

		assertThatThrownBy(() -> timeTracking.update(entry.getId(), place(true, null, true, null), member))
				.isInstanceOf(ApiException.class).hasMessage("error.time.required.project");
		WorkItem edited = timeTracking.update(unfiled.getId(), new TimeTrackingService.WorkItemPatch(20,
				null, null, null, false, null, false, null, null, null), member);
		assertThat(edited.getDurationMinutes()).isEqualTo(20);
		WorkItem filed = timeTracking.update(unfiled.getId(), toIssue(login.getId()), member);
		assertThat(filed.getProjectId()).isEqualTo(hinata.getId());
	}

	// --- billable --------------------------------------------------------------------

	@Test
	void anInheritedBillableFlagFollowsTheEntryToItsNewProject() {
		WorkItem entry = logged(null, null, 30, member);
		assertThat(entry.isBillable()).as("the instance default").isFalse();

		WorkItem filed = timeTracking.update(entry.getId(), toIssue(invoice.getId()), member);
		assertThat(filed.isBillable()).as("the billing project bills by default").isTrue();

		WorkItem back = timeTracking.update(entry.getId(), place(true, hinata.getId(), false, null), member);
		assertThat(back.isBillable()).as("and the inherited flag follows it back").isFalse();
	}

	@Test
	void aChosenBillableFlagStaysWhereverTheEntryGoes() {
		WorkItem entry = timeTracking.create(new TimeTrackingService.NewEntry(null, null, 30, DAY, null,
				"work", null, null, null, true), WorkItem.Source.APP, member);

		WorkItem moved = timeTracking.update(entry.getId(), place(true, hinata.getId(), false, null), member);

		assertThat(moved.isBillable()).isTrue();
	}

	@Test
	void aFlagSentWithTheMoveWins() {
		WorkItem entry = logged(null, null, 30, member);

		WorkItem moved = timeTracking.update(entry.getId(), new TimeTrackingService.WorkItemPatch(null,
				null, null, null, false, null, false, null, null, false, false, null, true,
				invoice.getId()), member);

		assertThat(moved.isBillable()).isFalse();
	}

	// --- the derived counter under concurrency ----------------------------------------

	@Test
	void differentEntriesMovedAtTheSameTimeLoseNoMinutes() throws Exception {
		List<WorkItem> entries = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			entries.add(logged(null, login.getId(), 10, member));
		}
		ExecutorService pool = Executors.newFixedThreadPool(6);
		try {
			List<Callable<WorkItem>> moves = entries.stream()
					.<Callable<WorkItem>>map(entry -> () -> timeTracking.update(entry.getId(),
							toIssue(invoice.getId()), member))
					.toList();
			for (Future<WorkItem> done : pool.invokeAll(moves)) {
				done.get();
			}
		}
		finally {
			pool.shutdown();
		}

		assertThat(spentOn(login)).isZero();
		assertThat(spentOn(invoice)).isEqualTo(120);
	}
	@Test
	void oneEntryMovedTwoWaysAtOnceIsCountedOnce() throws Exception {
		Issue third = issue(billing, "BIL-2");
		int rounds = 20;
		List<WorkItem> entries = new ArrayList<>();
		for (int i = 0; i < rounds; i++) {
			entries.add(logged(null, login.getId(), 10, member));
		}
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (WorkItem entry : entries) {
				CyclicBarrier start = new CyclicBarrier(2);
				List<Callable<WorkItem>> both = List.of(
						() -> movedTogether(start, entry, invoice),
						() -> movedTogether(start, entry, third));
				for (Future<WorkItem> done : pool.invokeAll(both)) {
					try {
						done.get();
					}
					catch (ExecutionException failed) {
						// The loser of a race is told the entry changed; nothing else may go wrong.
						assertThat(failed.getCause()).isInstanceOf(ApiException.class)
								.hasMessage("error.time.entryChanged");
						assertThat(((ApiException) failed.getCause()).getStatus())
								.isEqualTo(HttpStatus.CONFLICT);
					}
				}
			}
		}
		finally {
			pool.shutdown();
		}

		// Every entry is counted exactly once, wherever it ended up: a lost race is a 409 and
		// gives nobody minutes, never a second copy of them.
		assertThat(spentOn(login)).isZero();
		assertThat(spentOn(invoice) + spentOn(third)).isEqualTo(rounds * 10);
		long onInvoice = entries.stream()
				.filter(entry -> invoice.getId().equals(reread(entry).getIssueId()))
				.count();
		assertThat(spentOn(invoice)).isEqualTo((int) onInvoice * 10);
	}

	private WorkItem movedTogether(CyclicBarrier start, WorkItem entry, Issue to) throws Exception {
		start.await();
		return timeTracking.update(entry.getId(), toIssue(to.getId()), member);
	}

	@Test
	void anAdministratorsMoveOfSomebodyElsesEntrySaysWhereItCameFrom() {
		billing.getMemberIds().add(admin.getId());
		projects.save(billing);
		WorkItem entry = logged(null, login.getId(), 30, member);

		timeTracking.update(entry.getId(), toIssue(invoice.getId()), admin);

		assertThat(auditLog.findAll())
				.filteredOn(row -> row.getAction() == AuditAction.TIME_ENTRY_UPDATED)
				.singleElement()
				.satisfies(row -> {
					assertThat(row.getMetadata()).containsEntry("fromProject", hinata.getId())
							.containsEntry("fromIssue", login.getId())
							.containsEntry("project", billing.getId())
							.containsEntry("issue", invoice.getId());
					// And the owner's history shows both sides.
					assertThat(TimeEntryController.HistoryEntryResponse.from(row, false).metadata())
							.containsKeys("fromProject", "fromIssue", "project", "issue");
				});
	}

	@Test
	void anEntryIsNotMovedIntoALockedProject() {
		WorkItem entry = logged(null, login.getId(), 30, member);
		mongo.updateFirst(Query.query(Criteria.where("projectId").is(billing.getId())),
				new Update().set("lockBefore", LocalDate.now()), ProjectTimeSettings.class);
		locks.forgetProjectLocks();

		assertRefused(() -> timeTracking.update(entry.getId(), toIssue(invoice.getId()), member),
				HttpStatus.FORBIDDEN);
		WorkItem stored = reread(entry);
		assertThat(stored.getIssueId()).isEqualTo(login.getId());
		assertThat(spentOn(login)).isEqualTo(30);
		assertThat(spentOn(invoice)).isZero();
	}

	@Test
	void anEntryIsNotMovedIntoNorOutOfAnApprovedPeriod() {
		ServerSettings current = settings.get();
		current.getTimeTracking().setApprovalsEnabled(true);
		settings.save(current);
		WorkItem outside = logged(null, login.getId(), 30, member);
		approved(member, billing);

		assertRefused(() -> timeTracking.update(outside.getId(), toIssue(invoice.getId()), member),
				HttpStatus.FORBIDDEN);
		assertThat(reread(outside).getIssueId()).isEqualTo(login.getId());

		WorkItem inside = logged(null, null, 20, member);
		mongo.updateFirst(Query.query(Criteria.where("_id").is(inside.getId())),
				new Update().set("projectId", billing.getId()), WorkItem.class);
		assertRefused(() -> timeTracking.update(inside.getId(), toIssue(login.getId()), member),
				HttpStatus.FORBIDDEN);
		assertThat(reread(inside).getProjectId()).isEqualTo(billing.getId());
		assertThat(spentOn(login)).isEqualTo(30);
	}

	@Test
	void aLeadIsToldTheEntryIsNotTheirsBeforeAnythingAboutTheOtherProjectsFreeze() {
		ServerSettings current = settings.get();
		current.getTimeTracking().setApprovalsEnabled(true);
		settings.save(current);
		billing.getMemberIds().add(lead.getId());
		projects.save(billing);
		approved(member, billing);
		WorkItem entry = logged(null, login.getId(), 30, member);

		// Not the approval's refusal: which period of the member's the billing project froze
		// is nothing a lead of another project gets to read.
		assertRefused(() -> timeTracking.update(entry.getId(), toIssue(invoice.getId()), lead),
				HttpStatus.FORBIDDEN, "error.time.editOwnOnly");
	}

	private void approved(User owner, Project in) {
		mongo.insert(TimesheetApproval.builder().userId(owner.getId()).projectId(in.getId())
				.periodStart(DAY.minusDays(3)).periodEnd(DAY.plusDays(3))
				.periodType(TimePolicy.ApprovalPeriod.WEEKLY)
				.status(TimesheetApproval.Status.APPROVED).totalMinutes(0).build());
	}

	@Test
	void anInvisibleIssueIsNotFoundOnEveryWriteButTheFrozenOne() {
		assertRefused(() -> logged(null, foreignIssue.getId(), 30, member), HttpStatus.NOT_FOUND,
				"error.notFound");
		// POST /issues/{id}/work-items keeps the answer the published app has always had.
		TimeTrackingService.NewWorkItem draft = new TimeTrackingService.NewWorkItem(30, DAY, null,
				"work", null, null, null, null);
		assertRefused(() -> timeTracking.add(foreignIssue.getId(), draft, WorkItem.Source.APP, member),
				HttpStatus.FORBIDDEN);
	}
}
