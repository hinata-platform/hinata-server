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

	@Bean
	public LocaleResolver localeResolver() {
		AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
		resolver.setSupportedLocales(SUPPORTED);
		resolver.setDefaultLocale(Locale.ENGLISH);
		return resolver;
	}
}
