package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.project.ProjectRepository;
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
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules an entry has to obey, and the one number derived from entries.
 *
 * <p>Time is frozen rather than read from the machine: "not in the future" and
 * "not more than a year back" are statements about a boundary, and a test that
 * asserts them against the wall clock either drifts or never touches the edge.
 * The zone matters as much as the instant — the same calendar day is the future
 * for one user and today for another — so "today" is checked from two zones at
 * one instant.
 *
 * <p>The {@code spentMinutes} half is here for the opposite reason: it is a
 * claim about how the write reaches MongoDB (a targeted {@code $set}, not a
 * document replacement), and only a real database can tell the two apart.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Import(TimeTrackingRulesIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class TimeTrackingRulesIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	/**
	 * Midday UTC, which is already the next calendar day in Kiritimati (UTC+14)
	 * — the whole point of the zone tests below.
	 */
	static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

	/** The instance's own clock, replaced so the date boundaries can be hit exactly. */
	@TestConfiguration
	static class FrozenClock {
		@Bean
		@Primary
		Clock testClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	private static final LocalDate TODAY_UTC = LocalDate.of(2026, 9, 7);
	private static final LocalDate TOMORROW_UTC = TODAY_UTC.plusDays(1);

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimeTrackingService timeTracking;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;

	private User member;
	private Project project;
	private Issue issue;

	@BeforeEach
	void seed() {
		// server_settings too: one test moves the instance zone, and the next
		// must not inherit it.
		for (String collection : List.of("issues", "projects", "teams", "users", "work_items",
				"audit_log", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		member = user("member", "UTC");
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadId(member.getId()).leadIds(new ArrayList<>(List.of(member.getId())))
				.memberIds(new ArrayList<>(List.of(member.getId())))
				.build());
		issue = issueRepository.save(Issue.builder().projectId(project.getId()).readableId("HIN-1")
				.numberInProject(1).title("Login bug").state("Open").spentMinutes(0)
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build());
	}

	private User user(String name, String timezone) {
		User saved = users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(Role.MEMBER)).active(true).timezone(timezone)
				.build());
		if (project != null) {
			project.getMemberIds().add(saved.getId());
			project = projects.save(project);
		}
		return saved;
	}

	private WorkItem add(TimeTrackingService.NewWorkItem draft, User as) {
		return timeTracking.add(issue.getId(), draft, WorkItem.Source.APP, as);
	}

	private TimeTrackingService.NewWorkItem onDay(LocalDate day) {
		return new TimeTrackingService.NewWorkItem(30, day, null, null, null, null, null, null);
	}

	private void assertBadRequest(LocalDate day, User as, String messageKey) {
		assertThatThrownBy(() -> add(onDay(day), as))
				.isInstanceOf(ApiException.class)
				.hasMessage(messageKey)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	private int spentMinutes() {
		return issueRepository.findById(issue.getId()).orElseThrow().getSpentMinutes();
	}

	/**
	 * A document exactly as MongoDB holds it, unmapped fields and all. Matched by
	 * comparing the rendered {@code _id} rather than by querying for it, so the
	 * lookup does not depend on whether the driver stored the id as an ObjectId
	 * or as a string.
	 */
	private Document raw(String collection, String id) {
		for (Document doc : mongo.getCollection(collection).find()) {
			if (id.equals(String.valueOf(doc.get("_id")))) {
				return doc;
			}
		}
		return null;
	}

	// --- the date window --------------------------------------------------------

	@Test
	void aDayInTheFutureIsRefused() {
		assertBadRequest(TOMORROW_UTC, member, "error.time.dateInFuture");

		assertThat(workItems.count()).isZero();
		assertThat(spentMinutes()).isZero();
	}

	@Test
	void todayIsStillFine() {
		assertThat(add(onDay(TODAY_UTC), member).getDate()).isEqualTo(TODAY_UTC);
	}

	@Test
	void aDayMoreThanAYearBackIsRefusedAndTheBoundaryDayIsNot() {
		LocalDate oldest = TODAY_UTC.minusDays(TimeTrackingService.MAX_DAYS_BACK);

		assertThat(add(onDay(oldest), member).getDate()).isEqualTo(oldest);

		assertBadRequest(oldest.minusDays(1), member, "error.time.dateTooOld");
	}

	/**
	 * The same calendar day, the same instant, two users: for the one in
	 * Kiritimati it is today, for the one in UTC it has not begun. A server that
	 * read "today" off its own clock would refuse the first one's honest entry.
	 */
	@Test
	void todayIsTheUsersTodayNotTheServers() {
		User farEast = user("kiritimati", "Pacific/Kiritimati");

		assertThat(add(onDay(TOMORROW_UTC), farEast).getDate()).isEqualTo(TOMORROW_UTC);

		assertBadRequest(TOMORROW_UTC, member, "error.time.dateInFuture");
	}

	/** No day given: the entry lands on the caller's today, not on the server's. */
	@Test
	void anOmittedDayDefaultsToTheUsersToday() {
		User farEast = user("kiritimati", "Pacific/Kiritimati");

		assertThat(add(onDay(null), member).getDate()).isEqualTo(TODAY_UTC);
		assertThat(add(onDay(null), farEast).getDate()).isEqualTo(TOMORROW_UTC);
	}

	/**
	 * Without a profile zone the instance's configured zone decides.
	 *
	 * <p>The shipped default, Europe/Berlin, is useless for showing that: at
	 * 12:00Z in September it is 14:00 the same day, so it agrees with UTC and
	 * the assertion would hold just as well if the instance setting were never
	 * read at all. The instance is therefore moved somewhere the two disagree —
	 * where it is already tomorrow — and the test then fails if the chain stops
	 * at the profile.
	 */
	@Test
	void withoutAProfileZoneTheInstanceZoneDecides() {
		User unset = user("unset", null);
		ServerSettings settings = new ServerSettings();
		settings.getGeneral().setTimezone("Pacific/Kiritimati");
		mongo.save(settings);

		assertThat(add(onDay(TOMORROW_UTC), unset).getDate())
				.as("UTC's tomorrow is the instance's today")
				.isEqualTo(TOMORROW_UTC);
		assertBadRequest(TOMORROW_UTC.plusDays(1), unset, "error.time.dateInFuture");
	}

	/** An edit is allowed to leave an old entry old: only a day being *set* is checked. */
	@Test
	void anEntryOutsideTheWindowStaysEditableInItsOtherFields() {
		WorkItem old = workItems.save(WorkItem.builder().issueId(issue.getId())
				.projectId(project.getId()).userId(member.getId())
				.date(TODAY_UTC.minusYears(3)).durationMinutes(30).build());

		WorkItem patched = timeTracking.update(old.getId(),
				patch(null, null, "Meeting", null), member);

		assertThat(patched.getActivityType()).isEqualTo("Meeting");
		assertThat(patched.getDate()).isEqualTo(TODAY_UTC.minusYears(3));

		// Moving it to a future day is still refused.
		assertThatThrownBy(() -> timeTracking.update(old.getId(),
				patch(null, TOMORROW_UTC, null, null), member))
				.isInstanceOf(ApiException.class).hasMessage("error.time.dateInFuture");
	}

	// --- duration ---------------------------------------------------------------

	@Test
	void aDurationOutsideOneMinuteToOneDayIsRefused() {
		for (Integer minutes : new Integer[] {null, 0, -5, TimeTrackingService.MAX_MINUTES + 1}) {
			assertThatThrownBy(() -> add(new TimeTrackingService.NewWorkItem(minutes, TODAY_UTC,
					null, null, null, null, null, null), member))
					.as("duration %s", minutes)
					.isInstanceOf(ApiException.class)
					.hasMessage("error.time.invalidDuration");
		}

		assertThat(add(new TimeTrackingService.NewWorkItem(1, TODAY_UTC, null, null, null, null,
				null, null), member).getDurationMinutes()).isEqualTo(1);
		assertThat(add(new TimeTrackingService.NewWorkItem(TimeTrackingService.MAX_MINUTES,
				TODAY_UTC, null, null, null, null, null, null), member).getDurationMinutes())
				.isEqualTo(TimeTrackingService.MAX_MINUTES);
	}

	/** A complete interval defines the duration — and overrides a duration that disagrees. */
	@Test
	void anIntervalDefinesTheDurationAndTheDay() {
		Instant start = Instant.parse("2026-09-06T08:00:00Z");
		Instant end = Instant.parse("2026-09-06T09:30:00Z");

		WorkItem item = add(new TimeTrackingService.NewWorkItem(5, null, null, null, start, end,
				null, null), member);

		assertThat(item.getDurationMinutes()).as("the interval wins over the claimed 5").isEqualTo(90);
		assertThat(item.getDate()).as("the day comes from the start, in the user's zone")
				.isEqualTo(LocalDate.of(2026, 9, 6));
		assertThat(item.getStartedAt()).isEqualTo(start);
		assertThat(item.getEndedAt()).isEqualTo(end);
	}

	@Test
	void theDayOfAnIntervalIsReadInTheUsersZone() {
		User farEast = user("kiritimati", "Pacific/Kiritimati");
		// 22:00Z on the 6th is already 12:00 on the 7th in Kiritimati.
		Instant start = Instant.parse("2026-09-06T22:00:00Z");

		assertThat(add(new TimeTrackingService.NewWorkItem(null, null, null, null, start,
				start.plusSeconds(3600), null, null), member).getDate())
				.isEqualTo(LocalDate.of(2026, 9, 6));
		assertThat(add(new TimeTrackingService.NewWorkItem(null, null, null, null, start,
				start.plusSeconds(3600), null, null), farEast).getDate())
				.isEqualTo(LocalDate.of(2026, 9, 7));
	}

	@Test
	void anIntervalLongerThanADayOrRunningBackwardsIsRefused() {
		Instant start = Instant.parse("2026-09-06T08:00:00Z");

		assertThatThrownBy(() -> add(new TimeTrackingService.NewWorkItem(null, null, null, null,
				start, start.plus(25, java.time.temporal.ChronoUnit.HOURS), null, null), member))
				.isInstanceOf(ApiException.class).hasMessage("error.time.invalidDuration");
		assertThatThrownBy(() -> add(new TimeTrackingService.NewWorkItem(null, null, null, null,
				start, start.minusSeconds(600), null, null), member))
				.isInstanceOf(ApiException.class).hasMessage("error.time.invalidDuration");
	}

	/** Patching only one end of the interval re-derives the duration from both. */
	@Test
	void patchingTheIntervalRederivesTheDuration() {
		Instant start = Instant.parse("2026-09-06T08:00:00Z");
		WorkItem item = add(new TimeTrackingService.NewWorkItem(null, null, null, null, start,
				start.plusSeconds(3600), null, null), member);
		assertThat(item.getDurationMinutes()).isEqualTo(60);

		WorkItem patched = timeTracking.update(item.getId(),
				new TimeTrackingService.WorkItemPatch(null, null, null, null, false, null, true,
						start.plusSeconds(7200), null, null),
				member);

		assertThat(patched.getDurationMinutes()).isEqualTo(120);
		assertThat(spentMinutes()).isEqualTo(120);
	}

	/** An explicit null clears one end — and the entry keeps the duration it had. */
	@Test
	void clearingOneEndOfTheIntervalKeepsTheDuration() {
		Instant start = Instant.parse("2026-09-06T08:00:00Z");
		WorkItem item = add(new TimeTrackingService.NewWorkItem(null, null, null, null, start,
				start.plusSeconds(3600), null, null), member);

		WorkItem patched = timeTracking.update(item.getId(),
				new TimeTrackingService.WorkItemPatch(null, null, null, null, false, null, true,
						null, null, null),
				member);

		assertThat(patched.getEndedAt()).isNull();
		assertThat(patched.getStartedAt()).isEqualTo(start);
		assertThat(patched.getDurationMinutes()).isEqualTo(60);
	}

	// --- tags and flags -----------------------------------------------------------

	@Test
	void tagsAreTrimmedDeduplicatedAndStrippedOfBlanks() {
		WorkItem item = add(new TimeTrackingService.NewWorkItem(30, TODAY_UTC, null, null, null,
				null, new ArrayList<>(List.of(" deep-work ", "deep-work", "  ", "review")), true),
				member);

		assertThat(item.getTags()).containsExactly("deep-work", "review");
		assertThat(item.isBillable()).isTrue();
		assertThat(item.getSource()).isEqualTo(WorkItem.Source.APP);
	}

	@Test
	void billableIsWrittenOnEveryEntryEvenWhenTheClientSaysNothing() {
		WorkItem item = add(onDay(TODAY_UTC), member);

		Document stored = raw("work_items", item.getId());
		assertThat(stored).isNotNull();
		assertThat(stored.get("billable")).as("present as a real false, not absent")
				.isEqualTo(false);
		assertThat(stored.getString("source")).isEqualTo("APP");
	}

	// --- the derived counter --------------------------------------------------------

	private TimeTrackingService.WorkItemPatch patch(Integer minutes, LocalDate day,
			String activityType, String description) {
		return new TimeTrackingService.WorkItemPatch(minutes, day, activityType, description,
				false, null, false, null, null, null);
	}

	@Test
	void addingPatchingAndDeletingKeepTheCounterEqualToTheSum() {
		WorkItem first = add(new TimeTrackingService.NewWorkItem(30, TODAY_UTC, null, null, null,
				null, null, null), member);
		assertThat(spentMinutes()).isEqualTo(30);

		WorkItem second = add(new TimeTrackingService.NewWorkItem(45, TODAY_UTC, null, null, null,
				null, null, null), member);
		assertThat(spentMinutes()).isEqualTo(75);

		timeTracking.update(first.getId(), patch(120, null, null, null), member);
		assertThat(spentMinutes()).isEqualTo(165);

		timeTracking.delete(second.getId(), member);
		assertThat(spentMinutes()).isEqualTo(120);

		timeTracking.delete(first.getId(), member);
		assertThat(spentMinutes()).isZero();
	}

	/**
	 * The proof that the counter is written with a targeted {@code $set} rather
	 * than by saving the issue: a field MongoDB holds but the Java entity does
	 * not know survives the write. A document replacement would drop it — which
	 * is exactly what happens to any field another version, another node or a
	 * concurrent migration wrote.
	 */
	@Test
	void syncingTheCounterDoesNotReplaceTheIssueDocument() {
		mongo.updateFirst(Query.query(Criteria.where("_id").is(issue.getId())),
				new Update().set("fieldFromAnotherVersion", "keep me"), Issue.class);

		add(new TimeTrackingService.NewWorkItem(30, TODAY_UTC, null, null, null, null, null, null),
				member);

		Document stored = raw("issues", issue.getId());
		assertThat(stored).isNotNull();
		assertThat(stored.getString("fieldFromAnotherVersion")).isEqualTo("keep me");
		assertThat(stored.get("spentMinutes")).isEqualTo(30);
	}

	/** A change made to the issue while time is being logged is not rolled back by the sync. */
	@Test
	void aConcurrentIssueEditIsNotLostWhenTheCounterIsWritten() {
		add(new TimeTrackingService.NewWorkItem(30, TODAY_UTC, null, null, null, null, null, null),
				member);

		// Someone renames the issue and moves its state — the kind of write that
		// a full save of a stale entity would silently undo.
		mongo.updateFirst(Query.query(Criteria.where("_id").is(issue.getId())),
				new Update().set("title", "Renamed elsewhere").set("state", "In Progress"),
				Issue.class);

		add(new TimeTrackingService.NewWorkItem(45, TODAY_UTC, null, null, null, null, null, null),
				member);

		Issue after = issueRepository.findById(issue.getId()).orElseThrow();
		assertThat(after.getTitle()).isEqualTo("Renamed elsewhere");
		assertThat(after.getState()).isEqualTo("In Progress");
		assertThat(after.getSpentMinutes()).isEqualTo(75);
	}

	@Test
	void theCounterCarriesTheIssuesUpdatedStamp() {
		mongo.updateFirst(Query.query(Criteria.where("_id").is(issue.getId())),
				new Update().set("updatedAt", Instant.parse("2020-01-01T00:00:00Z")), Issue.class);

		add(new TimeTrackingService.NewWorkItem(30, TODAY_UTC, null, null, null, null, null, null),
				member);

		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getUpdatedAt())
				.isAfter(Instant.parse("2020-01-01T00:00:00Z"));
	}

	/**
	 * Twelve people logging time on the same issue at once. With a read of the
	 * counter followed by a write of it, all but one increment would be lost;
	 * summing in the database cannot lose one.
	 */
	@Test
	void simultaneousEntriesAreAllCounted() throws Exception {
		List<User> crowd = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			crowd.add(user("crowd" + i, "UTC"));
		}

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(12);
		try {
			for (User each : crowd) {
				pool.submit(() -> {
					start.await();
					return add(new TimeTrackingService.NewWorkItem(10, TODAY_UTC, null, null, null,
							null, null, null), each);
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(workItems.count()).isEqualTo(12);
		// The counter has to be the sum, not whichever writer wrote last. Read
		// and write it back and twelve simultaneous entries land as ninety.
		assertThat(spentMinutes()).isEqualTo(120);
	}

	/**
	 * The increments are how the counter is maintained; the sum of the entries
	 * is what it means. This states that, so the two cannot drift apart quietly
	 * — a delta dropped on any write path shows up here.
	 */
	@Test
	void theCounterEqualsTheSumOfTheEntriesAfterEveryKindOfWrite() {
		WorkItem first = add(onDay(TODAY_UTC), member);
		add(new TimeTrackingService.NewWorkItem(45, TODAY_UTC, null, null, null, null, null, null),
				member);
		WorkItem third = add(new TimeTrackingService.NewWorkItem(20, TODAY_UTC, null, null, null,
				null, null, null), member);

		timeTracking.update(first.getId(), new TimeTrackingService.WorkItemPatch(90, null, null,
				null, false, null, false, null, null, null), member);
		timeTracking.delete(third.getId(), member);
		// A note is not hours: this must move nothing.
		timeTracking.update(first.getId(), new TimeTrackingService.WorkItemPatch(null, null, null,
				"a note", false, null, false, null, null, null), member);

		int counted = spentMinutes();
		assertThat(counted).isEqualTo(90 + 45);
		timeTracking.syncSpentTime(issue.getId());
		assertThat(spentMinutes()).as("the increments agree with a full recount")
				.isEqualTo(counted);
	}

	/**
	 * The zone is a rule about deriving a day, not about storing one: two users
	 * fourteen hours apart who name the same calendar day store the same value
	 * and read the same day back. If a zone were ever applied to {@code date},
	 * these two would drift apart by a day.
	 */
	@Test
	void theStoredDayIsACalendarDayAndNeverShiftedByAZone() {
		User farEast = user("kiritimati", "Pacific/Kiritimati");

		WorkItem here = add(onDay(TODAY_UTC), member);
		WorkItem there = add(onDay(TODAY_UTC), farEast);

		assertThat(raw("work_items", here.getId()).get("date"))
				.isEqualTo(raw("work_items", there.getId()).get("date"));
		assertThat(workItems.findById(there.getId()).orElseThrow().getDate()).isEqualTo(TODAY_UTC);
	}
}
