package com.ahmadre.hinata.availability;

import de.focus_shift.jollyday.core.CalendarHierarchy;
import de.focus_shift.jollyday.core.HolidayManager;
import de.focus_shift.jollyday.core.HolidayType;
import de.focus_shift.jollyday.core.ManagerParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The statutory holidays of a region, for any year, without a feed.
 *
 * <p>A region is an ISO 3166 code, a country ({@code DE}) or one of its subdivisions
 * ({@code DE-BY}), and its days come from the rules Jollyday ships: Easter and everything that
 * hangs off it, a Bundesland's own days, a holiday that moves to Monday where the law says so. Only
 * public holidays count, never observances: Valentine's Day is not a day off.
 *
 * <p>Nothing here touches the database or the network, so a year is the same every time it is
 * asked for and costs no more than reading the rules once.
 */
@Slf4j
@Component
public class HolidayRules {

	/** A country or one of its regions, with the name it has in one language. */
	public record Region(String code, String name, List<Region> subdivisions) {
	}

	private static final String ZONES = "/availability/zone-countries.properties";

	private final Map<String, String> countryOfZone = readZones();
	private final Map<Locale, List<Region>> regions = new ConcurrentHashMap<>();

	/** Every country with rules, by name in [locale], each with its first level of regions. */
	public List<Region> regions(Locale locale) {
		return regions.computeIfAbsent(locale, this::readRegions);
	}

	/** The code as stored, {@code DE} or {@code DE-BY}, when rules exist for it; empty otherwise. */
	public Optional<String> normalize(String code) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		String[] path = code.strip().toLowerCase(Locale.ROOT).split("-", -1);
		CalendarHierarchy node = hierarchy(path[0]);
		for (int i = 1; node != null && i < path.length; i++) {
			// An empty part ("DE-", "DE--BY") names nothing; the map would not say so on its own.
			node = path[i].isEmpty() ? null : node.getChildren().get(path[i]);
		}
		return node == null ? Optional.empty() : Optional.of(String.join("-", path).toUpperCase(Locale.ROOT));
	}

	/** "Bayern, Deutschland" for {@code DE-BY}, "Deutschland" for {@code DE}. */
	public String nameOf(String code, Locale locale) {
		String[] path = code.toLowerCase(Locale.ROOT).split("-");
		String country = countryName(path[0], locale);
		CalendarHierarchy node = hierarchy(path[0]);
		List<String> parts = new ArrayList<>();
		for (int i = 1; node != null && i < path.length; i++) {
			node = node.getChildren().get(path[i]);
			if (node != null) {
				parts.addFirst(node.getDescription(locale));
			}
		}
		parts.add(country);
		return String.join(", ", parts);
	}

	/**
	 * The public holidays of [code] in [year], in date order, named in [locale]. A day moved by
	 * law (a Sunday holiday taken on Monday) is on the day people are off.
	 */
	public List<HolidayDays.Day> holidays(String code, int year, Locale locale) {
		String[] path = code.toLowerCase(Locale.ROOT).split("-");
		HolidayManager manager = HolidayManager.getInstance(ManagerParameters.create(path[0]));
		String[] subdivisions = Arrays.copyOfRange(path, 1, path.length);
		Map<LocalDate, String> byDay = new TreeMap<>();
		manager.getHolidays(Year.of(year), HolidayType.PUBLIC_HOLIDAY, subdivisions).stream()
				.sorted()
				.forEach(holiday -> byDay.putIfAbsent(holiday.getActualDate(), name(holiday.getDescription(locale))));
		return byDay.entrySet().stream()
				.filter(entry -> entry.getKey().getYear() == year)
				.limit(Holiday.PER_YEAR_MAX)
				.map(entry -> new HolidayDays.Day(entry.getKey(), entry.getValue()))
				.toList();
	}

	/** The country an instance in [zone] most likely runs in, when rules exist for it. */
	public Optional<String> countryOf(ZoneId zone) {
		return Optional.ofNullable(zone == null ? null : countryOfZone.get(zone.getId()))
				.flatMap(this::normalize);
	}

	private List<Region> readRegions(Locale locale) {
		Collator collator = Collator.getInstance(locale);
		Comparator<Region> byName = Comparator.comparing(Region::name, collator);
		Set<String> codes = HolidayManager.getSupportedCalendarCodes();
		List<Region> countries = new ArrayList<>();
		for (String country : codes) {
			CalendarHierarchy root = hierarchy(country.toLowerCase(Locale.ROOT));
			if (root == null || country.length() != 2) {
				continue;
			}
			String prefix = country.toUpperCase(Locale.ROOT) + "-";
			List<Region> subdivisions = root.getChildren().entrySet().stream()
					.map(child -> new Region(prefix + child.getKey().toUpperCase(Locale.ROOT),
							child.getValue().getDescription(locale), List.of()))
					.sorted(byName)
					.toList();
			countries.add(new Region(country.toUpperCase(Locale.ROOT), countryName(country, locale), subdivisions));
		}
		countries.sort(byName);
		return List.copyOf(countries);
	}

	private static CalendarHierarchy hierarchy(String country) {
		if (country.length() != 2 || !HolidayManager.getSupportedCalendarCodes().stream()
				.anyMatch(country::equalsIgnoreCase)) {
			return null;
		}
		try {
			return HolidayManager.getInstance(ManagerParameters.create(country)).getCalendarHierarchy();
		}
		catch (RuntimeException unreadable) {
			log.warn("[availability] no holiday rules for {}: {}", country, unreadable.getClass().getName());
			return null;
		}
	}

	private static String countryName(String country, Locale locale) {
		String name = Locale.of("", country.toUpperCase(Locale.ROOT)).getDisplayCountry(locale);
		return name.isBlank() ? country.toUpperCase(Locale.ROOT) : name;
	}

	private static String name(String description) {
		String name = description == null || description.isBlank() ? "?" : description.strip();
		return name.length() <= Holiday.NAME_MAX ? name : name.substring(0, Holiday.NAME_MAX);
	}

	private static Map<String, String> readZones() {
		Properties zones = new Properties();
		try (InputStream in = HolidayRules.class.getResourceAsStream(ZONES)) {
			if (in != null) {
				zones.load(new InputStreamReader(in, StandardCharsets.UTF_8));
			}
		}
		catch (IOException unreadable) {
			log.warn("[availability] could not read the zone countries: {}", unreadable.getMessage());
		}
		return zones.entrySet().stream()
				.collect(Collectors.toUnmodifiableMap(entry -> (String) entry.getKey(),
						entry -> (String) entry.getValue()));
	}
}
