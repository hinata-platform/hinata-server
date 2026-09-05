package com.ahmadre.hinata.me;

import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.audit.AuditLogRepository;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.BrandLogoService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.user.User;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.ahmadre.hinata.issue.export.ExportFonts;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataExportPdfService {

	private static final Color NAVY = new Color(0x2D, 0x2B, 0x55);
	private static final Color AMBER = new Color(0xD9, 0xA0, 0x32);
	private static final Color INK = new Color(0x23, 0x22, 0x3F);
	private static final Color MUTED = new Color(0x6B, 0x6A, 0x85);
	private static final Color HEAD_BG = new Color(0xF4, 0xF3, 0xEF);
	private static final Color LINE = new Color(0xE7, 0xE5, 0xDE);

	// The face is chosen from the text being drawn rather than fixed here: the
	// built-in PDF fonts hold Latin-1 only, so a Chinese label set in Helvetica
	// does not fail, it comes out blank. See ExportFonts.
	private static Font brand(String text) {
		return ExportFonts.forText(text, 12, Font.BOLD, AMBER);
	}

	private static Font hTitle(String text) {
		return ExportFonts.forText(text, 22, Font.BOLD, NAVY);
	}

	private static Font hSection(String text) {
		return ExportFonts.forText(text, 13, Font.BOLD, NAVY);
	}

	private static Font body(String text) {
		return ExportFonts.forText(text, 10, Font.NORMAL, INK);
	}

	private static Font bodyMuted(String text) {
		return ExportFonts.forText(text, 9, Font.NORMAL, MUTED);
	}

	private static Font credit(String text) {
		return ExportFonts.forText(text, 8, Font.NORMAL, MUTED);
	}

	private static Font th(String text) {
		return ExportFonts.forText(text, 8, Font.BOLD, NAVY);
	}

	private static Font td(String text) {
		return ExportFonts.forText(text, 9, Font.NORMAL, INK);
	}

	/**
	 * The box the organization's mark is contained in, in points. Contained, never
	 * fitted: a 6:1 wordmark and a square signet arrive through the same setting,
	 * so bounding both edges is the only rule that leaves an unknown aspect ratio
	 * recognizable. Matches the issue export, so the two documents this server
	 * produces share one letterhead.
	 */
	private static final float LOGO_MAX_H = 34f;
	private static final float LOGO_MAX_W = 220f;

	/** What heads and names the file on an instance whose organization has none. */
	private static final String PRODUCT = "Hinata";

	/** Keeps a long legal name from growing into an unwieldy filename. */
	private static final int MAX_SLUG = 48;

	private static final DateTimeFormatter DT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneId.of("UTC"));

	private final MeService me;
	private final SessionService sessions;
	private final IssueRepository issues;
	private final IssueCommentRepository comments;
	private final AuditLogRepository auditLogs;
	private final SettingsService settings;
	private final BrandLogoService brandLogo;
	private final com.ahmadre.hinata.common.UserWords words;

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
		Locale locale = com.ahmadre.hinata.issue.export.ExportFonts.renderableLocale(
				words.localeOf(user), t(words.localeOf(user), "export.pdf.title"));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Document doc = new Document(PageSize.A4, 48, 48, 56, 48);
		try {
			PdfWriter.getInstance(doc, out);
			doc.open();

			header(doc, user, locale);
			profileSection(doc, user, locale);
			securitySection(doc, user, locale);
			notificationSection(doc, user, locale);
			sessionsSection(doc, user, locale);
			membershipsSection(doc, user, locale);
			issuesSection(doc, user, locale);
			commentsSection(doc, user, locale);
			activitySection(doc, user, locale);
			footer(doc, locale);

			doc.close();
		} catch (Exception e) {
			throw new IllegalStateException("Failed to render data-export PDF", e);
		}
		return out.toByteArray();
	}

	// --- Sections -------------------------------------------------------------

	private void header(Document doc, User user, Locale locale) {
		masthead(doc);

		Paragraph title = new Paragraph(t(locale, "export.pdf.title"), hTitle(t(locale, "export.pdf.title")));
		doc.add(title);

		Paragraph sub = new Paragraph(t(locale, "export.pdf.subtitle"), bodyMuted(t(locale, "export.pdf.subtitle")));
		sub.setSpacingAfter(10);
		doc.add(sub);

		PdfPTable meta = new PdfPTable(2);
		meta.setWidthPercentage(100);
		try {
			meta.setWidths(new int[]{1, 3});
		} catch (Exception ignored) {
			// fixed widths are best-effort; default layout is acceptable
		}
		metaRow(meta, t(locale, "export.pdf.account"), nullSafe(user.getDisplayName()));
		metaRow(meta, t(locale, "export.pdf.username"), nullSafe(user.getUsername()));
		metaRow(meta, t(locale, "export.pdf.email"), nullSafe(user.getEmail()));
		metaRow(meta, t(locale, "export.pdf.generated"), DT.format(Instant.now()));
		meta.setSpacingBefore(6);
		meta.setSpacingAfter(8);
		doc.add(meta);
		doc.add(rule());
	}

	private void profileSection(Document doc, User user, Locale locale) {
		section(doc, t(locale, "export.pdf.profile"));
		PdfPTable t = kvTable();
		kv(t, "ID", user.getId());
		kv(t, t(locale, "export.pdf.displayName"), nullSafe(user.getDisplayName()));
		kv(t, t(locale, "export.pdf.username"), nullSafe(user.getUsername()));
		kv(t, t(locale, "export.pdf.jobTitle"), nullSafe(user.getTitle()));
		kv(t, t(locale, "export.pdf.pronouns"), nullSafe(user.getPronouns()));
		kv(t, t(locale, "export.pdf.email"), nullSafe(user.getEmail()));
		kv(t, t(locale, "export.pdf.emailVerified"), yesNo(user.isEmailVerified(), locale));
		if (user.getPendingEmail() != null) {
			kv(t, t(locale, "export.pdf.pendingEmail"), user.getPendingEmail());
		}
		kv(t, t(locale, "export.pdf.locale"), nullSafe(user.getLocale()));
		kv(t, t(locale, "export.pdf.origin"), user.getOrigin() == null ? "—" : user.getOrigin().name());
		kv(t, t(locale, "export.pdf.roles"),
				String.join(", ", user.getRoles().stream().map(Enum::name).sorted().toList()));
		kv(t, t(locale, "export.pdf.active"), yesNo(user.isActive(), locale));
		kv(t, t(locale, "export.pdf.accountCreated"), fmt(user.getCreatedAt()));
		doc.add(t);
	}

	private void securitySection(Document doc, User user, Locale locale) {
		section(doc, t(locale, "export.pdf.security"));
		PdfPTable t = kvTable();
		kv(t, t(locale, "export.pdf.passwordChanged"), fmt(user.getPasswordChangedAt()));
		kv(t, t(locale, "export.pdf.twoFactor"),
				user.isTotpEnabled() ? t(locale, "export.pdf.enabled") : t(locale, "export.pdf.disabled"));
		if (user.isTotpEnabled()) {
			kv(t, t(locale, "export.pdf.twoFactorAt"), fmt(user.getTotpEnabledAt()));
		}
		doc.add(t);
	}

	private void notificationSection(Document doc, User user, Locale locale) {
		section(doc, t(locale, "export.pdf.notifications"));
		NotificationPreferences prefs = me.notificationPreferences(user);
		PdfPTable head = kvTable();
		kv(head, t(locale, "export.pdf.emailGlobally"), yesNo(prefs.isEmailEnabled(), locale));
		kv(head, t(locale, "export.pdf.pushGlobally"), yesNo(prefs.isPushEnabled(), locale));
		doc.add(head);

		PdfPTable t = new PdfPTable(3);
		t.setWidthPercentage(100);
		t.setSpacingBefore(4);
		th(t, t(locale, "export.pdf.event"));
		th(t, "E-Mail");
		th(t, "Push");
		Map<String, NotificationPreferences.Channel> events = prefs.getEvents();
		for (String id : NotificationPreferences.EVENTS) {
			NotificationPreferences.Channel c = events == null ? null : events.get(id);
			td(t, id);
			td(t, yesNo(c != null && c.isEmail(), locale));
			td(t, yesNo(c != null && c.isPush(), locale));
		}
		doc.add(t);
	}

	private void sessionsSection(Document doc, User user, Locale locale) {
		List<RefreshSession> list = sessions.list(user.getId());
		section(doc, t(locale, "export.pdf.sessions") + " (" + list.size() + ")");
		if (list.isEmpty()) {
			doc.add(emptyNote(locale));
			return;
		}
		PdfPTable t = new PdfPTable(new float[]{2, 3, 3, 3, 3});
		t.setWidthPercentage(100);
		th(t, t(locale, "export.pdf.type"));
		th(t, t(locale, "export.pdf.os"));
		th(t, t(locale, "export.pdf.client"));
		th(t, t(locale, "export.pdf.location"));
		th(t, t(locale, "export.pdf.lastActive"));
		for (RefreshSession s : list) {
			td(t, s.getKind() == null ? "—" : s.getKind().name());
			td(t, nullSafe(s.getOs()));
			td(t, nullSafe(s.getClient()));
			td(t, nullSafe(s.getLocation()));
			td(t, fmt(s.getLastActiveAt()));
		}
		doc.add(t);
	}

	private void membershipsSection(Document doc, User user, Locale locale) {
		List<Team> teams = me.teamsOf(user.getId());
		section(doc, t(locale, "export.pdf.teams") + " (" + teams.size() + ")");
		if (teams.isEmpty()) {
			doc.add(emptyNote(locale));
		} else {
			PdfPTable t = new PdfPTable(new float[]{1, 3});
			t.setWidthPercentage(100);
			th(t, t(locale, "export.pdf.key"));
			th(t, t(locale, "export.pdf.name"));
			for (Team team : teams) {
				td(t, nullSafe(team.getKey()));
				td(t, nullSafe(team.getName()));
			}
			doc.add(t);
		}

		List<Project> projects = me.projectsOf(user);
		section(doc, t(locale, "export.pdf.projects") + " (" + projects.size() + ")");
		if (projects.isEmpty()) {
			doc.add(emptyNote(locale));
			return;
		}
		PdfPTable t = new PdfPTable(new float[]{1, 3, 2});
		t.setWidthPercentage(100);
		th(t, t(locale, "export.pdf.key"));
		th(t, t(locale, "export.pdf.name"));
		th(t, t(locale, "export.pdf.role"));
		for (Project p : projects) {
			td(t, nullSafe(p.getKey()));
			td(t, nullSafe(p.getName()));
			td(t, me.projectRole(p, user.getId()));
		}
		doc.add(t);
	}

	private void issuesSection(Document doc, User user, Locale locale) {
		List<Issue> reported = issues.findByReporterIdOrderByCreatedAtDesc(user.getId());
		List<Issue> assigned = issues.findByAssigneeIdsContainsOrderByCreatedAtDesc(user.getId());

		section(doc, t(locale, "export.pdf.issuesReported") + " (" + reported.size() + ")");
		issueTable(doc, reported, locale);

		section(doc, t(locale, "export.pdf.issuesAssigned") + " (" + assigned.size() + ")");
		issueTable(doc, assigned, locale);
	}

	private void issueTable(Document doc, List<Issue> list, Locale locale) {
		if (list.isEmpty()) {
			doc.add(emptyNote(locale));
			return;
		}
		PdfPTable t = new PdfPTable(new float[]{2, 5, 2, 2, 3});
		t.setWidthPercentage(100);
		th(t, "ID");
		th(t, t(locale, "export.pdf.jobTitle"));
		th(t, t(locale, "export.pdf.type"));
		th(t, t(locale, "export.pdf.issueState"));
		th(t, t(locale, "export.pdf.created"));
		for (Issue i : list) {
			td(t, nullSafe(i.getReadableId()));
			td(t, nullSafe(i.getTitle()));
			td(t, i.getType() == null ? "—" : i.getType().name());
			td(t, nullSafe(i.getState()));
			td(t, fmt(i.getCreatedAt()));
		}
		doc.add(t);
	}

	private void commentsSection(Document doc, User user, Locale locale) {
		List<IssueComment> list = comments.findByAuthorIdOrderByCreatedAtDesc(user.getId());
		section(doc, t(locale, "export.pdf.comments") + " (" + list.size() + ")");
		if (list.isEmpty()) {
			doc.add(emptyNote(locale));
			return;
		}
		PdfPTable t = new PdfPTable(new float[]{3, 7});
		t.setWidthPercentage(100);
		th(t, t(locale, "export.pdf.created"));
		th(t, t(locale, "export.pdf.comment"));
		for (IssueComment c : list) {
			td(t, fmt(c.getCreatedAt()));
			td(t, nullSafe(c.getText()));
		}
		doc.add(t);
	}

	private void activitySection(Document doc, User user, Locale locale) {
		List<AuditLog> logs = auditLogs.findTop200ByActorIdOrderByTimestampDesc(user.getId());
		section(doc, t(locale, "export.pdf.activity") + " (" + logs.size() + ")");
		if (logs.isEmpty()) {
			doc.add(emptyNote(locale));
			return;
		}
		PdfPTable t = new PdfPTable(new float[]{3, 4, 2});
		t.setWidthPercentage(100);
		th(t, t(locale, "export.pdf.time"));
		th(t, t(locale, "export.pdf.action"));
		th(t, t(locale, "export.pdf.outcome"));
		for (AuditLog l : logs) {
			td(t, fmt(l.getTimestamp()));
			td(t, l.getAction() == null ? "—" : l.getAction().name());
			td(t, l.getOutcome() == null ? "—" : l.getOutcome().name());
		}
		doc.add(t);
	}

	private void footer(Document doc, Locale locale) {
		doc.add(rule());
		String controller = organizationName();
		Paragraph p = new Paragraph(t(locale, "export.pdf.intro", controller),
				bodyMuted(t(locale, "export.pdf.intro", controller)));
		p.setSpacingBefore(8);
		doc.add(p);

		// The credit the masthead gave up. The reader still needs to know what
		// produced the file — just not to mistake it for who answers for it.
		Paragraph credit = new Paragraph(
				t(locale, "export.pdf.credit", PRODUCT), credit(t(locale, "export.pdf.credit", PRODUCT)));
		credit.setSpacingBefore(4);
		doc.add(credit);
	}

	// --- organization branding ------------------------------------------------

	/**
	 * Who is issuing this document: the organization's logo, its name when there
	 * is no usable logo, and only then the product — an instance that never
	 * completed its setup still has to be able to answer an Art. 15 request.
	 */
	/**
	 * The logo, if there is one, and the controller's name in every case.
	 *
	 * <p>The name is not optional here the way it is on a marketing surface: under
	 * Art. 15 the reader has to be able to tell who issued the document, and a
	 * picture-only signet — a crest, a hexagon, an initial — names nobody. The
	 * closing paragraph already points them at "the data controller"; this is
	 * where that controller is identified.
	 */
	private void masthead(Document doc) {
		logo(doc);
		Paragraph brand = new Paragraph(organizationName(), brand(organizationName()));
		brand.setSpacingAfter(2);
		doc.add(brand);
	}

	/**
	 * Places the configured logo and reports whether it made it onto the page.
	 * Failure is silence on purpose: {@link #build} turns anything thrown into a
	 * failed download, and a logo an admin mistyped — or a host that changed the
	 * bytes underneath us — must not be what stops someone exercising a right.
	 */
	private boolean logo(Document doc) {
		try {
			byte[] png = brandLogo.raster().orElse(null);
			if (png == null || png.length == 0) {
				return false;
			}
			Image image = Image.getInstance(png);
			image.scaleToFit(LOGO_MAX_W, LOGO_MAX_H);
			image.setAlignment(Element.ALIGN_LEFT);
			image.setSpacingAfter(6);
			doc.add(image);
			return true;
		} catch (Exception e) {
			log.warn("The organization logo was left out of the data export: {}", e.toString());
			return false;
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

	private void section(Document doc, String title) {
		Paragraph p = new Paragraph(title, hSection(title));
		p.setSpacingBefore(16);
		p.setSpacingAfter(6);
		doc.add(p);
	}

	private PdfPTable kvTable() {
		PdfPTable t = new PdfPTable(new float[]{2, 5});
		t.setWidthPercentage(100);
		return t;
	}

	private void kv(PdfPTable t, String key, String value) {
		PdfPCell k = new PdfPCell(new Phrase(key, bodyMuted(key)));
		k.setBorder(Rectangle.BOTTOM);
		k.setBorderColor(LINE);
		k.setPadding(5);
		PdfPCell v = new PdfPCell(new Phrase(value == null || value.isBlank() ? "—" : value, body(value)));
		v.setBorder(Rectangle.BOTTOM);
		v.setBorderColor(LINE);
		v.setPadding(5);
		t.addCell(k);
		t.addCell(v);
	}

	private void metaRow(PdfPTable t, String key, String value) {
		PdfPCell k = new PdfPCell(new Phrase(key, bodyMuted(key)));
		k.setBorder(Rectangle.NO_BORDER);
		k.setPadding(2);
		PdfPCell v = new PdfPCell(new Phrase(value, body(value)));
		v.setBorder(Rectangle.NO_BORDER);
		v.setPadding(2);
		t.addCell(k);
		t.addCell(v);
	}

	private void th(PdfPTable t, String label) {
		PdfPCell c = new PdfPCell(new Phrase(label, th(label)));
		c.setBackgroundColor(HEAD_BG);
		c.setBorderColor(LINE);
		c.setPadding(5);
		t.addCell(c);
	}

	private void td(PdfPTable t, String value) {
		PdfPCell c = new PdfPCell(new Phrase(value == null || value.isBlank() ? "—" : value, td(value)));
		c.setBorderColor(LINE);
		c.setPadding(5);
		c.setVerticalAlignment(Element.ALIGN_TOP);
		t.addCell(c);
	}

	private Paragraph emptyNote(Locale locale) {
		Paragraph p = new Paragraph(t(locale, "export.pdf.empty"), bodyMuted(t(locale, "export.pdf.empty")));
		p.setSpacingBefore(2);
		return p;
	}

	private Paragraph rule() {
		Paragraph p = new Paragraph(new Chunk(new com.lowagie.text.pdf.draw.LineSeparator(
				0.6f, 100, LINE, Element.ALIGN_CENTER, -2)));
		p.setSpacingBefore(6);
		return p;
	}

	private String fmt(Instant instant) {
		return instant == null ? "—" : DT.format(instant);
	}

	private String yesNo(boolean value, Locale locale) {
		return t(locale, value ? "export.pdf.yes" : "export.pdf.no");
	}

	/** One label of this document, in the language its reader chose. */
	private String t(Locale locale, String key, Object... args) {
		return words.in(locale, key, args);
	}

	private String nullSafe(String s) {
		return s == null || s.isBlank() ? "—" : s;
	}
}
