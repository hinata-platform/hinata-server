package com.ahmadre.hinata.ics;

import com.ahmadre.hinata.ics.IcsLexer.Component;
import com.ahmadre.hinata.ics.IcsLexer.Property;
import com.ahmadre.hinata.ics.IcsZoneDefinitions.Observance;
import net.fortuna.ical4j.model.Recur;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.Spliterator;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.ahmadre.hinata.ics.IcsParseException.Reason.MALFORMED;
import static com.ahmadre.hinata.ics.IcsParseException.Reason.TOO_LARGE;

/**
 * Reads an external calendar (RFC 5545) into the occurrences that fall inside a
 * window.
 *
 * <p>Pure and Spring-free, like {@code team/TeamAccess}: bytes and a window go in,
 * an {@link IcsCalendar} comes out, and a calendar that cannot be read is an
 * {@link IcsParseException} with a reason and a line. Nothing else leaves, whatever
 * the bytes are.
 *
 * <p>What is read: VEVENT with UID, RECURRENCE-ID, DTSTART, DTEND or DURATION,
 * SUMMARY, LOCATION, DESCRIPTION, STATUS and TRANSP; its RRULE, RDATE and EXDATE;
 * and VTIMEZONE, for the zone names nothing else explains. Series are expanded only
 * inside the window, and nothing grows without a cap: 5,000 events read, 500
 * occurrences per series, 5,000 per window. A cap that was reached shows as
 * {@link IcsCalendar#truncated()}; it is not an error.
 *
 * <p>Times follow RFC 5545. A time with a zone is converted with that zone's rules,
 * the way it reads on the wall there, so a weekly meeting at half past nine stays at
 * half past nine across the change to summer time. A time without a zone (floating)
 * is read in the zone the caller names, the reader's own, and an all-day event is
 * placed in that zone too when it is compared with the window.
 */
public final class IcsParser {

	static final int MAX_EVENTS = 5_000;
	static final int MAX_OCCURRENCES = 5_000;
	static final int MAX_PER_SERIES = 500;

	static final int MAX_SUMMARY = 500;
	static final int MAX_LOCATION = 500;

	/** As long as the description of a time entry may be, so a taken-over event fits whole. */
	static final int MAX_DESCRIPTION = 2_000;

	private static final int MAX_CALENDAR_NAME = 200;
	private static final int MAX_UID = 1_000;

	/** RDATE and EXDATE values of one event. */
	private static final int MAX_DATES = 10_000;

	/**
	 * Recurrence steps one calendar may cost. A series with COUNT is walked by the
	 * engine from its first occurrence, so it pays its COUNT before it starts; every
	 * occurrence taken from the engine costs one more.
	 */
	private static final long WORK_BUDGET = 250_000;

	/** Wider than any UTC offset, so nothing is lost at the edges of the window before it is converted. */
	private static final Duration MARGIN = Duration.ofHours(18);

	private static final DateTimeFormatter UTC_ID =
			DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

	private static final Zone UTC = new Zone(ZoneOffset.UTC.getRules(), ZoneOffset.UTC, false);

	private IcsParser() {
	}

	/**
	 * The occurrences in [ics] that overlap the window from [from], inclusive, to [to],
	 * exclusive.
	 *
	 * @param readerZone the zone floating times are read in and all-day events are placed
	 *                   in: the zone of the person the calendar is read for
	 * @throws IcsParseException        when the calendar cannot be read
	 * @throws IllegalArgumentException when there is no window or no zone
	 */
	public static IcsCalendar parse(byte[] ics, Instant from, Instant to, ZoneId readerZone) {
		if (ics == null || from == null || to == null || readerZone == null || !from.isBefore(to)) {
			throw new IllegalArgumentException("a calendar, a window that is not empty and a zone are needed");
		}
		if (ics.length > IcsLexer.MAX_BYTES) {
			throw new IcsParseException(TOO_LARGE, 0);
		}
		try {
			IcsLexer.Result read = IcsLexer.read(ics, MAX_EVENTS);
			return new Reading(read.calendar(), from, to, readerZone, read.truncated()).calendar();
		}
		catch (IcsParseException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			// A case nobody foresaw is still a calendar that could not be read, and the
			// message of whatever failed stays behind: it may quote the file.
			throw new IcsParseException(MALFORMED, 0);
		}
	}

	/** How a wall-clock time becomes an instant, and the zone shown beside it; null shows the offset of the moment. */
	private record Zone(ZoneRules rules, ZoneId shown, boolean floating) {

		ZoneId at(Instant instant) {
			return shown != null ? shown : rules.getOffset(instant);
		}
	}

	/** A DTSTART, DTEND, RDATE, EXDATE or RECURRENCE-ID value: a day, or a wall-clock time in a zone. */
	private record Stamp(LocalDate date, LocalDateTime local, Zone zone) {

		boolean isDate() {
			return date != null;
		}

		Instant instant() {
			return IcsZones.instant(local, zone.rules());
		}

		/** The day the value falls on where it was written. */
		LocalDate day() {
			return date != null ? date : local.toLocalDate();
		}
	}

	/**
	 * A VEVENT, read. {@code exact} is the length DTEND gives a timed event,
	 * {@code nominal} the one DURATION gives it, {@code days} the length of an all-day
	 * event; RFC 5545 3.8.5.3 has each series occurrence keep the kind its master has.
	 */
	private record Event(String uid, Stamp start, Duration exact, IcsValues.Span nominal, long days, IcsRule rule,
			int ruleLine, List<Stamp> rdates, List<Stamp> exdates, Stamp recurrenceId, String summary, String location,
			String description, IcsEvent.Status status, boolean transparent) {
	}

	/**
	 * Takes what the recurrence engine hands over, if it hands anything over.
	 *
	 * <p>The engine's spliterator also answers "there is more" for a candidate it steps
	 * over before the window, without handing one over. That breaks the Spliterator
	 * contract, and Java's iterator adapter reads it as the end, so every series whose
	 * first occurrence lay before the window came back empty. The parser calls
	 * tryAdvance itself and looks whether something arrived.
	 */
	private static final class Handed<T> implements Consumer<T> {

		private T value;

		@Override
		public void accept(T handedOver) {
			value = handedOver;
		}

		T take() {
			T taken = value;
			value = null;
			return taken;
		}
	}

	private static final class Reading {

		private final Component calendar;
		private final Instant from;
		private final Instant to;
		private final ZoneId readerZone;
		private final Zone floating;
		private final Map<String, Optional<Zone>> zones = new HashMap<>();
		private final PriorityQueue<IcsEvent> latestFirst;
		private long budget = WORK_BUDGET;
		private boolean truncated;

		Reading(Component calendar, Instant from, Instant to, ZoneId readerZone, boolean truncated) {
			this.calendar = calendar;
			this.from = from;
			this.to = to;
			this.readerZone = readerZone;
			this.floating = new Zone(readerZone.getRules(), readerZone, true);
			this.truncated = truncated;
			this.latestFirst = new PriorityQueue<>(order().reversed());
		}

		IcsCalendar calendar() {
			Map<String, Event> masters = new LinkedHashMap<>();
			List<Event> overrides = new ArrayList<>();
			for (Component vevent : calendar.children("VEVENT")) {
				Event event = event(vevent);
				if (event == null) {
					continue;
				}
				if (event.recurrenceId() == null) {
					masters.putIfAbsent(event.uid(), event);
				}
				else {
					overrides.add(event);
				}
			}
			Map<String, List<Event>> movedByUid = new HashMap<>();
			for (Event override : overrides) {
				movedByUid.computeIfAbsent(override.uid(), uid -> new ArrayList<>()).add(override);
			}
			for (Event master : masters.values()) {
				List<Event> moved = movedByUid.getOrDefault(master.uid(), List.of());
				if (master.rule() == null && master.rdates().isEmpty()) {
					emit(master, null);
				}
				else if (master.start().isDate()) {
					days(master, moved);
				}
				else {
					times(master, moved);
				}
			}
			// A moved occurrence stands on its own times, whether or not its series is in this file.
			for (Event override : overrides) {
				Stamp id = override.recurrenceId();
				emit(override, id.isDate() ? dayId(id.date()) : UTC_ID.format(id.instant()));
			}
			List<IcsEvent> events = new ArrayList<>(latestFirst);
			events.sort(order());
			return new IcsCalendar(text(calendar.first("X-WR-CALNAME"), MAX_CALENDAR_NAME), events, truncated);
		}

		// --- reading an event ------------------------------------------------------------

		private Event event(Component vevent) {
			Property dtstart = vevent.first("DTSTART");
			if (dtstart == null) {
				return null;
			}
			Stamp start = stamp(dtstart, dtstart.value(), null);
			Property dtend = vevent.first("DTEND");
			Stamp end = dtend == null ? null : stamp(dtend, dtend.value(), start.zone());
			Property duration = vevent.first("DURATION");
			IcsValues.Span nominal = duration == null ? null : read(duration, () -> IcsValues.duration(duration.value()));
			Property rrule = vevent.first("RRULE");
			IcsRule rule = rrule == null ? null : read(rrule, () -> IcsRule.read(rrule.value(), start.isDate()));
			Property recurrenceId = vevent.first("RECURRENCE-ID");
			return new Event(uid(vevent), start,
					end != null && !start.isDate() ? exact(start, end) : null,
					end == null ? nominal : null,
					start.isDate() ? days(start, end, nominal) : 0,
					rule, rrule == null ? 0 : rrule.line(),
					stamps(vevent.all("RDATE"), start.zone()),
					stamps(vevent.all("EXDATE"), start.zone()),
					recurrenceId == null ? null : stamp(recurrenceId, recurrenceId.value(), start.zone()),
					text(vevent.first("SUMMARY"), MAX_SUMMARY),
					text(vevent.first("LOCATION"), MAX_LOCATION),
					text(vevent.first("DESCRIPTION"), MAX_DESCRIPTION),
					status(vevent.first("STATUS")),
					transparent(vevent.first("TRANSP")));
		}

		/** A DATE or DATE-TIME [value] of [property]; [inherit] is the zone of a time with neither TZID nor Z, null for floating. */
		private Stamp stamp(Property property, String value, Zone inherit) {
			return read(property, () -> {
				String written = value.strip();
				if (written.length() == 8 || "DATE".equalsIgnoreCase(property.parameter("VALUE"))) {
					return new Stamp(IcsValues.date(written), null, null);
				}
				LocalDateTime local = IcsValues.dateTime(written);
				if (IcsValues.isUtc(written)) {
					return new Stamp(null, local, UTC);
				}
				String tzid = property.parameter("TZID");
				return new Stamp(null, local, tzid != null ? zone(tzid) : inherit != null ? inherit : floating);
			});
		}

		/** Every value of every [properties], comma lists and PERIOD values included. */
		private List<Stamp> stamps(List<Property> properties, Zone inherit) {
			List<Stamp> stamps = new ArrayList<>();
			for (Property property : properties) {
				String value = property.value();
				int start = 0;
				while (true) {
					int comma = value.indexOf(',', start);
					String part = value.substring(start, comma < 0 ? value.length() : comma);
					if (!part.isBlank()) {
						if (stamps.size() == MAX_DATES) {
							throw new IcsParseException(MALFORMED, property.line());
						}
						// A PERIOD starts an occurrence; the length is the event's own.
						int slash = part.indexOf('/');
						stamps.add(stamp(property, slash < 0 ? part : part.substring(0, slash), inherit));
					}
					if (comma < 0) {
						break;
					}
					start = comma + 1;
				}
			}
			return stamps;
		}

		private Zone zone(String tzid) {
			return zones.computeIfAbsent(tzid, this::resolve).orElse(floating);
		}

		/** A name a table knows, else the calendar's own definition of it, else nothing: the time floats. */
		private Optional<Zone> resolve(String tzid) {
			Optional<ZoneId> named = IcsZones.byName(tzid);
			if (named.isPresent()) {
				return Optional.of(new Zone(named.get().getRules(), named.get(), false));
			}
			return IcsZoneDefinitions.rules(definition(tzid)).map(rules -> new Zone(rules, null, false));
		}

		private List<Observance> definition(String tzid) {
			String wanted = tzid.strip();
			Component match = null;
			for (Component timezone : calendar.children("VTIMEZONE")) {
				String name = value(timezone, "TZID") == null ? "" : value(timezone, "TZID").strip();
				if (name.equals(wanted)) {
					match = timezone;
					break;
				}
				if (match == null && name.equalsIgnoreCase(wanted)) {
					match = timezone;
				}
			}
			if (match == null) {
				return List.of();
			}
			return match.children.stream()
					.map(block -> new Observance(block.name.equals("DAYLIGHT"), value(block, "DTSTART"),
							value(block, "TZOFFSETFROM"), value(block, "TZOFFSETTO"), value(block, "RRULE")))
					.toList();
		}

		// --- occurrences -----------------------------------------------------------------

		private void emit(Event event, String recurrenceId) {
			Stamp start = event.start();
			if (start.isDate()) {
				LocalDate end = start.date().plusDays(event.days());
				if (overlaps(start.date(), end)) {
					offer(allDay(event, recurrenceId, start.date(), end));
				}
				return;
			}
			Instant begin = start.instant();
			Instant end = end(event, start.local(), begin);
			if (overlaps(begin, end)) {
				offer(timed(event, recurrenceId, begin, end));
			}
		}

		/** A timed series: DTSTART, RDATE and the rule's occurrences, less EXDATE and the moved ones. */
		private void times(Event master, List<Event> moved) {
			Zone zone = master.start().zone();
			Set<Instant> skipped = new HashSet<>();
			Set<LocalDate> skippedDays = new HashSet<>();
			List<Stamp> exceptions = new ArrayList<>(master.exdates());
			moved.forEach(override -> exceptions.add(override.recurrenceId()));
			for (Stamp exception : exceptions) {
				if (exception.isDate()) {
					skippedDays.add(exception.date());
				}
				else {
					skipped.add(exception.instant());
				}
			}
			TreeMap<Instant, LocalDateTime> starts = new TreeMap<>();
			starts.put(master.start().instant(), master.start().local());
			for (Stamp rdate : master.rdates()) {
				if (rdate.isDate()) {
					LocalDateTime local = rdate.date().atTime(master.start().local().toLocalTime());
					starts.put(IcsZones.instant(local, zone.rules()), local);
				}
				else {
					Instant instant = rdate.instant();
					starts.put(instant, LocalDateTime.ofInstant(instant, zone.rules().getOffset(instant)));
				}
			}
			IcsRule rule = master.rule();
			if (rule != null && admit(rule)) {
				Instant until = rule.until() == null ? null : until(rule.until(), zone);
				if (until == null || !until.isBefore(from)) {
					LocalDateTime low = LocalDateTime.ofInstant(from.minus(longest(master)).minus(MARGIN), ZoneOffset.UTC);
					LocalDateTime high = LocalDateTime.ofInstant(to.plus(MARGIN), ZoneOffset.UTC);
					try {
						Spliterator<LocalDateTime> dates = new Recur<LocalDateTime>(rule.forRecur(), false)
								.getDatesAsStream(master.start().local(), low, high, -1).spliterator();
						Handed<LocalDateTime> handed = new Handed<>();
						int inWindow = 0;
						while (inWindow <= MAX_PER_SERIES && dates.tryAdvance(handed)) {
							LocalDateTime local = handed.take();
							if (local == null) {
								continue;
							}
							if (!charge(1)) {
								break;
							}
							Instant start = IcsZones.instant(local, zone.rules());
							if (until != null && start.isAfter(until)) {
								break;
							}
							starts.put(start, local);
							if (!skipped.contains(start) && !skippedDays.contains(local.toLocalDate())
									&& overlaps(start, end(master, local, start))) {
								inWindow++;
							}
						}
					}
					catch (RuntimeException ex) {
						throw new IcsParseException(MALFORMED, master.ruleLine());
					}
				}
			}
			int kept = 0;
			for (Map.Entry<Instant, LocalDateTime> occurrence : starts.entrySet()) {
				Instant start = occurrence.getKey();
				LocalDateTime local = occurrence.getValue();
				Instant end = end(master, local, start);
				if (skipped.contains(start) || skippedDays.contains(local.toLocalDate()) || !overlaps(start, end)) {
					continue;
				}
				if (kept == MAX_PER_SERIES) {
					truncated = true;
					break;
				}
				kept++;
				offer(timed(master, UTC_ID.format(start), start, end));
			}
		}

		/** A series of days: the same as {@link #times}, counted in dates. */
		private void days(Event master, List<Event> moved) {
			Set<LocalDate> skipped = new HashSet<>();
			master.exdates().forEach(exdate -> skipped.add(exdate.day()));
			moved.forEach(override -> skipped.add(override.recurrenceId().day()));
			TreeSet<LocalDate> starts = new TreeSet<>();
			starts.add(master.start().date());
			master.rdates().forEach(rdate -> starts.add(rdate.day()));
			IcsRule rule = master.rule();
			if (rule != null && admit(rule)) {
				LocalDate until = rule.until() == null ? null : untilDay(rule.until());
				LocalDate low = day(from).minusDays(master.days() + 1);
				LocalDate high = day(to).plusDays(1);
				if (until == null || !until.isBefore(low)) {
					try {
						Spliterator<LocalDate> dates = new Recur<LocalDate>(rule.forRecur(), false)
								.getDatesAsStream(master.start().date(), low, high, -1).spliterator();
						Handed<LocalDate> handed = new Handed<>();
						int inWindow = 0;
						while (inWindow <= MAX_PER_SERIES && dates.tryAdvance(handed)) {
							LocalDate date = handed.take();
							if (date == null) {
								continue;
							}
							if (!charge(1)) {
								break;
							}
							if (until != null && date.isAfter(until)) {
								break;
							}
							starts.add(date);
							if (!skipped.contains(date) && overlaps(date, date.plusDays(master.days()))) {
								inWindow++;
							}
						}
					}
					catch (RuntimeException ex) {
						throw new IcsParseException(MALFORMED, master.ruleLine());
					}
				}
			}
			int kept = 0;
			for (LocalDate date : starts) {
				LocalDate end = date.plusDays(master.days());
				if (skipped.contains(date) || !overlaps(date, end)) {
					continue;
				}
				if (kept == MAX_PER_SERIES) {
					truncated = true;
					break;
				}
				kept++;
				offer(allDay(master, dayId(date), date, end));
			}
		}

		/** Whether the engine may expand [rule]; a cut or a refusal marks the result as cut. */
		private boolean admit(IcsRule rule) {
			if (rule.shortened()) {
				truncated = true;
			}
			if (!rule.expandable()) {
				truncated = true;
				return false;
			}
			return charge(rule.count() == null ? 0 : rule.count());
		}

		private boolean charge(long steps) {
			if (steps > budget) {
				truncated = true;
				return false;
			}
			budget -= steps;
			return true;
		}

		private void offer(IcsEvent occurrence) {
			latestFirst.offer(occurrence);
			if (latestFirst.size() > MAX_OCCURRENCES) {
				latestFirst.poll();
				truncated = true;
			}
		}

		/** The window holds the start of a moment, or some part of something that lasts. */
		private boolean overlaps(Instant start, Instant end) {
			if (!start.isBefore(to)) {
				return false;
			}
			return end.isAfter(from) || end.equals(start) && !start.isBefore(from);
		}

		private boolean overlaps(LocalDate start, LocalDate end) {
			return start.atStartOfDay(readerZone).toInstant().isBefore(to)
					&& end.atStartOfDay(readerZone).toInstant().isAfter(from);
		}

		private LocalDate day(Instant instant) {
			return instant.atZone(readerZone).toLocalDate();
		}

		private Comparator<IcsEvent> order() {
			return Comparator.comparing(this::startOf)
					.thenComparing(IcsEvent::uid)
					.thenComparing(event -> event.recurrenceId() == null ? "" : event.recurrenceId());
		}

		private Instant startOf(IcsEvent event) {
			return switch (event) {
				case IcsEvent.Timed timed -> timed.start();
				case IcsEvent.AllDay day -> day.start().atStartOfDay(readerZone).toInstant();
			};
		}

		private static IcsEvent.Timed timed(Event event, String recurrenceId, Instant start, Instant end) {
			Zone zone = event.start().zone();
			return new IcsEvent.Timed(event.uid(), recurrenceId, start, end, zone.at(start), zone.floating(),
					event.summary(), event.location(), event.description(), event.status(), event.transparent());
		}

		private static IcsEvent.AllDay allDay(Event event, String recurrenceId, LocalDate start, LocalDate end) {
			return new IcsEvent.AllDay(event.uid(), recurrenceId, start, end, event.summary(), event.location(),
					event.description(), event.status(), event.transparent());
		}

		// --- lengths and bounds ------------------------------------------------------------

		private static Duration exact(Stamp start, Stamp end) {
			Instant last = end.isDate() ? IcsZones.instant(end.date().atStartOfDay(), start.zone().rules()) : end.instant();
			Duration exact = Duration.between(start.instant(), last);
			return exact.isNegative() ? Duration.ZERO : exact;
		}

		/** How many days an all-day event lasts: to DTEND, by DURATION, or the one day RFC 5545 gives it. */
		private static long days(Stamp start, Stamp end, IcsValues.Span nominal) {
			if (end != null) {
				return Math.max(1, ChronoUnit.DAYS.between(start.date(), end.day()));
			}
			return nominal == null ? 1 : Math.max(1, nominal.days());
		}

		private static Instant end(Event event, LocalDateTime local, Instant start) {
			if (event.exact() != null) {
				return start.plus(event.exact());
			}
			if (event.nominal() != null) {
				Instant end = IcsZones.instant(local.plusDays(event.nominal().days()), event.start().zone().rules())
						.plusSeconds(event.nominal().seconds());
				return end.isBefore(start) ? start : end;
			}
			return start;
		}

		/** At most how long one occurrence lasts, to widen the stretch the engine searches. */
		private static Duration longest(Event event) {
			if (event.exact() != null) {
				return event.exact();
			}
			if (event.nominal() != null) {
				return Duration.ofHours(25 * Math.max(0, event.nominal().days()))
						.plusSeconds(Math.max(0, event.nominal().seconds()));
			}
			return Duration.ZERO;
		}

		/** The last instant UNTIL allows. A date allows its whole day. */
		private static Instant until(String until, Zone zone) {
			if (until.length() == 8) {
				return IcsZones.instant(IcsValues.date(until).plusDays(1).atStartOfDay(), zone.rules()).minusNanos(1);
			}
			LocalDateTime local = IcsValues.dateTime(until);
			return IcsValues.isUtc(until) ? local.toInstant(ZoneOffset.UTC) : IcsZones.instant(local, zone.rules());
		}

		private static LocalDate untilDay(String until) {
			return until.length() == 8 ? IcsValues.date(until) : IcsValues.dateTime(until).toLocalDate();
		}

		// --- text --------------------------------------------------------------------------

		private static String uid(Component vevent) {
			String uid = text(vevent.first("UID"), MAX_UID);
			if (uid != null) {
				return uid;
			}
			// No UID: a stand-in made of what the event says about itself, the same on every read.
			StringBuilder seed = new StringBuilder();
			for (String name : List.of("DTSTART", "DTEND", "DURATION", "RRULE", "SUMMARY")) {
				seed.append(value(vevent, name)).append('\n');
			}
			try {
				byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.toString().getBytes(StandardCharsets.UTF_8));
				return "hinata-" + HexFormat.of().formatHex(digest, 0, 16);
			}
			catch (NoSuchAlgorithmException ex) {
				throw new IllegalStateException("SHA-256 unavailable", ex);
			}
		}

		private static IcsEvent.Status status(Property property) {
			if (property == null) {
				return null;
			}
			return switch (property.value().strip().toUpperCase(Locale.ROOT)) {
				case "TENTATIVE" -> IcsEvent.Status.TENTATIVE;
				case "CONFIRMED" -> IcsEvent.Status.CONFIRMED;
				case "CANCELLED" -> IcsEvent.Status.CANCELLED;
				default -> null;
			};
		}

		private static boolean transparent(Property property) {
			return property != null && property.value().strip().equalsIgnoreCase("TRANSPARENT");
		}

		private static String text(Property property, int max) {
			return property == null ? null : IcsValues.text(property.value(), max);
		}

		private static String value(Component component, String property) {
			Property found = component.first(property);
			return found == null ? null : found.value();
		}

		private static String dayId(LocalDate date) {
			return DateTimeFormatter.BASIC_ISO_DATE.format(date);
		}

		/** Runs [reading]; a value it cannot read fails the calendar at the property's line, quoting nothing. */
		private static <T> T read(Property property, Supplier<T> reading) {
			try {
				return reading.get();
			}
			catch (DateTimeException | IllegalArgumentException | ArithmeticException ex) {
				throw new IcsParseException(MALFORMED, property.line());
			}
		}
	}
}
