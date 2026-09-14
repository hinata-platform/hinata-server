package com.ahmadre.hinata.ics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The java.time zone behind the name a calendar wrote into TZID, found without the
 * network and without registering anything in the JVM.
 *
 * <p>Calendars name zones in more ways than the standard allows for:
 *
 * <ul>
 * <li>IANA names ({@code Europe/Berlin}), the way Google and Apple write them;</li>
 * <li>Windows names ({@code W. Europe Standard Time}), the way Exchange and Outlook
 * write them, looked up in the CLDR table {@code ics/windows-zones.tsv};</li>
 * <li>an IANA name behind a vendor prefix, from older Thunderbird and Evolution
 * exports ({@code /mozilla.org/20050126_1/Europe/Berlin}), or Outlook's
 * {@code tzone://Microsoft/Utc};</li>
 * <li>names that mean nothing outside the file ({@code Mitteleuropäische Zeit},
 * {@code Customized Time Zone}). Only the calendar's own VTIMEZONE explains those,
 * and they are not resolved here.</li>
 * </ul>
 */
final class IcsZones {

	private static final int MAX_NAME_LENGTH = 128;

	/** Every IANA id the JDK knows, by its lower-case spelling. */
	private static final Map<String, String> IANA = ianaByLowerCase();

	/** Windows name, lower-cased, to IANA zone. */
	private static final Map<String, ZoneId> WINDOWS = windowsZones();

	private IcsZones() {
	}

	/** The zone [tzid] names, if it names one by itself. */
	static Optional<ZoneId> byName(String tzid) {
		if (tzid == null) {
			return Optional.empty();
		}
		String name = tzid.strip();
		if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
			name = name.substring(1, name.length() - 1).strip();
		}
		if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
			return Optional.empty();
		}
		String key = name.toLowerCase(Locale.ROOT);
		ZoneId windows = WINDOWS.get(key);
		if (windows != null) {
			return Optional.of(windows);
		}
		// The whole name first, then shorter and shorter tails, so that
		// /mozilla.org/20050126_1/America/Argentina/Buenos_Aires still finds the
		// three-part IANA name at its end.
		int from = 0;
		while (from < key.length()) {
			String iana = IANA.get(key.substring(from));
			if (iana != null) {
				return Optional.of(ZoneId.of(iana));
			}
			int slash = key.indexOf('/', from);
			if (slash < 0) {
				break;
			}
			from = slash + 1;
		}
		return Optional.empty();
	}

	/**
	 * The instant a wall-clock time means under [rules], resolved the way RFC 5545
	 * 3.3.5 asks: a time that happens twice, in the hour the clocks go back, is its
	 * first occurrence; a time that never happens, in the hour they go forward, is
	 * read with the offset from before the gap.
	 */
	static Instant instant(LocalDateTime local, ZoneRules rules) {
		List<ZoneOffset> offsets = rules.getValidOffsets(local);
		if (!offsets.isEmpty()) {
			return local.toInstant(offsets.get(0));
		}
		return local.toInstant(rules.getTransition(local).getOffsetBefore());
	}

	private static Map<String, String> ianaByLowerCase() {
		Map<String, String> ids = new HashMap<>();
		for (String id : ZoneId.getAvailableZoneIds()) {
			// With ical4j on the system class path (tests, bootRun) the JDK also loads
			// its zone provider, which adds random "ical4j~<uuid>" ids backed by
			// ical4j's own zone loader. They are nothing a calendar should reach.
			if (id.indexOf('~') < 0) {
				ids.put(id.toLowerCase(Locale.ROOT), id);
			}
		}
		return Map.copyOf(ids);
	}

	private static Map<String, ZoneId> windowsZones() {
		Map<String, ZoneId> zones = new HashMap<>();
		try (InputStream in = IcsZones.class.getResourceAsStream("/ics/windows-zones.tsv")) {
			if (in == null) {
				throw new IllegalStateException("ics/windows-zones.tsv is missing from the classpath");
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				int tab = line.indexOf('\t');
				if (line.startsWith("#") || tab < 0) {
					continue;
				}
				try {
					zones.put(line.substring(0, tab).toLowerCase(Locale.ROOT), ZoneId.of(line.substring(tab + 1)));
				}
				catch (DateTimeException ex) {
					// A zone this JDK's tzdb does not know yet; the name stays unresolved.
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return Map.copyOf(zones);
	}
}
