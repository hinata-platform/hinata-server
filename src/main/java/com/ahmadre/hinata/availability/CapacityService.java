package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Somebody's availability over a window: the planned minutes of every day, the holidays of the
 * calendar they follow, their absences, and the capacity that is left.
 *
 * <p>The interface other modules read. Time tracking draws a {@link #window} beside the entries and
 * names {@link #holidayDates} in its hints, and shift planning (HIN-43/45) checks shifts against the
 * capacity of a window. It reads and never decides: nothing here may become a reason to refuse a
 * write (R9).
 *
 * <p>Whether the caller may see this person's availability is not decided here, because the
 * readers differ: a person's own calendar needs no check, anybody else's does
 * ({@link AvailabilityAccess}).
 */
@Service
@RequiredArgsConstructor
public class CapacityService {

	/** Widest window, in days, both ends included. */
	public static final int WINDOW_DAYS_MAX = 366;

	/** The years a window may name; a bound on what the storage layer carries, not a rule. */
	static final int YEAR_MIN = 1970;
	static final int YEAR_MAX = 2200;

	/** Most absences one window reads. A year holds far fewer without overlaps. */
	static final int ABSENCES_MAX = 1_000;

	private final WorkingScheduleRepository schedules;
	private final MongoTemplate mongo;
	private final HinataProperties properties;

	/** The planned minutes of one day. */
	public record ScheduledDay(LocalDate date, int minutes) {
	}

	/** A holiday on a day of the window, from the calendar the person follows on that day. */
	public record HolidayMark(LocalDate date, String name, boolean halfDay) {
	}

	/** An absence touching the window. */
	/**
	 * An absence as a window shows it. [typeId] and [requestId] are there for the person's own
	 * calendar, which opens an absence from the day it falls on; everybody who reads a window reads
	 * their own or is a keeper.
	 */
	public record AbsenceMark(String id, TimeOff.Type type, LocalDate from, LocalDate to, boolean halfDay,
			String note, String typeId, String requestId) {
	}

	public record Window(LocalDate from, LocalDate to, List<ScheduledDay> days, List<HolidayMark> holidays,
			List<AbsenceMark> absences, Capacity.Result capacity) {
	}

	public Window window(String userId, LocalDate from, LocalDate to) {
		assertWindow(from, to);
		Plan plan = planOf(userId, from, to);

		List<ScheduledDay> days = new ArrayList<>();
		Map<LocalDate, Integer> minutesByDay = new HashMap<>();
		Map<LocalDate, Boolean> holidayHalfDays = new HashMap<>();
		List<HolidayMark> holidayMarks = new ArrayList<>();
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			WorkingSchedule pattern = patternOn(plan.patterns(), day);
			int minutes = minutesOn(pattern, day);
			days.add(new ScheduledDay(day, minutes));
			minutesByDay.put(day, minutes);
			Holiday holiday = plan.holidayOn(pattern, day);
			if (holiday != null) {
				holidayHalfDays.put(day, holiday.isHalfDay());
				holidayMarks.add(new HolidayMark(day, holiday.getName(), holiday.isHalfDay()));
			}
		}

		List<TimeOff> absences = mongo.find(Query
				.query(Criteria.where("userId").is(userId).and("to").gte(from).and("from").lte(to))
				.with(Sort.by(Sort.Order.asc("from"), Sort.Order.asc("_id")))
				.limit(ABSENCES_MAX), TimeOff.class);
		Capacity.Result capacity = Capacity.of(from, to, minutesByDay::get, holidayHalfDays,
				absences.stream().map(a -> new Capacity.Absence(a.getFrom(), a.getTo(), a.isHalfDay())).toList());
		List<AbsenceMark> absenceMarks = absences.stream()
				.map(a -> new AbsenceMark(a.getId(), a.getType(), a.getFrom(), a.getTo(), a.isHalfDay(), a.getNote(),
						a.getTypeId(), a.getRequestId()))
				.toList();
		return new Window(from, to, List.copyOf(days), List.copyOf(holidayMarks), absenceMarks, capacity);
	}

	/**
	 * The days from [from] to [to] that are holidays of the calendar [userId] follows on them: the
	 * part of a {@link #window} a working-time hint needs, without absences or minutes.
	 */
	public Set<LocalDate> holidayDates(String userId, LocalDate from, LocalDate to) {
		assertWindow(from, to);
		Plan plan = planOf(userId, from, to);
		Set<LocalDate> dates = new HashSet<>();
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			if (plan.holidayOn(patternOn(plan.patterns(), day), day) != null) {
				dates.add(day);
			}
		}
		return dates;
	}

	/**
	 * Who of [userIds] has capacity left on [day]: a planned day that neither a whole holiday nor a
	 * whole absence takes. Half a holiday or half a vacation day still counts, a day without planned
	 * minutes never does. The same arithmetic as {@link #window}, in {@link Capacity}.
	 *
	 * <p>For a job asking about many people at once (the reminders of HIN-92, shift planning in
	 * HIN-43): a fixed number of queries whatever the size of the set — patterns, the default
	 * calendar, the holidays of the calendars they follow on that day, and the absences touching it —
	 * where a {@link #window} per person would be four each.
	 *
	 * <p>Answers only a yes or no per person. Why somebody is not working stays here: a caller that
	 * learnt "vacation" or "sick" could carry it into a channel other people read.
	 */
	public Set<String> workingOn(Collection<String> userIds, LocalDate day) {
		if (userIds == null || userIds.isEmpty()) {
			return Set.of();
		}
		assertWindow(day, day);
		Map<String, WorkingSchedule> patterns = latestPatterns(userIds, day);
		String defaultCalendarId = defaultCalendarId();

		Set<String> calendarIds = new HashSet<>();
		if (defaultCalendarId != null) {
			calendarIds.add(defaultCalendarId);
		}
		patterns.values().stream().map(WorkingSchedule::getHolidayCalendarId).filter(Objects::nonNull)
				.forEach(calendarIds::add);
		Map<String, Holiday> holidayByCalendar = new HashMap<>();
		if (!calendarIds.isEmpty()) {
			mongo.find(Query.query(Criteria.where("calendarId").in(calendarIds).and("date").is(day)), Holiday.class)
					.forEach(holiday -> holidayByCalendar.put(holiday.getCalendarId(), holiday));
		}

		Map<String, List<Capacity.Absence>> absences = new HashMap<>();
		mongo.find(Query.query(Criteria.where("userId").in(userIds).and("to").gte(day).and("from").lte(day)),
						TimeOff.class)
				.forEach(off -> absences.computeIfAbsent(off.getUserId(), id -> new ArrayList<>())
						.add(new Capacity.Absence(off.getFrom(), off.getTo(), off.isHalfDay())));

		Set<String> working = new HashSet<>();
		for (String userId : new HashSet<>(userIds)) {
			WorkingSchedule pattern = patterns.get(userId);
			String calendarId = calendarIdOf(pattern, defaultCalendarId);
			Holiday holiday = calendarId == null ? null : holidayByCalendar.get(calendarId);
			int minutes = minutesOn(pattern, day);
			Map<LocalDate, Boolean> holidays = holiday == null ? Map.of() : Map.of(day, holiday.isHalfDay());
			if (Capacity.of(day, day, ignored -> minutes, holidays, absences.getOrDefault(userId, List.of()))
					.capacityMinutes() > 0) {
				working.add(userId);
			}
		}
		return working;
	}

	/**
	 * The pattern that applies on [day] to each of [userIds], without the rest of their history: the
	 * newest per person, taken first off user_valid_from, so a person with fifty past patterns costs
	 * one document.
	 */
	private Map<String, WorkingSchedule> latestPatterns(Collection<String> userIds, LocalDate day) {
		Map<String, WorkingSchedule> patterns = new HashMap<>();
		mongo.aggregate(Aggregation.newAggregation(
						Aggregation.match(Criteria.where("userId").in(userIds).and("validFrom").lte(day)),
						Aggregation.sort(Sort.by(Sort.Order.asc("userId"), Sort.Order.desc("validFrom"))),
						Aggregation.group("userId").first("minutesPerWeekday").as("minutesPerWeekday")
								.first("holidayCalendarId").as("holidayCalendarId")),
				WorkingSchedule.class, Document.class).forEach(row -> patterns.put(String.valueOf(row.get("_id")),
						WorkingSchedule.builder()
								.minutesPerWeekday(row.getList("minutesPerWeekday", Integer.class))
								.holidayCalendarId(row.getString("holidayCalendarId"))
								.build()));
		return patterns;
	}

	/** The planned minutes of [day] under [pattern], or under the instance default without one. */
	private int minutesOn(WorkingSchedule pattern, LocalDate day) {
		return pattern == null
				? WorkingSchedule.minutesOn(properties.getAvailability().getDefaultWeekdayMinutes(), day.getDayOfWeek())
				: pattern.minutesOn(day.getDayOfWeek());
	}

	/** The calendar [pattern] follows, or the default without one; null when there is neither. */
	private static String calendarIdOf(WorkingSchedule pattern, String defaultCalendarId) {
		return pattern != null && pattern.getHolidayCalendarId() != null ? pattern.getHolidayCalendarId()
				: defaultCalendarId;
	}

	/** The window rule every availability route shares: in order, at most a year, in storable years. */
	public static void assertWindow(LocalDate from, LocalDate to) {
		if (from == null || to == null || to.isBefore(from) || from.getYear() < YEAR_MIN || to.getYear() > YEAR_MAX) {
			throw ApiException.badRequest("error.availability.windowInvalid");
		}
		if (ChronoUnit.DAYS.between(from, to) + 1 > WINDOW_DAYS_MAX) {
			throw ApiException.badRequest("error.availability.windowTooLong", WINDOW_DAYS_MAX);
		}
	}

	/**
	 * A person's patterns up to the end of a window, newest first, and the holidays in the window of
	 * every calendar they follow on one of its days.
	 */
	private record Plan(List<WorkingSchedule> patterns, String defaultCalendarId, Map<String, Holiday> holidays) {

		/** The holiday on [day] of the calendar [pattern] names, or of the default without one. */
		Holiday holidayOn(WorkingSchedule pattern, LocalDate day) {
			String calendarId = calendarIdOf(pattern, defaultCalendarId);
			return calendarId == null ? null : holidays.get(key(calendarId, day));
		}
	}

	private Plan planOf(String userId, LocalDate from, LocalDate to) {
		List<WorkingSchedule> patterns = schedules.findByUserIdAndValidFromLessThanEqualOrderByValidFromDesc(
				userId, to, PageRequest.of(0, WorkingSchedule.HISTORY_MAX));
		String defaultCalendarId = defaultCalendarId();
		return new Plan(patterns, defaultCalendarId, holidaysOf(patterns, defaultCalendarId, from, to));
	}

	/**
	 * The id of the default calendar, or null without one: from its sparse index and without the rest
	 * of the document. The one way this package asks.
	 */
	String defaultCalendarId() {
		Query query = Query.query(Criteria.where("defaultCalendar").is(true));
		query.fields().include("_id");
		Document found = mongo.query(HolidayCalendar.class).as(Document.class).matching(query).firstValue();
		return found == null ? null : String.valueOf(found.get("_id"));
	}

	/** The pattern that applies on [day]: the latest one starting on or before it. */
	private static WorkingSchedule patternOn(List<WorkingSchedule> newestFirst, LocalDate day) {
		for (WorkingSchedule pattern : newestFirst) {
			if (!pattern.getValidFrom().isAfter(day)) {
				return pattern;
			}
		}
		return null;
	}

	private Map<String, Holiday> holidaysOf(List<WorkingSchedule> patterns, String defaultCalendarId,
			LocalDate from, LocalDate to) {
		Set<String> calendarIds = new HashSet<>();
		if (defaultCalendarId != null) {
			calendarIds.add(defaultCalendarId);
		}
		for (WorkingSchedule pattern : patterns) {
			if (pattern.getHolidayCalendarId() != null) {
				calendarIds.add(pattern.getHolidayCalendarId());
			}
		}
		Map<String, Holiday> byKey = new HashMap<>();
		if (calendarIds.isEmpty()) {
			return byKey;
		}
		mongo.find(Query.query(Criteria.where("calendarId").in(calendarIds).and("date").gte(from).lte(to)),
				Holiday.class).forEach(holiday -> byKey.put(key(holiday.getCalendarId(), holiday.getDate()), holiday));
		return byKey;
	}

	private static String key(String calendarId, LocalDate day) {
		return calendarId + '|' + day;
	}
}
