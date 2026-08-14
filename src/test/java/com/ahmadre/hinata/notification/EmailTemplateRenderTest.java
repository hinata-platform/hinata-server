package com.ahmadre.hinata.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the transactional e-mail templates against the failures that are
 * invisible until a real mail lands in someone's inbox: a template that no
 * longer parses, a message key that was renamed on one side of the bundle, a
 * locale that silently falls back to English, or a CTA that renders without its
 * link. Runs on the same Spring/SpEL Thymeleaf engine the application uses.
 */
class EmailTemplateRenderTest {

	private final SpringTemplateEngine engine = EmailFixtures.engine();

	/**
	 * Rendering is the only thing exercised here, and it touches none of the
	 * collaborators — going through the real service means the Locale derivation
	 * and the masthead injection are under test instead of re-implemented.
	 */
	private final MailService mail = new MailService(null, null, null);

	static List<String> templates() {
		return EmailFixtures.TEMPLATES;
	}

	@ParameterizedTest(name = "{0} renders in both locales")
	@MethodSource("templates")
	void rendersInBothLocales(String template) {
		for (String locale : List.of("de", "en")) {
			String html = render(template, locale);

			assertThat(html)
					.as("%s (%s) must be a complete document", template, locale)
					.startsWith("<!DOCTYPE html>")
					.contains("<html")
					.contains("</html>");
			assertThat(html)
					.as("%s (%s) must carry the brand chrome", template, locale)
					.contains("Hinata")
					.contains("cid:") // the inlined masthead is referenced by Content-ID
					.doesNotContain("hn-canvas\" style=\"background:#F2F1F8"); // the retired palette
			assertThat(html)
					.as("%s (%s) must not leak an unresolved message key", template, locale)
					.doesNotContain("??email.")
					.doesNotContain("email.eyebrow.");
			assertThat(html)
					.as("%s (%s) must not leak an unrendered expression", template, locale)
					// Leading space on purpose: `width:`/`max-width:` end in "th:".
					.doesNotContain(" th:")
					.doesNotContain("data-th-")
					.doesNotContain("xmlns:th")
					.doesNotContain("${");
		}
	}

	/**
	 * The whole point of the message bundle: a German recipient gets German. A
	 * missing {@code _de} key, a wrong Context locale or
	 * {@code fallback-to-system-locale} creeping back on would all show up here.
	 */
	@Test
	void germanRecipientsGetGermanCopy() {
		assertThat(render("email/password-reset", "de"))
				.contains("Passwort zurücksetzen")
				.contains("Diese Nachricht wurde automatisch");
		assertThat(render("email/password-reset", "en"))
				.contains("Reset your password")
				.contains("This is an automated message")
				.doesNotContain("Passwort");
	}

	/** Arguments must reach MessageFormat rather than printing as {0}. */
	@Test
	void interpolatesMessageArguments() {
		assertThat(render("email/verify-email", "de"))
				.contains("Ada Lovelace")
				.contains("24 Stunden gültig")
				.doesNotContain("{0}");
		assertThat(render("email/invite", "en"))
				.contains("Jördis Brandt has invited you")
				.contains("valid for 7 days");
	}

	/** The eyebrow key is resolved through preprocessing, not printed raw. */
	@Test
	void notificationShowsItsTypeLabel() {
		assertThat(render("email/notification", "de")).contains("Dir zugewiesen");
		assertThat(render("email/notification", "en")).contains("Assigned to you");
	}

	/** Every CTA must carry a real href, and the copy-paste fallback with it. */
	@Test
	void ctaLinksAreRendered() {
		String html = render("email/password-reset", "de");
		assertThat(html)
				.contains("href=\"https://track.asta.hn/reset-password?token=")
				.contains("Button funktioniert nicht?");
	}

	/**
	 * Mails whose action link is absent (deactivated, deleted) must render the
	 * body without an empty button — {@code th:if} on the fragment call, not a
	 * button pointing at nothing.
	 */
	@Test
	void omitsTheButtonWhenThereIsNoLink() {
		assertThat(render("email/account-deactivated", "de"))
				.doesNotContain("border-radius:999px;background:#2D2B55");
		assertThat(render("email/account-activated", "de"))
				.contains("border-radius:999px;background:#2D2B55");
	}

	/** The digest is the only mail with lists; empty ones must not blow up. */
	@Test
	void weeklySummaryRendersItsSections() {
		String html = render("email/weekly-summary", "de");
		assertThat(html)
				.contains("Aktiver Sprint")
				.contains("Top-Mitwirkende")
				.contains("Marek Wilczyński")
				.contains("HIN-142")
				.contains("1 überfällig");
	}

	private String render(String template, String locale) {
		return mail.render(engine, template, EmailFixtures.model(template, locale));
	}
}
