package com.ahmadre.hinata.ics;

/**
 * A calendar that could not be read.
 *
 * <p>Everything that goes wrong while parsing leaves the package as this exception,
 * including whatever ical4j throws: a reason, the line it happened on, and a
 * message key. Never the library's own message or stack, because those can quote
 * the calendar, and a calendar is somebody's schedule.
 */
public class IcsParseException extends RuntimeException {

	public enum Reason {

		/** Does not start with {@code BEGIN:VCALENDAR}, or is empty. */
		NOT_A_CALENDAR("error.ics.notACalendar"),

		/** More than 2 MB. */
		TOO_LARGE("error.ics.tooLarge"),

		/** More components than the parser reads. */
		TOO_MANY_COMPONENTS("error.ics.tooManyComponents"),

		/** Anything else: a broken structure, an unreadable value, a line or nesting past the limits. */
		MALFORMED("error.ics.malformed");

		private final String messageKey;

		Reason(String messageKey) {
			this.messageKey = messageKey;
		}
	}

	private final Reason reason;
	private final int line;

	IcsParseException(Reason reason, int line) {
		super(line > 0 ? reason + " at line " + line : reason.toString(), null, false, false);
		this.reason = reason;
		this.line = line;
	}

	public Reason reason() {
		return reason;
	}

	/** The line of the calendar the problem was found on, counted from 1; 0 when it belongs to no line. */
	public int line() {
		return line;
	}

	/** Key into {@code messages*.properties}; {@link Reason#MALFORMED} takes the line as {0}. */
	public String messageKey() {
		return reason.messageKey;
	}
}
