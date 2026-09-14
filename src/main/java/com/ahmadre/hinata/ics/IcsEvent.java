package com.ahmadre.hinata.ics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * One occurrence of a calendar event as the parser hands it on: a single event, or
 * one instance of a series inside the window the series was expanded for.
 *
 * <p>Two shapes, because a calendar has two kinds of time. A timed event is a pair
 * of instants, with the zone it was written in kept beside them so it can be shown
 * the way it was meant. An all-day event is a pair of dates and nothing more: the
 * 6th of January is a holiday in every time zone, and turning it into midnight UTC
 * would move it to the 5th for everyone west of Greenwich.
 */
public sealed interface IcsEvent permits IcsEvent.Timed, IcsEvent.AllDay {

	/** The event's UID, shared by every occurrence of a series. */
	String uid();

	/**
	 * Which occurrence of a series this is: its original start in iCalendar form,
	 * {@code 20260323T083000Z} for a timed series and {@code 20260323} for a series of
	 * days. Null for an event that belongs to no series. With {@link #uid()} it names
	 * the occurrence for as long as the series exists, also after the occurrence
	 * itself has been moved.
	 */
	String recurrenceId();

	String summary();

	String location();

	/** Shortened to the length the parser keeps; see {@code IcsParser}. */
	String description();

	/** Null when the calendar does not say. */
	Status status();

	/** {@code TRANSP:TRANSPARENT}: the event does not occupy the time (holidays, reminders). */
	boolean transparent();

	enum Status {
		TENTATIVE, CONFIRMED, CANCELLED
	}

	/**
	 * An event with a start and an end in time.
	 *
	 * @param zone     the zone the times were written in: the resolved TZID, UTC for
	 *                 times written in UTC, and for floating times the zone the parser
	 *                 was asked to read them in
	 * @param floating the calendar gave no zone at all, so {@code zone} is the reader's
	 */
	record Timed(String uid, String recurrenceId, Instant start, Instant end, ZoneId zone, boolean floating,
			String summary, String location, String description, Status status, boolean transparent)
			implements IcsEvent {

		/** The kind of event only. What it says, where and when it is, is somebody's schedule. */
		@Override
		public String toString() {
			return recurrenceId == null ? "IcsEvent.Timed[single]" : "IcsEvent.Timed[occurrence of a series]";
		}
	}

	/**
	 * An event on whole days.
	 *
	 * @param end the day after the last day, as iCalendar counts it: a single day ends on the next date
	 */
	record AllDay(String uid, String recurrenceId, LocalDate start, LocalDate end,
			String summary, String location, String description, Status status, boolean transparent)
			implements IcsEvent {

		/** The kind of event only. What it says, where and when it is, is somebody's schedule. */
		@Override
		public String toString() {
			return recurrenceId == null ? "IcsEvent.AllDay[single]" : "IcsEvent.AllDay[occurrence of a series]";
		}
	}
}
