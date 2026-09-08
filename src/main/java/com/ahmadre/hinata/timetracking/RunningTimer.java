package com.ahmadre.hinata.timetracking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A timer that is running right now. At most one per person, and never a
 * {@link WorkItem}.
 *
 * <p>That separation is the load-bearing decision of this stage. A running
 * entry could have been a {@code work_items} document with no {@code endedAt},
 * and every aggregation in the product would then have needed a filter it does
 * not have: the dashboard tracker, the weekly summary's {@code focusMinutes},
 * the timesheet matrix, {@code Issue.spentMinutes}, the reports. Worse, the
 * published 10.3 app would have rendered it — a "0m" entry that grows while
 * nobody looks at it. Here a timer is invisible to all of that until it stops,
 * and stopping is the single moment an entry comes into being.
 *
 * <p>The document's {@code _id} becomes the {@code _id} of that entry, which is
 * what makes stopping idempotent without a transaction (the development stack
 * has no replica set to offer one): a second stop tries to insert a document
 * that is already there, and a duplicate key is the answer rather than a second
 * entry. See {@link TimerService#stop}.
 *
 * <p>{@code mode} and the countdown/pomodoro fields live on the server rather
 * than in the app so that a timer started on a phone reads the same on a
 * desktop — which is the whole reason a running timer is server state at all.
 * They were stored from stage 3 and written only as {@code STOPWATCH} until
 * stage 5, so no stored value has ever changed meaning.
 */
@Data
@Builder(toBuilder = true)
@Document("running_timers")
public class RunningTimer {

	/** How the timer counts. */
	public enum Mode {
		/** Counts up from zero, towards nothing. */
		STOPWATCH,
		/** Counts down towards {@code plannedMinutes}. */
		COUNTDOWN,
		/** Alternating work and break phases. */
		POMODORO
	}

	/** Which half of a pomodoro cycle is running. */
	public enum Phase {
		WORK, BREAK, LONG_BREAK
	}

	@Id
	private String id;

	/**
	 * The owner — one running timer each, enforced by the database rather than by
	 * a read-then-write. Two "start" requests racing is not a hypothetical: it is
	 * a phone and a laptop reconnecting at the same moment, and the loser has to
	 * be told 409 instead of quietly orphaning the other's timer.
	 */
	@Indexed(unique = true)
	private String userId;

	private Instant startedAt;

	/** Optional, like the entry it becomes: time may be tracked before it is filed. */
	private String projectId;

	private String issueId;

	private String description;

	/**
	 * Null until somebody says. A timer is started before it is described, so
	 * "not chosen yet" is a real state; {@code TimeTrackingService} supplies the
	 * default at the moment the entry is filed, which is the only place that
	 * default is allowed to live.
	 */
	private String activityType;

	@Builder.Default
	private List<String> tags = new ArrayList<>();

	@Builder.Default
	private boolean billable = false;

	@Builder.Default
	private Mode mode = Mode.STOPWATCH;

	/** Target length for {@link Mode#COUNTDOWN}; null otherwise. */
	private Integer plannedMinutes;

	/** Cycle lengths for {@link Mode#POMODORO}; null otherwise. */
	private Pomodoro pomodoro;

	private Phase phase;

	/**
	 * When the current {@link #phase} began — the phase clock, not the timer's.
	 *
	 * <p>The two coincide today, because every phase change writes a new timer
	 * document (see {@link TimerService#advancePhase}). It is kept as its own
	 * field rather than collapsed into {@link #startedAt} because the two mean
	 * different things and only one of them may ever be adjusted: a phase is a
	 * position in a rhythm, a start is a record of when work began.
	 */
	private Instant phaseStartedAt;

	/**
	 * Work intervals completed in this pomodoro run, which is what decides when
	 * the long break falls.
	 *
	 * <p>It rides along from one phase's timer document to the next, because the
	 * documents are what a run is made of: there is no session record beyond the
	 * timer that is running right now, and a counter that reset on every phase
	 * would make the long break unreachable.
	 *
	 * <p>An {@code Integer} and not an {@code int}, for the same reason
	 * {@link #getTags()} exists. Spring Data instantiates this document through
	 * its all-args constructor, and a field that is not in the stored document
	 * arrives as null — which a primitive parameter rejects outright. A timer
	 * that was already running when this version was deployed would then be
	 * unreadable: not stoppable, not discardable, not sweepable, for as long as
	 * it existed. See {@link #getCyclesDone()}.
	 */
	private Integer cyclesDone;

	/**
	 * When an automatic stop was claimed for this timer, so that two application
	 * instances running the same hourly sweep produce one entry and one message.
	 * The entry itself is already safe (the insert would collide on {@code _id});
	 * this is what keeps the person from being told twice.
	 */
	private Instant autoStopClaimedAt;

	/** Never null: a timer written before this field existed has no tags. */
	public List<String> getTags() {
		return tags == null ? List.of() : tags;
	}

	/** Never null: a timer written before this field existed has completed none. */
	public int getCyclesDone() {
		return cyclesDone == null ? 0 : cyclesDone;
	}

	/** Whether this timer is counting a break rather than work. */
	public boolean isBreak() {
		return phase == Phase.BREAK || phase == Phase.LONG_BREAK;
	}

	/**
	 * The lengths one pomodoro run counts by, copied onto the timer at start.
	 *
	 * <p>A copy rather than a reference to the owner's {@link
	 * com.ahmadre.hinata.me.TimePreferences}: changing your preferred break
	 * length must not silently rewrite the run you are in the middle of, and a
	 * run that outlives a preference change still has to know what it agreed to.
	 */
	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Pomodoro {
		private int work;
		private int shortBreak;
		private int longBreak;
		private int cycles;

		/**
		 * Which break follows work interval number {@code done}.
		 *
		 * <p>{@code done > 0} as well as the modulo: zero divides evenly by
		 * everything, so without it a run whose first interval recorded nothing
		 * would open with the long break — the reward for a set nobody has
		 * worked yet.
		 */
		public Phase phaseAfter(int done) {
			return cycles > 0 && done > 0 && done % cycles == 0 ? Phase.LONG_BREAK : Phase.BREAK;
		}
	}
}
