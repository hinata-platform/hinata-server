package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
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
import java.util.List;
import java.util.Map;
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
	private final MongoTemplate mongo;
	private final Clock clock;

	/** What a client may say when starting a timer, or when changing a running one. */
	public record TimerDraft(String projectId, String issueId, String description,
			String activityType, List<String> tags, Boolean billable) {
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
	public RunningTimer start(TimerDraft draft, User user) {
		TimeTrackingService.Placement placement =
				entries.resolvePlacement(draft.projectId(), draft.issueId(), user);
		RunningTimer timer = RunningTimer.builder()
				.userId(user.getId())
				.startedAt(TimeTrackingService.stored(clock.instant()))
				.projectId(placement.projectId())
				.issueId(placement.issueId())
				.description(draft.description())
				.activityType(activityOrNull(draft.activityType()))
				.tags(TimeTrackingService.normalizeTags(draft.tags()))
				.billable(Boolean.TRUE.equals(draft.billable()))
				.mode(RunningTimer.Mode.STOPWATCH)
				.build();
		try {
			RunningTimer saved = timers.insert(timer);
			publish(user.getId(), saved);
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
		timer.setProjectId(placement.projectId());
		timer.setIssueId(placement.issueId());
		timer.setDescription(draft.description());
		timer.setActivityType(activityOrNull(draft.activityType()));
		timer.setTags(TimeTrackingService.normalizeTags(draft.tags()));
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
		return stop(running, request, user);
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

	private Stopped stop(RunningTimer timer, StopRequest request, User user) {
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
		TimeTrackingService.Placement placement = placementFor(request, timer, user);
		ZoneId zone = entries.zoneOf(user);
		WorkItem item = WorkItem.builder()
				// The timer's id, so a second stop collides instead of duplicating.
				.id(timer.getId())
				.issueId(placement.issueId())
				.projectId(placement.projectId())
				.userId(user.getId())
				// The day it started, in the user's zone. A timer that runs past
				// midnight stays one entry on the day the work began — splitting it
				// would invent a boundary the person never drew.
				.date(LocalDate.ofInstant(timer.getStartedAt(), zone))
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
				.tags(TimeTrackingService.normalizeTags(
						request.tags() != null ? request.tags() : timer.getTags()))
				.source(WorkItem.Source.TIMER)
				.build();
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
		return start(new TimerDraft(item.getProjectId(), item.getIssueId(), item.getDescription(),
				item.getActivityType(), item.getTags(), item.isBillable()), user);
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
		Stopped result = stop(timer, new StopRequest(null, timer.getStartedAt().plus(MAX_RUN),
				null, null, null, null, null, null), owner);
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
					: Map.of("running", true, "timerId", timer.getId()));
		}
		catch (RuntimeException ex) {
			log.debug("[time] could not publish timer event for {}: {}", userId, ex.toString());
		}
	}
}
