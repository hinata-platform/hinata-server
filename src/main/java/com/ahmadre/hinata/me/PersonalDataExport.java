package com.ahmadre.hinata.me;

import com.ahmadre.hinata.user.User;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * A module's contribution to a person's data export (Art. 15 and 20 DSGVO).
 *
 * <p>Inverted on purpose, the way {@code SettingsPrefill} and
 * {@code SettingsGuard} are: {@code me} renders the export and must not know the
 * modules that hold personal data, or switching one off — or deleting its package —
 * would mean editing the account code. A module implements this and the export
 * picks it up; the export itself stays one document with one letterhead.
 */
public interface PersonalDataExport {

	/**
	 * How a point in time reads in the report — one format for the host's tables and
	 * every module's, so one document does not show the same kind of value two ways.
	 */
	DateTimeFormatter INSTANT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
			.withZone(ZoneOffset.UTC);

	/** {@link #INSTANT}, with the report's mark for a missing value. */
	static String instant(Instant value) {
		return value == null ? "—" : INSTANT.format(value);
	}

	/** The key the module's data appears under in the JSON export. */
	String key();

	/**
	 * The module's data about {@code user}, as plain maps and lists for JSON.
	 *
	 * <p>Bounded by the module. A history too long for one document is capped with
	 * a flag saying so and where the complete record can be had — never cut silently.
	 */
	Object data(User user);

	/** The same data as tables for the PDF report, labelled in {@code locale}. */
	List<Table> tables(User user, Locale locale);

	/**
	 * One titled table of the PDF report. {@code widths} are relative column
	 * widths, one per header; {@code note} is shown beneath it when not null — the
	 * place a cap says what was left out.
	 */
	record Table(String title, List<String> headers, float[] widths, List<List<String>> rows,
			String note) {
	}
}
