package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.availability.Holiday;
import com.ahmadre.hinata.availability.HolidayCalendar;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * The organisation's whole-day holidays in a span, for a budget's forecast (HIN-156).
 *
 * <p>A calendar, never a person: it reads the default holiday calendar's dates and nothing else,
 * no working pattern, no capacity, no absence. A forecast of when a project's budget runs out must
 * not move because somebody booked leave. One of the named readers of availability
 * (ModuleBoundaryTest), and it writes nothing.
 */
@Component
@RequiredArgsConstructor
class TimeForecastHolidays {

	private final MongoTemplate mongo;

	Set<LocalDate> between(LocalDate from, LocalDate to) {
		Set<LocalDate> days = new HashSet<>();
		Query calendarQuery = Query.query(Criteria.where("defaultCalendar").is(true));
		calendarQuery.fields().include("_id");
		HolidayCalendar calendar = mongo.findOne(calendarQuery, HolidayCalendar.class);
		if (calendar == null) {
			return days;
		}
		Query query = Query.query(Criteria.where("calendarId").is(calendar.getId()).and("date").gte(from).lte(to)
				.and("halfDay").ne(true));
		query.fields().include("date");
		mongo.find(query, Holiday.class).forEach(holiday -> days.add(holiday.getDate()));
		return days;
	}
}
