package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a timer counts: a countdown that stops at its target, and a pomodoro whose
 * work becomes entries and whose breaks become nothing.
 *
 * <p>Two claims are worth a real database rather than a mock. The first is that
 * <em>each work interval is exactly one entry</em> — the entry is written under
 * its timer's id, so a phase that kept its document would file the next interval
 * against an id whose entry already exists and silently hand back the previous
 * one. The second is that <em>a break records nothing</em>, which is a
 * compliance property (HIN-60 R2/R7), not a nicety: booked time has to be worked
 * time, or a report of somebody's day is a record of their pauses.
 *
 * <p>The clock is moved by hand ({@link TestClock}), because every assertion here
 * is about a length of time.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true"
})
@Import(TestClock.Config.class)
@Testcontainers(disabledWithoutDocker = true)
class TimerModeIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimerService timers;
	@Autowired
	private RunningTimerRepository timerRepository;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;
	@Autowired
	private TestClock clock;

	private User owner;
	private Project project;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "projects", "teams", "users", "work_items",
				"running_timers", "notifications", "audit_log", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		clock.set(TestClock.START);
		owner = users.save(User.builder().email("owner@example.org").username("owner")
				.displayName("owner").roles(Set.of(Role.MEMBER)).active(true).timezone("UTC")
				.build());
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadId(owner.getId()).leadIds(new ArrayList<>(List.of(owner.getId())))
				.memberIds(new ArrayList<>(List.of(owner.getId()))).build());
	}

	private TimerService.TimerDraft content() {
		return new TimerService.TimerDraft(project.getId(), null, "Deep work", "Development",
				List.of("focus"), true);
	}

	private RunningTimer startPomodoro(int work, int shortBreak, int longBreak, int cycles) {
		return timers.start(new TimerService.StartDraft(content(), RunningTimer.Mode.POMODORO, null,
				RunningTimer.Pomodoro.builder().work(work).shortBreak(shortBreak)
						.longBreak(longBreak).cycles(cycles).build()),
				owner);
	}

	// --- countdown -------------------------------------------------------------

	@Test
	@DisplayName("a countdown files exactly its target, however late the stop arrives")
	void countdownStopsAtItsTarget() {
		timers.start(new TimerService.StartDraft(content(), RunningTimer.Mode.COUNTDOWN, 25, null),
				owner);
		// The phone slept through the end and reported it eleven minutes late. The
		// entry is still the twenty-five minutes that were asked for: the server
		// decides the length against its own clock rather than taking the client's
		// word for when the countdown ran out.
		clock.advance(Duration.ofMinutes(36));
		WorkItem entry = timers.stop(
				new TimerService.StopRequest(null, null, null, null, null, null, null, null), owner)
				.entry();
		assertThat(entry.getDurationMinutes()).isEqualTo(25);
		assertThat(entry.getEndedAt()).isEqualTo(TestClock.START.plus(Duration.ofMinutes(25)));
	}

	@Test
	@DisplayName("a countdown stopped early is worth what was actually worked")
	void countdownStoppedEarlyKeepsItsRealLength() {
		timers.start(new TimerService.StartDraft(content(), RunningTimer.Mode.COUNTDOWN, 25, null),
				owner);
		clock.advance(Duration.ofMinutes(9));
		assertThat(timers.stop(new TimerService.StopRequest(null, null, null, null, null, null, null,
				null), owner).entry().getDurationMinutes()).isEqualTo(9);
	}

	@Test
	@DisplayName("a countdown with no target named takes the default rather than none")
	void countdownWithoutATargetGetsOne() {
		RunningTimer started = timers.start(
				new TimerService.StartDraft(content(), RunningTimer.Mode.COUNTDOWN, null, null),
				owner);
		assertThat(started.getPlannedMinutes()).isEqualTo(25);
	}

	@Test
	@DisplayName("a stopwatch keeps no target, so nothing caps it")
	void stopwatchCarriesNoTarget() {
		RunningTimer started = timers.start(
				new TimerService.StartDraft(content(), RunningTimer.Mode.STOPWATCH, 25, null),
				owner);
		// The planned minutes are dropped rather than stored beside a mode that
		// does not count towards them: anything reading the field instead of the
		// mode would otherwise treat this as a countdown and cut the entry short.
		assertThat(started.getPlannedMinutes()).isNull();
		clock.advance(Duration.ofMinutes(40));
		assertThat(timers.stop(new TimerService.StopRequest(null, null, null, null, null, null, null,
				null), owner).entry().getDurationMinutes()).isEqualTo(40);
	}

	// --- pomodoro --------------------------------------------------------------

	@Test
	@DisplayName("each work interval becomes one entry and each break becomes none")
	void pomodoroFilesWorkAndNotBreaks() {
		RunningTimer work = startPomodoro(25, 5, 15, 4);
		assertThat(work.getPhase()).isEqualTo(RunningTimer.Phase.WORK);
		assertThat(work.getCyclesDone()).isZero();

		clock.advance(Duration.ofMinutes(25));
		RunningTimer shortBreak = timers.advancePhase(work.getId(), owner);
		assertThat(shortBreak.getPhase()).isEqualTo(RunningTimer.Phase.BREAK);
		assertThat(shortBreak.getCyclesDone()).isEqualTo(1);
		// The break is a different document, because the first one's id now
		// belongs to an entry.
		assertThat(shortBreak.getId()).isNotEqualTo(work.getId());
		assertThat(workItems.count()).isEqualTo(1);
		assertThat(workItems.findAll().getFirst().getDurationMinutes()).isEqualTo(25);

		clock.advance(Duration.ofMinutes(5));
		RunningTimer second = timers.advancePhase(shortBreak.getId(), owner);
		assertThat(second.getPhase()).isEqualTo(RunningTimer.Phase.WORK);
		assertThat(second.getCyclesDone()).isEqualTo(1);
		// Five minutes of break, and not a minute of it booked.
		assertThat(workItems.count()).isEqualTo(1);
		assertThat(workItems.findAll().getFirst().getDurationMinutes()).isEqualTo(25);
		// The next interval carries the same description and placement, so a run
		// reads as one piece of work rather than four unrelated entries.
		assertThat(second.getDescription()).isEqualTo("Deep work");
		assertThat(second.getProjectId()).isEqualTo(project.getId());
		assertThat(second.getPomodoro().getWork()).isEqualTo(25);
	}

	@Test
	@DisplayName("the long break falls after a full set, and the counter keeps going")
	void longBreakAfterAFullSet() {
		RunningTimer timer = startPomodoro(25, 5, 15, 2);
		// Two work intervals make a set of two, so the second break is the long one.
		clock.advance(Duration.ofMinutes(25));
		timer = timers.advancePhase(timer.getId(), owner);
		assertThat(timer.getPhase()).isEqualTo(RunningTimer.Phase.BREAK);

		clock.advance(Duration.ofMinutes(5));
		timer = timers.advancePhase(timer.getId(), owner);
		clock.advance(Duration.ofMinutes(25));
		timer = timers.advancePhase(timer.getId(), owner);
		assertThat(timer.getPhase()).isEqualTo(RunningTimer.Phase.LONG_BREAK);
		assertThat(timer.getCyclesDone()).isEqualTo(2);
		assertThat(workItems.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("a work interval reported late is still worth one interval")
	void workPhaseIsCappedAtItsLength() {
		RunningTimer timer = startPomodoro(25, 5, 15, 4);
		// The tab was in the background for half an hour past the end of the
		// interval. What was agreed was twenty-five minutes of work.
		clock.advance(Duration.ofMinutes(55));
		timers.advancePhase(timer.getId(), owner);
		assertThat(workItems.findAll().getFirst().getDurationMinutes()).isEqualTo(25);
	}

	@Test
	@DisplayName("an interval with nothing in it files nothing and counts nothing")
	void anEmptyIntervalIsSkipped() {
		RunningTimer work = startPomodoro(25, 5, 15, 4);
		// Pressed a few seconds in. There is no minute to book, and rounding one
		// up would be an entry for time nobody worked — which is also what makes
		// the phase route an entry factory when it is called in a loop.
		clock.advance(Duration.ofSeconds(20));

		RunningTimer next = timers.advancePhase(work.getId(), owner);

		assertThat(next.getPhase()).isEqualTo(RunningTimer.Phase.BREAK);
		assertThat(workItems.count()).isZero();
		// Nothing recorded, so nothing counted towards the long break either.
		assertThat(next.getCyclesDone()).isZero();
	}

	@Test
	@DisplayName("a run whose project is gone keeps going, unfiled")
	void aRunOutlivesItsProject() {
		RunningTimer work = startPomodoro(25, 5, 15, 4);
		assertThat(work.getProjectId()).isEqualTo(project.getId());
		clock.advance(Duration.ofMinutes(25));
		// Access ends mid-run. Every phase change writes a new document with a new
		// startedAt, so the sweep never ends this run — and without re-checking,
		// it would keep writing into the project for as long as somebody kept
		// pressing.
		projects.deleteById(project.getId());

		RunningTimer next = timers.advancePhase(work.getId(), owner);

		// The rhythm continues — the work is still theirs — but it stops being
		// filed somewhere they can no longer reach.
		assertThat(next.getPhase()).isEqualTo(RunningTimer.Phase.BREAK);
		assertThat(next.getProjectId()).isNull();
		assertThat(next.getDescription()).isEqualTo("Deep work");
		// And the interval that was worked while access still held is still filed.
		assertThat(workItems.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a repeated phase request does not skip the next interval")
	void advancingTheSamePhaseTwiceIsIdempotent() {
		RunningTimer work = startPomodoro(25, 5, 15, 4);
		clock.advance(Duration.ofMinutes(25));
		RunningTimer first = timers.advancePhase(work.getId(), owner);
		// The same request again — a retry after a timeout. Without the id it
		// would end the break as well and skip straight back into work.
		RunningTimer again = timers.advancePhase(work.getId(), owner);
		assertThat(again.getId()).isEqualTo(first.getId());
		assertThat(again.getPhase()).isEqualTo(RunningTimer.Phase.BREAK);
		assertThat(workItems.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a break cannot be stopped into an entry")
	void stoppingABreakIsRefused() {
		RunningTimer work = startPomodoro(25, 5, 15, 4);
		clock.advance(Duration.ofMinutes(25));
		timers.advancePhase(work.getId(), owner);
		clock.advance(Duration.ofMinutes(3));
		assertThatThrownBy(() -> timers.stop(
				new TimerService.StopRequest(null, null, null, null, null, null, null, null), owner))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
				.hasMessageContaining("error.time.breakNotRecorded");
		// And nothing was written on the way to being refused.
		assertThat(workItems.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a break is ended by discarding it, which records nothing")
	void discardingABreakEndsTheRun() {
		RunningTimer work = startPomodoro(25, 5, 15, 4);
		clock.advance(Duration.ofMinutes(25));
		timers.advancePhase(work.getId(), owner);
		timers.discard(owner);
		assertThat(timerRepository.count()).isZero();
		assertThat(workItems.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("a stopwatch has no phase to advance")
	void advancingANonPomodoroIsRefused() {
		RunningTimer started = timers.start(TimerService.StartDraft.stopwatch(content()), owner);
		assertThatThrownBy(() -> timers.advancePhase(started.getId(), owner))
				.isInstanceOf(ApiException.class)
				.hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
				.hasMessageContaining("error.time.notPomodoro");
	}

	@Test
	@DisplayName("nonsense lengths are clamped rather than started as given")
	void pomodoroLengthsAreClamped() {
		// A zero-minute work interval would turn the phase route into an entry
		// factory; 9 999 cycles would put the long break out of reach.
		RunningTimer timer = startPomodoro(0, 0, 0, 9999);
		assertThat(timer.getPomodoro().getWork()).isEqualTo(25);
		assertThat(timer.getPomodoro().getShortBreak()).isEqualTo(5);
		assertThat(timer.getPomodoro().getLongBreak()).isEqualTo(15);
		assertThat(timer.getPomodoro().getCycles()).isEqualTo(12);
	}

	// --- what was already running when this shipped -------------------------------

	@Test
	@DisplayName("a timer written before this stage is still readable, and still stoppable")
	void aTimerFromBeforeThisStageStillReads() {
		// Exactly what the collection holds for somebody who had a stopwatch
		// running when the new version was deployed: no mode, no phase, and no
		// cyclesDone. A primitive int field would reject the missing one outright
		// and the document would be unreadable — the timer not stoppable, not
		// discardable, not sweepable, for as long as it existed.
		mongo.getCollection("running_timers").insertOne(new Document()
				.append("_id", new org.bson.types.ObjectId())
				.append("userId", owner.getId())
				.append("startedAt", java.util.Date.from(TestClock.START))
				.append("description", "started before the upgrade")
				.append("tags", List.of())
				.append("billable", false));

		RunningTimer running = timers.current(owner).orElseThrow();
		assertThat(running.getCyclesDone()).isZero();
		assertThat(running.getMode()).isNull();
		assertThat(running.isBreak()).isFalse();

		clock.advance(Duration.ofMinutes(30));
		WorkItem entry = timers.stop(
				new TimerService.StopRequest(null, null, null, null, null, null, null, null), owner)
				.entry();
		assertThat(entry.getDurationMinutes()).isEqualTo(30);
	}

	// --- the ceiling -----------------------------------------------------------

	@Test
	@DisplayName("a break left running for a day is removed, not filed")
	void theSweepDoesNotFileForgottenBreaks() {
		RunningTimer work = startPomodoro(25, 5, 15, 4);
		clock.advance(Duration.ofMinutes(25));
		timers.advancePhase(work.getId(), owner);
		assertThat(workItems.count()).isEqualTo(1);

		clock.advance(TimerService.MAX_RUN.plusHours(1));
		// Nothing is *stopped* — a break has nothing to file, so the sweep counts
		// no work done — but the timer is gone rather than swept forever.
		assertThat(timers.stopExpired()).isZero();
		assertThat(timerRepository.count()).isZero();
		assertThat(workItems.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("continuing an entry starts a stopwatch, not somebody's old rhythm")
	void continueFromStartsAStopwatch() {
		RunningTimer work = startPomodoro(25, 5, 15, 4);
		clock.advance(Duration.ofMinutes(25));
		timers.advancePhase(work.getId(), owner);
		timers.discard(owner);
		String entryId = workItems.findAll().getFirst().getId();

		RunningTimer resumed = timers.continueFrom(entryId, owner);
		assertThat(resumed.getMode()).isEqualTo(RunningTimer.Mode.STOPWATCH);
		assertThat(resumed.getPomodoro()).isNull();
		assertThat(resumed.getPhase()).isNull();
		assertThat(resumed.getDescription()).isEqualTo("Deep work");
	}
}
