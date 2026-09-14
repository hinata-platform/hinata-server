package com.ahmadre.hinata.ics;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneOffsetTransitionRule;
import java.time.zone.ZoneRules;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules of a time zone a calendar defines for itself in a VTIMEZONE block, for
 * the zone names no table knows ({@code Mitteleuropäische Zeit}, {@code Customized
 * Time Zone}).
 *
 * <p>What calendars write there is narrow: a STANDARD and a DAYLIGHT block, each
 * recurring yearly on one weekday of one month ("the last Sunday of March"). That
 * shape maps exactly onto java.time's {@link ZoneOffsetTransitionRule}, and the
 * result is a {@link ZoneRules} object of its own, registered nowhere. The newest
 * block of each kind is the rule in force; older blocks are history.
 *
 * <p>Anything else (no DAYLIGHT block, a rule on a day of the month, a rule that
 * ends) falls back to the standard offset of the newest block. That is an hour off
 * in summer at worst, and never a walk over the four hundred years of onsets an
 * observance starting in 1601 describes.
 */
final class IcsZoneDefinitions {

	/** The year the yearly rules are anchored in; java.time applies them only after an explicit transition. */
	private static final int ANCHOR_YEAR = 1900;

	private static final Pattern WEEKDAY = Pattern.compile("([+-]?[1-5])(MO|TU|WE|TH|FR|SA|SU)");

	private IcsZoneDefinitions() {
	}

	/** One STANDARD or DAYLIGHT block, as the calendar wrote it. */
	record Observance(boolean daylight, String start, String offsetFrom, String offsetTo, String rrule) {
	}

	/** The rules [observances] describe; empty when none of them can be read. */
	static Optional<ZoneRules> rules(List<Observance> observances) {
		Readable standard = newest(observances, false);
		Readable daylight = newest(observances, true);
		if (standard == null && daylight == null) {
			return Optional.empty();
		}
		if (standard == null || daylight == null) {
			return Optional.of(ZoneRules.of((standard != null ? standard : daylight).offsetTo()));
		}
		ZoneOffset standardOffset = standard.offsetTo();
		Optional<ZoneOffsetTransitionRule> toSummer = yearly(daylight, standardOffset);
		Optional<ZoneOffsetTransitionRule> toWinter = yearly(standard, standardOffset);
		if (standardOffset.equals(daylight.offsetTo()) || toSummer.isEmpty() || toWinter.isEmpty()) {
			return Optional.of(ZoneRules.of(standardOffset));
		}
		List<ZoneOffsetTransitionRule> rules = java.util.stream.Stream.of(toSummer.get(), toWinter.get())
				.sorted(Comparator.comparingInt(IcsZoneDefinitions::placeInYear))
				.toList();
		ZoneOffsetTransition anchor = rules.get(0).createTransition(ANCHOR_YEAR);
		return Optional.of(ZoneRules.of(standardOffset, anchor.getOffsetBefore(), List.of(), List.of(anchor), rules));
	}

	private static Readable newest(List<Observance> observances, boolean daylight) {
		Readable newest = null;
		for (Observance observance : observances) {
			if (observance.daylight() != daylight) {
				continue;
			}
			try {
				Readable readable = new Readable(IcsValues.dateTime(observance.start()),
						IcsValues.offset(observance.offsetFrom()), IcsValues.offset(observance.offsetTo()),
						observance.rrule());
				if (newest == null || readable.start().isAfter(newest.start())) {
					newest = readable;
				}
			}
			catch (DateTimeException | NullPointerException ex) {
				// An unreadable block describes nothing; the others may still.
			}
		}
		return newest;
	}

	/** "FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU" and nothing more, as a java.time rule. */
	private static Optional<ZoneOffsetTransitionRule> yearly(Readable observance, ZoneOffset standardOffset) {
		if (observance.rrule() == null || observance.rrule().length() > 128) {
			return Optional.empty();
		}
		Map<String, String> parts = new HashMap<>();
		for (String part : observance.rrule().toUpperCase(Locale.ROOT).split(";")) {
			int equals = part.indexOf('=');
			if (equals > 0) {
				parts.put(part.substring(0, equals).strip(), part.substring(equals + 1).strip());
			}
		}
		parts.remove("WKST");
		if (!"1".equals(parts.getOrDefault("INTERVAL", "1"))) {
			return Optional.empty();
		}
		parts.remove("INTERVAL");
		Matcher weekday = WEEKDAY.matcher(parts.getOrDefault("BYDAY", ""));
		String month = parts.getOrDefault("BYMONTH", "");
		if (parts.size() != 3 || !"YEARLY".equals(parts.get("FREQ")) || !weekday.matches() || !month.matches("\\d{1,2}")
				|| Integer.parseInt(month) < 1 || Integer.parseInt(month) > 12) {
			return Optional.empty();
		}
		int ordinal = Integer.parseInt(weekday.group(1));
		// java.time counts "on or after" a day of the month, or "on or before" one counted
		// from its end. A fifth weekday is how Windows says "the last one".
		int dayIndicator = ordinal >= 5 ? -1 : ordinal > 0 ? 1 + (ordinal - 1) * 7 : -1 + (ordinal + 1) * 7;
		return Optional.of(ZoneOffsetTransitionRule.of(Month.of(Integer.parseInt(month)), dayIndicator,
				dayOfWeek(weekday.group(2)), observance.start().toLocalTime(), false,
				ZoneOffsetTransitionRule.TimeDefinition.WALL, standardOffset, observance.offsetFrom(),
				observance.offsetTo()));
	}

	private static int placeInYear(ZoneOffsetTransitionRule rule) {
		int day = rule.getDayOfMonthIndicator();
		return rule.getMonth().getValue() * 100 + (day > 0 ? day : 32 + day);
	}

	private static DayOfWeek dayOfWeek(String code) {
		return switch (code) {
			case "MO" -> DayOfWeek.MONDAY;
			case "TU" -> DayOfWeek.TUESDAY;
			case "WE" -> DayOfWeek.WEDNESDAY;
			case "TH" -> DayOfWeek.THURSDAY;
			case "FR" -> DayOfWeek.FRIDAY;
			case "SA" -> DayOfWeek.SATURDAY;
			default -> DayOfWeek.SUNDAY;
		};
	}

	private record Readable(LocalDateTime start, ZoneOffset offsetFrom, ZoneOffset offsetTo, String rrule) {
	}
}
