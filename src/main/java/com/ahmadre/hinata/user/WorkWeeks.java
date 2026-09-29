package com.ahmadre.hinata.user;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which days are working days where a person lives — the week most people there
 * work, not anybody's own schedule. The default for "on which days may Hinata
 * notify me", before the person picks their own.
 *
 * <p>Monday to Friday for most of the world. The exceptions are the weekends of
 * the Unicode CLDR week data ({@code weekendStart}/{@code weekendEnd}): Friday and
 * Saturday across much of the Middle East and North Africa, Friday alone in Iran,
 * Thursday and Friday in Afghanistan, Sunday alone in India and Uganda. The JDK
 * carries the first day of the week per region but not the weekend, hence the
 * table.
 *
 * <p>The region comes from the language tag when it names one ({@code ar-SA}),
 * else from the time zone ({@code Asia/Riyadh}); Hinata's language setting is
 * usually a bare language, and a language alone says nothing about a weekend.
 */
public final class WorkWeeks {

	private static final Set<DayOfWeek> MONDAY_TO_FRIDAY = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);

	private static final Set<DayOfWeek> FRIDAY_SATURDAY_WEEKEND =
			EnumSet.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
					DayOfWeek.THURSDAY);

	/** Regions whose weekend is not Saturday and Sunday, with the days they work. */
	private static final Map<String, Set<DayOfWeek>> WORKING_DAYS = Map.ofEntries(
			Map.entry("AF", EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY,
					DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY)),
			Map.entry("IR", EnumSet.complementOf(EnumSet.of(DayOfWeek.FRIDAY))),
			Map.entry("IN", EnumSet.complementOf(EnumSet.of(DayOfWeek.SUNDAY))),
			Map.entry("UG", EnumSet.complementOf(EnumSet.of(DayOfWeek.SUNDAY))),
			Map.entry("BH", FRIDAY_SATURDAY_WEEKEND), Map.entry("DZ", FRIDAY_SATURDAY_WEEKEND),
			Map.entry("EG", FRIDAY_SATURDAY_WEEKEND), Map.entry("IL", FRIDAY_SATURDAY_WEEKEND),
			Map.entry("IQ", FRIDAY_SATURDAY_WEEKEND), Map.entry("JO", FRIDAY_SATURDAY_WEEKEND),
			Map.entry("KW", FRIDAY_SATURDAY_WEEKEND), Map.entry("LY", FRIDAY_SATURDAY_WEEKEND),
			Map.entry("OM", FRIDAY_SATURDAY_WEEKEND), Map.entry("QA", FRIDAY_SATURDAY_WEEKEND),
			Map.entry("SA", FRIDAY_SATURDAY_WEEKEND), Map.entry("SD", FRIDAY_SATURDAY_WEEKEND),
			Map.entry("SY", FRIDAY_SATURDAY_WEEKEND), Map.entry("YE", FRIDAY_SATURDAY_WEEKEND));

	/** The zones of those regions, for a person whose language names no region. */
	private static final Map<String, String> REGION_OF_ZONE = Map.ofEntries(
			Map.entry("Asia/Kabul", "AF"), Map.entry("Asia/Tehran", "IR"),
			Map.entry("Asia/Kolkata", "IN"), Map.entry("Asia/Calcutta", "IN"),
			Map.entry("Africa/Kampala", "UG"), Map.entry("Asia/Bahrain", "BH"),
			Map.entry("Africa/Algiers", "DZ"), Map.entry("Africa/Cairo", "EG"),
			Map.entry("Asia/Jerusalem", "IL"), Map.entry("Asia/Tel_Aviv", "IL"),
			Map.entry("Asia/Baghdad", "IQ"), Map.entry("Asia/Amman", "JO"),
			Map.entry("Asia/Kuwait", "KW"), Map.entry("Africa/Tripoli", "LY"),
			Map.entry("Asia/Muscat", "OM"), Map.entry("Asia/Qatar", "QA"),
			Map.entry("Asia/Riyadh", "SA"), Map.entry("Africa/Khartoum", "SD"),
			Map.entry("Asia/Damascus", "SY"), Map.entry("Asia/Aden", "YE"));

	private WorkWeeks() {
	}

	/** The working days for a language tag and a zone; never empty. */
	public static Set<DayOfWeek> workingDays(String languageTag, ZoneId zone) {
		String region = languageTag == null ? "" : Locale.forLanguageTag(languageTag.replace('_', '-')).getCountry();
		if (region.isEmpty() && zone != null) {
			region = REGION_OF_ZONE.getOrDefault(zone.getId(), "");
		}
		Set<DayOfWeek> days = WORKING_DAYS.get(region.toUpperCase(Locale.ROOT));
		return EnumSet.copyOf(days != null ? days : MONDAY_TO_FRIDAY);
	}
}
