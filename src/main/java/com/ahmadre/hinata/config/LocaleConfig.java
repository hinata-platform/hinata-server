package com.ahmadre.hinata.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * Resolves the request locale from the {@code Accept-Language} header so error
 * messages (and any other {@code MessageSource} lookup) come back in the
 * client's language. Unsupported languages fall back to English.
 *
 * <p>The message bundles live in {@code messages.properties} (English, default)
 * and {@code messages_de.properties}; Spring Boot auto-configures the
 * {@code MessageSource} that reads them (UTF-8).
 */
@Configuration
public class LocaleConfig {

	/**
	 * The languages this server can answer in — one per {@code messages_*} and
	 * {@code email-messages_*} bundle pair. English stays the fallback for
	 * anything else a browser asks for.
	 */
	private static final List<Locale> SUPPORTED = List.of(Locale.ENGLISH, Locale.GERMAN,
			Locale.CHINESE, Locale.forLanguageTag("hi"), Locale.forLanguageTag("es"),
			Locale.JAPANESE, Locale.FRENCH, Locale.forLanguageTag("ru"),
			Locale.forLanguageTag("ar"));

	/**
	 * The same list as a {@code @Pattern} regex for the profile endpoints, which
	 * accept a language a user picks by hand. A constant, because an annotation
	 * cannot read the list — and one that {@code ProfileLocaleValidationTest}
	 * pins to the list, so a tenth language cannot be added to one and not the
	 * other.
	 */
	public static final String LANGUAGE_PATTERN = "en|de|zh|hi|es|ja|fr|ru|ar";

	/** The language tags behind {@link #LANGUAGE_PATTERN}, for tests and callers that need the list. */
	public static List<String> supportedLanguages() {
		return SUPPORTED.stream().map(Locale::getLanguage).toList();
	}

	@Bean
	public LocaleResolver localeResolver() {
		AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
		resolver.setSupportedLocales(SUPPORTED);
		resolver.setDefaultLocale(Locale.ENGLISH);
		return resolver;
	}
}
