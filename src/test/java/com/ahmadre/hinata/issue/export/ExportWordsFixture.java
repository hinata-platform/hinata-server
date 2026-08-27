package com.ahmadre.hinata.issue.export;

import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.time.ZoneId;
import java.util.Locale;

/**
 * The {@link ExportWords} an export test renders with.
 *
 * <p>Backed by the application's real {@code messages.properties} rather than a
 * stub, on purpose: a stub answers every key, so a test using one would keep
 * passing after a label was renamed in the code and not in the bundle — and the
 * only place that mistake would then surface is a document in somebody's hands,
 * showing them {@code export.field.dueDate}.
 */
final class ExportWordsFixture {

	/** Berlin, so a test can tell a localized stamp from a UTC one at a glance. */
	static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

	private ExportWordsFixture() {
	}

	static ExportWords english() {
		return new ExportWords(bundle(), Locale.ENGLISH, ZoneId.of("UTC"));
	}

	static ExportWords german() {
		return new ExportWords(bundle(), Locale.GERMAN, BERLIN);
	}

	static ExportWords of(Locale locale, ZoneId zone) {
		return new ExportWords(bundle(), locale, zone);
	}

	static MessageSource bundle() {
		ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
		messages.setBasename("messages");
		messages.setDefaultEncoding("UTF-8");
		// Without this a machine whose own locale is German answers an English
		// lookup out of messages_de.properties, and the suite passes or fails
		// depending on whose laptop it runs on.
		messages.setFallbackToSystemLocale(false);
		return messages;
	}
}
