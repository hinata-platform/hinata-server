package com.ahmadre.hinata.common;

import java.time.Instant;

/**
 * Whether two spans of time touch.
 *
 * <p>A pure function with no dependencies, because three unrelated features ask
 * the same question and none of them should own the answer: time entries warn
 * about an overlap (HIN-84), shift planning refuses one (HIN-43), and capacity
 * subtracts absences from a working day (HIN-90). One of those is a warning and
 * two are rules, which is exactly why the arithmetic has to be the same — a
 * warning that disagrees with the rule it precedes is worse than no warning.
 */
public final class TimeRanges {

	private TimeRanges() {
	}

	/**
	 * Whether {@code [aStart, aEnd)} and {@code [bStart, bEnd)} share any instant.
	 *
	 * <p>Half-open on purpose: a span that ends at 10:00 and one that starts at
	 * 10:00 do not overlap. That is how a person describes back-to-back work, and
	 * treating it as a clash would flag every ordinary day.
	 *
	 * <p>A null bound is an open end — an unfinished span reaches from its start
	 * to whenever, which is what a running timer is. Two spans that are both
	 * entirely unbounded overlap; a span with neither bound is "always" and
	 * overlaps anything.
	 *
	 * <p>A span of no length occupies no instant and therefore shares none, even
	 * with a span it sits inside. The plain two-comparison form says otherwise,
	 * which is a difference nothing in this stage can reach — an entry is at
	 * least a minute long — but shift planning will hand this arbitrary bounds,
	 * and a rule that contradicts its own definition at the edge is a rule
	 * somebody eventually debugs.
	 */
	public static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
		if (isEmpty(aStart, aEnd) || isEmpty(bStart, bEnd)) {
			return false;
		}
		// a starts before b ends, and b starts before a ends. Written with the
		// nulls folded in rather than special-cased: an absent end is "never
		// ends", so the comparison it takes part in is simply true.
		boolean aStartsBeforeBEnds = aStart == null || bEnd == null || aStart.isBefore(bEnd);
		boolean bStartsBeforeAEnds = bStart == null || aEnd == null || bStart.isBefore(aEnd);
		return aStartsBeforeBEnds && bStartsBeforeAEnds;
	}

	/** Both bounds known and the end not after the start: nothing is inside it. */
	private static boolean isEmpty(Instant start, Instant end) {
		return start != null && end != null && !end.isAfter(start);
	}
}
