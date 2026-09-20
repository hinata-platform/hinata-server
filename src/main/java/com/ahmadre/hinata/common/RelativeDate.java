package com.ahmadre.hinata.common;

/**
 * A deadline expressed as a distance from a project's event date: "28 days before", "2 weeks
 * before", "3 days after".
 *
 * <p>Negative {@link #amount()} is before the event, positive is after, and zero is the day of the
 * event itself. The sign carries the direction rather than a separate field, because two fields
 * that can contradict each other ("before, +4") are two fields too many.
 *
 * <p>It lives in {@code common} rather than in {@code template} for one reason: it is a field of
 * {@code Issue}, and {@code issue} must not depend on the module that computes with it. The
 * arithmetic is {@code template.RelativeDates}; this is only the stored shape.
 *
 * <p>Immutable, and the two enums are wire contracts: the names are what a stored document holds
 * and what the app sends, so renaming one is a breaking change.
 */
public record RelativeDate(int amount, Unit unit, Basis basis) {

	/** What {@link #amount()} counts. A week is seven days, without exception. */
	public enum Unit {
		DAYS, WEEKS
	}

	/** Which days are counted. */
	public enum Basis {
		/** Every day, weekends and holidays included. The preselected, obvious reading. */
		CALENDAR,
		/**
		 * Working days only: weekends are skipped, and so are the holidays of the calendar the
		 * project names. Without such a calendar a holiday counts like any other working day,
		 * which the admin documentation says out loud.
		 */
		WORKING
	}

	/** The furthest a day-based offset may reach, in either direction. */
	public static final int MAX_DAYS = 730;

	/** The furthest a week-based offset may reach, in either direction. */
	public static final int MAX_WEEKS = 104;

	/**
	 * Fills in the two enums when a document or a request left them out.
	 *
	 * <p>Calendar days are the preselected reading everywhere else, so a value that arrives
	 * without a basis is a calendar-day value rather than an error. Doing it here means every
	 * reader — the arithmetic, the copy, the preview — sees the same completed shape, instead of
	 * each one guessing separately.
	 */
	public RelativeDate {
		unit = unit == null ? Unit.DAYS : unit;
		basis = basis == null ? Basis.CALENDAR : basis;
	}

	/**
	 * Whether the distance is inside the bounds an offset is allowed to span.
	 *
	 * <p>Compared on both sides rather than through {@code Math.abs}: {@code abs(MIN_VALUE)} is
	 * {@code MIN_VALUE}, so the one value that matters would pass a one-sided check and land a
	 * deadline somewhere around the year −5 877 584.
	 */
	public boolean withinLimits() {
		int limit = unit == Unit.WEEKS ? MAX_WEEKS : MAX_DAYS;
		return amount >= -limit && amount <= limit;
	}

	/** The same distance counted in days; a week is seven of them. */
	public int days() {
		return unit == Unit.WEEKS ? amount * 7 : amount;
	}
}
