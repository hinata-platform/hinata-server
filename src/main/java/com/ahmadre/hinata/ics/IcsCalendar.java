package com.ahmadre.hinata.ics;

import java.util.List;

/**
 * What the parser read out of one calendar for one window.
 *
 * @param name      the calendar's own name ({@code X-WR-CALNAME}), when it has one
 * @param events    the occurrences inside the window, ordered by start
 * @param truncated a cap was reached, on the whole calendar or on one series, so
 *                  there is more in the window than {@code events} holds
 */
public record IcsCalendar(String name, List<IcsEvent> events, boolean truncated) {

	public IcsCalendar {
		events = List.copyOf(events);
	}
}
