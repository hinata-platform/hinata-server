package com.ahmadre.hinata.common;

import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * The words we send *to* a person, in the language that person reads.
 *
 * <p>Distinct from every other message lookup in the server, which resolves the
 * language from the request's {@code Accept-Language}. Mail, push and the
 * scheduled digests have no request behind them — they are written by a job, or
 * by somebody else's action, long after the recipient's last visit. The only
 * honest source for their language is the recipient's own stored
 * {@link User#getLocale() locale}.
 *
 * <p>It exists because that language used to be decided by a ternary. Every
 * subject line, every push body and every security alert was written as
 * {@code de(user) ? "…" : "…"}, which is not a language choice but a coin flip
 * between two: the moment the platform learned a third language, every one of
 * those readers would have been handed English. Keys resolve for as many
 * languages as there are bundles, and adding one is adding a file.
 */
@Component
@RequiredArgsConstructor
public class UserWords {

	private final MessageSource messages;

	/**
	 * The recipient's language, or English when they have not chosen one (or
	 * chose one we do not speak — {@code MessageSource} falls back on its own,
	 * but the formatter below wants a concrete locale).
	 */
	public Locale localeOf(User user) {
		String tag = user == null ? null : user.getLocale();
		if (tag == null || tag.isBlank()) {
			return Locale.ENGLISH;
		}
		Locale locale = Locale.forLanguageTag(tag);
		return locale.getLanguage().isEmpty() ? Locale.ENGLISH : locale;
	}

	/** {@code key} rendered for this user, with {@code args} substituted. */
	public String of(User user, String key, Object... args) {
		return messages.getMessage(key, args, key, localeOf(user));
	}

	/**
	 * A number of absence days, written the way [locale] writes numbers.
	 *
	 * <p>Days travel as thousandths of a working day, so half a day is 500 and five twelfths of
	 * twenty days is 8333. Two callers render that — the data export and the notification that
	 * tells somebody their entitlement changed — and they must agree, so the conversion is here.
	 *
	 * <p>The divisor mirrors {@code TimeOffType.DAY}, which lives in the absence-management module
	 * and is not importable from here: the module sits on top of the core and the core knows
	 * nothing about it. {@code TimeOffDaysTest} asserts the two agree.
	 */
	public String timeOffDays(Locale locale, int milliDays) {
		return in(locale, "notify.timeOff.days", milliDays / 1000.0);
	}

	/**
	 * A date, written the way [locale] writes dates.
	 *
	 * <p>Not left to {@code MessageFormat}: a bare placeholder prints a {@code LocalDate} as
	 * {@code 2026-09-18}, which is nobody's spelling, and {@code {0,date,medium}} throws on one
	 * outright because it wants a {@code java.util.Date}. HIN-87 found the other half of the same
	 * trap — a plain placeholder with a {@code Date} invents a time of day.
	 */
	public String date(Locale locale, java.time.LocalDate date) {
		if (date == null) {
			return "";
		}
		return java.time.format.DateTimeFormatter
				.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
				.withLocale(locale == null ? Locale.ENGLISH : locale)
				.format(date);
	}

	/** The bundles themselves, for a document that writes more than one label at a time. */
	public MessageSource messages() {
		return messages;
	}

	/** {@code key} rendered for an explicit locale. */
	public String in(Locale locale, String key, Object... args) {
		return messages.getMessage(key, args, key, locale == null ? Locale.ENGLISH : locale);
	}
}
