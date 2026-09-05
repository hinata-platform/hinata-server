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
import java.util.Locale;
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

	/**
	 * The organization the fixture instance belongs to. Long enough and umlauted
	 * enough to catch a footer that assumed a short ASCII name.
	 */
	static final String ORGANIZATION = "AStA der Hochschule Niederrhein";

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

	/**
	 * A {@link SettingsService} that reports the fixture organization, so the
	 * rendered footer and subject tag are the ones a real instance produces.
	 */
	static com.ahmadre.hinata.setup.SettingsService settings() {
		var settings = org.mockito.Mockito.mock(com.ahmadre.hinata.setup.SettingsService.class);
		var stored = new com.ahmadre.hinata.setup.ServerSettings();
		stored.setOrganizationName(ORGANIZATION);
		org.mockito.Mockito.when(settings.get()).thenReturn(stored);
		return settings;
	}

	/** A {@link BrandLogoService} with no organization logo — the default instance. */
	static com.ahmadre.hinata.setup.BrandLogoService brandLogo() {
		var brand = org.mockito.Mockito.mock(com.ahmadre.hinata.setup.BrandLogoService.class);
		org.mockito.Mockito.when(brand.mailBand(org.mockito.ArgumentMatchers.anyString()))
				.thenReturn(java.util.Optional.empty());
		return brand;
	}

	/** Sample model for {@code template} in {@code locale} ("de" / "en"). */
	static Map<String, Object> model(String template, String locale) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("locale", locale);
		m.put("displayName", "Ada Lovelace");
		m.put("organizationName", ORGANIZATION);
		boolean de = "de".equals(locale);

		switch (template) {
			case "email/notification" -> {
				// An issue update, of the notification types this one template serves.
				// It is the only one that carries a diff, and the diff is the reason
				// the template has a change section at all — so the gallery shows that
				// case, exactly as NotificationService composes it: the body is the
				// same change list squeezed onto the one line the push and the bell
				// get, above the diff the mail has room to paint.
				m.put("headline", de ? "HIN-142 aktualisiert" : "HIN-142 updated");
				List<IssueChangeRenderer.Line> lines = changeLines(de);
				m.put("body", renderer().summaryOf(lines));
				m.put("lines", lines);
				m.put("ctaLink", BASE + "/issues/HIN-142");
				m.put("ctaLabel", de ? "Vorgang öffnen" : "Open issue");
				m.put("eyebrowKey", "email.eyebrow.ISSUE_UPDATED");
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
				m.put("lines", changeLines(de));
				// No ctaLabel: the change mail lets the layout fall back to
				// email.cta.open, exactly as it does in production.
				m.put("ctaLink", BASE + "/issues/HIN-142");
			}
			case "email/weekly-summary" -> weekly(m, de);
			default -> throw new IllegalArgumentException("No sample model for " + template);
		}
		return m;
	}

	/**
	 * A change list covering every shape the {@code diff} fragment has to set: a
	 * plain before/after, a list that both gained and lost a member, a field that
	 * was empty before, and a word-level diff of a description.
	 *
	 * <p>Built through the real {@link IssueChangeRenderer} rather than by hand, so
	 * the gallery cannot show a layout the renderer would never produce.
	 */
	static List<IssueChangeRenderer.Line> changeLines(boolean de) {
		return renderer().lines(List.of(
				new FieldChange(IssueChangeDiff.STATE, "Open", "In Progress"),
				new FieldChange(IssueChangeDiff.PRIORITY, "NORMAL", "MAJOR"),
				new FieldChange(IssueChangeDiff.DUE_DATE, "2026-08-20", "2026-08-23"),
				new FieldChange(IssueChangeDiff.ESTIMATE, null, "150"),
				new FieldChange(IssueChangeDiff.ASSIGNEES,
						join("Jördis Brandt", "Ada Lovelace"),
						join("Ada Lovelace", "Marek Wilczyński")),
				new FieldChange(IssueChangeDiff.TAGS, join("frontend"), join("frontend", "regression")),
				new FieldChange(IssueChangeDiff.DESCRIPTION,
						de
								? "Die Wochenansicht beginnt am Sonntag, obwohl das Gebietsschema "
										+ "Montag als ersten Tag der Woche führt. Betrifft nur den "
										+ "Kalender, nicht das Board."
								: "The week view starts on Sunday even though the locale lists "
										+ "Monday as the first day of the week. Only the calendar is "
										+ "affected, not the board.",
						de
								? "Die Wochenansicht beginnt am Sonntag, obwohl das Gebietsschema "
										+ "Montag als ersten Tag der Woche führt. Betrifft den "
										+ "Kalender und den Sprint-Bericht."
								: "The week view starts on Sunday even though the locale lists "
										+ "Monday as the first day of the week. It affects the "
										+ "calendar and the sprint report.")),
				de ? Locale.GERMAN : Locale.ENGLISH);
	}

	/** The renderer with every lookup answered by the id itself — the sample values
	 *  above are already display names, not ids. */
	private static IssueChangeRenderer renderer() {
		var users = org.mockito.Mockito.mock(com.ahmadre.hinata.user.UserRepository.class);
		var sprints = org.mockito.Mockito.mock(com.ahmadre.hinata.board.SprintRepository.class);
		var issues = org.mockito.Mockito.mock(com.ahmadre.hinata.issue.IssueRepository.class);
		var projects = org.mockito.Mockito.mock(com.ahmadre.hinata.project.ProjectRepository.class);
		org.mockito.Mockito.when(users.findById(org.mockito.ArgumentMatchers.anyString()))
				.thenAnswer(call -> java.util.Optional.of(com.ahmadre.hinata.user.User.builder()
						.displayName(call.getArgument(0)).build()));
		return new IssueChangeRenderer(users, sprints, issues, projects,
				com.ahmadre.hinata.common.UserWordsFixture.real());
	}

	private static String join(String... values) {
		return String.join(IssueChangeDiff.LIST_SEPARATOR, values);
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
