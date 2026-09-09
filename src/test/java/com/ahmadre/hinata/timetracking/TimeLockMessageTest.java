package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.common.UserWordsFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a person is actually told when they try to change a frozen day.
 *
 * <p>The lock is a date. It has no time of day, and the sentence must not
 * invent one: {@code MessageFormat} renders a bare <code>{0}</code> holding a
 * {@link Date} in the locale's date <em>and time</em> style, which produced
 * "Entries before 9/9/26, 12:00 AM are locked" -- read literally, that says the
 * ninth is locked until midnight, which is not what the rule does. The bundles
 * therefore spell the placeholder {@code {0,date,medium}}.
 *
 * <p>Asserted against the real bundles in every language, because this is the
 * kind of detail that survives a code review and is only ever caught by reading
 * the sentence. Nothing here asserts on digits: Arabic renders the year as
 * ٢٠٢٦, and a test that demanded "2026" would be pinning English, not the rule.
 */
class TimeLockMessageTest {

	private static final String KEY = "error.time.locked";

	private static Date atUtcMidnight(LocalDate day) {
		return Date.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
	}

	@ParameterizedTest
	@ValueSource(strings = { "en", "de", "ar", "es", "fr", "hi", "ja", "ru", "zh" })
	@DisplayName("the lock sentence names a day, never a time of day")
	void theLockSentenceNamesADayNeverATimeOfDay(String language) {
		UserWords words = UserWordsFixture.real();

		String sentence = words.in(Locale.of(language), KEY, atUtcMidnight(LocalDate.of(2026, 9, 9)));

		assertThat(sentence)
				.as("the key resolved to a sentence")
				.doesNotContain(KEY)
				.as("the placeholder was substituted")
				.doesNotContain("{0")
				// A clock separator would mean either the locale's date-TIME
				// style (the bug) or Date.toString() leaking through -- both
				// say midnight, which is not part of a date-only rule.
				.as("no clock time: a lock date has none")
				.doesNotContain(":")
				.as("no ISO string: that was what formatting the Date fixed")
				.doesNotContain("2026-09-09");
	}

	@ParameterizedTest
	@ValueSource(strings = { "en", "de", "ar", "es", "fr", "hi", "ja", "ru", "zh" })
	@DisplayName("the date reaches the sentence rather than being dropped")
	void theDateReachesTheSentence(String language) {
		UserWords words = UserWordsFixture.real();
		Locale locale = Locale.of(language);

		// Two different days must read differently. This proves substitution in
		// every script without asserting on a single digit, and it catches a
		// pattern that quietly swallowed its argument.
		assertThat(words.in(locale, KEY, atUtcMidnight(LocalDate.of(2026, 9, 9))))
				.isNotEqualTo(words.in(locale, KEY, atUtcMidnight(LocalDate.of(2026, 1, 31))));
	}

	@ParameterizedTest
	@ValueSource(strings = { "en", "de" })
	@DisplayName("the day named is the lock date, not the one before it")
	void theDayNamedIsTheLockDate(String language) {
		UserWords words = UserWordsFixture.real();

		// The JVM runs in UTC (HinataServerApplication), so UTC midnight
		// renders on its own calendar day. In a negative-offset default zone it
		// would silently name the 8th -- worth pinning rather than assuming.
		assertThat(words.in(Locale.of(language), KEY, atUtcMidnight(LocalDate.of(2026, 9, 9))))
				.contains("9")
				.doesNotContain("8");
	}
}
