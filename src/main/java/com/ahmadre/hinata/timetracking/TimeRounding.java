package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.TimePolicy;

/**
 * Folds a duration onto the operator's increment — a <em>reporting</em>
 * parameter, and nothing else.
 *
 * <p>The stored entry is never touched. What somebody logged is what they
 * logged, and a policy that quietly rewrote it would make the module unusable as
 * a working-time record: an entry of 3 minutes that became 15 in the database is
 * a statement about their day that they did not make. So this is a pure function
 * with no repository behind it, applied by the reports of stage 12 on the way
 * out, and {@code minutesPerDay} in a timesheet answer stays the sum of the
 * minutes as filed.
 *
 * <p><b>Nothing calls this yet.</b> Stage 6 owns the policy — the setting, its
 * validation, its publication to the clients and this function; stage 12 owns
 * the reports that apply it, and is the first caller. The rule is here rather
 * than there because an operator can configure it now, and a policy screen that
 * offers a choice nothing implements is a promise; this way the arithmetic that
 * will honour it is written and pinned by tests before the first report reads
 * it.
 *
 * <p>Rounding happens once, on the number being shown. Rounding each entry and
 * then adding them up is a different — and larger — number than adding them up
 * and rounding once; which of the two an operator means is a decision for the
 * report that displays it, and every caller states it by choosing what to pass
 * in here.
 */
public final class TimeRounding {

	private TimeRounding() {
	}

	/**
	 * {@code minutes} folded onto {@code increment} the way {@code mode} says.
	 *
	 * <p>Defensive about its inputs rather than strict: an increment of zero or
	 * less, or a mode of null, means "no rounding" instead of an exception. The
	 * values reaching here come from a settings document an operator edited and
	 * an environment variable a deployment set, and a report that dies at
	 * midnight because someone typed a zero is a worse answer than the unrounded
	 * truth. The admin form and the settings validation are where a nonsensical
	 * increment is refused.
	 *
	 * <p>Negative minutes are folded by the same rules rather than clamped —
	 * nothing produces them today, and a helper that silently turned −30 into 0
	 * would hide the caller that started to.
	 *
	 * @param minutes   the duration as filed
	 * @param mode      how to fold it, or null for {@link TimePolicy.Rounding#NONE}
	 * @param increment the step in minutes; {@code <= 1} leaves the value alone
	 */
	public static int round(int minutes, TimePolicy.Rounding mode, int increment) {
		if (mode == null || mode == TimePolicy.Rounding.NONE || increment <= 1) {
			return minutes;
		}
		int remainder = Math.floorMod(minutes, increment);
		if (remainder == 0) {
			return minutes;
		}
		// floorDiv/floorMod rather than / and %, so a negative duration folds
		// downwards like a positive one instead of towards zero.
		int down = minutes - remainder;
		int up = down + increment;
		return switch (mode) {
			case UP -> up;
			case DOWN -> down;
			// Halves up, which is what every invoice in the world does: 7.5
			// minutes on a 15-minute increment bills 15.
			case NEAREST -> remainder * 2 >= increment ? up : down;
			case NONE -> minutes;
		};
	}

	/** The same fold, reading the increment and mode from a resolved policy. */
	public static int round(int minutes, TimeTrackingSettings.Rounding rounding) {
		return rounding == null ? minutes
				: round(minutes, rounding.mode(), rounding.increment());
	}
}
