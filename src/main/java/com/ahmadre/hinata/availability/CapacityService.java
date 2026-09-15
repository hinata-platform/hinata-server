package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Somebody's availability over a window: the planned minutes of every day, the holidays of the
 * calendar they follow, their absences, and the capacity that is left.
 *
 * <p>The interface other modules read. Time tracking draws it beside the entries, and shift
 * planning (HIN-43/45) checks shifts against {@link #capacityMinutes} and {@link #window}. It reads
 * and never decides: nothing here may become a reason to refuse a write (R9).
 *
 * <p>Whether the caller may see this person's availability is not decided here, because the
 * readers differ: a person's own calendar needs no check, a lead's view does
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
	private final HolidayCalendarRepository calendars;
	private final MongoTemplate mongo;
	private final HinataProperties properties;

	/** The planned minutes of one day. */
	public record ScheduledDay(LocalDate date, int minutes) {
	}

	/** A holiday on a day of the window, from the calendar the person follows on that day. */
	public record HolidayMark(LocalDate date, String name, boolean halfDay) {
	}

	/** An absence touching the window. */
	public record AbsenceMark(String id, TimeOff.Type type, LocalDate from, LocalDate to, boolean halfDay,
			String note) {
	}

	public record Window(LocalDate from, LocalDate to, List<ScheduledDay> days, List<HolidayMark> holidays,
			List<AbsenceMark> absences, Capacity.Result capacity) {
	}

	/** The minutes [user] is available from [from] to [to], both included. */
	public int capacityMinutes(User user, LocalDate from, LocalDate to) {
		return window(user.getId(), from, to).capacity().capacityMinutes();
	}

	public Window window(String userId, LocalDate from, LocalDate to) {
		assertWindow(from, to);
		List<WorkingSchedule> patterns = schedules.findByUserIdAndValidFromLessThanEqualOrderByValidFromDesc(
				userId, to, PageRequest.of(0, WorkingSchedule.HISTORY_MAX));
		String defaultCalendarId = calendars.findFirstByDefaultCalendarTrue().map(HolidayCalendar::getId)
				.orElse(null);
		Map<String, Holiday> holidays = holidaysOf(patterns, defaultCalendarId, from, to);
		List<Integer> defaults = properties.getAvailability().getDefaultWeekdayMinutes();

		List<ScheduledDay> days = new ArrayList<>();
		Map<LocalDate, Integer> minutesByDay = new HashMap<>();
		Map<LocalDate, Boolean> holidayHalfDays = new HashMap<>();
		List<HolidayMark> holidayMarks = new ArrayList<>();
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			WorkingSchedule pattern = patternOn(patterns, day);
			int minutes = pattern == null ? WorkingSchedule.minutesOn(defaults, day.getDayOfWeek())
					: pattern.minutesOn(day.getDayOfWeek());
			days.add(new ScheduledDay(day, minutes));
			minutesByDay.put(day, minutes);
			String calendarId = pattern != null && pattern.getHolidayCalendarId() != null
					? pattern.getHolidayCalendarId() : defaultCalendarId;
			Holiday holiday = calendarId == null ? null : holidays.get(key(calendarId, day));
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
				.map(a -> new AbsenceMark(a.getId(), a.getType(), a.getFrom(), a.getTo(), a.isHalfDay(), a.getNote()))
				.toList();
		return new Window(from, to, List.copyOf(days), List.copyOf(holidayMarks), absenceMarks, capacity);
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
