package com.ahmadre.hinata.notification;

import org.springframework.context.MessageSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared rig for everything that exercises the transactional e-mail templates:
 * a Thymeleaf engine wired exactly like the application's, plus one realistic
 * model per template.
 *
 * The sample data is deliberately awkward — long signed URLs, a multi-line
 * personal note, an overdue item, an umlauted name — because those are the
 * cases that break a layout, and a preview built from tidy data proves nothing.
 *
 * @see EmailTemplateRenderTest for the assertions
 * @see EmailPreviewTest for the browsable gallery
 */
final class EmailFixtures {

	private EmailFixtures() {
	}

	/** Every template that ships, in the order the gallery should show them. */
	static final List<String> TEMPLATES = List.of(
			"email/notification",
			"email/verify-email",
			"email/invite",
			"email/password-reset",
			"email/email-change-verify",
			"email/approval-request",
			"email/data-report-ready",
			"email/account-activated",
			"email/account-role-changed",
			"email/account-deactivated",
			"email/account-deleted",
			"email/issue-changes",
			"email/weekly-summary");

	private static final String BASE = "https://track.asta.hn";

	private static final String TOKEN =
			"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZGEiLCJuYmYiOjE3NTUyMDAwMDB9.q7Vb3xR2mKpN8sT1uZ";

	/**
	 * The same engine the application builds, minus Spring Boot's autoconfiguration:
	 * classpath template resolution plus the {@code messages,email-messages} bundles
	 * that the {@code #{...}} expressions resolve against.
	 */
	static SpringTemplateEngine engine() {
		GenericApplicationContext context = new GenericApplicationContext();
		context.refresh();

		var resolver = new SpringResourceTemplateResolver();
		resolver.setApplicationContext(context);
		resolver.setPrefix("classpath:/templates/");
		resolver.setSuffix(".html");
		resolver.setTemplateMode(TemplateMode.HTML);
		resolver.setCharacterEncoding("UTF-8");

		var engine = new SpringTemplateEngine();
		engine.setTemplateResolver(resolver);
		engine.setTemplateEngineMessageSource(messages());
		return engine;
	}

	/** Mirrors {@code spring.messages} in application.yml, fallback included. */
	private static MessageSource messages() {
		var source = new ResourceBundleMessageSource();
		source.setBasenames("messages", "email-messages");
		source.setDefaultEncoding("UTF-8");
		// Must match application.yml: with the system fallback on, an English
		// recipient on a German host would silently receive German copy.
		source.setFallbackToSystemLocale(false);
		return source;
	}

	/** Sample model for {@code template} in {@code locale} ("de" / "en"). */
	static Map<String, Object> model(String template, String locale) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("locale", locale);
		m.put("displayName", "Ada Lovelace");
		// Only the variant, exactly as AuthMailService / AdminMailService set it.
		// MailService#render turns that into the cid: source and the band height;
		// the preview swaps in a file path instead. Neither is decided here.
		String variant = mastheadVariant(template);
		if (variant != null) m.put("masthead", variant);
		boolean de = "de".equals(locale);

		switch (template) {
			case "email/notification" -> {
				m.put("headline", de ? "HIN-142 wurde dir zugewiesen" : "HIN-142 was assigned to you");
				m.put("body", de
						? "Marek Wilczyński hat dir \"Kalenderansicht: Woche beginnt am falschen Tag\" "
								+ "zugewiesen. Fällig am 22. August."
						: "Marek Wilczyński assigned you \"Calendar view: week starts on the wrong day\". "
								+ "Due on 22 August.");
				m.put("ctaLink", BASE + "/issues/HIN-142");
				m.put("ctaLabel", de ? "Vorgang öffnen" : "Open issue");
				m.put("eyebrowKey", "email.eyebrow.ISSUE_ASSIGNED");
			}
			case "email/verify-email" -> {
				m.put("verifyUrl", BASE + "/verify-email?token=" + TOKEN);
				m.put("expiresHours", 24);
			}
			case "email/invite" -> {
				m.put("inviteUrl", BASE + "/invite?token=" + TOKEN + "&server=" + BASE);
				m.put("inviterName", "Jördis Brandt");
				m.put("message", de
						? "Hi Ada — wir ziehen das Roadmap-Board diese Woche um.\n"
								+ "Schau dir bitte zuerst HIN-98 an, da hängt der Rest dran."
						: "Hi Ada — we are moving the roadmap board this week.\n"
								+ "Start with HIN-98, everything else hangs off it.");
				m.put("expiresDays", 7);
			}
			case "email/password-reset" -> {
				m.put("resetUrl", BASE + "/reset-password?token=" + TOKEN + "&server=" + BASE);
				m.put("expiresMinutes", 30);
			}
			case "email/email-change-verify" -> {
				m.put("newEmail", "ada.lovelace@asta-hochschule-hannover.de");
				m.put("confirmUrl", BASE + "/confirm-email?token=" + TOKEN);
				m.put("expiresHours", 24);
			}
			case "email/approval-request" -> {
				m.put("displayName", "Jördis Brandt");
				m.put("newUserName", "Ada Lovelace");
				m.put("newUserEmail", "ada.lovelace@asta-hochschule-hannover.de");
				m.put("reviewUrl", BASE + "/admin/users?user=66b1f0c4e2a9");
			}
			case "email/data-report-ready" -> {
				m.put("downloadUrl", BASE + "/api/v1/me/export.pdf?token=" + TOKEN);
				m.put("expiresHours", 72);
			}
			case "email/account-activated" -> m.put("ctaLink", BASE + "/login");
			case "email/account-role-changed" -> {
				m.put("isAdmin", true);
				m.put("roles", de ? "Administrator, Mitglied" : "Administrator, Member");
			}
			case "email/account-deactivated", "email/account-deleted" -> m.put("ctaLink", null);
			case "email/issue-changes" -> {
				m.put("headline", de
						? "HIN-142 · Kalenderansicht: Woche beginnt am falschen Tag"
						: "HIN-142 · Calendar view: week starts on the wrong day");
				m.put("preheader", de
						? "Status: Open → In Arbeit · Priorität: NORMAL → MAJOR"
						: "Status: Open → In Progress · Priority: NORMAL → MAJOR");
				m.put("lines", List.of(
						new IssueChangeRenderer.Line(de ? "Status" : "Status",
								de ? "Open → In Arbeit" : "Open → In Progress"),
						new IssueChangeRenderer.Line(de ? "Priorität" : "Priority",
								"NORMAL → MAJOR"),
						new IssueChangeRenderer.Line(de ? "Fällig" : "Due",
								de ? "20.08.2026 → 23.08.2026" : "Aug 20, 2026 → Aug 23, 2026"),
						new IssueChangeRenderer.Line(de ? "Zuständig" : "Assignees",
								"+Marek Wilczyński, −Jördis Brandt"),
						new IssueChangeRenderer.Line(de ? "Beschreibung" : "Description",
								de ? "geändert" : "changed")));
				// No ctaLabel: the change mail lets the layout fall back to
				// email.cta.open, exactly as it does in production.
				m.put("ctaLink", BASE + "/issues/HIN-142");
			}
			case "email/weekly-summary" -> weekly(m, de);
			default -> throw new IllegalArgumentException("No sample model for " + template);
		}
		return m;
	}

	/** The illustrated band a template asks for, or null for the plain aurora. */
	static String mastheadVariant(String template) {
		return switch (template) {
			case "email/verify-email" -> "welcome";
			case "email/invite" -> "invite";
			default -> null;
		};
	}

	/** Mirrors the flat map that {@code WeeklyDigestJob#mailModel} builds. */
	private static void weekly(Map<String, Object> m, boolean de) {
		m.put("weekRange", de ? "8. Aug – 15. Aug" : "Aug 8 – Aug 15");
		m.put("completed", 17L);
		m.put("created", 23L);
		m.put("focusLabel", "6h 40m");
		m.put("ctaLink", BASE + "/weekly-summary");

		m.put("sprint", Map.of(
				"name", "Sprint 12 · Kalender & Zeiterfassung",
				"day", 6, "days", 10, "issuesDone", 9L, "issuesTotal", 21L));

		m.put("contributors", List.of(
				contributor("Marek Wilczyński", "MW", 7L),
				contributor("Ada Lovelace", "AL", 5L),
				contributor("Jördis Brandt", "JB", 3L)));

		m.put("highlights", List.of(
				issue("HIN-131", de ? "Zeiterfassung: Timer läuft nach Reload weiter"
						: "Time tracking: timer survives a reload"),
				issue("HIN-127", de ? "Gantt-Abhängigkeiten im PDF-Export"
						: "Gantt dependencies in the PDF export"),
				issue("HIN-119", de ? "Board-Filter merkt sich die Auswahl pro Projekt"
						: "Board filter remembers the selection per project")));

		List<Map<String, Object>> upcoming = new ArrayList<>();
		upcoming.add(todo("HIN-142", de ? "Kalenderansicht: Woche beginnt am falschen Tag"
				: "Calendar view: week starts on the wrong day", de ? "13. Aug" : "Aug 13", true));
		upcoming.add(todo("HIN-144", de ? "Wochenbericht als CSV exportieren"
				: "Export the weekly report as CSV", de ? "18. Aug" : "Aug 18", false));
		upcoming.add(todo("HIN-151", de ? "Benachrichtigungsmatrix: Push separat schaltbar"
				: "Notification matrix: toggle push independently", null, false));
		m.put("upcoming", upcoming);
		m.put("upcomingTotal", 8L);
		m.put("upcomingShown", upcoming.size());
		m.put("overdueCount", 1L);
	}

	private static Map<String, Object> contributor(String name, String initials, long completed) {
		return Map.of("displayName", name, "initials", initials, "completed", completed);
	}

	private static Map<String, Object> issue(String readableId, String title) {
		return Map.of("readableId", readableId, "title", title);
	}

	private static Map<String, Object> todo(String id, String title, String due, boolean overdue) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("readableId", id);
		m.put("title", title);
		m.put("due", due);
		m.put("overdue", overdue);
		return m;
	}
}
