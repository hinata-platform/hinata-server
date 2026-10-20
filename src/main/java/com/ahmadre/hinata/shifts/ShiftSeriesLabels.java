package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The words a series is shown with (HIN-161), {@code shifts.series.label.*} in the reader's
 * language: "weekly Mon, Wed, until 31 Dec 2026". The app shows the sentence as it comes and builds
 * none of its own.
 *
 * <p>A list costs one read for all its series, whatever the number of rows: {@code shift_series}
 * by {@code _id $in}, only the rule, the zone and the first start. Rows without a series cost
 * nothing.
 */
@Component
@RequiredArgsConstructor
public class ShiftSeriesLabels {

	private static final List<String> WEEK = List.of("MO", "TU", "WE", "TH", "FR", "SA", "SU");

	private final MongoTemplate mongo;
	private final UserWords words;

	/** The label of every series [events] belong to, by series id; empty when none does. */
	public Map<String, String> of(User reader, Collection<ShiftEvent> events) {
		Set<String> ids = events.stream().map(ShiftEvent::getSeriesId).filter(java.util.Objects::nonNull)
				.collect(Collectors.toSet());
		if (ids.isEmpty()) {
			return Map.of();
		}
		Query query = Query.query(Criteria.where("_id").in(ids));
		query.fields().include("rrule", "timezone", "firstStart");
		Locale locale = words.localeOf(reader);
		Map<String, String> out = new HashMap<>();
		for (ShiftSeries series : mongo.find(query, ShiftSeries.class)) {
			out.put(series.getId(), label(locale, series.getRrule(), series.getFirstStart()));
		}
		return out;
	}

	public String of(User reader, ShiftSeries series) {
		return label(words.localeOf(reader), series.getRrule(), series.getFirstStart());
	}

	public String of(User reader, String rrule, LocalDateTime firstStart) {
		return label(words.localeOf(reader), rrule, firstStart);
	}

	/** The sentence for [rrule] starting at [firstStart]; null for a rule outside the subset. */
	String label(Locale locale, String rrule, LocalDateTime firstStart) {
		Map<String, String> parts = ShiftSeriesPlan.parts(rrule);
		String frequency = parts.get("FREQ");
		if (frequency == null || firstStart == null) {
			return null;
		}
		int interval = parts.containsKey("INTERVAL") ? Integer.parseInt(parts.get("INTERVAL")) : 1;
		String key = "shifts.series.label." + frequency.toLowerCase(Locale.ROOT) + (interval > 1 ? "_plural" : "");
		String head = switch (frequency) {
			case "DAILY" -> interval > 1 ? words.in(locale, key, interval) : words.in(locale, key);
			case "WEEKLY" -> {
				String days = weekdays(locale, parts.get("BYDAY"), firstStart);
				yield interval > 1 ? words.in(locale, key, interval, days) : words.in(locale, key, days);
			}
			case "MONTHLY" -> {
				String day = parts.getOrDefault("BYMONTHDAY", String.valueOf(firstStart.getDayOfMonth()));
				yield interval > 1 ? words.in(locale, key, interval, day) : words.in(locale, key, day);
			}
			default -> null;
		};
		if (head == null) {
			return null;
		}
		if (parts.containsKey("UNTIL")) {
			LocalDate until = LocalDate.parse(parts.get("UNTIL"), DateTimeFormatter.BASIC_ISO_DATE);
			return words.in(locale, "shifts.series.label.joined", head,
					words.in(locale, "shifts.series.label.until", words.date(locale, until)));
		}
		if (parts.containsKey("COUNT")) {
			return words.in(locale, "shifts.series.label.joined", head,
					words.in(locale, "shifts.series.label.count", Integer.parseInt(parts.get("COUNT"))));
		}
		return head;
	}

	/** The rule's weekdays, Monday first, short and in the reader's language; else the first start's. */
	private static String weekdays(Locale locale, String byDay, LocalDateTime firstStart) {
		List<String> named = new ArrayList<>();
		if (byDay == null || byDay.isBlank()) {
			named.add(WEEK.get(firstStart.getDayOfWeek().getValue() - 1));
		}
		else {
			for (String day : WEEK) {
				if (List.of(byDay.split(",")).contains(day)) {
					named.add(day);
				}
			}
		}
		return named.stream()
				.map(day -> DayOfWeek.of(WEEK.indexOf(day) + 1).getDisplayName(TextStyle.SHORT_STANDALONE, locale))
				.collect(Collectors.joining(", "));
	}
}
