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
import java.time.temporal.Temporal;
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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.ahmadre.hinata.ics.IcsParseException.Reason.MALFORMED;
import static com.ahmadre.hinata.ics.IcsParseException.Reason.TOO_LARGE;

/**
 * Reads an external calendar (RFC 5545) into the occurrences that fall inside a
 * window.
 *
 * <p>Spring-free and without state between calls: bytes and a window go in, an
 * {@link IcsCalendar} comes out, and a calendar that cannot be read is an
 * {@link IcsParseException} with a reason and a line. Nothing else leaves, whatever
 * the bytes are. It is not a pure function, though: it reads on a thread of its own,
 * and the clock can cut its result, see the last paragraph.
 *
 * <p>What is read: VEVENT with UID, RECURRENCE-ID, DTSTART, DTEND or DURATION,
 * SUMMARY, LOCATION, DESCRIPTION, STATUS and TRANSP; its RRULE, RDATE and EXDATE;
 * and VTIMEZONE, for the zone names nothing else explains.
 *
 * <p>Nothing grows without a limit, and a limit either cuts or refuses:
 *
 * <ul>
 * <li><b>Cut</b>, shown as {@link IcsCalendar#truncated()}: events after the first
 * 5,000; time zone definitions after the first 100, and observances after the first
 * 50 in one of them; occurrences after 500 in one series and after the earliest 5,000
 * of the window; a COUNT above 5,000; a rule the engine is not given (see
 * {@link IcsRule}); and a calendar that has spent its recurrence steps or its two
 * seconds.</li>
 * <li><b>Refused</b>: more than 2 MB, anything that is not a calendar, the structural
 * limits of {@link IcsLexer}, more than 10,000 RDATE or EXDATE values in one event,
 * and every value that cannot be read.</li>
 * </ul>
 *
 * <p>Times follow RFC 5545. A time with a zone is converted with that zone's rules,
 * the way it reads on the wall there, so a weekly meeting at half past nine stays at
 * half past nine across the change to summer time. A time without a zone (floating)
 * is read in the zone the caller names, the reader's own, and an all-day event is
 * placed in that zone too when it is compared with the window.
 *
 * <p>Each calendar is read on a thread of its own, in a pool of one. ical4j checks
 * BYDAY with a parallel stream, which then stays on that thread instead of spreading
 * over the JVM's common pool, and when the two seconds are up the thread is
 * interrupted, which ends even a single long step of the engine. The caller waits for
 * it, so parses still belong on an executor of their own, not on a request or
 * scheduler thread.
 */
public final class IcsParser {

	private static final int MAX_EVENTS = 5_000;
	private static final int MAX_OCCURRENCES = 5_000;
	private static final int MAX_PER_SERIES = 500;

	private static final int MAX_SUMMARY = 500;
	private static final int MAX_LOCATION = 500;

	/** As long as the description of a time entry may be, so a taken-over event fits whole. */
	private static final int MAX_DESCRIPTION = 2_000;

	private static final int MAX_CALENDAR_NAME = 200;
	private static final int MAX_UID = 1_000;

	/** RDATE and EXDATE values of one event. */
	private static final int MAX_DATES = 10_000;

	/**
	 * Steps of the recurrence engine one calendar may take. Every step is paid for, also
	 * one in which the engine hands nothing over because it passed a candidate before the
	 * window, and so is the start of every series.
	 */
	private static final long WORK_BUDGET = 250_000;

	/**
	 * What the series of one calendar may take. A step of the engine still running then
	 * is interrupted; one step may build a thousand empty periods before the engine gives
	 * up. Real calendars take milliseconds.
	 */
	private static final Duration TIME_BUDGET = Duration.ofSeconds(2);

	/** Wider than any UTC offset, so nothing is lost at the edges of the window before it is converted. */
	private static final Duration MARGIN = Duration.ofHours(18);

	private static final DateTimeFormatter UTC_ID =
			DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

	private static final Zone UTC = new Zone(ZoneOffset.UTC.getRules(), ZoneOffset.UTC, false);

	private static final Comparator<Ranked> ORDER = Comparator.comparing(Ranked::start)
			.thenComparing(Ranked::uid)
			.thenComparing(ranked -> ranked.event().recurrenceId() == null ? "" : ranked.event().recurrenceId());

	/** The thread one calendar is read on; its pool has no other. */
	private static final ForkJoinPool.ForkJoinWorkerThreadFactory OWN_THREAD = pool -> {
		ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
		thread.setName("ics-parse");
		return thread;
	};

	/** Interrupts a reading whose time is up; ical4j gives up a step when its thread is interrupted. */
	private static final ScheduledThreadPoolExecutor ALARMS = alarms();

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
		return parse(ics, from, to, readerZone, WORK_BUDGET, TIME_BUDGET);
	}

	/**
	 * {@link #parse(byte[], Instant, Instant, ZoneId)} with other budgets for the series, so a
	 * test can reach each limit on its own.
	 */
	static IcsCalendar parse(byte[] ics, Instant from, Instant to, ZoneId readerZone, long workBudget,
			Duration timeBudget) {
		if (ics == null || from == null || to == null || readerZone == null || !from.isBefore(to)) {
			throw new IllegalArgumentException("a calendar, a window that is not empty and a zone are needed");
		}
		if (ics.length > IcsLexer.MAX_BYTES) {
			throw new IcsParseException(TOO_LARGE, 0);
		}
		// No second thread, not even to stand in for a blocked one: the pool stays at one.
		ForkJoinPool own = new ForkJoinPool(1, OWN_THREAD, null, false, 1, 1, 1, pool -> true, 1, TimeUnit.SECONDS);
		try {
			return own.submit(() -> read(ics, from, to, readerZone, workBudget, timeBudget)).join();
		}
		finally {
			own.shutdownNow();
		}
	}

	private static IcsCalendar read(byte[] ics, Instant from, Instant to, ZoneId readerZone, long workBudget,
			Duration timeBudget) {
		try {
			IcsLexer.Result read = IcsLexer.read(ics, MAX_EVENTS);
			return new Reading(read.calendar(), from, to, readerZone, read.truncated(), workBudget, timeBudget)
					.calendar();
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

	private static ScheduledThreadPoolExecutor alarms() {
		ScheduledThreadPoolExecutor alarms = new ScheduledThreadPoolExecutor(1,
				Thread.ofPlatform().name("ics-parse-alarm").daemon().factory());
		alarms.setRemoveOnCancelPolicy(true);
		return alarms;
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
	 * event; RFC 5545 3.8.5.3 has each occurrence of a series keep its master's kind.
	 */
	private record Event(String uid, Stamp start, Duration exact, IcsValues.Span nominal, long days, IcsRule rule,
			int ruleLine, List<Stamp> rdates, List<Stamp> exdates, Stamp recurrenceId, String summary, String location,
			String description, IcsEvent.Status status, boolean transparent) {
	}

	/** An occurrence among the earliest ones kept so far, with the key it is ordered by. */
	private record Ranked(Instant start, String uid, IcsEvent event) {
	}

	/**
	 * Takes what the recurrence engine hands over, if it hands anything over.
	 *
	 * <p>The engine's spliterator also answers "there is more" for a candidate it steps
	 * over before the window, without handing one over. That breaks the Spliterator
	 * contract, and Java's iterator adapter reads it as the end, so every series whose
	 * first occurrence lay before the window came back empty. The parser calls tryAdvance
	 * itself and looks whether something arrived.
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

	/**
	 * What sets a series of times apart from a series of days. The walk through the rule,
	 * the caps and the choice of occurrences are the same for both, in
	 * {@link Reading#expand}.
	 */
	private interface Series<T extends Temporal> {

		/** The first occurrence, which the engine counts from. */
		T seed();

		/** Where the engine starts looking: early enough for every offset and for an occurrence that lasts into the window. */
		T low();

		/** Where the engine stops looking. */
		T high();

		/** UNTIL ends the series before any occurrence can reach the window. */
		boolean endsBeforeWindow();

		boolean afterUntil(T start);

		/** DTSTART and the RDATE values, which count whatever the rule says. */
		List<T> fixed();

		/** The instant an occurrence is ordered and told apart by. */
		Instant order(T start);

		/** Struck out by EXDATE, or moved by an event of its own with a RECURRENCE-ID. */
		boolean skipped(T start);

		boolean overlaps(T start);

		IcsEvent occurrence(T start);
	}

	private static final class Reading {

		private final Component calendar;
		private final Instant from;
		private final Instant to;
		private final ZoneId readerZone;
		private final Zone floating;
		private final Map<String, Component> timezones = new HashMap<>();
		private final Map<String, Optional<Zone>> zones = new HashMap<>();
		private final PriorityQueue<Ranked> latestFirst = new PriorityQueue<>(ORDER.reversed());
		private final long deadline;
		private long budget;
		private boolean truncated;

		Reading(Component calendar, Instant from, Instant to, ZoneId readerZone, boolean truncated, long workBudget,
				Duration timeBudget) {
			this.calendar = calendar;
			this.from = from;
			this.to = to;
			this.readerZone = readerZone;
			this.floating = new Zone(readerZone.getRules(), readerZone, true);
			this.truncated = truncated;
			this.budget = workBudget;
			this.deadline = System.nanoTime() + timeBudget.toNanos();
			for (Component timezone : calendar.children("VTIMEZONE")) {
				String tzid = value(timezone, "TZID");
				if (tzid != null) {
					timezones.putIfAbsent(normalized(tzid), timezone);
				}
			}
		}

		/** The calendar, read on this thread, which the alarm interrupts when the time is up. */
		IcsCalendar calendar() {
			ScheduledFuture<?> alarm = ALARMS.schedule(Thread.currentThread()::interrupt,
					deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
			try {
				return collect();
			}
			finally {
				alarm.cancel(false);
				// An alarm that went off has done its work; the mark it left on the thread is spent.
				Thread.interrupted();
			}
		}

		private IcsCalendar collect() {
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
					expand(master, new Days(master, moved));
				}
				else {
					expand(master, new Times(master, moved));
				}
			}
			// A moved occurrence stands on its own times, whether or not its series is in this file.
			for (Event override : overrides) {
				Stamp id = override.recurrenceId();
				emit(override, id.isDate() ? dayId(id.date()) : UTC_ID.format(id.instant()));
			}
			List<IcsEvent> events = latestFirst.stream().sorted(ORDER).map(Ranked::event).toList();
			return new IcsCalendar(text(calendar.first("X-WR-CALNAME"), MAX_CALENDAR_NAME), events, truncated);
		}

		// --- series ------------------------------------------------------------------------

		private <T extends Temporal> void expand(Event master, Series<T> series) {
			TreeMap<Instant, T> starts = new TreeMap<>();
			for (T start : series.fixed()) {
				starts.putIfAbsent(series.order(start), start);
			}
			IcsRule rule = master.rule();
			// A series that ended before the window has nothing left to cut.
			if (rule != null && !series.endsBeforeWindow() && admit(rule)) {
				walk(rule, master.ruleLine(), series, starts);
			}
			int kept = 0;
			for (T start : starts.values()) {
				if (series.skipped(start) || !series.overlaps(start)) {
					continue;
				}
				if (kept == MAX_PER_SERIES) {
					truncated = true;
					break;
				}
				kept++;
				offer(series.order(start), master.uid(), () -> series.occurrence(start));
			}
		}

		/** Takes the rule's occurrences into [starts] until the series, the window or the calendar has had enough. */
		private <T extends Temporal> void walk(IcsRule rule, int line, Series<T> series, TreeMap<Instant, T> starts) {
			try {
				Spliterator<T> dates = new Recur<T>(rule.forRecur(), false)
						.getDatesAsStream(series.seed(), series.low(), series.high(), -1).spliterator();
				Handed<T> handed = new Handed<>();
				int inWindow = 0;
				while (inWindow <= MAX_PER_SERIES && step() && dates.tryAdvance(handed)) {
					T start = handed.take();
					if (start == null) {
						continue;
					}
					if (series.afterUntil(start)) {
						break;
					}
					starts.putIfAbsent(series.order(start), start);
					if (!series.skipped(start) && series.overlaps(start)) {
						inWindow++;
					}
				}
			}
			catch (RuntimeException ex) {
				// The alarm's interrupt makes ical4j give up the step with an exception of its own.
				if (outOfTime()) {
					truncated = true;
					return;
				}
				throw new IcsParseException(MALFORMED, line);
			}
		}

		/** Whether the engine may start on [rule]; a cut or a refusal marks the result as cut. */
		private boolean admit(IcsRule rule) {
			if (rule.shortened()) {
				truncated = true;
			}
			if (!rule.expandable()) {
				truncated = true;
				return false;
			}
			return step();
		}

		/** One step of the engine: false, and the result cut, once the calendar has spent its steps or its time. */
		private boolean step() {
			if (budget <= 0 || outOfTime()) {
				truncated = true;
				return false;
			}
			budget--;
			return true;
		}

		private boolean outOfTime() {
			return System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted();
		}

		/** A series of wall-clock times, in the zone of its DTSTART. */
		private final class Times implements Series<LocalDateTime> {

			private final Event master;
			private final Zone zone;
			private final Instant searchFrom;
			private final Instant until;
			private final Set<Instant> skippedTimes = new HashSet<>();
			private final Set<LocalDate> skippedDays = new HashSet<>();

			Times(Event master, List<Event> moved) {
				this.master = master;
				this.zone = master.start().zone();
				this.searchFrom = from.minus(longest(master));
				IcsRule rule = master.rule();
				this.until = rule == null || rule.until() == null ? null : rule.until().instant(zone.rules());
				List<Stamp> exceptions = new ArrayList<>(master.exdates());
				moved.forEach(override -> exceptions.add(override.recurrenceId()));
				for (Stamp exception : exceptions) {
					if (exception.isDate()) {
						skippedDays.add(exception.date());
					}
					else {
						skippedTimes.add(exception.instant());
					}
				}
			}

			@Override
			public LocalDateTime seed() {
				return master.start().local();
			}

			@Override
			public LocalDateTime low() {
				return LocalDateTime.ofInstant(searchFrom.minus(MARGIN), ZoneOffset.UTC);
			}

			@Override
			public LocalDateTime high() {
				return LocalDateTime.ofInstant(to.plus(MARGIN), ZoneOffset.UTC);
			}

			@Override
			public boolean endsBeforeWindow() {
				return until != null && until.isBefore(searchFrom);
			}

			@Override
			public boolean afterUntil(LocalDateTime start) {
				return until != null && order(start).isAfter(until);
			}

			@Override
			public List<LocalDateTime> fixed() {
				List<LocalDateTime> starts = new ArrayList<>();
				starts.add(master.start().local());
				for (Stamp rdate : master.rdates()) {
					if (rdate.isDate()) {
						starts.add(rdate.date().atTime(master.start().local().toLocalTime()));
					}
					else {
						Instant instant = rdate.instant();
						starts.add(LocalDateTime.ofInstant(instant, zone.rules().getOffset(instant)));
					}
				}
				return starts;
			}

			@Override
			public Instant order(LocalDateTime start) {
				return IcsZones.instant(start, zone.rules());
			}

			@Override
			public boolean skipped(LocalDateTime start) {
				return skippedTimes.contains(order(start)) || skippedDays.contains(start.toLocalDate());
			}

			@Override
			public boolean overlaps(LocalDateTime start) {
				Instant begin = order(start);
				return Reading.this.overlaps(begin, end(master, start, begin));
			}

			@Override
			public IcsEvent occurrence(LocalDateTime start) {
				Instant begin = order(start);
				return timed(master, UTC_ID.format(begin), begin, end(master, start, begin));
			}
		}

		/** A series of whole days, placed in the reader's zone. */
		private final class Days implements Series<LocalDate> {

			private final Event master;
			private final LocalDate low;
			private final LocalDate until;
			private final Set<LocalDate> skippedDays = new HashSet<>();

			Days(Event master, List<Event> moved) {
				this.master = master;
				this.low = day(from).minusDays(master.days() + 1);
				IcsRule rule = master.rule();
				this.until = rule == null || rule.until() == null ? null : rule.until().day();
				master.exdates().forEach(exdate -> skippedDays.add(exdate.day()));
				moved.forEach(override -> skippedDays.add(override.recurrenceId().day()));
			}

			@Override
			public LocalDate seed() {
				return master.start().date();
			}

			@Override
			public LocalDate low() {
				return low;
			}

			@Override
			public LocalDate high() {
				return day(to).plusDays(1);
			}

			@Override
			public boolean endsBeforeWindow() {
				return until != null && until.isBefore(low);
			}

			@Override
			public boolean afterUntil(LocalDate start) {
				return until != null && start.isAfter(until);
			}

			@Override
			public List<LocalDate> fixed() {
				List<LocalDate> starts = new ArrayList<>();
				starts.add(master.start().date());
				master.rdates().forEach(rdate -> starts.add(rdate.day()));
				return starts;
			}

			@Override
			public Instant order(LocalDate start) {
				return startOfDay(start);
			}

			@Override
			public boolean skipped(LocalDate start) {
				return skippedDays.contains(start);
			}

			@Override
			public boolean overlaps(LocalDate start) {
				return Reading.this.overlaps(start, start.plusDays(master.days()));
			}

			@Override
			public IcsEvent occurrence(LocalDate start) {
				return allDay(master, dayId(start), start, start.plusDays(master.days()));
			}
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
			return zones.computeIfAbsent(normalized(tzid), this::resolve).orElse(floating);
		}

		/** A name a table knows, else the calendar's own definition of it, else nothing: the time floats. */
		private Optional<Zone> resolve(String name) {
			Optional<ZoneId> named = IcsZones.byName(name);
			if (named.isPresent()) {
				return Optional.of(new Zone(named.get().getRules(), named.get(), false));
			}
			Component definition = timezones.get(name);
			if (definition == null) {
				return Optional.empty();
			}
			List<Observance> observances = definition.children.stream()
					.map(block -> new Observance(block.name.equals("DAYLIGHT"), value(block, "DTSTART"),
							value(block, "TZOFFSETFROM"), value(block, "TZOFFSETTO"), value(block, "RRULE")))
					.toList();
			return IcsZoneDefinitions.rules(observances).map(rules -> new Zone(rules, null, false));
		}

		// --- occurrences -----------------------------------------------------------------

		private void emit(Event event, String recurrenceId) {
			Stamp start = event.start();
			if (start.isDate()) {
				LocalDate end = start.date().plusDays(event.days());
				if (overlaps(start.date(), end)) {
					offer(startOfDay(start.date()), event.uid(), () -> allDay(event, recurrenceId, start.date(), end));
				}
				return;
			}
			Instant begin = start.instant();
			Instant end = end(event, start.local(), begin);
			if (overlaps(begin, end)) {
				offer(begin, event.uid(), () -> timed(event, recurrenceId, begin, end));
			}
		}

		/** Keeps the earliest occurrences of the window; [occurrence] is built only when it is kept. */
		private void offer(Instant start, String uid, Supplier<IcsEvent> occurrence) {
			if (latestFirst.size() == MAX_OCCURRENCES) {
				truncated = true;
				Ranked latest = latestFirst.peek();
				int order = start.compareTo(latest.start());
				if (order > 0 || order == 0 && uid.compareTo(latest.uid()) >= 0) {
					return;
				}
				latestFirst.poll();
			}
			latestFirst.offer(new Ranked(start, uid, occurrence.get()));
		}

		/** The window holds the start of a moment, or some part of something that lasts. */
		private boolean overlaps(Instant start, Instant end) {
			if (!start.isBefore(to)) {
				return false;
			}
			return end.isAfter(from) || end.equals(start) && !start.isBefore(from);
		}

		private boolean overlaps(LocalDate start, LocalDate end) {
			return startOfDay(start).isBefore(to) && startOfDay(end).isAfter(from);
		}

		private Instant startOfDay(LocalDate date) {
			return date.atStartOfDay(readerZone).toInstant();
		}

		private LocalDate day(Instant instant) {
			return instant.atZone(readerZone).toLocalDate();
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

		// --- lengths ---------------------------------------------------------------------

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

		// --- text ----------------------------------------------------------------------------

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

		/** A TZID as it is looked up: calendars are not consistent about case or spaces. */
		private static String normalized(String tzid) {
			return tzid.strip().toLowerCase(Locale.ROOT);
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
