package com.ahmadre.hinata.shifts;

/**
 * Where shift planning lives in the app, in one place: the addresses a notice, the calendar feed
 * and a search hit link to, as {@code board.BoardLinks} is for boards.
 */
final class ShiftLinks {

	/** Where "my shifts" live in the app. */
	static final String MY_SHIFTS = "/shifts";

	private ShiftLinks() {
	}

	/** An event's sheet. */
	static String event(String eventId) {
		return "/shifts/events/" + eventId;
	}

	/** An event's sheet, opened on one of its shifts. */
	static String shift(String eventId, String shiftId) {
		return event(eventId) + "?shift=" + shiftId;
	}
}
