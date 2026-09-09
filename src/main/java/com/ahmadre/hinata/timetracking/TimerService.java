package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.me.TimePreferences;
import com.ahmadre.hinata.me.UserEvents;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The running timer: starting one, changing it while it runs, and turning it
 * into an entry.
 *
 * <p>It is a service of its own rather than more methods on
 * {@link TimeTrackingService} because it owns a different collection with a
 * different lifetime — a timer is transient state that at most one document per
 * person may hold, while an entry is a permanent record. What the two share is
 * the rules about entries, and those stay where they are: this class builds a
 * {@link WorkItem} and hands it to {@link TimeTrackingService#insertTimed},
 * which runs the same write gate every other path runs.
 *
 * <p>Nothing here is transactional. The development stack has no replica set to
 * offer a transaction, and the two operations that matter are made safe by
 * shape instead: starting is guarded by a unique index, stopping by writing the
 * entry under the timer's own id. See {@link #stop}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimerService {

	/**
	 * How long a timer may run before the server ends it for the person.
	 *
	 * <p>The one automatic thing in the module, and deliberately the only one:
	 * a timer left on overnight would otherwise produce an entry longer than an
	 * entry is allowed to be, and be rejected at the moment its owner tries to
	 * stop it — losing the day's record to protect a validation rule. This is
	 * data validation, not activity monitoring; nothing observes whether anyone
	 * was working, only that a clock has been running for a day.
	 */
	public static final Duration MAX_RUN = Duration.ofHours(24);

	/**
	 * How long an automatic-stop claim holds before another sweep may retake it.
	 *
	 * <p>Shorter than the hourly sweep on purpose. At exactly one interval the
	 * comparison sits on the boundary, so whether the next pass retries a failed
	 * attempt or skips it for an hour comes down to scheduler jitter and clock
	 * drift. Three quarters of an hour is long enough that two instances sweeping
	 * together never both act, and short enough that the retry is the next pass
	 * rather than the one after.
	 */
	private static final Duration CLAIM_TTL = Duration.ofMinutes(45);

	/** SSE event name on {@code /api/v1/me/stream}; the payload is the timer or {@code null}. */
	public static final String EVENT = "timer";

	private final RunningTimerRepository timers;
	private final WorkItemRepository workItems;
	private final TimeTrackingService entries;
	private final UserRepository users;
	private final UserEvents userEvents;
	private final NotificationService notifications;
	private final AuditService audit;
	private final MongoTemplate mongo;
	private final Clock clock;

	/** What a client may say when starting a timer, or when changing a running one. */
	public record TimerDraft(String projectId, String issueId, String description,
			String activityType, List<String> tags, Boolean billable) {
	}

	/**
	 * A timer draft plus how it is to count.
	 *
	 * <p>Its own record rather than three more fields on {@link TimerDraft},
	 * because {@code TimerDraft} is also what {@link #patch} receives — and there
	 * the contract is "a field you leave out is cleared". A rename that silently
	 * turned a pomodoro back into a stopwatch is exactly the bug that shape
	 * would produce, and no amount of care at the call sites would stop it
	 * coming back. How a timer counts is decided when it starts.
	 */
	public record StartDraft(TimerDraft content, RunningTimer.Mode mode, Integer plannedMinutes,
			RunningTimer.Pomodoro pomodoro) {

		/** An ordinary stopwatch — what every caller before stage 5 asks for. */
		public static StartDraft stopwatch(TimerDraft content) {
			return new StartDraft(content, RunningTimer.Mode.STOPWATCH, null, null);
		}
	}

	/**
	 * How a timer is stopped: optionally at a different instant than now, and
	 * optionally with the details filled in on the way out — which is how a timer
	 * is meant to be used. Start it, do the work, say afterwards what it was.
	 */
	public record StopRequest(String timerId, Instant endedAt, String projectId, String issueId,
			String description, String activityType, List<String> tags, Boolean billable) {
	}

	/** The caller's running timer, if there is one. */
	public Optional<RunningTimer> current(User user) {
		return timers.findByUserId(user.getId());
	}

	/**
	 * Starts a timer for {@code user}.
	 *
	 * <p>The refusal for "one is already running" comes from the unique index on
	 * {@code userId}, not from a read: two devices reconnecting at the same
	 * moment both find nothing and both insert, and only the database can settle
	 * which of them wins. The pre-check exists so the ordinary case answers
	 * without an exception, not as the guard.
	 */
	public RunningTimer start(StartDraft start, User user) {
		TimerDraft draft = start.content();
		TimeTrackingService.Placement placement =
				entries.resolvePlacement(draft.projectId(), draft.issueId(), user);
		RunningTimer.Mode mode = start.mode() == null ? RunningTimer.Mode.STOPWATCH : start.mode();
		RunningTimer.Pomodoro pomodoro =
				mode == RunningTimer.Mode.POMODORO ? sanitized(start.pomodoro(), user) : null;
		// Deliberately *not* {@link TimeTrackingService#assertRequiredFields}.
		//
		// A running timer is not an entry. Nothing is filed yet, and the request
		// that starts one has no composer behind it: the button is pressed by
		// somebody who has just begun and does not yet know which issue this will
		// turn out to be. Checked here, an operator who requires a description
		// makes the timer unstartable — there is no field on the start path to
		// put one in — and a stopwatch that has to be described before it may
		// begin is not a stopwatch.
		//
		// The rule belongs where the entry is born, which is the stop: the client
		// asks for what is missing then, in the composer, and sends it with the
		// stop request, which carries every one of these fields. See
		// {@link #stop}, which is where it is enforced.
		List<String> tags = TimeTrackingService.normalizeTags(draft.tags());
		// The "already running" pre-check: it answers the ordinary case without
		// an exception, and it stops a refused start from coining a tag. The
		// unique index below is still the guard — two devices reconnecting at the
		// same moment both find nothing here.
		if (current(user).isPresent()) {
			throw ApiException.conflict("error.time.timerAlreadyRunning");
		}
		// The catalogue last, after every reason to refuse: a start that answers
		// 409 or 400 must not leave a word behind in it.
		tags = entries.resolveTags(tags, user);
		Instant startedAt = TimeTrackingService.stored(clock.instant());
		RunningTimer timer = RunningTimer.builder()
				.userId(user.getId())
				.startedAt(startedAt)
				.projectId(placement.projectId())
				.issueId(placement.issueId())
				.description(draft.description())
				.activityType(activityOrNull(draft.activityType()))
				.tags(tags)
				.billable(Boolean.TRUE.equals(draft.billable()))
				.mode(mode)
				// Only where it means something. A stopwatch with a target it does
				// not count towards would read back as a countdown to anything that
				// checks the field rather than the mode.
				.plannedMinutes(mode == RunningTimer.Mode.COUNTDOWN
						? plannedOrDefault(start.plannedMinutes(), user)
						: null)
				.pomodoro(pomodoro)
				// A pomodoro begins in its work half, and the phase clock begins
				// with it. Neither of the other two modes has phases at all.
				.phase(mode == RunningTimer.Mode.POMODORO ? RunningTimer.Phase.WORK : null)
				.phaseStartedAt(mode == RunningTimer.Mode.POMODORO ? startedAt : null)
				.build();
		try {
			RunningTimer saved = timers.insert(timer);
			publish(user.getId(), saved);
			auditTimer(AuditAction.TIME_TIMER_STARTED, saved, user);
			return saved;
		}
		catch (DuplicateKeyException alreadyRunning) {
			// Caught here rather than in a global handler: a duplicate key means
			// something different in every collection that has one, and this is
			// the only place that knows it means "your timer is already running".
			throw ApiException.conflict("error.time.timerAlreadyRunning");
		}
	}

	/**
	 * Replaces what a running timer says about itself, without stopping it.
	 *
	 * <p>The body is the timer's editable state, whole: a field the caller omits
	 * is cleared, not left alone. That is deliberate and it is one rule rather
	 * than two. The alternative — "absent means leave it" — cannot express
	 * "remove the project" at all without the explicit-null bookkeeping
	 * {@code WorkItemPatchRequest} needs for its instants, and it is that
	 * bookkeeping, not the semantics, that would be the surprise here: a timer is
	 * a single resource with five editable fields that one row of UI edits
	 * together, and every caller sends all five.
	 *
	 * <p>What it cannot change is when the timer started. A clock that could be
	 * moved after the fact is not a record of anything.
	 */
	public RunningTimer patch(TimerDraft draft, User user) {
		RunningTimer timer = require(user);
		TimeTrackingService.Placement unchanged =
				new TimeTrackingService.Placement(timer.getProjectId(), timer.getIssueId());
		TimeTrackingService.Placement asked =
				new TimeTrackingService.Placement(draft.projectId(), draft.issueId());
		// A patch that leaves the timer where it is does not re-authorise where it
		// is — for the same reason `stop` does not. The app resends the current
		// placement on every patch, so renaming a timer whose project has since
		// become unreachable would otherwise be a 403 on a request that changes
		// nothing about the placement at all.
		TimeTrackingService.Placement placement = asked.equals(unchanged)
				? unchanged
				: entries.resolvePlacement(draft.projectId(), draft.issueId(), user);
		// No required-field check, for the same reason the start has none: this
		// edits a timer, not an entry. Filing one in a project mid-run must not
		// depend on whether a description has been typed yet — that would make
		// the bar's placement row refuse a change it is showing as available, and
		// leave the person no way to fill in the first of two required fields
		// because the second is still empty.
		//
		// The catalogue is still touched only once the request is going to
		// succeed: a patch that answers 403 must not coin a word.
		List<String> tags = entries.resolveTags(
				TimeTrackingService.normalizeTags(draft.tags()), user);
		timer.setProjectId(placement.projectId());
		timer.setIssueId(placement.issueId());
		timer.setDescription(draft.description());
		timer.setActivityType(activityOrNull(draft.activityType()));
		timer.setTags(tags);
		timer.setBillable(Boolean.TRUE.equals(draft.billable()));
		// A conditional update, not a save. `save` on an entity that carries an id
		// is an upsert, so a patch that races a stop would write the timer back
		// into existence after the stop deleted it — and the next stop would then
		// collide with the entry the first one already filed, hand back that old
		// entry as if it were the new one, and discard everything in between. The
		// document has to still be there, or there is nothing to patch.
		RunningTimer saved = mongo.findAndModify(
				// The owner as well as the id. The document was read through
				// findByUserId, so this cannot currently differ — but a query that
				// carries its own scope does not depend on how its caller found
				// the thing it is updating.
				Query.query(Criteria.where("_id").is(timer.getId())
						.and("userId").is(user.getId())),
				updateOf(timer),
				FindAndModifyOptions.options().returnNew(true),
				RunningTimer.class);
		if (saved == null) {
			throw ApiException.notFound("timer");
		}
		publish(user.getId(), saved);
		return saved;
	}

	/** The editable fields of a patched timer, as one atomic document update. */
	private static Update updateOf(RunningTimer timer) {
		Update update = new Update()
				.set("tags", timer.getTags())
				.set("billable", timer.isBillable());
		// Null is a value here — see the contract above — and it is written as an
		// absent field rather than an explicit null, which reads back as null
		// either way and keeps the document free of keys that say nothing.
		setOrUnset(update, "projectId", timer.getProjectId());
		setOrUnset(update, "issueId", timer.getIssueId());
		setOrUnset(update, "description", timer.getDescription());
		setOrUnset(update, "activityType", timer.getActivityType());
		return update;
	}

	private static void setOrUnset(Update update, String field, Object value) {
		if (value == null) {
			update.unset(field);
		}
		else {
			update.set(field, value);
		}
	}

	/**
	 * Stops the running timer and files the entry it became.
	 *
	 * <p>Idempotent without a transaction, which is the whole design of this
	 * method. The entry is written under the timer's own id, so a second stop —
	 * a retried request, a phone and a laptop pressing the button together — does
	 * not insert a second entry: it collides, and the collision is read as "this
	 * timer has already been stopped". The existing entry is then returned, so
	 * both callers see the same answer, and the timer is deleted either way. The
	 * only ordering that matters is that the entry exists before the timer stops
	 * existing; a crash in between leaves a timer whose entry is already written,
	 * and the next stop cleans it up.
	 *
	 * <p><b>This is where the required fields are checked</b>, because this is
	 * where the entry is born. The start and the patch deliberately do not — see
	 * {@link #start} — so if the rule did not hold here it would not hold
	 * anywhere: {@link TimeTrackingService#insertTimed} is exempt by design, and
	 * two calls with no body at all would file an entry that breaks every one of
	 * the operator's requirements.
	 *
	 * <p>A refusal costs nothing, and that is what makes it safe to refuse. The
	 * check runs after the entry has been assembled and before anything is
	 * written, so a stop that is turned away leaves the timer exactly as it was:
	 * still running, still holding its minutes. The client reads the same policy,
	 * opens the composer for what is missing, and sends the stop again with the
	 * fields filled in — and somebody who would rather not is one
	 * {@code discard} away. It is a request to answer, not a clock to be stuck
	 * with.
	 *
	 * <p>Only a stop somebody asked for is refused, which is what
	 * {@link StopOrigin} is for: the sweep that ends a timer at its ceiling has
	 * nobody to ask and files what it found. A pomodoro's phase turnover does
	 * not come through here at all — it files through {@code fileInterval},
	 * which checks the same rule but answers a refusal differently, because
	 * there the timer is already gone and refusing would wedge a set that is
	 * still running.
	 */
	public Stopped stop(StopRequest request, User user) {
		RunningTimer running = current(user).orElse(null);
		if (request.timerId() != null
				&& (running == null || !request.timerId().equals(running.getId()))) {
			// The timer this request names is not the one running. Either it was
			// already stopped — in which case its entry exists and is the honest
			// answer — or it never belonged to this account. Without this, a stop
			// that timed out on a flaky link and was retried after the person had
			// started a *new* timer would end that new one instead, which is the
			// opposite of the guarantee the route advertises.
			//
			// 404 for both, where `continueFrom` below answers 403 for a foreign
			// entry. Deliberate, not a drift: there the caller names an entry they
			// are meant to have seen in their own list, and 403 is what every 1.x
			// route says about somebody else's. Here the id is an idempotency
			// token for a timer of their own, and "no timer of yours by that name"
			// is exactly what not-found means.
			WorkItem filed = workItems.findById(request.timerId())
					.filter(entry -> user.getId().equals(entry.getUserId()))
					.orElseThrow(() -> ApiException.notFound("timer"));
			return new Stopped(filed, true);
		}
		if (running == null) {
			throw ApiException.notFound("timer");
		}
		return stop(running, request, user, StopOrigin.BY_HAND);
	}

	/**
	 * What a stopped timer is worth, in whole minutes.
	 *
	 * <p>At least one, and this is the interesting half. An entry is stored in
	 * minutes and validated at a minimum of one, so a timer started and stopped
	 * within the same minute has nothing to file — and refusing the stop is the
	 * worst of the available answers: the request fails, the timer keeps running,
	 * and pressing the button again fails the same way until a minute has gone
	 * by. Someone who started the wrong timer would be stuck with it.
	 *
	 * <p>So it floors, with one as the floor. Ninety seconds is a minute and forty
	 * seconds is a minute — the first because that is what truncation gives, the
	 * second because zero is not a number of minutes an entry may have. Someone
	 * who meant to record nothing has {@code discard}. The stored instants stay
	 * true either way: the entry says it ended when the button was pressed, so
	 * the minutes are the rounded figure and the interval is the exact one.
	 */
	static int minutesOf(Instant start, Instant end) {
		long minutes = Duration.between(start, end).toMinutes();
		return (int) Math.clamp(minutes, 1, TimeTrackingService.MAX_MINUTES);
	}

	/** The entry a stop produced, and whether this call is what produced it. */
	public record Stopped(WorkItem entry, boolean alreadyStopped) {
	}

	/**
	 * Who asked for the stop, which decides whether the operator's required
	 * fields may turn it down.
	 *
	 * <p>The distinction is whether there is anybody to ask. A refusal is only
	 * useful to somebody who can answer it.
	 */
	private enum StopOrigin {
		/**
		 * Somebody pressed the button. A missing required field is a question
		 * for them, and the timer keeps running until they have answered it.
		 */
		BY_HAND,
		/**
		 * The sweep, ending a timer that ran past its ceiling. There is nobody
		 * at the other end: refusing would leave a timer the sweep retries every
		 * hour for ever, and the work it found would never be filed at all. It
		 * files what it has, incomplete or not.
		 */
		UNATTENDED
	}

	/**
	 * Both stops, less what the caller decides.
	 *
	 * <p>Publishing "nothing is running" is unconditional. It used to be a flag,
	 * for a phase change that would have published the phase it arrived at
	 * instead — but a phase change files through {@code fileInterval} and has
	 * not come through here for some time, so the flag was a parameter both
	 * callers passed the same value to and a comment describing a caller that
	 * does not exist.
	 *
	 * @param origin whether the required fields may refuse this stop
	 */
	private Stopped stop(RunningTimer timer, StopRequest request, User user, StopOrigin origin) {
		if (timer.isBreak()) {
			// A break is not worked time and never becomes an entry — that is the
			// whole reason the phases are on the server and not a client's idea of
			// a rhythm. There is a route for ending one without recording it, and
			// the app uses it; reaching here means a client asked for something the
			// module does not do, so it is told so rather than quietly obeyed.
			throw ApiException.badRequest("error.time.breakNotRecorded");
		}
		Instant end = TimeTrackingService.stored(
				request.endedAt() != null ? request.endedAt() : clock.instant());
		if (!end.isAfter(timer.getStartedAt())) {
			throw ApiException.badRequest("error.time.invalidDuration");
		}
		// A stop that arrives late for a timer that ran past its ceiling is
		// truncated rather than refused: the person pressing stop must not be the
		// one who pays for having forgotten. Beyond the ceiling the sweep would
		// have ended it at exactly this instant anyway.
		Instant capped = timer.getStartedAt().plus(MAX_RUN);
		if (end.isAfter(capped)) {
			end = capped;
		}
		// And a timer that was counting towards a target stops at that target,
		// whenever the news of it arrives. This is what "the server verifies the
		// end" means: the client says a countdown ran out, and the length of the
		// entry is decided here against this clock rather than taken on trust —
		// a client whose clock is fast, or that reports ten minutes late because
		// the phone was asleep, still files the twenty-five minutes that were
		// asked for. Stopping early is untouched; only the overrun is cut.
		Instant target = targetEndOf(timer);
		if (target != null && end.isAfter(target)) {
			end = target;
		}
		TimeTrackingService.Placement placement = placementFor(request, timer, user);
		ZoneId zone = entries.zoneOf(user);
		LocalDate day = LocalDate.ofInstant(timer.getStartedAt(), zone);
		if (entries.isLocked(day)) {
			// The interval belongs to a day the operator froze, so there is nowhere
			// to file it — and leaving the timer running would be worse than losing
			// it: every stop from now on would answer 403 while the clock kept
			// going, and the only way out would be to discard it anyway. So the
			// timer goes and the person is told why, in the same breath. Reachable
			// only where the lock date reaches today, since a timer cannot outlive
			// its start by more than a day.
			timers.deleteById(timer.getId());
			publish(user.getId(), null);
			throw ApiException.forbidden("error.time.lockedTimer");
		}
		WorkItem item = WorkItem.builder()
				// The timer's id, so a second stop collides instead of duplicating.
				.id(timer.getId())
				.issueId(placement.issueId())
				.projectId(placement.projectId())
				.userId(user.getId())
				// The day it started, in the user's zone. A timer that runs past
				// midnight stays one entry on the day the work began — splitting it
				// would invent a boundary the person never drew.
				.date(day)
				.durationMinutes(minutesOf(timer.getStartedAt(), end))
				.activityType(TimeTrackingService.activityOrDefault(
						request.activityType() != null ? request.activityType()
								: timer.getActivityType()))
				.description(request.description() != null ? request.description()
						: timer.getDescription())
				.startedAt(timer.getStartedAt())
				.endedAt(end)
				// Set by hand, because auditing will not: @CreatedDate is applied
				// only to an entity Spring Data considers new, and this one carries
				// the timer's id so that a second stop collides instead of
				// duplicating. Without this the one kind of entry a timer produces
				// would be the one kind with no creation date — while every entry
				// written through save() has one.
				.createdAt(TimeTrackingService.stored(clock.instant()))
				.billable(request.billable() != null ? request.billable() : timer.isBillable())
				// Tidied, not yet resolved: the catalogue is touched below, once
				// this stop is going to succeed.
				.tags(TimeTrackingService.normalizeTags(
						request.tags() != null ? request.tags() : timer.getTags()))
				.source(WorkItem.Source.TIMER)
				.build();
		if (origin == StopOrigin.BY_HAND) {
			// Against the assembled entry rather than against the request, so what
			// is judged is what would be written: a description the timer has
			// carried since it started satisfies the rule as surely as one typed
			// into the composer a moment ago.
			//
			// Nothing has been written at this point — the lock check above is the
			// only branch that touches anything, and it throws — so the timer is
			// still running when this refuses, which is the whole reason it is
			// safe to refuse at all.
			entries.assertRequiredFields(
					new TimeTrackingService.EntryContent(item.getProjectId(), item.getIssueId(),
							item.getDescription(), item.getTags()),
					TimeTrackingService.PlacementRule.ENFORCED);
		}
		if (request.tags() != null) {
			// The request's own tags through the catalogue — and only now, after
			// every reason to refuse has been checked, the order a create and a
			// patch both keep: resolving first coins a word for a request that is
			// about to answer 400, and leaves a tag document and a configuration
			// audit record behind for ever.
			//
			// The timer's own tags do not come back through here. They were
			// resolved when it started, and re-resolving them is the one thing
			// that could make a timer unstoppable: a tag deleted from the
			// catalogue mid-run would refuse every stop, with the clock still
			// going. Emptiness is all the rule above asks of them, and
			// normalising cannot change that.
			item.setTags(entries.resolveTags(request.tags(), user));
		}
		boolean alreadyStopped = false;
		WorkItem saved;
		try {
			saved = entries.insertTimed(item, user);
		}
		catch (DuplicateKeyException stoppedByAnotherRequest) {
			alreadyStopped = true;
			saved = workItems.findById(timer.getId())
					.orElseThrow(() -> ApiException.notFound("workItem"));
			// The entry is there; whether the counter was moved for it is another
			// question. An insert that succeeded and then failed to increment
			// Issue.spentMinutes leaves the entry written and the counter short,
			// and the retry that lands here would never notice.
			//
			// Reconcile, not sync: this is a live path, and a plain recompute is
			// a read-then-write that would discard a concurrent $inc from
			// somebody else logging time on the same issue. The conditional
			// version stands down instead.
			entries.reconcileSpentTime(saved.getIssueId());
		}
		timers.deleteById(timer.getId());
		publish(user.getId(), null);
		if (!alreadyStopped) {
			auditTimer(AuditAction.TIME_TIMER_STOPPED, timer, user);
		}
		return new Stopped(saved, alreadyStopped);
	}

	/**
	 * Where a stopped timer's entry is filed.
	 *
	 * <p>A placement the stop request names is checked, because the caller is
	 * asking for something new. One the timer already carries is not, and that is
	 * the load-bearing half: it was checked when the timer started, and checking
	 * it again is not extra safety — it is the one thing that can make a timer
	 * impossible to stop. Lose access to the project, or have the issue deleted
	 * under you, and every stop, every patch and every sweep answers 403 forever
	 * while the clock keeps running; the only way out is to discard the whole
	 * interval. The work was done, and the person who did it must be able to file
	 * it. How stale that authorisation can get is bounded by the 24-hour ceiling.
	 *
	 * <p>The request's own pair is resolved as given rather than merged with the
	 * timer's, so "file it under this project, no issue" is expressible instead of
	 * colliding with a stale {@code issueId}.
	 */
	private TimeTrackingService.Placement placementFor(StopRequest request, RunningTimer timer,
			User user) {
		if (request.projectId() != null || request.issueId() != null) {
			return entries.resolvePlacement(request.projectId(), request.issueId(), user);
		}
		return new TimeTrackingService.Placement(timer.getProjectId(), timer.getIssueId());
	}

	/** Throws the running timer away without recording anything. */
	public void discard(User user) {
		RunningTimer timer = require(user);
		timers.deleteById(timer.getId());
		publish(user.getId(), null);
		auditTimer(AuditAction.TIME_TIMER_DISCARDED, timer, user);
	}

	/** Starts a timer carrying an existing entry's description and placement. */
	public RunningTimer continueFrom(String workItemId, User user) {
		WorkItem item = workItems.findById(workItemId)
				.orElseThrow(() -> ApiException.notFound("workItem"));
		if (item.getUserId() == null || !item.getUserId().equals(user.getId())) {
			// Continuing somebody else's entry would copy their description into
			// your day. The entry stays theirs; there is nothing to continue.
			throw ApiException.forbidden("error.time.continueOwnOnly");
		}
		// A stopwatch, whatever the entry came from. "Continue this" is about the
		// description and where it is filed; carrying a pomodoro rhythm across
		// from an entry filed last Tuesday would be inventing an intention.
		return start(StartDraft.stopwatch(
				new TimerDraft(item.getProjectId(), item.getIssueId(), item.getDescription(),
						item.getActivityType(), item.getTags(), item.isBillable())), user);
	}

	// --- pomodoro -------------------------------------------------------------

	/**
	 * Ends the current pomodoro phase and begins the next one.
	 *
	 * <p>A work phase becomes an entry and is followed by a break; a break records
	 * nothing and is followed by work. That asymmetry is the honest one: booked
	 * time is worked time, so a report of somebody's day never contains their
	 * pauses, and nothing here observes whether a break was taken or how long it
	 * really lasted — only that the person asked for the next phase.
	 *
	 * <p>Every phase is a <em>new timer document</em>. It has to be: an entry is
	 * written under its timer's id so that a repeated stop collides instead of
	 * duplicating, and a document that survived a phase change would carry an id
	 * whose entry already exists — the next stop would hand back the previous
	 * interval and silently throw away the one just worked.
	 *
	 * @param timerId which phase the caller means to end — required, because it
	 *        is the whole idempotency guarantee. A request that timed out and is
	 *        retried after the phase has already turned would otherwise skip a
	 *        second interval; named, it answers with the phase that is running.
	 */
	public RunningTimer advancePhase(String timerId, User user) {
		// The controller enforces this; stated here so the service carries its own
		// contract rather than borrowing one, and so a second caller — an MCP tool,
		// say — meets a name rather than a null dereference.
		Objects.requireNonNull(timerId, "timerId");
		RunningTimer running = require(user);
		// Before the idempotency branch: a stale id sent while a stopwatch is
		// running is still a request to turn a phase on something that has none,
		// and answering 200 with the stopwatch would tell the caller it worked.
		if (running.getMode() != RunningTimer.Mode.POMODORO || running.getPomodoro() == null) {
			throw ApiException.badRequest("error.time.notPomodoro");
		}
		if (!timerId.equals(running.getId())) {
			// Not the phase this caller meant — it has already turned, here or on
			// another device. What is running now is the answer, and it is the same
			// answer the winning request got.
			return running;
		}
		return advance(running, user);
	}

	private RunningTimer advance(RunningTimer timer, User user) {
		Instant now = TimeTrackingService.stored(clock.instant());
		RunningTimer.Pomodoro config = timer.getPomodoro();
		boolean wasBreak = timer.isBreak();
		int done = timer.getCyclesDone();
		// Before anything is deleted. It reads only what the timer already
		// carries, and doing it here keeps a database hiccup out of the window
		// between "this timer is gone" and "the next one exists" — where it would
		// leave the entry written, the run dead, and nothing published to say so.
		TimeTrackingService.Placement placement = placementForNextPhase(timer, user);
		if (!claimForPhaseChange(timer, user)) {
			// Somebody ended this timer while the phase was turning — a stop, a
			// discard, or the other device's phase change. Whatever is running now
			// is the answer; inserting the next phase would put back a timer that
			// its owner ended.
			return current(user).orElseThrow(() -> ApiException.notFound("timer"));
		}
		// A break files nothing; the claim above is all that had to happen to it.
		if (!wasBreak) {
			// An interval that has not lasted a minute records nothing.
			//
			// Deliberately not the same answer {@code stop} gives, which floors at
			// one minute: a stop is somebody saying "that was work, book it", and
			// refusing it would leave them with a timer they cannot stop. Turning a
			// phase is a marker in a rhythm, not a decision to book anything — and
			// a marker pressed a moment early has nothing behind it. Rounding it up
			// would write a minute nobody worked, once per request, which is what
			// makes an unattended loop on this route a data problem rather than
			// merely a noisy one.
			//
			// Nothing recorded, so nothing counted: the cycle it would have been
			// does not advance towards the long break either.
			if (Duration.between(timer.getStartedAt(), now).toMinutes() >= 1) {
				// Files the interval, through the same path a manual stop takes —
				// the write gate, the overlap bookkeeping and the duplicate-key
				// idempotency all apply unchanged. The end is capped at the
				// interval's length by targetEndOf, so a phase reported late is
				// still worth exactly one work interval.
				//
				// The `timer` event is suppressed: a stop announces "nothing is
				// running", and a device that acted on that between here and the
				// insert below would clear its bar and stop its ticker mid-turn.
				// The one event this makes is the new phase.
				fileInterval(timer, now, user);
				done++;
			}
		}
		RunningTimer next = RunningTimer.builder()
				.userId(user.getId())
				.startedAt(now)
				.projectId(placement.projectId())
				.issueId(placement.issueId())
				.description(timer.getDescription())
				.activityType(timer.getActivityType())
				.tags(timer.getTags())
				.billable(timer.isBillable())
				.mode(RunningTimer.Mode.POMODORO)
				.pomodoro(config)
				.phase(wasBreak ? RunningTimer.Phase.WORK : config.phaseAfter(done))
				.phaseStartedAt(now)
				.cyclesDone(done)
				.build();
		try {
			RunningTimer saved = timers.insert(next);
			publish(user.getId(), saved);
			return saved;
		}
		catch (DuplicateKeyException raced) {
			// Two devices turned the same phase. The other one's timer is running;
			// this call's entry either collided with its entry or was the one it
			// collided with, so nothing is lost either way — and both callers are
			// told about the same phase.
			return current(user).orElseThrow(() -> ApiException.notFound("timer"));
		}
	}

	/**
	 * Takes this timer out of the collection, or answers false because somebody
	 * else already did.
	 *
	 * <p>The interlock for a phase change. {@code deleteById} on a document that
	 * is already gone is a silent no-op, so a phase change that raced a stop or a
	 * discard used to insert the next phase anyway — putting a timer back behind
	 * somebody who had just ended one, and answering their next start with 409
	 * until they discarded it. The delete has to say whether it did anything.
	 *
	 * <p>Scoped by owner as well as by id, so the query does not depend on how
	 * its caller found the document.
	 */
	private boolean claimForPhaseChange(RunningTimer timer, User user) {
		return mongo.remove(
				Query.query(Criteria.where("_id").is(timer.getId())
						.and("userId").is(user.getId())),
				RunningTimer.class).getDeletedCount() > 0;
	}

	/**
	 * Writes the work interval this phase was, without touching the timer.
	 *
	 * <p>The timer is already gone by the time this runs — {@link
	 * #claimForPhaseChange} removed it, and winning that is what earns the right
	 * to file. So this is {@link #stop}'s second half only: build the entry, hand
	 * it to the write gate, and reconcile the counter if somebody beat us to the
	 * insert.
	 *
	 * <p>Two things can stop it filing, and neither stops the phase: a frozen
	 * day, and an interval that does not meet the operator's required fields.
	 * Both are refusals that arrive too late to be useful — the claim has
	 * already removed the timer, so throwing would end a pomodoro run
	 * mid-rhythm, and the person who set it going twenty-five minutes ago is not
	 * sitting in front of a form. The phase turns, the interval is not written,
	 * and the log says which of the two it was. The alternative is filing an
	 * entry that breaks the operator's rule every time a phase turns over, which
	 * is how a policy stops meaning anything.
	 */
	private void fileInterval(RunningTimer timer, Instant now, User user) {
		ZoneId zone = entries.zoneOf(user);
		LocalDate day = LocalDate.ofInstant(timer.getStartedAt(), zone);
		if (entries.isLocked(day)) {
			// The claim above already removed the timer, so throwing here would
			// end the run mid-rhythm over a day nothing may be written to anyway.
			// The phase turns, the interval is not filed, and the log says so.
			log.warn("[time] pomodoro interval of user {} on {} not filed: the day is frozen",
					user.getId(), day);
			return;
		}
		Instant end = now;
		Instant target = targetEndOf(timer);
		if (target != null && end.isAfter(target)) {
			end = target;
		}
		Instant capped = timer.getStartedAt().plus(MAX_RUN);
		if (end.isAfter(capped)) {
			end = capped;
		}
		WorkItem item = WorkItem.builder()
				.id(timer.getId())
				.issueId(timer.getIssueId())
				.projectId(timer.getProjectId())
				.userId(user.getId())
				.date(day)
				.durationMinutes(minutesOf(timer.getStartedAt(), end))
				.activityType(TimeTrackingService.activityOrDefault(timer.getActivityType()))
				.description(timer.getDescription())
				.startedAt(timer.getStartedAt())
				.endedAt(end)
				.createdAt(TimeTrackingService.stored(clock.instant()))
				.billable(timer.isBillable())
				.tags(TimeTrackingService.normalizeTags(timer.getTags()))
				.source(WorkItem.Source.TIMER)
				.build();
		try {
			entries.assertRequiredFields(
					new TimeTrackingService.EntryContent(item.getProjectId(), item.getIssueId(),
							item.getDescription(), item.getTags()),
					TimeTrackingService.PlacementRule.ENFORCED);
		}
		catch (ApiException incomplete) {
			// Said the same way the frozen day is said, and for the same reason.
			// A pomodoro carries whatever the bar was given before it started; if
			// that is not enough for this instance, the phases still turn and the
			// intervals are simply not written down.
			log.warn("[time] pomodoro interval of user {} on {} not filed: {}", user.getId(), day,
					incomplete.getMessage());
			return;
		}
		try {
			entries.insertTimed(item, user);
		}
		catch (DuplicateKeyException alreadyFiled) {
			// The entry is there — a retry, or the other device — and whether the
			// counter was moved for it is another question. Same reconciliation
			// `stop` does, for the same reason.
			entries.reconcileSpentTime(item.getIssueId());
		}
	}

	/**
	 * Where the next phase files its work.
	 *
	 * <p>Re-authorised, unlike the placement a {@code stop} carries. The reason
	 * {@link #placementFor} does not re-check is that a person must always be
	 * able to file work they have already done, and how stale that authorisation
	 * can get is bounded by the 24-hour ceiling. A pomodoro run has no such
	 * bound: every phase change writes a new document with a new
	 * {@code startedAt}, so the sweep never sees it, and a run turned once a day
	 * would keep writing into a project its owner left months ago.
	 *
	 * <p>Losing the placement does not stop the run — the work is still theirs
	 * and still gets filed, just not into a project they can no longer reach.
	 * Refusing the phase change instead would strand somebody mid-rhythm over a
	 * change of access they had nothing to do with.
	 */
	private TimeTrackingService.Placement placementForNextPhase(RunningTimer timer, User user) {
		if (timer.getProjectId() == null && timer.getIssueId() == null) {
			return new TimeTrackingService.Placement(null, null);
		}
		try {
			return entries.resolvePlacement(timer.getProjectId(), timer.getIssueId(), user);
		}
		catch (ApiException unreachable) {
			log.info("[time] pomodoro run of user {} continues unfiled: {}",
					user.getId(), unreachable.getMessage());
			return new TimeTrackingService.Placement(null, null);
		}
	}

	/**
	 * When a timer that counts towards something reaches it, or null when it
	 * counts towards nothing.
	 *
	 * <p>A stopwatch has no target by definition. A break has none either: it is
	 * never filed, so there is nothing for a cap to be the length of.
	 */
	private static Instant targetEndOf(RunningTimer timer) {
		if (timer.getMode() == RunningTimer.Mode.COUNTDOWN && timer.getPlannedMinutes() != null) {
			return timer.getStartedAt().plus(Duration.ofMinutes(timer.getPlannedMinutes()));
		}
		if (timer.getMode() == RunningTimer.Mode.POMODORO && timer.getPomodoro() != null
				&& !timer.isBreak()) {
			return timer.getStartedAt().plus(Duration.ofMinutes(timer.getPomodoro().getWork()));
		}
		return null;
	}

	/**
	 * A pomodoro configuration inside the bounds {@link TimePreferences} states,
	 * with the owner's own lengths filled in for whatever the client left out.
	 *
	 * <p>Clamped rather than refused, and here rather than only in a request
	 * validator: this is what the run counts by for as long as it lasts, and a
	 * zero-minute work interval is not a rhythm.
	 */
	private static RunningTimer.Pomodoro sanitized(RunningTimer.Pomodoro asked, User user) {
		TimePreferences usual = usualOf(user);
		if (asked == null) {
			return RunningTimer.Pomodoro.builder()
					.work(usual.getPomodoroWork())
					.shortBreak(usual.getPomodoroShortBreak())
					.longBreak(usual.getPomodoroLongBreak())
					.cycles(usual.getPomodoroCycles())
					.build();
		}
		return RunningTimer.Pomodoro.builder()
				.work(clamp(asked.getWork(), TimePreferences.MIN_WORK, TimePreferences.MAX_WORK,
						usual.getPomodoroWork()))
				.shortBreak(clamp(asked.getShortBreak(), TimePreferences.MIN_BREAK,
						TimePreferences.MAX_BREAK, usual.getPomodoroShortBreak()))
				.longBreak(clamp(asked.getLongBreak(), TimePreferences.MIN_LONG_BREAK,
						TimePreferences.MAX_LONG_BREAK, usual.getPomodoroLongBreak()))
				.cycles(clamp(asked.getCycles(), TimePreferences.MIN_CYCLES,
						TimePreferences.MAX_CYCLES, usual.getPomodoroCycles()))
				.build();
	}

	/** The countdown's target, clamped; the owner's usual when a client names none. */
	private static int plannedOrDefault(Integer asked, User user) {
		int usual = usualOf(user).getCountdownMinutes();
		return asked == null ? usual
				: clamp(asked, TimePreferences.MIN_COUNTDOWN, TimePreferences.MAX_COUNTDOWN, usual);
	}

	/**
	 * This person's own lengths, repaired.
	 *
	 * <p>What a number a client leaves out falls back to. Their preference, not
	 * the class default: somebody who set a fifty-minute interval and whose client
	 * omits the block means "the usual", and answering twenty-five would be the
	 * app deciding their rhythm for them. The class defaults stand in only for an
	 * account that has never said.
	 */
	private static TimePreferences usualOf(User user) {
		return user.getTimePreferences() == null
				? TimePreferences.defaults()
				: user.getTimePreferences().sanitized();
	}

	/**
	 * {@code value} inside its bounds, or {@code fallback} when it is not a
	 * length at all.
	 *
	 * <p>Not the same rule as {@link TimePreferences}'s own clamp, which treats
	 * only zero as "never set" and pulls a negative up to the minimum. Here a
	 * negative is a client sending nonsense rather than a document written before
	 * a field existed, and the honest answer is the person's usual length.
	 */
	private static int clamp(int value, int min, int max, int fallback) {
		return value <= 0 ? fallback : Math.clamp(value, min, max);
	}

	// --- the ceiling ----------------------------------------------------------

	/**
	 * Ends timers that have been running for {@link #MAX_RUN}, once each.
	 *
	 * <p>Hourly rather than by the minute: the ceiling exists to keep a forgotten
	 * timer from becoming an entry that cannot be saved, and being an hour late
	 * about it costs nothing — the entry is cut at exactly 24 hours regardless of
	 * when the sweep notices.
	 *
	 * <p>The claim is what makes two application instances produce one message.
	 * The <em>entry</em> is already safe without it, because both instances would
	 * write under the same id and one would collide; the person being told twice
	 * that their timer was stopped is what the claim prevents. Whoever wins the
	 * {@code findAndModify} on {@code autoStopClaimedAt} does the work.
	 */
	public int stopExpired() {
		Instant now = clock.instant();
		Instant cutoff = now.minus(MAX_RUN);
		int stopped = 0;
		for (RunningTimer expired : timers.findByStartedAtLessThanEqual(cutoff)) {
			// The claim goes stale after one sweep interval. Without that, a stop
			// that failed halfway would leave a timer claimed by nobody and swept
			// by no one: permanently running, never filed, silently.
			RunningTimer claimed = claim(expired.getId(), now, now.minus(CLAIM_TTL));
			if (claimed == null) {
				continue; // another instance got there first, or is still trying
			}
			try {
				if (stopOnBehalf(claimed)) {
					stopped++;
				}
			}
			catch (RuntimeException ex) {
				// One stuck timer must not stop the sweep for everyone else's.
				log.warn("[time] could not auto-stop timer {} of user {}: {}",
						claimed.getId(), claimed.getUserId(), ex.toString());
			}
		}
		return stopped;
	}

	/**
	 * Stops one expired timer on its owner's behalf and tells them.
	 *
	 * <p>The owner is loaded because the entry's rules are written in terms of a
	 * person — the write gate asks who is making the change, and the answer here
	 * is the owner, not the scheduler. An owner whose account has since gone
	 * leaves a timer nobody can file; it is removed rather than left to be swept
	 * forever.
	 *
	 * @return whether this call is what filed the entry — false for an orphan
	 *         that was simply dropped, and false when somebody else got there
	 *         first, so the number in the log means what it says
	 */
	private boolean stopOnBehalf(RunningTimer timer) {
		User owner = users.findById(timer.getUserId()).orElse(null);
		if (owner == null) {
			timers.deleteById(timer.getId());
			return false;
		}
		if (timer.isBreak()) {
			// A break a day old is a pomodoro run whose owner walked away. There is
			// nothing to file — a break never becomes an entry — and so nothing to
			// tell them about either: a notification saying their timer was stopped
			// would send them looking for a record that was never going to exist.
			timers.deleteById(timer.getId());
			return false;
		}
		if (entries.isLocked(LocalDate.ofInstant(timer.getStartedAt(), entries.zoneOf(owner)))) {
			// Nothing can be written to that day any more, and a timer the sweep
			// cannot file is a timer the sweep would try again every hour for ever.
			// Removed, and said out loud in the log rather than counted as stopped.
			timers.deleteById(timer.getId());
			publish(owner.getId(), null);
			log.warn("[time] auto-stop of timer {} discarded: its day is frozen by the lock date",
					timer.getId());
			return false;
		}
		Stopped result = stop(timer, new StopRequest(null, timer.getStartedAt().plus(MAX_RUN),
				null, null, null, null, null, null), owner, StopOrigin.UNATTENDED);
		if (result.alreadyStopped()) {
			return false;
		}
		notifications.notifyTimerAutoStopped(owner);
		return true;
	}

	// --- helpers ---------------------------------------------------------------

	/**
	 * Takes the right to auto-stop one timer, or returns null if somebody else
	 * holds it. One atomic document update: whoever the database lets through is
	 * the one instance that stops this timer and tells its owner.
	 */
	private RunningTimer claim(String timerId, Instant now, Instant staleBefore) {
		return mongo.findAndModify(
				Query.query(Criteria.where("_id").is(timerId)
						.orOperator(Criteria.where("autoStopClaimedAt").is(null),
								Criteria.where("autoStopClaimedAt").lt(staleBefore))),
				new Update().set("autoStopClaimedAt", now),
				FindAndModifyOptions.options().returnNew(true),
				RunningTimer.class);
	}

	/** What the {@code timer} event says about a timer that is running. */
	private static Map<String, Object> phaseOf(RunningTimer timer) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("running", true);
		payload.put("timerId", timer.getId());
		payload.put("mode", timer.getMode() == null ? RunningTimer.Mode.STOPWATCH.name()
				: timer.getMode().name());
		// Absent rather than null for the two modes that have no phases — a key
		// that is only ever null says nothing a missing key does not.
		if (timer.getPhase() != null) {
			payload.put("phase", timer.getPhase().name());
			payload.put("cyclesDone", timer.getCyclesDone());
		}
		return payload;
	}

	/**
	 * Records that somebody started, stopped or threw away their own timer.
	 *
	 * <p>Off unless an operator switched the event on — see
	 * {@link AuditAction#TIME_TIMER_STARTED}. A complete log of when each person
	 * began and ended their working intervals is exactly the kind of record
	 * § 87 Abs. 1 Nr. 6 BetrVG is about, so it is never a default; the check is
	 * made before the lookup so an instance that has not asked for it pays
	 * nothing per timer.
	 *
	 * <p>Nothing here says <em>how</em> the timer was operated. A tap in the app,
	 * a keyboard shortcut, a notification action from stage 18's OS surfaces —
	 * all indistinguishable in the record, deliberately, because a field naming
	 * the device would turn this into a location trail.
	 */
	private void auditTimer(AuditAction action, RunningTimer timer, User user) {
		if (!audit.isEnabled(action)) {
			return;
		}
		audit.event(action).actor(user).target(user)
				// The same id under both keys. A stopped timer files its entry
				// under the timer's own id, and the entry's history reads
				// `metadata.workItem` — without this the most common way an entry
				// comes into existence would be missing from the one screen the
				// person it belongs to can open (Art. 15).
				.meta("timer", timer.getId())
				.meta("workItem", timer.getId())
				.meta("mode", String.valueOf(timer.getMode()))
				.meta("project", timer.getProjectId())
				.meta("issue", timer.getIssueId())
				.log();
	}

	private RunningTimer require(User user) {
		return current(user).orElseThrow(() -> ApiException.notFound("timer"));
	}

	private static String activityOrNull(String activityType) {
		return activityType == null || activityType.isBlank() ? null : activityType.trim();
	}

	/**
	 * Tells the person's other devices. Best-effort by design: a timer that
	 * started is a timer that started, whether or not a laptop three time zones
	 * away heard about it. The client reconciles through {@code GET /me/timer} on
	 * reconnect and on resume, which is what makes that acceptable.
	 */
	private void publish(String userId, RunningTimer timer) {
		try {
			userEvents.publish(userId, EVENT, timer == null ? Map.of("running", false)
					// The mode and the phase ride along so a second device can show
					// "on a break" the instant it happens rather than after its own
					// GET comes back. They are a hint, not the truth: the client
					// re-reads the timer either way, which is what keeps a dropped
					// event from being a wrong screen.
					: phaseOf(timer));
		}
		catch (RuntimeException ex) {
			log.debug("[time] could not publish timer event for {}: {}", userId, ex.toString());
		}
	}
}
