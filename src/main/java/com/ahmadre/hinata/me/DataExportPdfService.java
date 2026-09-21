package com.ahmadre.hinata.me;

import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.audit.AuditLogRepository;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.issue.export.ExportBlock;
import com.ahmadre.hinata.issue.export.ExportDocument;
import com.ahmadre.hinata.issue.export.ExportFonts;
import com.ahmadre.hinata.issue.export.ExportWords;
import com.ahmadre.hinata.issue.export.PdfDocumentRenderer;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.BrandLogoService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders a complete, human-readable PDF of a user's personal data for the GDPR
 * self-service data export (Art. 15). Pulls every record we hold that relates to
 * the requesting user — profile, security, preferences, sessions, memberships,
 * authored content and account activity — into one document.
 *
 * <p>The document is headed by the organization running this instance, not by us:
 * an Art. 15 request is answered by the data controller, and the closing note
 * already sends the reader there with any question about the processing. A
 * masthead naming the software would name a different party than the one the
 * document holds answerable.
 *
 * <p>Laid out by {@link PdfDocumentRenderer}, the server's one PDF layout (HIN-93): this
 * class decides what the report says, the renderer how it looks — the same letterhead and
 * the same fonts an exported issue or a time report has.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataExportPdfService {

	/** What heads and names the file on an instance whose organization has none. */
	private static final String PRODUCT = "Hinata";

	/** Keeps a long legal name from growing into an unwieldy filename. */
	private static final int MAX_SLUG = 48;

	private static final DateTimeFormatter DT = PersonalDataExport.INSTANT;

	private static final String NONE = "—";

	private final MeService me;
	private final SessionService sessions;
	private final IssueRepository issues;
	private final IssueCommentRepository comments;
	private final AuditLogRepository auditLogs;
	private final SettingsService settings;
	private final BrandLogoService brandLogo;
	private final com.ahmadre.hinata.common.UserWords words;
	/** The modules holding personal data of their own; see {@link PersonalDataExport}. */
	private final List<PersonalDataExport> personalData;
	/** Stateless; one layout for every document, see the class comment. */
	private final PdfDocumentRenderer renderer = new PdfDocumentRenderer();

	/**
	 * A suggested, filesystem-safe download name for {@code user}'s export. The
	 * organization leads it for the same reason it heads the page: this file is
	 * the controller's answer to a request against it, and is filed as such —
	 * often next to the answers of other controllers.
	 */
	public String fileName(User user) {
		String who = user.getUsername() == null ? user.getId() : user.getUsername();
		who = who.replaceAll("[^A-Za-z0-9._-]", "_");
		String day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC")).format(Instant.now());
		return slug(organizationName()) + "-data-export-" + who + "-" + day + ".pdf";
	}

	/** Builds the full PDF document and returns its bytes. */
	public byte[] build(User user) {
		// The reader's language, unless a PDF cannot carry its script — a complete
		// report in English beats a correctly-labelled one with every label blank.
		Locale locale = ExportFonts.renderableLocale(
				words.localeOf(user), t(words.localeOf(user), "export.pdf.title"));
		List<ExportBlock> blocks = new ArrayList<>();
		meta(blocks, user, locale);
		profileSection(blocks, user, locale);
		securitySection(blocks, user, locale);
		notificationSection(blocks, user, locale);
		sessionsSection(blocks, user, locale);
		membershipsSection(blocks, user, locale);
		issuesSection(blocks, user, locale);
		commentsSection(blocks, user, locale);
		activitySection(blocks, user, locale);
		for (PersonalDataExport module : personalData) {
			for (PersonalDataExport.Table table : module.tables(user, locale)) {
				moduleTable(blocks, table, locale);
			}
		}
		closing(blocks, locale);

		String controller = organizationName();
		// UTC, as every timestamp in this report: the rows below are stamped by
		// PersonalDataExport.INSTANT, and a footer on another clock would disagree with them.
		ExportDocument document = new ExportDocument(controller, t(locale, "export.pdf.title"),
				t(locale, "export.pdf.subtitle"), blocks, controller, logo(), Instant.now(),
				new ExportWords(words.messages(), locale, ZoneOffset.UTC));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			renderer.render(document, out);
		}
		catch (RuntimeException e) {
			throw new IllegalStateException("Failed to render data-export PDF", e);
		}
		return out.toByteArray();
	}

	// --- Sections -------------------------------------------------------------

	private void meta(List<ExportBlock> blocks, User user, Locale locale) {
		blocks.add(new ExportBlock.KeyValues(List.of(
				kv(t(locale, "export.pdf.account"), user.getDisplayName()),
				kv(t(locale, "export.pdf.username"), user.getUsername()),
				kv(t(locale, "export.pdf.email"), user.getEmail()),
				kv(t(locale, "export.pdf.generated"), DT.format(Instant.now())))));
		blocks.add(new ExportBlock.Rule());
	}

	private void profileSection(List<ExportBlock> blocks, User user, Locale locale) {
		blocks.add(new ExportBlock.Section(t(locale, "export.pdf.profile")));
		List<ExportBlock.KeyValue> rows = new ArrayList<>();
		rows.add(kv("ID", user.getId()));
		rows.add(kv(t(locale, "export.pdf.displayName"), user.getDisplayName()));
		rows.add(kv(t(locale, "export.pdf.username"), user.getUsername()));
		rows.add(kv(t(locale, "export.pdf.jobTitle"), user.getTitle()));
		rows.add(kv(t(locale, "export.pdf.pronouns"), user.getPronouns()));
		rows.add(kv(t(locale, "export.pdf.email"), user.getEmail()));
		rows.add(kv(t(locale, "export.pdf.emailVerified"), yesNo(user.isEmailVerified(), locale)));
		if (user.getPendingEmail() != null) {
			rows.add(kv(t(locale, "export.pdf.pendingEmail"), user.getPendingEmail()));
		}
		rows.add(kv(t(locale, "export.pdf.locale"), user.getLocale()));
		rows.add(kv(t(locale, "export.pdf.origin"), user.getOrigin() == null ? NONE : user.getOrigin().name()));
		rows.add(kv(t(locale, "export.pdf.roles"),
				String.join(", ", user.getRoles().stream().map(Enum::name).sorted().toList())));
		rows.add(kv(t(locale, "export.pdf.active"), yesNo(user.isActive(), locale)));
		rows.add(kv(t(locale, "export.pdf.accountCreated"), fmt(user.getCreatedAt())));
		blocks.add(new ExportBlock.KeyValues(rows));
	}

	private void securitySection(List<ExportBlock> blocks, User user, Locale locale) {
		blocks.add(new ExportBlock.Section(t(locale, "export.pdf.security")));
		List<ExportBlock.KeyValue> rows = new ArrayList<>();
		rows.add(kv(t(locale, "export.pdf.passwordChanged"), fmt(user.getPasswordChangedAt())));
		rows.add(kv(t(locale, "export.pdf.twoFactor"),
				user.isTotpEnabled() ? t(locale, "export.pdf.enabled") : t(locale, "export.pdf.disabled")));
		if (user.isTotpEnabled()) {
			rows.add(kv(t(locale, "export.pdf.twoFactorAt"), fmt(user.getTotpEnabledAt())));
		}
		blocks.add(new ExportBlock.KeyValues(rows));
	}

	private void notificationSection(List<ExportBlock> blocks, User user, Locale locale) {
		blocks.add(new ExportBlock.Section(t(locale, "export.pdf.notifications")));
		NotificationPreferences prefs = me.notificationPreferences(user);
		blocks.add(new ExportBlock.KeyValues(List.of(
				kv(t(locale, "export.pdf.emailGlobally"), yesNo(prefs.isEmailEnabled(), locale)),
				kv(t(locale, "export.pdf.pushGlobally"), yesNo(prefs.isPushEnabled(), locale)))));
		Map<String, NotificationPreferences.Channel> events = prefs.getEvents();
		List<List<String>> rows = new ArrayList<>();
		for (String id : NotificationPreferences.EVENTS) {
			NotificationPreferences.Channel c = events == null ? null : events.get(id);
			rows.add(List.of(id, yesNo(c != null && c.isEmail(), locale), yesNo(c != null && c.isPush(), locale)));
		}
		blocks.add(table(List.of(t(locale, "export.pdf.event"), "E-Mail", "Push"), rows, List.of(1f, 1f, 1f)));
	}

	private void sessionsSection(List<ExportBlock> blocks, User user, Locale locale) {
		List<RefreshSession> list = sessions.list(user.getId());
		blocks.add(new ExportBlock.Section(t(locale, "export.pdf.sessions") + " (" + list.size() + ")"));
		tableOrEmpty(blocks, locale, List.of(t(locale, "export.pdf.type"), t(locale, "export.pdf.os"),
						t(locale, "export.pdf.client"), t(locale, "export.pdf.location"),
						t(locale, "export.pdf.lastActive")),
				list.stream().map(s -> List.of(s.getKind() == null ? NONE : s.getKind().name(), orNone(s.getOs()),
						orNone(s.getClient()), orNone(s.getLocation()), fmt(s.getLastActiveAt()))).toList(),
				List.of(2f, 3f, 3f, 3f, 3f));
	}

	private void membershipsSection(List<ExportBlock> blocks, User user, Locale locale) {
		List<Team> teams = me.teamsOf(user.getId());
		blocks.add(new ExportBlock.Section(t(locale, "export.pdf.teams") + " (" + teams.size() + ")"));
		tableOrEmpty(blocks, locale, List.of(t(locale, "export.pdf.key"), t(locale, "export.pdf.name")),
				teams.stream().map(team -> List.of(orNone(team.getKey()), orNone(team.getName()))).toList(),
				List.of(1f, 3f));

		List<Project> projects = me.projectsOf(user);
		blocks.add(new ExportBlock.Section(t(locale, "export.pdf.projects") + " (" + projects.size() + ")"));
		tableOrEmpty(blocks, locale, List.of(t(locale, "export.pdf.key"), t(locale, "export.pdf.name"),
						t(locale, "export.pdf.role")),
				projects.stream().map(p -> List.of(orNone(p.getKey()), orNone(p.getName()),
						orNone(me.projectRole(p, user.getId())))).toList(),
				List.of(1f, 3f, 2f));
	}

	private void issuesSection(List<ExportBlock> blocks, User user, Locale locale) {
		List<Issue> reported = issues.findByReporterIdOrderByCreatedAtDesc(user.getId());
		List<Issue> assigned = issues.findByAssigneeIdsContainsOrderByCreatedAtDesc(user.getId());

		blocks.add(new ExportBlock.Section(t(locale, "export.pdf.issuesReported") + " (" + reported.size() + ")"));
		issueTable(blocks, reported, locale);

		blocks.add(new ExportBlock.Section(t(locale, "export.pdf.issuesAssigned") + " (" + assigned.size() + ")"));
		issueTable(blocks, assigned, locale);
	}

	private void issueTable(List<ExportBlock> blocks, List<Issue> list, Locale locale) {
		tableOrEmpty(blocks, locale, List.of("ID", t(locale, "export.pdf.jobTitle"), t(locale, "export.pdf.type"),
						t(locale, "export.pdf.issueState"), t(locale, "export.pdf.created")),
				list.stream().map(i -> List.of(orNone(i.getReadableId()), orNone(i.getTitle()),
						i.getType() == null ? NONE : i.getType().name(), orNone(i.getState()),
						fmt(i.getCreatedAt()))).toList(),
				List.of(2f, 5f, 2f, 2f, 3f));
	}

	private void commentsSection(List<ExportBlock> blocks, User user, Locale locale) {
		List<IssueComment> list = comments.findByAuthorIdOrderByCreatedAtDesc(user.getId());
		blocks.add(new ExportBlock.Section(t(locale, "export.pdf.comments") + " (" + list.size() + ")"));
		tableOrEmpty(blocks, locale, List.of(t(locale, "export.pdf.created"), t(locale, "export.pdf.comment")),
				list.stream().map(c -> List.of(fmt(c.getCreatedAt()), orNone(c.getText()))).toList(),
				List.of(3f, 7f));
	}

	private void activitySection(List<ExportBlock> blocks, User user, Locale locale) {
		List<AuditLog> logs = auditLogs.findTop200ByActorIdOrderByTimestampDesc(user.getId());
		blocks.add(new ExportBlock.Section(t(locale, "export.pdf.activity") + " (" + logs.size() + ")"));
		tableOrEmpty(blocks, locale, List.of(t(locale, "export.pdf.time"), t(locale, "export.pdf.action"),
						t(locale, "export.pdf.outcome")),
				logs.stream().map(l -> List.of(fmt(l.getTimestamp()),
						l.getAction() == null ? NONE : l.getAction().name(),
						l.getOutcome() == null ? NONE : l.getOutcome().name())).toList(),
				List.of(3f, 4f, 2f));
	}

	/** A table a module contributed, in this document's letterhead and type. */
	private void moduleTable(List<ExportBlock> blocks, PersonalDataExport.Table table, Locale locale) {
		blocks.add(new ExportBlock.Section(table.title() + " (" + table.rows().size() + ")"));
		List<Float> widths = new ArrayList<>(table.widths().length);
		for (float width : table.widths()) {
			widths.add(width);
		}
		tableOrEmpty(blocks, locale, table.headers(),
				table.rows().stream().map(row -> row.stream().map(DataExportPdfService::orNone).toList()).toList(),
				widths);
		if (table.note() != null) {
			blocks.add(new ExportBlock.Note(table.note()));
		}
	}

	/**
	 * Who answers for the processing, and what produced the file: the reader still needs to
	 * know the software, just not to mistake it for the controller the masthead names.
	 */
	private void closing(List<ExportBlock> blocks, Locale locale) {
		blocks.add(new ExportBlock.Rule());
		blocks.add(new ExportBlock.Note(t(locale, "export.pdf.intro", organizationName())));
		blocks.add(new ExportBlock.Note(t(locale, "export.pdf.credit", PRODUCT)));
	}

	// --- organization branding ------------------------------------------------

	/**
	 * The configured logo, or null. Failure is silence on purpose: a logo an admin mistyped
	 * — or a host that changed the bytes underneath us — must not be what stops someone
	 * exercising a right. The renderer is as forgiving about bytes it cannot draw.
	 */
	private byte[] logo() {
		try {
			return brandLogo.raster().orElse(null);
		}
		catch (Exception e) {
			log.warn("The organization logo was left out of the data export: {}", e.toString());
			return null;
		}
	}

	/**
	 * The organization this instance belongs to, or the product name when it has
	 * none. Swallows a settings read failure for the same reason the logo swallows
	 * its own: branding decorates this document, it does not gate it.
	 */
	private String organizationName() {
		try {
			String name = settings.get().getOrganizationName();
			return name == null || name.isBlank() ? PRODUCT : name.trim();
		} catch (Exception e) {
			return PRODUCT;
		}
	}

	/**
	 * An organization name flattened into a filename. Admins type names in
	 * whatever script they use, while the file travels through mail clients,
	 * archives and file systems that still disagree about everything past ASCII —
	 * so accents are folded ("Müller" becomes "muller") rather than trusted, and a
	 * name that leaves nothing behind falls back to the product.
	 */
	private String slug(String name) {
		String folded = Normalizer.normalize(name.replace("ß", "ss"), Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "")
				.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9]+", "-");
		if (folded.length() > MAX_SLUG) {
			folded = folded.substring(0, MAX_SLUG);
		}
		folded = folded.replaceAll("(^-+|-+$)", "");
		return folded.isBlank() ? PRODUCT.toLowerCase(Locale.ROOT) : folded;
	}

	// --- Building blocks ------------------------------------------------------

	private static ExportBlock.KeyValue kv(String label, String value) {
		return new ExportBlock.KeyValue(label, orNone(value));
	}

	private static ExportBlock.Table table(List<String> headers, List<List<String>> rows, List<Float> widths) {
		return new ExportBlock.Table(headers, rows, widths, Set.of());
	}

	private void tableOrEmpty(List<ExportBlock> blocks, Locale locale, List<String> headers,
			List<List<String>> rows, List<Float> widths) {
		blocks.add(rows.isEmpty() ? new ExportBlock.Note(t(locale, "export.pdf.empty"))
				: table(headers, rows, widths));
	}

	private static String fmt(Instant instant) {
		return instant == null ? NONE : DT.format(instant);
	}

	private String yesNo(boolean value, Locale locale) {
		return t(locale, value ? "export.pdf.yes" : "export.pdf.no");
	}

	/** One label of this document, in the language its reader chose. */
	private String t(Locale locale, String key, Object... args) {
		return words.in(locale, key, args);
	}

	private static String orNone(String s) {
		return s == null || s.isBlank() ? NONE : s;
	}
}
