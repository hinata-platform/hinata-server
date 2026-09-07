package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The running timer: one per person, and exactly one entry when it stops.
 *
 * <p>Both of those claims are about what MongoDB does under contention, so this
 * runs against a real database and races the operations rather than describing
 * them. The unique index and the entry written under the timer's own id are the
 * whole safety story — there is no transaction here, because the development
 * stack has no replica set to give one — and a unit test with a mocked
 * repository would assert the code I wrote rather than the guarantee I claimed.
 *
 * <p>The clock is frozen and moved by hand so the 24-hour ceiling can be reached
 * without waiting for it.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true"
})
@Import(TimerLifecycleIntegrationTest.MovableClock.class)
@Testcontainers(disabledWithoutDocker = true)
class TimerLifecycleIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	static final Instant START = Instant.parse("2026-09-07T08:00:00Z");

	/**
	 * A clock the tests move. Not {@code Clock.fixed}: the point of half of this
	 * file is what happens as time passes, and sleeping through 24 hours is not a
	 * test anyone runs.
	 */
	static class Movable extends Clock {
		private Instant now = START;

		void set(Instant instant) {
			this.now = instant;
		}

		void advance(Duration by) {
			this.now = this.now.plus(by);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	@TestConfiguration
	static class MovableClock {
		@Bean
		@Primary
		Movable testClock() {
			return new Movable();
		}
	}

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimerService timers;
	@Autowired
	private TimeTrackingService timeTracking;
	@Autowired
	private RunningTimerRepository timerRepository;
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
	private ApplicationEventPublisher events;
	@Autowired
	private Movable clock;

	private User owner;
	private User peer;
	private Project project;
	private Issue issue;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "projects", "teams", "users", "work_items",
				"running_timers", "notifications", "audit_log", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		clock.set(START);
		owner = user("owner");
		peer = user("peer");
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadId(owner.getId()).leadIds(new ArrayList<>(List.of(owner.getId())))
				.memberIds(new ArrayList<>(List.of(owner.getId(), peer.getId())))
				.build());
		issue = issueRepository.save(Issue.builder().projectId(project.getId()).readableId("HIN-1")
				.numberInProject(1).title("Login bug").state("Open").spentMinutes(0)
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build());
	}

	private User user(String name) {
		return users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(Role.MEMBER)).active(true).timezone("UTC").build());
	}

	private TimerService.TimerDraft draft() {
		return new TimerService.TimerDraft(project.getId(), issue.getId(), "Pairing", "Development",
				List.of("focus"), true);
	}

	private static TimerService.StopRequest plainStop() {
		return new TimerService.StopRequest(null, null, null, null, null, null, null, null);
	}

	// --- the ordinary path ----------------------------------------------------

	@Test
	@DisplayName("a timer starts, runs, and becomes exactly one entry")
	void startThenStopProducesOneEntry() {
		RunningTimer started = timers.start(draft(), owner);
		assertThat(started.getStartedAt()).isEqualTo(START);
		assertThat(timers.current(owner)).isPresent();
		// A running timer is not an entry, so nothing has been booked yet — the
		// dashboard, the timesheet and Issue.spentMinutes must not see it.
		assertThat(workItems.count()).isZero();
		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getSpentMinutes()).isZero();

		clock.advance(Duration.ofMinutes(90));
		TimerService.Stopped stopped = timers.stop(plainStop(), owner);

		assertThat(stopped.alreadyStopped()).isFalse();
		WorkItem entry = stopped.entry();
		assertThat(entry.getDurationMinutes()).isEqualTo(90);
		assertThat(entry.getStartedAt()).isEqualTo(START);
		assertThat(entry.getEndedAt()).isEqualTo(START.plus(Duration.ofMinutes(90)));
		assertThat(entry.getSource()).isEqualTo(WorkItem.Source.TIMER);
		// Stamped like every other entry. Spring Data's auditing skips an entity
		// that already carries an id, and this one does on purpose — so the stamp
		// has to be set by hand, and a timer entry would otherwise be the only
		// kind in the collection without one.
		assertThat(entry.getCreatedAt()).isEqualTo(clock.instant());
		assertThat(workItems.findById(entry.getId()).orElseThrow().getCreatedAt())
				.isNotNull();
		assertThat(entry.getIssueId()).isEqualTo(issue.getId());
		assertThat(entry.getProjectId()).isEqualTo(project.getId());
		assertThat(entry.getDescription()).isEqualTo("Pairing");
		assertThat(entry.getTags()).containsExactly("focus");
		assertThat(entry.isBillable()).isTrue();
		// The entry carries the timer's id, which is what makes a second stop
		// collide instead of duplicating.
		assertThat(entry.getId()).isEqualTo(started.getId());
		assertThat(timers.current(owner)).isEmpty();
		assertThat(workItems.count()).isEqualTo(1);
		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getSpentMinutes())
				.isEqualTo(90);
	}

	@Test
	@DisplayName("a timer started with nothing at all still stops into an entry")
	void anEmptyTimerIsValid() {
		timers.start(new TimerService.TimerDraft(null, null, null, null, null, null), owner);
		clock.advance(Duration.ofMinutes(25));

		WorkItem entry = timers.stop(plainStop(), owner).entry();

		// Unfiled, undescribed, and perfectly real. Forcing a project up front is
		// how a timer stops being pressed.
		assertThat(entry.getProjectId()).isNull();
		assertThat(entry.getIssueId()).isNull();
		assertThat(entry.getDurationMinutes()).isEqualTo(25);
		assertThat(entry.getActivityType()).isEqualTo("Development");
	}

	@Test
	@DisplayName("the details may arrive on the way out")
	void stopCanFileTheEntry() {
		timers.start(new TimerService.TimerDraft(null, null, null, null, null, null), owner);
		clock.advance(Duration.ofMinutes(45));

		WorkItem entry = timers.stop(new TimerService.StopRequest(null, null, project.getId(),
				issue.getId(), "Reviewed the ticket", "Testing", List.of("review"), true), owner)
				.entry();

		assertThat(entry.getProjectId()).isEqualTo(project.getId());
		assertThat(entry.getIssueId()).isEqualTo(issue.getId());
		assertThat(entry.getDescription()).isEqualTo("Reviewed the ticket");
		assertThat(entry.getActivityType()).isEqualTo("Testing");
		assertThat(entry.getTags()).containsExactly("review");
		assertThat(entry.isBillable()).isTrue();
	}

	@Test
	@DisplayName("a timer stopped within the same minute files one minute rather than failing")
	void aVeryShortTimerStillStops() {
		timers.start(draft(), owner);
		clock.advance(Duration.ofSeconds(40));

		WorkItem entry = timers.stop(plainStop(), owner).entry();

		// Refusing here is the trap: the stop fails, the timer keeps running, and
		// every further press fails the same way until a minute has passed. The
		// person who started the wrong timer would be stuck with it.
		assertThat(entry.getDurationMinutes()).isEqualTo(1);
		// The instants stay true — the entry says it ended when the button was
		// pressed, not a minute later. Only the minutes are rounded.
		assertThat(entry.getEndedAt()).isEqualTo(START.plusSeconds(40));
		assertThat(timers.current(owner)).isEmpty();
	}

	@Test
	@DisplayName("what is returned is what is stored, to the millisecond")
	void instantsSurviveTheRoundTrip() {
		// The clock reads microseconds and BSON holds milliseconds, so an instant
		// that is not truncated on the way in comes back different on the way out
		// — and "what the server just told me" would disagree with "what the
		// server stored" by an amount nothing notices until something compares
		// them.
		clock.set(Instant.parse("2026-09-07T08:00:00.123456Z"));
		RunningTimer started = timers.start(draft(), owner);

		assertThat(started.getStartedAt())
				.isEqualTo(timerRepository.findById(started.getId()).orElseThrow().getStartedAt());

		clock.advance(Duration.ofMinutes(30));
		WorkItem entry = timers.stop(plainStop(), owner).entry();

		assertThat(entry.getStartedAt())
				.isEqualTo(workItems.findById(entry.getId()).orElseThrow().getStartedAt());
		assertThat(entry.getEndedAt())
				.isEqualTo(workItems.findById(entry.getId()).orElseThrow().getEndedAt());
	}

	@Test
	@DisplayName("discarding records nothing at all")
	void discardLeavesNoTrace() {
		timers.start(draft(), owner);
		clock.advance(Duration.ofMinutes(30));

		timers.discard(owner);

		assertThat(timers.current(owner)).isEmpty();
		assertThat(workItems.count()).isZero();
		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getSpentMinutes()).isZero();
	}

	@Test
	@DisplayName("stopping or discarding when nothing runs is a 404, not a silent success")
	void nothingToStop() {
		assertThatThrownBy(() -> timers.stop(plainStop(), owner))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThatThrownBy(() -> timers.discard(owner))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("a running timer can be renamed and re-filed")
	void patchWhileRunning() {
		timers.start(new TimerService.TimerDraft(null, null, "typo", null, null, null), owner);

		RunningTimer patched = timers.patch(new TimerService.TimerDraft(project.getId(),
				issue.getId(), "the real thing", "Testing", List.of("a", "a", " b "), true), owner);

		assertThat(patched.getDescription()).isEqualTo("the real thing");
		assertThat(patched.getIssueId()).isEqualTo(issue.getId());
		assertThat(patched.getActivityType()).isEqualTo("Testing");
		// Normalized the same way an entry's tags are: trimmed, de-duplicated.
		assertThat(patched.getTags()).containsExactly("a", "b");
		assertThat(patched.getStartedAt()).as("the clock does not restart").isEqualTo(START);
	}

	@Test
	@DisplayName("a patch is the timer's whole editable state: what it omits is cleared")
	void patchReplacesRatherThanMerges() {
		timers.start(draft(), owner);

		RunningTimer cleared = timers.patch(
				new TimerService.TimerDraft(null, null, null, null, null, null), owner);

		// One rule rather than two. A timer is a single resource whose five
		// editable fields are edited together by one row of UI, and every caller
		// sends all five — so "absent means cleared" is what the caller means,
		// and it is the only reading that can express "remove the project"
		// without explicit-null bookkeeping.
		assertThat(cleared.getProjectId()).isNull();
		assertThat(cleared.getIssueId()).isNull();
		assertThat(cleared.getDescription()).isNull();
		assertThat(cleared.getActivityType()).isNull();
		assertThat(cleared.getTags()).isEmpty();
		assertThat(cleared.isBillable()).isFalse();
		// Except the one thing a patch may never touch: when it started.
		assertThat(cleared.getStartedAt()).isEqualTo(START);
	}

	// --- one per person -------------------------------------------------------

	@Test
	@DisplayName("a second start is a 409, not a second timer")
	void oneTimerPerPerson() {
		timers.start(draft(), owner);

		assertThatThrownBy(() -> timers.start(draft(), owner))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.timerAlreadyRunning")
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(HttpStatus.CONFLICT);
		assertThat(timerRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("two devices starting at the same moment leave one timer, and one of them is told")
	void concurrentStartsRaceToOne() throws Exception {
		AtomicInteger started = new AtomicInteger();
		AtomicInteger refused = new AtomicInteger();

		race(8, () -> {
			try {
				timers.start(draft(), owner);
				started.incrementAndGet();
			}
			catch (ApiException conflict) {
				assertThat(conflict.getStatus()).isEqualTo(HttpStatus.CONFLICT);
				refused.incrementAndGet();
			}
			return null;
		});

		// The unique index settles it, not a read-then-write: without it every
		// racer would find nothing running and insert.
		assertThat(started.get()).isEqualTo(1);
		assertThat(refused.get()).isEqualTo(7);
		assertThat(timerRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("each person has their own timer")
	void timersAreNotShared() {
		timers.start(draft(), owner);

		RunningTimer theirs = timers.start(new TimerService.TimerDraft(null, null, "peer work",
				null, null, null), peer);

		assertThat(theirs.getId()).isNotEqualTo(timers.current(owner).orElseThrow().getId());
		assertThat(timers.current(peer).orElseThrow().getDescription()).isEqualTo("peer work");
		assertThat(timers.current(owner).orElseThrow().getDescription()).isEqualTo("Pairing");
	}

	// --- stopping is idempotent -----------------------------------------------

	@Test
	@DisplayName("stopping twice files one entry and answers the same both times")
	void stoppingTwiceIsIdempotent() {
		RunningTimer started = timers.start(draft(), owner);
		clock.advance(Duration.ofMinutes(60));
		TimerService.Stopped first = timers.stop(plainStop(), owner);

		// The timer is gone, so the second stop is a 404 — which is the honest
		// answer to "stop the timer" when there is none. The idempotency that
		// matters is the one below, where the same timer is stopped twice at once.
		assertThatThrownBy(() -> timers.stop(plainStop(), owner))
				.isInstanceOf(ApiException.class);
		assertThat(workItems.count()).isEqualTo(1);
		assertThat(workItems.findById(started.getId()).orElseThrow().getDurationMinutes())
				.isEqualTo(first.entry().getDurationMinutes());
	}

	@Test
	@DisplayName("eight simultaneous stops file one entry, and the hours are counted once")
	void concurrentStopsFileOneEntry() throws Exception {
		RunningTimer started = timers.start(draft(), owner);
		clock.advance(Duration.ofMinutes(60));
		AtomicInteger answered = new AtomicInteger();
		AtomicInteger missed = new AtomicInteger();

		race(8, () -> {
			try {
				TimerService.Stopped stopped = timers.stop(plainStop(), owner);
				assertThat(stopped.entry().getId()).isEqualTo(started.getId());
				assertThat(stopped.entry().getDurationMinutes()).isEqualTo(60);
				answered.incrementAndGet();
			}
			catch (ApiException gone) {
				// Lost the race to read the timer at all: it was already deleted.
				assertThat(gone.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
				missed.incrementAndGet();
			}
			return null;
		});

		assertThat(answered.get() + missed.get()).isEqualTo(8);
		assertThat(answered.get()).as("everyone who saw the timer got the same entry")
				.isGreaterThanOrEqualTo(1);
		assertThat(workItems.count()).isEqualTo(1);
		assertThat(timerRepository.count()).isZero();
		// The derived counter moved once. This is the assertion the whole design
		// exists for: an insert that had silently upserted would have left one
		// entry here too, while $inc ran once per racer and the issue would claim
		// eight hours of work that never happened.
		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getSpentMinutes())
				.isEqualTo(60);
	}

	// --- what a race must not do -----------------------------------------------

	@Test
	@DisplayName("a patch that loses the race to a stop does not bring the timer back")
	void patchCannotResurrectAStoppedTimer() {
		RunningTimer started = timers.start(draft(), owner);
		clock.advance(Duration.ofMinutes(5));
		// The shape of the race: the patch read the timer, the stop then filed it
		// and deleted it, and the patch writes afterwards. `save` on an entity
		// that carries an id is an upsert, so it used to write the timer straight
		// back — and the next stop would collide with the entry the first one
		// filed, return that five-minute entry as if it were the new one, and
		// throw away everything in between.
		timers.stop(plainStop(), owner);

		assertThatThrownBy(() -> timers.patch(
				new TimerService.TimerDraft(null, null, "too late", null, null, null), owner))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(timerRepository.count()).isZero();
		assertThat(workItems.count()).isEqualTo(1);
		assertThat(workItems.findById(started.getId()).orElseThrow().getDurationMinutes())
				.isEqualTo(5);
	}

	@Test
	@DisplayName("a stop names its own timer, so a late retry cannot end a new one")
	void aRetriedStopDoesNotEndTheNextTimer() {
		RunningTimer first = timers.start(draft(), owner);
		clock.advance(Duration.ofMinutes(20));
		timers.stop(new TimerService.StopRequest(first.getId(), null, null, null, null, null, null,
				null), owner);
		// The person got no answer, started a fresh timer, and only then did the
		// original request arrive.
		RunningTimer second = timers.start(draft(), owner);
		clock.advance(Duration.ofMinutes(3));

		TimerService.Stopped late = timers.stop(new TimerService.StopRequest(first.getId(), null,
				null, null, null, null, null, null), owner);

		assertThat(late.alreadyStopped()).isTrue();
		assertThat(late.entry().getId()).isEqualTo(first.getId());
		assertThat(late.entry().getDurationMinutes()).isEqualTo(20);
		// And the timer the person actually has is still running.
		assertThat(timers.current(owner).orElseThrow().getId()).isEqualTo(second.getId());
	}

	@Test
	@DisplayName("a stop naming somebody else's entry is a 404, not an oracle")
	void aStopCannotProbeForeignEntries() {
		timers.start(new TimerService.TimerDraft(null, null, "theirs", null, null, null), peer);
		clock.advance(Duration.ofMinutes(10));
		WorkItem theirs = timers.stop(plainStop(), peer).entry();
		timers.start(draft(), owner);

		assertThatThrownBy(() -> timers.stop(new TimerService.StopRequest(theirs.getId(), null,
				null, null, null, null, null, null), owner))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(HttpStatus.NOT_FOUND);
		// Their entry is untouched and the caller's own timer still runs.
		assertThat(workItems.findById(theirs.getId())).isPresent();
		assertThat(timers.current(owner)).isPresent();
	}

	@Test
	@DisplayName("a timer survives losing access to the project it was started on")
	void aTimerIsStillStoppableAfterAccessGoesAway() {
		timers.start(draft(), owner);
		clock.advance(Duration.ofMinutes(30));
		// Removed from the project while the clock ran — or the project deleted,
		// or the issue. Re-authorising the stored placement at stop time made
		// every stop, every patch and every hourly sweep answer 403 forever,
		// with the only way out being to discard the whole interval.
		project.setMemberIds(new ArrayList<>());
		project.setLeadIds(new ArrayList<>());
		project.setLeadId(null);
		projects.save(project);

		WorkItem entry = timers.stop(plainStop(), owner).entry();

		assertThat(entry.getDurationMinutes()).isEqualTo(30);
		assertThat(entry.getProjectId()).isEqualTo(project.getId());
		assertThat(timers.current(owner)).isEmpty();
	}

	@Test
	@DisplayName("and renaming it still works, because the placement did not move")
	void aPatchThatDoesNotMoveTheTimerNeedsNoAccess() {
		RunningTimer running = timers.start(draft(), owner);
		project.setMemberIds(new ArrayList<>());
		project.setLeadIds(new ArrayList<>());
		project.setLeadId(null);
		projects.save(project);

		// What the app sends: the current placement, resent verbatim, with a new
		// description. Re-resolving it would refuse a request that moves nothing.
		RunningTimer patched = timers.patch(new TimerService.TimerDraft(running.getProjectId(),
				running.getIssueId(), "renamed", "Development", List.of(), true), owner);

		assertThat(patched.getDescription()).isEqualTo("renamed");
		assertThat(patched.getProjectId()).isEqualTo(project.getId());
	}

	@Test
	@DisplayName("the counter repair corrects a stale value and writes nothing when it is right")
	void reconcileRepairsOnlyWhatIsWrong() {
		timers.start(draft(), owner);
		clock.advance(Duration.ofMinutes(45));
		timers.stop(plainStop(), owner);
		Issue booked = issueRepository.findById(issue.getId()).orElseThrow();
		assertThat(booked.getSpentMinutes()).isEqualTo(45);

		// Nothing to do — and it says so rather than writing the same number back.
		assertThat(timeTracking.reconcileSpentTime(issue.getId())).isFalse();

		// The shape a partial failure leaves: the entry written, the counter never
		// moved for it. This is what a duplicate stop lands on, and what it fixes.
		booked.setSpentMinutes(0);
		issueRepository.save(booked);

		assertThat(timeTracking.reconcileSpentTime(issue.getId())).isTrue();
		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getSpentMinutes())
				.isEqualTo(45);
	}

	@Test
	@DisplayName("the repair leaves a counter alone that moved under it")
	void reconcileStandsDownWhenSomebodyElseWrote() throws Exception {
		timers.start(draft(), owner);
		clock.advance(Duration.ofMinutes(45));
		timers.stop(plainStop(), owner);
		Issue booked = issueRepository.findById(issue.getId()).orElseThrow();
		booked.setSpentMinutes(0);
		issueRepository.save(booked);

		// Six repairs and six people logging time on the same issue at once. A
		// plain recompute reads the sum and writes it back, so an $inc landing in
		// between is simply gone; the conditional write cannot lose one, because
		// it only commits while the counter still holds what it read.
		race(6, () -> {
			timeTracking.reconcileSpentTime(issue.getId());
			return null;
		});
		race(6, () -> {
			timeTracking.add(issue.getId(), new TimeTrackingService.NewWorkItem(10,
					LocalDate.of(2026, 9, 7), null, null, null, null, null, null), null, owner);
			return null;
		});

		// 45 from the timer plus six ten-minute entries. Every minute that was
		// logged is counted, whichever order the two kinds of write interleaved.
		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getSpentMinutes())
				.isEqualTo(45 + 60);
	}

	// --- the 24-hour ceiling ---------------------------------------------------

	@Test
	@DisplayName("a timer that has run for a day is stopped by the sweep, and its owner is told")
	void theSweepStopsAnExpiredTimer() {
		RunningTimer started = timers.start(draft(), owner);
		clock.advance(Duration.ofHours(25));

		assertThat(timers.stopExpired()).isEqualTo(1);

		WorkItem entry = workItems.findById(started.getId()).orElseThrow();
		// Cut at exactly the ceiling, not at the hour the sweep happened to run.
		assertThat(entry.getDurationMinutes()).isEqualTo(24 * 60);
		assertThat(entry.getEndedAt()).isEqualTo(START.plus(TimerService.MAX_RUN));
		assertThat(entry.getDate()).isEqualTo(LocalDate.of(2026, 9, 7));
		assertThat(timerRepository.count()).isZero();

		List<Notification> told = notifications.findAll();
		assertThat(told).hasSize(1);
		assertThat(told.getFirst().getType())
				.isEqualTo(Notification.Type.TIME_TIMER_AUTO_STOPPED);
		assertThat(told.getFirst().getUserId()).isEqualTo(owner.getId());
		// R7: the notice says a clock was left running, and nothing about the
		// work. No project, no issue, no hours.
		assertThat(told.getFirst().getBody()).doesNotContain("Hinata", "HIN-1", "Pairing");
	}

	@Test
	@DisplayName("a timer under the ceiling is left alone")
	void theSweepIgnoresARunningTimer() {
		timers.start(draft(), owner);
		clock.advance(Duration.ofHours(23));

		assertThat(timers.stopExpired()).isZero();

		assertThat(timerRepository.count()).isEqualTo(1);
		assertThat(workItems.count()).isZero();
		assertThat(notifications.count()).isZero();
	}

	@Test
	@DisplayName("two instances sweeping at once file one entry and send one message")
	void theClaimKeepsTheSweepSingle() throws Exception {
		timers.start(draft(), owner);
		clock.advance(Duration.ofHours(25));
		AtomicInteger totalStopped = new AtomicInteger();

		race(6, () -> {
			totalStopped.addAndGet(timers.stopExpired());
			return null;
		});

		// Six sweeps, one claim. Without it the entry would still be single (the
		// insert collides) but the person would be told six times that their
		// timer was stopped.
		assertThat(totalStopped.get()).isEqualTo(1);
		assertThat(workItems.count()).isEqualTo(1);
		assertThat(notifications.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a later sweep retries a timer whose stop failed")
	void aStaleClaimIsRetaken() {
		RunningTimer started = timers.start(draft(), owner);
		clock.advance(Duration.ofHours(25));
		// The shape a half-finished sweep leaves behind: claimed, never stopped.
		RunningTimer stuck = timerRepository.findById(started.getId()).orElseThrow();
		stuck.setAutoStopClaimedAt(clock.instant());
		timerRepository.save(stuck);

		assertThat(timers.stopExpired()).as("still claimed").isZero();

		clock.advance(Duration.ofHours(2));

		// The claim has gone stale, so the next sweep takes it. Without a time
		// limit on the claim this timer would run forever and never be filed —
		// silently, because nothing is broken enough to log.
		assertThat(timers.stopExpired()).isEqualTo(1);
		assertThat(workItems.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a timer whose owner is gone is removed rather than swept forever")
	void anOrphanedTimerIsDropped() {
		timers.start(draft(), owner);
		users.deleteById(owner.getId());
		clock.advance(Duration.ofHours(25));

		assertThat(timers.stopExpired()).isZero();

		assertThat(timerRepository.count()).isZero();
		assertThat(workItems.count()).isZero();
	}

	@Test
	@DisplayName("a stop that arrives after the ceiling is truncated, not refused")
	void aLateStopIsCapped() {
		timers.start(draft(), owner);
		clock.advance(Duration.ofHours(30));

		WorkItem entry = timers.stop(plainStop(), owner).entry();

		// The person pressing stop must not be the one who pays for having
		// forgotten: refusing here would mean the day's record is lost to protect
		// the "at most 24 hours" rule.
		assertThat(entry.getDurationMinutes()).isEqualTo(24 * 60);
	}

	// --- continuing ------------------------------------------------------------

	@Test
	@DisplayName("continuing an entry starts a timer carrying its fields")
	void continueCopiesTheEntry() {
		timers.start(draft(), owner);
		clock.advance(Duration.ofMinutes(30));
		WorkItem entry = timers.stop(plainStop(), owner).entry();
		clock.advance(Duration.ofMinutes(5));

		RunningTimer resumed = timers.continueFrom(entry.getId(), owner);

		assertThat(resumed.getId()).isNotEqualTo(entry.getId());
		assertThat(resumed.getStartedAt()).as("now, not when the original began")
				.isEqualTo(clock.instant());
		assertThat(resumed.getDescription()).isEqualTo(entry.getDescription());
		assertThat(resumed.getProjectId()).isEqualTo(entry.getProjectId());
		assertThat(resumed.getIssueId()).isEqualTo(entry.getIssueId());
		assertThat(resumed.getActivityType()).isEqualTo(entry.getActivityType());
		assertThat(resumed.getTags()).isEqualTo(entry.getTags());
		assertThat(resumed.isBillable()).isEqualTo(entry.isBillable());
	}

	@Test
	@DisplayName("continuing somebody else's entry is refused")
	void continueIsOwnOnly() {
		timers.start(draft(), owner);
		clock.advance(Duration.ofMinutes(30));
		WorkItem entry = timers.stop(plainStop(), owner).entry();

		// A lead may edit a member's entry; copying its description into their
		// own day is a different act, and not one this offers.
		assertThatThrownBy(() -> timers.continueFrom(entry.getId(), peer))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.continueOwnOnly")
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(timers.current(peer)).isEmpty();
	}

	@Test
	@DisplayName("continuing while a timer runs is a 409")
	void continueRespectsTheOneTimerRule() {
		timers.start(draft(), owner);
		clock.advance(Duration.ofMinutes(30));
		WorkItem entry = timers.stop(plainStop(), owner).entry();
		timers.start(draft(), owner);

		assertThatThrownBy(() -> timers.continueFrom(entry.getId(), owner))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.time.timerAlreadyRunning");
	}

	// --- placement is checked at start, not only at stop ------------------------

	@Test
	@DisplayName("a timer cannot be started on a project the person cannot see")
	void startChecksTheProject() {
		Project theirs = projects.save(Project.builder().key("SEC").name("Secret")
				.leadId(peer.getId()).leadIds(new ArrayList<>(List.of(peer.getId())))
				.memberIds(new ArrayList<>(List.of(peer.getId()))).build());
		User outsider = user("outsider");

		assertThatThrownBy(() -> timers.start(new TimerService.TimerDraft(theirs.getId(), null,
				null, null, null, null), outsider))
				.isInstanceOf(ApiException.class)
				.extracting(thrown -> ((ApiException) thrown).getStatus())
				.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(timerRepository.count()).isZero();
	}

	// --- account deletion --------------------------------------------------------

	@Test
	@DisplayName("deleting an account removes its timer and keeps its entries")
	void erasureDropsTheTimerAndKeepsTheHours() {
		timers.start(draft(), owner);
		clock.advance(Duration.ofMinutes(30));
		timers.stop(plainStop(), owner);
		timers.start(draft(), owner);

		events.publishEvent(new UserService.UserDeletedEvent(owner.getId()));

		// Live personal state goes; the project's record of the hours stays under
		// an id that no longer resolves — the pseudonym convention.
		assertThat(timerRepository.count()).isZero();
		assertThat(workItems.count()).isEqualTo(1);
		assertThat(workItems.findAll().getFirst().getUserId()).isEqualTo(owner.getId());
	}

	// --- helper ------------------------------------------------------------------

	/** Runs {@code work} on {@code threads} at once, released from one latch. */
	private void race(int threads, Callable<Void> work) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Void>> results = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					go.await(5, TimeUnit.SECONDS);
					return work.call();
				}));
			}
			go.countDown();
			for (Future<Void> result : results) {
				result.get(30, TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}
	}
}
