package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
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
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retention sweep (Art. 5 Abs. 1 lit. e DSGVO): it deletes exactly what the
 * operator configured, keeps what a signed-off period stands on, runs once a night
 * however many instances there are, picks up where a night that ran out of time
 * stopped, records what it did — and, configured as it ships, does nothing at all.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Import(TimeRetentionIntegrationTest.TestClock.class)
@Testcontainers(disabledWithoutDocker = true)
class TimeRetentionIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");

	static final MovableClock CLOCK = new MovableClock();

	@TestConfiguration
	static class TestClock {
		@Bean
		@Primary
		Clock testClock() {
			return CLOCK;
		}
	}

	/**
	 * A clock that stands still, or moves on by a step each time the thread that set
	 * it reads it: how a test lets a night run out of time without waiting five
	 * minutes. Other threads read it without moving it, so nothing running in the
	 * background can spend the budget the test is counting on.
	 */
	static final class MovableClock extends Clock {

		private volatile Instant now = NOW;
		private volatile Duration step = Duration.ZERO;
		private volatile Thread stepping;

		void set(Instant instant, Duration everyRead) {
			now = instant;
			step = everyRead;
			stepping = Thread.currentThread();
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			Instant read = now;
			if (Thread.currentThread() == stepping && !step.isZero()) {
				now = read.plus(step);
			}
			return read;
		}
	}

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SettingsService settings;
	@Autowired
	private TimeRetentionService retention;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private TimesheetApprovalRepository approvals;
	@Autowired
	private DepartedTimeUserRepository departed;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private IssueRepository issues;
	@Autowired
	private UserRepository users;

	private User member;
	private User colleague;
	private Project project;
	private Issue issue;

	@BeforeEach
	void seed() {
		CLOCK.set(NOW, Duration.ZERO);
		for (String collection : List.of("projects", "issues", "users", "work_items",
				"timesheet_approvals", "audit_log", "server_settings", "time_departed_users",
				"time_retention_runs")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		policy(block -> {
		});
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadIds(new ArrayList<>()).memberIds(new ArrayList<>()).build());
		issue = issues.save(Issue.builder().projectId(project.getId()).readableId("HIN-1")
				.numberInProject(1).title("Login bug").state("Open").spentMinutes(0)
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build());
		member = user("member");
		colleague = user("colleague");
	}

	private User user(String name) {
		return users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(Role.MEMBER)).active(true).timezone("UTC").build());
	}

	private void policy(Consumer<ServerSettings.TimeTracking> edit) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = current.getTimeTracking() != null
				? current.getTimeTracking() : new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		edit.accept(block);
		current.setTimeTracking(block);
		settings.save(current);
	}

	private void retain(Integer descriptionMonths, Integer entryMonths) {
		policy(block -> {
			ServerSettings.TimeTracking.Retention retained = new ServerSettings.TimeTracking.Retention();
			retained.setDescriptionPurgeMonths(descriptionMonths);
			retained.setEntryPurgeMonths(entryMonths);
			block.setRetention(retained);
		});
	}

	private WorkItem entry(String userId, LocalDate day, int minutes, String description) {
		WorkItem saved = workItems.save(WorkItem.builder().userId(userId).projectId(project.getId())
				.issueId(issue.getId()).date(day).durationMinutes(minutes).description(description)
				.tags(List.of()).source(WorkItem.Source.APP).build());
		mongo.updateFirst(Query.query(Criteria.where("_id").is(issue.getId())),
				new org.springframework.data.mongodb.core.query.Update().inc("spentMinutes", minutes),
				Issue.class);
		return saved;
	}

	private long runRecords() {
		return mongo.count(new Query(Criteria.where("action").is(AuditAction.TIME_RETENTION_RUN)),
				AuditLog.class);
	}

	private AuditLog runRecord() {
		return mongo.findOne(new Query(Criteria.where("action").is(AuditAction.TIME_RETENTION_RUN)),
				AuditLog.class);
	}

	@Test
	void asItShipsTheSweepDeletesNothingAndLeavesNoTrace() {
		WorkItem ancient = entry(member.getId(), LocalDate.of(2015, 1, 5), 60, "old notes");

		assertThat(retention.run()).isEmpty();

		assertThat(workItems.findById(ancient.getId())).get()
				.extracting(WorkItem::getDescription).isEqualTo("old notes");
		assertThat(mongo.count(new Query(), TimeRetentionRun.class)).isZero();
		assertThat(runRecords()).isZero();
	}

	@Test
	void entriesPastTheirPeriodGoExceptTheOnesASignedOffPeriodStandsOn() {
		retain(null, 24);
		LocalDate old = LocalDate.of(2024, 8, 5);
		WorkItem doomed = entry(member.getId(), old, 60, "gone");
		WorkItem approvedOld = entry(colleague.getId(), old, 90, "approved");
		WorkItem recent = entry(member.getId(), LocalDate.of(2024, 10, 1), 30, "kept");
		approvals.save(TimesheetApproval.builder().userId(colleague.getId()).projectId(project.getId())
				.periodStart(LocalDate.of(2024, 8, 1)).periodEnd(LocalDate.of(2024, 8, 31))
				.periodType(TimePolicy.ApprovalPeriod.MONTHLY)
				.status(TimesheetApproval.Status.APPROVED).submittedAt(NOW).build());

		TimeRetentionRun run = retention.run().orElseThrow();

		assertThat(workItems.findById(doomed.getId())).isEmpty();
		assertThat(workItems.findById(approvedOld.getId())).isPresent();
		assertThat(workItems.findById(recent.getId())).isPresent();
		assertThat(run.getEntriesDeleted()).isEqualTo(1);
		assertThat(run.getEntriesKept()).isEqualTo(1);
		assertThat(run.isComplete()).isTrue();
		// The issue's counter loses exactly the minutes that left.
		assertThat(issues.findById(issue.getId())).get()
				.extracting(Issue::getSpentMinutes).isEqualTo(120);
		AuditLog record = runRecord();
		assertThat(record).isNotNull();
		assertThat(record.getMetadata()).containsEntry("entriesDeleted", "1")
				.containsEntry("entriesKept", "1")
				.containsEntry("entryPurgeMonths", "24")
				.containsEntry("complete", "true");
	}

	@Test
	void anEntryRetentionShorterThanTwoYearsIsAppliedAsTwoYears() {
		// Stored as 2 behind the admin area's back, or typed for 24 by mistake: the
		// records the law asks to keep for two years stay for two years.
		retain(null, 2);
		WorkItem lastYear = entry(member.getId(), LocalDate.of(2025, 9, 1), 60, "last year");
		WorkItem threeYearsAgo = entry(member.getId(), LocalDate.of(2023, 9, 1), 60, "long ago");

		TimeRetentionRun run = retention.run().orElseThrow();

		assertThat(workItems.findById(lastYear.getId())).isPresent();
		assertThat(workItems.findById(threeYearsAgo.getId())).isEmpty();
		assertThat(run.getEntriesDeleted()).isEqualTo(1);
		assertThat(runRecord().getMetadata()).containsEntry("entryPurgeMonths", "24");
	}

	@Test
	void aNightThatRunsOutOfTimeLeavesTheRestToTheNextNightWhereItStopped() {
		retain(null, 24);
		LocalDate firstDay = LocalDate.of(2023, 3, 1);
		for (int day = 0; day < 12; day++) {
			entry(member.getId(), firstDay.plusDays(day), 10, "old " + day);
		}
		// Every look at the clock costs this thread a minute, so the five-minute budget
		// ends after a few days.
		CLOCK.set(NOW, Duration.ofMinutes(1));

		TimeRetentionRun first = retention.run().orElseThrow();

		assertThat(first.isComplete()).isFalse();
		assertThat(first.getEntriesDeleted()).isPositive().isLessThan(12);
		assertThat(workItems.count()).isEqualTo(12 - first.getEntriesDeleted());
		assertThat(mongo.findById(TimeRetentionService.ENTRY_CURSOR, TimeRetentionRun.class))
				.isNotNull();

		CLOCK.set(NOW.plus(Duration.ofDays(1)), Duration.ZERO);
		TimeRetentionRun next = retention.run().orElseThrow();

		assertThat(next.isComplete()).isTrue();
		assertThat(first.getEntriesDeleted() + next.getEntriesDeleted()).isEqualTo(12);
		assertThat(workItems.count()).isZero();
		assertThat(mongo.findById(TimeRetentionService.ENTRY_CURSOR, TimeRetentionRun.class))
				.isNull();
		assertThat(issues.findById(issue.getId())).get()
				.extracting(Issue::getSpentMinutes).isEqualTo(0);
		assertThat(runRecords()).isEqualTo(2);
	}

	@Test
	void theNightIsClaimedOnceHoweverOftenTheSweepIsStarted() {
		retain(null, 24);
		entry(member.getId(), LocalDate.of(2020, 1, 1), 60, "old");

		assertThat(retention.run()).isPresent();
		assertThat(retention.run()).isEmpty();

		assertThat(runRecords()).isEqualTo(1);
	}

	@Test
	void onlyTheDescriptionsOfDeletedAccountsAreEmptiedAndTheHoursStay() {
		retain(6, null);
		LocalDate old = LocalDate.of(2026, 1, 12);
		departed.save(new DepartedTimeUser("gone-user", NOW.minusSeconds(3600)));
		WorkItem goneOld = entry("gone-user", old, 60, "met with a customer");
		WorkItem goneRecent = entry("gone-user", LocalDate.of(2026, 8, 3), 30, "recent");
		// Deleted before this stage recorded deletions: found by the one-off backfill.
		WorkItem ghostOld = entry("deleted-long-ago", old, 45, "legacy note");
		WorkItem livingOld = entry(member.getId(), old, 20, "still mine");
		TimesheetApproval oldSheet = approvals.save(TimesheetApproval.builder().userId("gone-user")
				.projectId(project.getId()).periodStart(LocalDate.of(2026, 1, 1))
				.periodEnd(LocalDate.of(2026, 1, 31)).periodType(TimePolicy.ApprovalPeriod.MONTHLY)
				.status(TimesheetApproval.Status.APPROVED).submittedAt(NOW)
				.note("Freigegeben, trotz der Krankheitstage")
				.history(new ArrayList<>(List.of(TimesheetApproval.Event.builder().at(NOW)
						.by("gone-user").to(TimesheetApproval.Status.SUBMITTED)
						.note("Krankheitstage nachgetragen").build())))
				.build());
		TimesheetApproval recentSheet = approvals.save(TimesheetApproval.builder().userId("gone-user")
				.projectId(project.getId()).periodStart(LocalDate.of(2026, 8, 1))
				.periodEnd(LocalDate.of(2026, 8, 31)).periodType(TimePolicy.ApprovalPeriod.MONTHLY)
				.status(TimesheetApproval.Status.APPROVED).submittedAt(NOW).note("August passt")
				.build());

		TimeRetentionRun run = retention.run().orElseThrow();

		assertThat(workItems.findById(goneOld.getId())).get().satisfies(item -> {
			assertThat(item.getDescription()).isNull();
			assertThat(item.getDurationMinutes()).isEqualTo(60);
		});
		assertThat(workItems.findById(ghostOld.getId())).get()
				.extracting(WorkItem::getDescription).isNull();
		assertThat(workItems.findById(goneRecent.getId())).get()
				.extracting(WorkItem::getDescription).isEqualTo("recent");
		assertThat(workItems.findById(livingOld.getId())).get()
				.extracting(WorkItem::getDescription).isEqualTo("still mine");
		assertThat(run.getDescriptionsCleared()).isEqualTo(2);
		// The notes on their signed-off timesheets go the same way; the decision stays.
		assertThat(approvals.findById(oldSheet.getId())).get().satisfies(sheet -> {
			assertThat(sheet.getNote()).isNull();
			assertThat(sheet.getHistory()).singleElement()
					.extracting(TimesheetApproval.Event::getNote).isNull();
			assertThat(sheet.getStatus()).isEqualTo(TimesheetApproval.Status.APPROVED);
		});
		assertThat(approvals.findById(recentSheet.getId())).get()
				.extracting(TimesheetApproval::getNote).isEqualTo("August passt");
		assertThat(run.getApprovalNotesCleared()).isEqualTo(2);
		// When that account went is not known, and the night it was found is not it.
		assertThat(departed.findById("deleted-long-ago")).get()
				.extracting(DepartedTimeUser::getDeletedAt).isNull();
		assertThat(departed.findById(member.getId())).isEmpty();
	}

	@Test
	void withTheModuleOffNothingIsSweptWhateverIsConfigured() {
		retain(1, 1);
		policy(block -> block.setAdvancedEnabled(false));
		WorkItem old = entry(member.getId(), LocalDate.of(2020, 1, 1), 60, "old");

		assertThat(retention.run()).isEmpty();
		assertThat(workItems.findById(old.getId())).isPresent();
	}
}
