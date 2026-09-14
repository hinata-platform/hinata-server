/**
 * External calendars (iCalendar, RFC 5545), shared by every feature that reads one:
 * holiday imports, calendar subscriptions and, later, a writer for shift plans
 * beside the parser. Nothing here knows about time tracking.
 *
 * <ul>
 * <li>{@link com.ahmadre.hinata.ics.IcsFetcher} fetches a calendar from an address
 * somebody typed, hardened against SSRF.</li>
 * <li>{@link com.ahmadre.hinata.ics.IcsParser} reads one into the occurrences of a
 * window. It needs no application context and keeps no state between calls, and it
 * reads each calendar on a thread of its own.</li>
 * <li>{@link com.ahmadre.hinata.ics.IcsUrlCipher} encrypts the addresses that are
 * stored.</li>
 * </ul>
 *
 * <p><b>What ical4j is used for, and what not.</b> Only its recurrence engine,
 * {@code Recur}, and only with rules {@code IcsRule} has checked. The lexer, the zone
 * handling and the merging of exceptions are this package's own. Measured against
 * ical4j 4.3.0 in September 2026, its builder and model are unfit for files from
 * strangers, and anyone adding to this package should know why:
 *
 * <ul>
 * <li>every VTIMEZONE is expanded while the file is read, without a bound, so an
 * observance recurring every minute since 1601 exhausts the heap before a single
 * event is read;</li>
 * <li>every VTIMEZONE takes an id from a JVM-wide pool of 1,500 zone ids, so one file
 * with 1,501 of them breaks every calendar of every person in the process;</li>
 * <li>an ATTACH with ENCODING is written to a temporary file that is deleted only when
 * the JVM exits;</li>
 * <li>raw property values are logged at WARN, and StackOverflowError and
 * OutOfMemoryError escape the builder.</li>
 * </ul>
 *
 * <p>A writer only formats, so none of this stands in its way. It should still build
 * its VTIMEZONE blocks from java.time and never register one with ical4j's time zone
 * registry.
 */
package com.ahmadre.hinata.ics;
