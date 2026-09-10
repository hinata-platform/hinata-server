package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Handing periods in, signing them off, and the freeze that follows.
 *
 * <p>Against a real database, because most of what is claimed here is MongoDB
 * doing something the code cannot do on its own: a unique index settling two
 * concurrent submissions of the same period, an insert-then-look-again settling
 * two overlapping free spans, a containment query that keeps finding a stored
 * period after the operator has changed the rhythm underneath it.
 *
 * <p>The clock is frozen. "Before the lock date" and "at most today" are
 * statements about a boundary, and a test that asserts them against the wall
 * clock either drifts or never touches the edge.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Import(TimesheetApprovalIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class TimesheetApprovalIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);
	/** A finished month, so a submission over it never freezes anything in "today". */
	private static final LocalDate LAST_MONTH_START = LocalDate.of(2026, 8, 1);
	private static final LocalDate LAST_MONTH_END = LocalDate.of(2026, 8, 31);

	@TestConfiguration
	static class FrozenClock {
		@Bean
		@Primary
		Clock testClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimesheetApprovalService approvals;
	@Autowired
	private TimesheetApprovalRepository approvalRepository;
	@Autowired
	private TimeTrackingService timeTracking;
	@Autowired
	private TimeLocks locks;
	@Autowired
	private SettingsService settings;
	@Autowired
	private ProjectTimeSettingsRepository projectSettings;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;
	@Autowired
	private NotificationRepository notifications;
	@Autowired
	private TimeTrackingMoveGuard moveGuard;

	private User member;
	private User lead;
	private User secondLead;
	private User admin;
	private User stranger;
	private Project project;
	private Project otherProject;
	private Issue issue;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "projects", "teams", "users", "work_items",
				"running_timers", "time_tags", "project_time_settings", "timesheet_approvals",
				"audit_log", "notifications", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		approvalsEnabled(true);
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadIds(new ArrayList<>()).memberIds(new ArrayList<>()).build());
		otherProject = projects.save(Project.builder().key("MOB").name("Mobile")
				.leadIds(new ArrayList<>()).memberIds(new ArrayList<>()).build());
		member = user("member", Role.MEMBER);
		lead = user("lead", Role.MEMBER);
		secondLead = user("second", Role.MEMBER);
		admin = user("admin", Role.ADMIN);
		// Deliberately in no project: "a stranger" has to mean somebody the
		// submission is genuinely none of, or the 404 assertions prove nothing.
		stranger = users.save(User.builder().email("stranger@example.org").username("stranger")
				.displayName("stranger").roles(Set.of(Role.MEMBER)).active(true).timezone("UTC")
				.build());
		project.getLeadIds().add(lead.getId());
		project.getLeadIds().add(secondLead.getId());
		project = projects.save(project);
		issue = issueRepository.save(Issue.builder().projectId(project.getId()).readableId("HIN-1")
				.numberInProject(1).title("Login bug").state("Open").spentMinutes(0)
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build());
	}

	private User user(String name, Role role) {
		User saved = users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(role)).active(true).timezone("UTC").build());
		for (Project each : List.of(project, otherProject)) {
			each.getMemberIds().add(saved.getId());
			projects.save(each);
		}
		project = projects.findById(project.getId()).orElseThrow();
		otherProject = projects.findById(otherProject.getId()).orElseThrow();
		return saved;
	}

	// --- policy plumbing ------------------------------------------------------------

	private ServerSettings.TimeTracking block() {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking found = current.getTimeTracking();
		if (found == null) {
			found = new ServerSettings.TimeTracking();
			found.setAdvancedEnabled(true);
			current.setTimeTracking(found);
		}
		return found;
	}

	private void store(ServerSettings.TimeTracking updated) {
		ServerSettings current = settings.get();
		current.setTimeTracking(updated);
		settings.save(current);
	}

	private void approvalsEnabled(boolean enabled) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking updated = current.getTimeTracking() != null
				? current.getTimeTracking() : new ServerSettings.TimeTracking();
		updated.setAdvancedEnabled(true);
		updated.setApprovalsEnabled(enabled);
		current.setTimeTracking(updated);
		settings.save(current);
	}

	private void rhythm(TimePolicy.ApprovalPeriod type, DayOfWeek weekStart) {
		ServerSettings.TimeTracking updated = block();
		ServerSettings.TimeTracking.ApprovalPeriod period =
				new ServerSettings.TimeTracking.ApprovalPeriod();
		period.setType(type);
		period.setWeekStartsOn(weekStart);
		updated.setApprovalPeriod(period);
		store(updated);
	}

	private void lockBefore(LocalDate day) {
		ServerSettings.TimeTracking updated = block();
		updated.setLockBefore(day);
		store(updated);
	}

	// --- fixtures -------------------------------------------------------------------

	/** An entry of {@code who} on {@code day}, in {@code in}, written past every gate. */
	private WorkItem entry(User who, Project in, LocalDate day, int minutes) {
		return workItems.save(WorkItem.builder()
				.userId(who.getId())
				.projectId(in == null ? null : in.getId())
				.issueId(in == project ? issue.getId() : null)
				.date(day)
				.durationMinutes(minutes)
				.activityType("Development")
				.tags(List.of())
				.source(WorkItem.Source.APP)
				.build());
	}

	private List<TimesheetApproval> submitLastMonth(User who) {
		return approvals.submit(LAST_MONTH_START, LAST_MONTH_END, null, who);
	}

	// --- the policy gate ---------------------------------------------------------------

	@Test
	void withThePolicyOffEveryApprovalRouteBehavesAsIfItDoesNotExist() {
		approvalsEnabled(false);
		entry(member, project, LAST_MONTH_START, 60);

		// 404 and not 403: a feature that is not switched on does not exist, and a
		// client told "forbidden" would show "denied" where the honest answer is
		// "not on this server".
		assertThatThrownBy(() -> submitLastMonth(member))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThatThrownBy(() -> approvals.periods(LAST_MONTH_START, LAST_MONTH_END, null, member))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void withThePolicyOffNothingFreezesAnEntry() {
		approvalsEnabled(false);
		WorkItem old = entry(member, project, LAST_MONTH_START, 60);
		approvalRepository.save(TimesheetApproval.builder()
				.userId(member.getId()).projectId(project.getId())
				.periodStart(LAST_MONTH_START).periodEnd(LAST_MONTH_END)
				.periodType(TimePolicy.ApprovalPeriod.MONTHLY)
				.status(TimesheetApproval.Status.APPROVED).build());

		// A row left behind by a policy that has since been switched off must not
		// keep freezing anybody's time — switching the policy off has to give
		// people their records back.
		assertThat(locks.lockStateFor(member.getId(), project.getId(), old.getDate())).isNull();
	}

	// --- the default rhythm --------------------------------------------------------------

	@Test
	void theDefaultRhythmIsMonthlyAndNothingAssumesAWeek() {
		entry(member, project, LAST_MONTH_START, 60);

		// Nothing configured anywhere. A month is what comes out — which is the
		// whole of the "no code assumes a week" claim that a grep cannot make.
		List<TimesheetApprovalService.PeriodView> views =
				approvals.periods(LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 15), null, member);
		assertThat(views).singleElement().satisfies(view -> {
			assertThat(view.type()).isEqualTo(TimePolicy.ApprovalPeriod.MONTHLY);
			assertThat(view.start()).isEqualTo(LAST_MONTH_START);
			assertThat(view.end()).isEqualTo(LAST_MONTH_END);
		});
		assertThat(submitLastMonth(member)).singleElement()
				.extracting(TimesheetApproval::getPeriodType)
				.isEqualTo(TimePolicy.ApprovalPeriod.MONTHLY);
	}

	@Test
	void eachPeriodInAWindowCarriesItsOwnFigureAndNotTheWindowsTotal() {
		// The failure this is written against would give three months the same
		// number — a wrong answer wearing the shape of a right one, and exactly
		// what somebody would compare against a payslip.
		entry(member, project, LocalDate.of(2026, 7, 10), 60);
		entry(member, project, LocalDate.of(2026, 8, 10), 120);
		entry(member, project, LocalDate.of(2026, 8, 20), 30);

		List<TimesheetApprovalService.PeriodView> views = approvals.periods(
				LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), null, member);

		assertThat(views).hasSize(3);
		assertThat(views).extracting(view -> view.projects().isEmpty()
						? 0 : view.projects().getFirst().minutes())
				.containsExactly(60, 150, 0);
	}

	@Test
	void aProjectOverrideBeatsTheInstanceRhythmAndItsAbsenceDoesNot() {
		// The instance submits monthly; this project closes per quarter.
		projectSettings.save(ProjectTimeSettings.builder()
				.projectId(project.getId())
				.approvalPeriod(ProjectTimeSettings.ApprovalPeriod.builder()
						.type(TimePolicy.ApprovalPeriod.QUARTERLY).build())
				.build());

		assertThat(approvals.periodPolicy(project.getId()).type())
				.isEqualTo(TimePolicy.ApprovalPeriod.QUARTERLY);
		assertThat(approvals.periodPolicy(otherProject.getId()).type())
				.isEqualTo(TimePolicy.ApprovalPeriod.MONTHLY);

		entry(member, project, LAST_MONTH_START, 60);
		// A month is not a period of this project's rhythm any more.
		assertThatThrownBy(() -> submitLastMonth(member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.periodNotOnGrid");
		// Its quarter is — the one that has *ended*. The quarter August sits in is
		// still running on this suite's clock, and a period that has not ended
		// cannot be handed in: freezing it would stop the person recording the
		// working time they have yet to do.
		entry(member, project, LocalDate.of(2026, 5, 12), 90);
		assertThat(approvals.submit(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), null,
				member)).hasSize(1);
	}

	@Test
	void anOverrideThatNamesOnlyTheTypeKeepsTheInstancesOtherFields() {
		rhythm(TimePolicy.ApprovalPeriod.WEEKLY, DayOfWeek.SUNDAY);
		projectSettings.save(ProjectTimeSettings.builder().projectId(project.getId())
				.approvalPeriod(ProjectTimeSettings.ApprovalPeriod.builder()
						.type(TimePolicy.ApprovalPeriod.WEEKLY).build())
				.build());

		assertThat(approvals.periodPolicy(project.getId()).weekStartsOn())
				.isEqualTo(DayOfWeek.SUNDAY);
	}

	// --- submitting ---------------------------------------------------------------------

	@Test
	void submittingCreatesOneRowPerProjectAndLeavesUnfiledHoursAlone() {
		entry(member, project, LAST_MONTH_START, 60);
		entry(member, otherProject, LAST_MONTH_START.plusDays(1), 30);
		// No project: private, belongs to no lead, nobody to approve it.
		entry(member, null, LAST_MONTH_START.plusDays(2), 45);

		List<TimesheetApproval> submitted = submitLastMonth(member);

		assertThat(submitted).hasSize(2)
				.extracting(TimesheetApproval::getProjectId)
				.containsExactlyInAnyOrder(project.getId(), otherProject.getId());
		assertThat(submitted).allMatch(row -> row.getStatus() == TimesheetApproval.Status.SUBMITTED);
		assertThat(submitted).extracting(TimesheetApproval::getTotalMinutes)
				.containsExactlyInAnyOrder(60, 30);
	}

	@Test
	void submittingTwiceIsAConflictAndNotASecondRow() {
		entry(member, project, LAST_MONTH_START, 60);
		submitLastMonth(member);

		assertThatThrownBy(() -> submitLastMonth(member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.periodAlreadySubmitted");
		assertThat(approvalRepository.count()).isEqualTo(1);
	}

	@Test
	void aPeriodWithNoHoursIsRefusedRatherThanSucceedingEmptily() {
		// An empty 200 would look like a submission that then cannot be found.
		assertThatThrownBy(() -> submitLastMonth(member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.periodEmpty");
	}

	@Test
	void aSpanThatIsNotOnTheGridIsRefused() {
		entry(member, project, LAST_MONTH_START, 60);

		assertThatThrownBy(() -> approvals.submit(LAST_MONTH_START, LAST_MONTH_END.minusDays(1),
				null, member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.periodNotOnGrid");
	}

	@Test
	void aPeriodThatHasNotEndedCannotBeHandedIn() {
		// The same rule the lock date is held to, and for the same reason: an
		// approved span is immutable, so submitting the rest of this month would
		// stop this person recording the working time they are about to perform
		// (§ 16 Abs. 2 ArbZG, EuGH C-55/18). One freeze may not be exempt from a
		// rule the other keeps.
		entry(member, project, TODAY.withDayOfMonth(1), 60);

		assertThatThrownBy(() -> approvals.submit(TODAY.withDayOfMonth(1),
				TODAY.withDayOfMonth(TODAY.lengthOfMonth()), null, member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.periodNotEnded");
		// The month that has ended is fine.
		entry(member, project, LAST_MONTH_START, 60);
		assertThat(submitLastMonth(member)).hasSize(1);
	}

	@Test
	void aSpanLongerThanTheCeilingIsRefusedBeforeTheGridIsEvenConsulted() {
		entry(member, project, LAST_MONTH_START, 60);

		assertThatThrownBy(() -> approvals.submit(LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 8, 31), null, member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.periodTooLong");
	}

	@Test
	void aFreeRhythmTakesAnySpanButNotOneThatOverlapsWhatIsAlreadyIn() {
		rhythm(TimePolicy.ApprovalPeriod.FREE, null);
		entry(member, project, LocalDate.of(2026, 8, 3), 60);
		entry(member, project, LocalDate.of(2026, 8, 12), 60);

		approvals.submit(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), null, member);

		// Abutting is fine — the spans share no day.
		approvals.submit(LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 14), null, member);
		// Sharing one day is not.
		entry(member, project, LocalDate.of(2026, 8, 20), 60);
		assertThatThrownBy(() -> approvals.submit(LocalDate.of(2026, 8, 7),
				LocalDate.of(2026, 8, 20), null, member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.periodOverlaps");
		assertThat(approvalRepository.count()).isEqualTo(2);
	}

	@Test
	void aWithdrawnSpanStopsBlockingAnOverlappingOne() {
		rhythm(TimePolicy.ApprovalPeriod.FREE, null);
		entry(member, project, LocalDate.of(2026, 8, 3), 60);
		TimesheetApproval first = approvals
				.submit(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), null, member)
				.getFirst();

		approvals.withdraw(first.getId(), member);

		// Withdrawn does not freeze, so it cannot block either — and the person who
		// took it back is exactly the one who needs to re-cut the span.
		assertThat(approvals.submit(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10), null,
				member)).hasSize(1);
	}

	@Test
	void concurrentOverlappingFreeSubmissionsLeaveExactlyOneWinner() throws Exception {
		rhythm(TimePolicy.ApprovalPeriod.FREE, null);
		entry(member, project, LocalDate.of(2026, 8, 10), 60);
		int racers = 6;
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger won = new AtomicInteger();
		AtomicInteger lost = new AtomicInteger();
		try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
			for (int i = 0; i < racers; i++) {
				// Overlapping but not identical spans, so the unique index on
				// periodStart cannot be what settles it — the insert-then-look-again
				// has to.
				LocalDate start = LocalDate.of(2026, 8, 5).plusDays(i);
				pool.submit(() -> {
					try {
						go.await();
						approvals.submit(start, start.plusDays(10), null, member);
						won.incrementAndGet();
					}
					catch (ApiException refused) {
						lost.incrementAndGet();
					}
					catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
					}
					return null;
				});
			}
			go.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(won.get()).isEqualTo(1);
		assertThat(lost.get()).isEqualTo(racers - 1);
		// And the loser left nothing behind: a row that was inserted and then stood
		// down has to be gone, not merely unreferenced.
		assertThat(approvalRepository.count()).isEqualTo(1);
	}

	// --- the freeze -----------------------------------------------------------------------

	@Test
	void aSubmittedPeriodFreezesItsEntriesAgainstEveryWritePath() {
		WorkItem old = entry(member, project, LAST_MONTH_START, 60);
		submitLastMonth(member);

		// Editing, deleting and adding. The gate is one method, so this covers the
		// app, MCP and a smart commit at once — none of them has its own path.
		assertThatThrownBy(() -> timeTracking.update(old.getId(),
				new TimeTrackingService.WorkItemPatch(30, null, null, null, false, null, false,
						null, null, null), member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.approvalLocked");
		assertThatThrownBy(() -> timeTracking.delete(old.getId(), member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.approvalLocked");
		assertThatThrownBy(() -> timeTracking.create(new TimeTrackingService.NewEntry(
						project.getId(), null, 30, LAST_MONTH_END, null, "more", null, null,
						List.of(), null), WorkItem.Source.APP, member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.approvalLocked");
	}

	@Test
	void theFreezeBindsTheLeadAndTheAdministratorToo() {
		WorkItem old = entry(member, project, LAST_MONTH_START, 60);
		submitLastMonth(member);

		// A freeze the most powerful account can edit around is a suggestion. The
		// way through is an audited reopen, not a quiet edit.
		for (User privileged : List.of(lead, admin)) {
			assertThatThrownBy(() -> timeTracking.delete(old.getId(), privileged))
					.isInstanceOf(ApiException.class)
					.hasMessage("error.time.approvalLocked");
		}
	}

	@Test
	void movingAnEntryOutOfAFrozenPeriodIsRefusedAndSoIsMovingOneIn() {
		WorkItem inside = entry(member, project, LAST_MONTH_START, 60);
		WorkItem outside = entry(member, project, TODAY, 60);
		submitLastMonth(member);

		// Out of: the period's total changes as surely as if something were added.
		assertThatThrownBy(() -> timeTracking.update(inside.getId(),
				new TimeTrackingService.WorkItemPatch(null, TODAY, null, null, false, null, false,
						null, null, null), member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.approvalLocked");
		// Into: the other tuple, and the reason the gate is handed both sides.
		assertThatThrownBy(() -> timeTracking.update(outside.getId(),
				new TimeTrackingService.WorkItemPatch(null, LAST_MONTH_END, null, null, false,
						null, false, null, null, null), member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.approvalLocked");
	}

	@Test
	void anotherPersonsSubmissionFreezesNothingOfYours() {
		WorkItem mine = entry(member, project, LAST_MONTH_START, 60);
		entry(lead, project, LAST_MONTH_START, 60);
		submitLastMonth(lead);

		// The tuple is (owner, project, day). A colleague handing in the same month
		// of the same project must leave your own hours editable.
		assertThat(locks.lockStateFor(member.getId(), project.getId(), mine.getDate())).isNull();
		timeTracking.delete(mine.getId(), member);
	}

	@Test
	void aProjectlessEntryIsNeverFrozenByAnApproval() {
		WorkItem unfiled = entry(member, null, LAST_MONTH_START, 45);
		entry(member, project, LAST_MONTH_START, 60);
		submitLastMonth(member);

		// It was never part of the submission, so it cannot be covered by it. The
		// lock date is what covers unfiled hours, and that is tested separately.
		assertThat(locks.lockStateFor(member.getId(), null, unfiled.getDate())).isNull();
		timeTracking.delete(unfiled.getId(), member);
	}

	@Test
	void aRejectedPeriodIsEditableAgainAndCanBeHandedBackIn() {
		WorkItem old = entry(member, project, LAST_MONTH_START, 60);
		TimesheetApproval submitted = submitLastMonth(member).getFirst();

		approvals.decide(submitted.getId(), TimesheetApproval.Status.REJECTED,
				"Friday is missing", lead);

		// The whole point of sending it back is that it can be fixed.
		assertThat(locks.lockStateFor(member.getId(), project.getId(), old.getDate())).isNull();
		timeTracking.update(old.getId(), new TimeTrackingService.WorkItemPatch(90, null, null, null,
				false, null, false, null, null, null), member);
		// And the second submission reuses the same row, carrying its history.
		TimesheetApproval again = submitLastMonth(member).getFirst();
		assertThat(again.getId()).isEqualTo(submitted.getId());
		assertThat(again.getStatus()).isEqualTo(TimesheetApproval.Status.SUBMITTED);
		assertThat(again.getTotalMinutes()).isEqualTo(90);
		assertThat(again.getHistory()).hasSize(3);
		// The rejection's reason belongs to the rejection, not to the new attempt.
		assertThat(again.getNote()).isNull();
		assertThat(again.getDecidedBy()).isNull();
	}

	@Test
	void aRhythmChangeLeavesExistingSubmissionsExactlyWhereTheyAre() {
		WorkItem old = entry(member, project, LAST_MONTH_START.plusDays(3), 60);
		rhythm(TimePolicy.ApprovalPeriod.WEEKLY, DayOfWeek.MONDAY);
		TimesheetApproval weekly = approvals
				.submit(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9), null, member)
				.getFirst();

		// The operator moves the instance to monthly. Nothing is recomputed and
		// nothing is migrated.
		rhythm(TimePolicy.ApprovalPeriod.MONTHLY, null);

		TimesheetApproval reread = approvalRepository.findById(weekly.getId()).orElseThrow();
		assertThat(reread.getPeriodType()).isEqualTo(TimePolicy.ApprovalPeriod.WEEKLY);
		assertThat(reread.getPeriodStart()).isEqualTo(LocalDate.of(2026, 8, 3));
		// And the freeze still covers exactly the days it named — which is what
		// asking by containment buys, and what asking "does this day's period have
		// an approval" would have lost.
		assertThat(locks.lockStateFor(member.getId(), project.getId(), old.getDate())).isNotNull();
		assertThat(locks.lockStateFor(member.getId(), project.getId(), LocalDate.of(2026, 8, 20)))
				.isNull();
	}

	// --- who decides -----------------------------------------------------------------------

	@Test
	void nobodySignsOffTheirOwnPeriod() {
		entry(lead, project, LAST_MONTH_START, 60);
		TimesheetApproval own = submitLastMonth(lead).getFirst();

		assertThatThrownBy(() -> approvals.decide(own.getId(),
				TimesheetApproval.Status.APPROVED, null, lead))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.approvalSelf");
		// A second lead can, which is why the rule is workable at all.
		assertThat(approvals.decide(own.getId(), TimesheetApproval.Status.APPROVED, null, secondLead)
				.getStatus()).isEqualTo(TimesheetApproval.Status.APPROVED);
	}

	@Test
	void anAdministratorCanDecideASoleLeadsOwnPeriod() {
		// Why administrators are not excluded from the self-approval rule: an
		// instance has to have somebody who can decide the period of the only lead.
		project.setLeadIds(new ArrayList<>(List.of(lead.getId())));
		project = projects.save(project);
		entry(lead, project, LAST_MONTH_START, 60);
		TimesheetApproval own = submitLastMonth(lead).getFirst();

		assertThat(approvals.decide(own.getId(), TimesheetApproval.Status.APPROVED, null, admin)
				.getStatus()).isEqualTo(TimesheetApproval.Status.APPROVED);
	}

	@Test
	void aStrangerIsNotToldTheSubmissionExists() {
		entry(member, project, LAST_MONTH_START, 60);
		TimesheetApproval submitted = submitLastMonth(member).getFirst();

		// 404, not 403: a 403 would confirm that an id belongs to some colleague's
		// period, which is an answer an id space can be walked with.
		for (String id : List.of(submitted.getId())) {
			assertThatThrownBy(() -> approvals.get(id, stranger))
					.isInstanceOf(ApiException.class)
					.extracting(thrown -> ((ApiException) thrown).getStatus())
					.isEqualTo(HttpStatus.NOT_FOUND);
			assertThatThrownBy(() -> approvals.decide(id, TimesheetApproval.Status.APPROVED, null,
					stranger))
					.isInstanceOf(ApiException.class)
					.extracting(thrown -> ((ApiException) thrown).getStatus())
					.isEqualTo(HttpStatus.NOT_FOUND);
		}
	}

	@Test
	void anOrdinaryMemberOfTheProjectStillCannotDecide() {
		entry(lead, project, LAST_MONTH_START, 60);
		TimesheetApproval submitted = submitLastMonth(lead).getFirst();

		// Being in the project is not being a lead of it. The rule is
		// assertLeadOrAdmin and deliberately not canDeleteIssues, which also admits
		// Team-Admins — tidying a backlog is not the same authority as accepting
		// somebody's record of their working time.
		assertThatThrownBy(() -> approvals.decide(submitted.getId(),
				TimesheetApproval.Status.APPROVED, null, member))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void theInboxShowsOnlyTheProjectsTheReaderLeads() {
		entry(member, project, LAST_MONTH_START, 60);
		entry(member, otherProject, LAST_MONTH_START, 60);
		submitLastMonth(member);

		assertThat(approvals.inbox(null, 0, 25, lead).getContent())
				.extracting(TimesheetApproval::getProjectId)
				.containsExactly(project.getId());
		// An administrator sees every project — the one case where the filter is
		// dropped rather than widened into a two-thousand-id $in.
		assertThat(approvals.inbox(null, 0, 25, admin).getTotalElements()).isEqualTo(2);
		assertThat(approvals.inbox(null, 0, 25, stranger).getContent()).isEmpty();
		// And "mine" is about the reader's own time, never about the inbox.
		assertThat(approvals.mine(null, 0, 25, lead).getContent()).isEmpty();
		assertThat(approvals.mine(null, 0, 25, member).getTotalElements()).isEqualTo(2);
	}

	@Test
	void withdrawingIsTheSubmittersOwnActAndOnlyWhilePending() {
		entry(member, project, LAST_MONTH_START, 60);
		TimesheetApproval submitted = submitLastMonth(member).getFirst();

		assertThatThrownBy(() -> approvals.withdraw(submitted.getId(), lead))
				.isInstanceOf(ApiException.class);
		approvals.decide(submitted.getId(), TimesheetApproval.Status.APPROVED, null, lead);
		assertThatThrownBy(() -> approvals.withdraw(submitted.getId(), member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.approvalNotPending");
	}

	@Test
	void aRejectionNeedsAReasonAndAnApprovalDoesNot() {
		entry(member, project, LAST_MONTH_START, 60);
		TimesheetApproval submitted = submitLastMonth(member).getFirst();

		assertThatThrownBy(() -> approvals.decide(submitted.getId(),
				TimesheetApproval.Status.REJECTED, "   ", lead))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.approvalNoteRequired");
		assertThat(approvals.decide(submitted.getId(), TimesheetApproval.Status.APPROVED, null, lead)
				.getStatus()).isEqualTo(TimesheetApproval.Status.APPROVED);
	}

	@Test
	void reopeningNeedsAReasonAndMakesThePeriodEditableAgain() {
		WorkItem old = entry(member, project, LAST_MONTH_START, 60);
		TimesheetApproval submitted = submitLastMonth(member).getFirst();
		approvals.decide(submitted.getId(), TimesheetApproval.Status.APPROVED, null, lead);
		assertThat(locks.lockStateFor(member.getId(), project.getId(), old.getDate())).isNotNull();

		assertThatThrownBy(() -> approvals.reopen(submitted.getId(), null, lead))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.approvalNoteRequired");

		TimesheetApproval reopened =
				approvals.reopen(submitted.getId(), "Payroll found a missing day", lead);

		assertThat(reopened.getNote()).isEqualTo("Payroll found a missing day");
		assertThat(reopened.getHistory()).last()
				.satisfies(event -> assertThat(event.getTo())
						.isEqualTo(TimesheetApproval.Status.REJECTED));
		assertThat(locks.lockStateFor(member.getId(), project.getId(), old.getDate())).isNull();
		// Only an approved period can be reopened; a pending one is withdrawn or
		// decided instead.
		assertThatThrownBy(() -> approvals.reopen(reopened.getId(), "again", lead))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.approvalNotApproved");
	}

	@Test
	void aRefusedSubmissionOfSeveralProjectsLeavesNoneOfThemFrozen() {
		// Validation and writing are two loops, and this is why. Without the split,
		// a person with hours in two projects whose rhythms disagree submits the
		// month: the first is written, audited and frozen, the second throws 400, and
		// the answer says nothing happened — while a month they can no longer edit
		// sits there with nobody asked to decide it.
		projectSettings.save(ProjectTimeSettings.builder()
				.projectId(otherProject.getId())
				.approvalPeriod(ProjectTimeSettings.ApprovalPeriod.builder()
						.type(TimePolicy.ApprovalPeriod.QUARTERLY).build())
				.build());
		entry(member, project, LAST_MONTH_START, 60);
		entry(member, otherProject, LAST_MONTH_START, 30);

		assertThatThrownBy(() -> submitLastMonth(member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.periodNotOnGrid");

		assertThat(approvalRepository.count())
				.as("a refused submission writes nothing at all")
				.isZero();
	}

	@Test
	void anIssueWhoseHoursAreFrozenDoesNotMove() {
		// Moving an issue re-points projectId on every entry attached to it, in one
		// bulk update that never passes the write gate — so without a veto any member
		// of both projects could walk somebody's approved hours out of the period
		// that was signed off, edit them, and walk them back.
		WorkItem attached = entry(member, project, LAST_MONTH_START, 60);
		mongo.save(attached.toBuilder().issueId(issue.getId()).build());
		submitLastMonth(member);

		assertThatThrownBy(() -> moveGuard.check(issue.getId(), project.getId(),
				otherProject.getId()))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.approvalLocked");
	}

	@Test
	void andMovesasSoonAsThePeriodIsOpenAgain() {
		WorkItem attached = entry(member, project, LAST_MONTH_START, 60);
		mongo.save(attached.toBuilder().issueId(issue.getId()).build());
		TimesheetApproval submitted = submitLastMonth(member).getFirst();
		approvals.withdraw(submitted.getId(), member);

		moveGuard.check(issue.getId(), project.getId(), otherProject.getId());
	}

	// --- the vocabulary the refusal travels in -----------------------------------------------

	@Test
	void bothFreezesNameAReasonAHolderAndAWayBack() {
		WorkItem old = entry(member, project, LAST_MONTH_START, 60);

		// The approval.
		submitLastMonth(member);
		ApiException byApproval = refusalOf(old, member);
		assertThat(byApproval.getDetails())
				.containsEntry("reason", "approval")
				.containsEntry("holder", "approver")
				.containsEntry("remedy", "reopen")
				.containsKey("approvalId");

		// And the lock date, which answers with the same three keys and different
		// values. One component renders both; HIN-96's invoice will be the third.
		approvalRepository.deleteAll();
		lockBefore(TODAY);
		ApiException byLockDate = refusalOf(old, member);
		assertThat(byLockDate.getDetails())
				.containsEntry("reason", "lockDate")
				.containsEntry("holder", "admin")
				.containsEntry("remedy", "lockException")
				.containsEntry("lockDate", TODAY.toString());
	}

	private ApiException refusalOf(WorkItem item, User who) {
		return (ApiException) org.assertj.core.api.Assertions
				.catchThrowable(() -> timeTracking.delete(item.getId(), who));
	}

	// --- notifications and audit -----------------------------------------------------------

	@Test
	void submittingTellsTheApproversAndNeverTheSubmitter() {
		entry(lead, project, LAST_MONTH_START, 60);

		submitLastMonth(lead);

		// The submitter leads the project, and a message about your own action is
		// noise — here it would also read as if you were expected to approve it.
		assertThat(notifiedUsers(Notification.Type.TIMESHEET_SUBMITTED))
				.containsExactly(secondLead.getId());
	}

	@Test
	void aDecisionTellsTheOwnerAndTheBodyNamesTheSpanRatherThanAWeek() {
		entry(member, project, LAST_MONTH_START, 60);
		TimesheetApproval submitted = submitLastMonth(member).getFirst();

		approvals.decide(submitted.getId(), TimesheetApproval.Status.APPROVED, null, lead);

		Notification told = notifications.findAll().stream()
				.filter(row -> row.getType() == Notification.Type.TIMESHEET_APPROVED)
				.findFirst().orElseThrow();
		assertThat(told.getUserId()).isEqualTo(member.getId());
		// How often timesheets are handed in is the operator's decision, so no
		// sentence may assume one: "week" must not appear in any of them.
		assertThat(told.getBody()).doesNotContainIgnoringCase("week")
				.contains(LAST_MONTH_START.toString());
	}

	@Test
	void everyTransitionIsAudited() {
		entry(member, project, LAST_MONTH_START, 60);
		TimesheetApproval submitted = submitLastMonth(member).getFirst();
		approvals.decide(submitted.getId(), TimesheetApproval.Status.REJECTED, "missing day", lead);
		submitLastMonth(member);
		approvals.decide(submitted.getId(), TimesheetApproval.Status.APPROVED, null, lead);
		approvals.reopen(submitted.getId(), "payroll correction", admin);

		assertThat(mongo.find(new Query(Criteria.where("action")
						.in(AuditAction.TIMESHEET_SUBMITTED, AuditAction.TIMESHEET_REJECTED,
								AuditAction.TIMESHEET_APPROVED, AuditAction.TIMESHEET_REOPENED)),
				AuditLog.class))
				.extracting(AuditLog::getAction)
				.containsExactlyInAnyOrder(AuditAction.TIMESHEET_SUBMITTED,
						AuditAction.TIMESHEET_REJECTED, AuditAction.TIMESHEET_SUBMITTED,
						AuditAction.TIMESHEET_APPROVED, AuditAction.TIMESHEET_REOPENED);
	}

	@Test
	void aCorrectionRequestReachesWhoeverCanLiftTheFreezeAndChangesNothing() {
		WorkItem old = entry(member, project, LAST_MONTH_START, 60);
		submitLastMonth(member);

		approvals.requestCorrection(old.getId(), "Tuesday is double-counted", member);

		assertThat(notifiedUsers(Notification.Type.TIME_CORRECTION_REQUESTED))
				.containsExactlyInAnyOrder(lead.getId(), secondLead.getId());
		// It is an ask, not an act: the entry is still frozen afterwards.
		assertThat(locks.lockStateFor(member.getId(), project.getId(), old.getDate())).isNotNull();
		assertThat(mongo.find(new Query(Criteria.where("action")
				.is(AuditAction.TIME_CORRECTION_REQUESTED)), AuditLog.class)).hasSize(1);
	}

	@Test
	void anUnfrozenEntryCannotBeUsedToPingAProjectsLeads() {
		WorkItem open = entry(member, project, TODAY, 60);

		assertThatThrownBy(() -> approvals.requestCorrection(open.getId(), "look at this", member))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.entryNotLocked");
		assertThat(notifications.findAll()).isEmpty();
	}

	@Test
	void aCorrectionRequestForALockedDayGoesToAdministratorsInstead() {
		WorkItem old = entry(member, project, LAST_MONTH_START, 60);
		lockBefore(TODAY);

		approvals.requestCorrection(old.getId(), "wrong duration", member);

		// Who can lift it decides who hears about it: the lock date is the
		// instance's archive, and only an administrator can open a span in it.
		assertThat(notifiedUsers(Notification.Type.TIME_CORRECTION_REQUESTED))
				.containsExactly(admin.getId());
	}

	@Test
	void somebodyElsesEntryIsNotSomethingYouCanRequestACorrectionFor() {
		WorkItem theirs = entry(lead, project, LAST_MONTH_START, 60);
		submitLastMonth(lead);

		assertThatThrownBy(() -> approvals.requestCorrection(theirs.getId(), "fix it", member))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	private List<String> notifiedUsers(Notification.Type type) {
		return notifications.findAll().stream()
				.filter(row -> row.getType() == type)
				.map(Notification::getUserId)
				.toList();
	}
}
