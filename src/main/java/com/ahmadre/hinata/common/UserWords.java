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

	/** {@code key} rendered for an explicit locale. */
	public String in(Locale locale, String key, Object... args) {
		return messages.getMessage(key, args, key, locale == null ? Locale.ENGLISH : locale);
	}
}
