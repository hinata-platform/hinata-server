package com.ahmadre.hinata.me;

import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.audit.AuditLogRepository;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.user.User;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Renders a complete, human-readable PDF of a user's personal data for the GDPR
 * self-service data export (Art. 15). Pulls every record we hold that relates to
 * the requesting user — profile, security, preferences, sessions, memberships,
 * authored content and account activity — into one branded document.
 */
@Service
@RequiredArgsConstructor
public class DataExportPdfService {

	private static final Color NAVY = new Color(0x2D, 0x2B, 0x55);
	private static final Color AMBER = new Color(0xD9, 0xA0, 0x32);
	private static final Color INK = new Color(0x23, 0x22, 0x3F);
	private static final Color MUTED = new Color(0x6B, 0x6A, 0x85);
	private static final Color HEAD_BG = new Color(0xF4, 0xF3, 0xEF);
	private static final Color LINE = new Color(0xE7, 0xE5, 0xDE);

	private static final Font H_TITLE = new Font(Font.HELVETICA, 22, Font.BOLD, NAVY);
	private static final Font H_SECTION = new Font(Font.HELVETICA, 13, Font.BOLD, NAVY);
	private static final Font BODY = new Font(Font.HELVETICA, 10, Font.NORMAL, INK);
	private static final Font BODY_MUTED = new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED);
	private static final Font TH = new Font(Font.HELVETICA, 8, Font.BOLD, NAVY);
	private static final Font TD = new Font(Font.HELVETICA, 9, Font.NORMAL, INK);

	private static final DateTimeFormatter DT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneId.of("UTC"));

	private final MeService me;
	private final SessionService sessions;
	private final IssueRepository issues;
	private final IssueCommentRepository comments;
	private final AuditLogRepository auditLogs;

	/** A suggested, filesystem-safe download name for {@code user}'s export. */
	public String fileName(User user) {
		String who = user.getUsername() == null ? user.getId() : user.getUsername();
		who = who.replaceAll("[^A-Za-z0-9._-]", "_");
		String day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC")).format(Instant.now());
		return "hinata-data-export-" + who + "-" + day + ".pdf";
	}

	/** Builds the full PDF document and returns its bytes. */
	public byte[] build(User user) {
		boolean de = "de".equalsIgnoreCase(user.getLocale());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Document doc = new Document(PageSize.A4, 48, 48, 56, 48);
		try {
			PdfWriter.getInstance(doc, out);
			doc.open();

			header(doc, user, de);
			profileSection(doc, user, de);
			securitySection(doc, user, de);
			notificationSection(doc, user, de);
			sessionsSection(doc, user, de);
			membershipsSection(doc, user, de);
			issuesSection(doc, user, de);
			commentsSection(doc, user, de);
			activitySection(doc, user, de);
			footer(doc, de);

			doc.close();
		} catch (Exception e) {
			throw new IllegalStateException("Failed to render data-export PDF", e);
		}
		return out.toByteArray();
	}

	// --- Sections -------------------------------------------------------------

	private void header(Document doc, User user, boolean de) {
		Paragraph brand = new Paragraph("Hinata", new Font(Font.HELVETICA, 12, Font.BOLD, AMBER));
		brand.setSpacingAfter(2);
		doc.add(brand);

		Paragraph title = new Paragraph(de ? "Datenexport" : "Data export", H_TITLE);
		doc.add(title);

		Paragraph sub = new Paragraph(de
				? "Auskunft über deine personenbezogenen Daten gemäß Art. 15 DSGVO"
				: "Disclosure of your personal data under GDPR Art. 15", BODY_MUTED);
		sub.setSpacingAfter(10);
		doc.add(sub);

		PdfPTable meta = new PdfPTable(2);
		meta.setWidthPercentage(100);
		try {
			meta.setWidths(new int[]{1, 3});
		} catch (Exception ignored) {
			// fixed widths are best-effort; default layout is acceptable
		}
		metaRow(meta, de ? "Konto" : "Account", nullSafe(user.getDisplayName()));
		metaRow(meta, de ? "Benutzername" : "Username", nullSafe(user.getUsername()));
		metaRow(meta, de ? "E-Mail" : "Email", nullSafe(user.getEmail()));
		metaRow(meta, de ? "Erstellt am" : "Generated", DT.format(Instant.now()));
		meta.setSpacingBefore(6);
		meta.setSpacingAfter(8);
		doc.add(meta);
		doc.add(rule());
	}

	private void profileSection(Document doc, User user, boolean de) {
		section(doc, de ? "Profil" : "Profile");
		PdfPTable t = kvTable();
		kv(t, "ID", user.getId());
		kv(t, de ? "Anzeigename" : "Display name", nullSafe(user.getDisplayName()));
		kv(t, de ? "Benutzername" : "Username", nullSafe(user.getUsername()));
		kv(t, de ? "Titel" : "Title", nullSafe(user.getTitle()));
		kv(t, de ? "E-Mail" : "Email", nullSafe(user.getEmail()));
		kv(t, de ? "E-Mail bestätigt" : "Email verified", yesNo(user.isEmailVerified(), de));
		if (user.getPendingEmail() != null) {
			kv(t, de ? "Ausstehende E-Mail" : "Pending email", user.getPendingEmail());
		}
		kv(t, de ? "Sprache" : "Locale", nullSafe(user.getLocale()));
		kv(t, de ? "Herkunft" : "Origin", user.getOrigin() == null ? "—" : user.getOrigin().name());
		kv(t, de ? "Rollen" : "Roles",
				String.join(", ", user.getRoles().stream().map(Enum::name).sorted().toList()));
		kv(t, de ? "Aktiv" : "Active", yesNo(user.isActive(), de));
		kv(t, de ? "Konto erstellt" : "Account created", fmt(user.getCreatedAt()));
		doc.add(t);
	}

	private void securitySection(Document doc, User user, boolean de) {
		section(doc, de ? "Sicherheit" : "Security");
		PdfPTable t = kvTable();
		kv(t, de ? "Passwort zuletzt geändert" : "Password last changed", fmt(user.getPasswordChangedAt()));
		kv(t, de ? "Zwei-Faktor (TOTP)" : "Two-factor (TOTP)",
				user.isTotpEnabled() ? (de ? "Aktiviert" : "Enabled") : (de ? "Deaktiviert" : "Disabled"));
		if (user.isTotpEnabled()) {
			kv(t, de ? "2FA aktiviert am" : "2FA enabled at", fmt(user.getTotpEnabledAt()));
		}
		doc.add(t);
	}

	private void notificationSection(Document doc, User user, boolean de) {
		section(doc, de ? "Benachrichtigungseinstellungen" : "Notification preferences");
		NotificationPreferences prefs = me.notificationPreferences(user);
		PdfPTable head = kvTable();
		kv(head, de ? "E-Mail global" : "Email globally", yesNo(prefs.isEmailEnabled(), de));
		kv(head, de ? "Push global" : "Push globally", yesNo(prefs.isPushEnabled(), de));
		doc.add(head);

		PdfPTable t = new PdfPTable(3);
		t.setWidthPercentage(100);
		t.setSpacingBefore(4);
		th(t, de ? "Ereignis" : "Event");
		th(t, "E-Mail");
		th(t, "Push");
		Map<String, NotificationPreferences.Channel> events = prefs.getEvents();
		for (String id : NotificationPreferences.EVENTS) {
			NotificationPreferences.Channel c = events == null ? null : events.get(id);
			td(t, id);
			td(t, yesNo(c != null && c.isEmail(), de));
			td(t, yesNo(c != null && c.isPush(), de));
		}
		doc.add(t);
	}

	private void sessionsSection(Document doc, User user, boolean de) {
		List<RefreshSession> list = sessions.list(user.getId());
		section(doc, (de ? "Aktive Sitzungen" : "Active sessions") + " (" + list.size() + ")");
		if (list.isEmpty()) {
			doc.add(emptyNote(de));
			return;
		}
		PdfPTable t = new PdfPTable(new float[]{2, 3, 3, 3, 3});
		t.setWidthPercentage(100);
		th(t, de ? "Typ" : "Type");
		th(t, de ? "Betriebssystem" : "OS");
		th(t, de ? "Client" : "Client");
		th(t, de ? "Standort" : "Location");
		th(t, de ? "Zuletzt aktiv" : "Last active");
		for (RefreshSession s : list) {
			td(t, s.getKind() == null ? "—" : s.getKind().name());
			td(t, nullSafe(s.getOs()));
			td(t, nullSafe(s.getClient()));
			td(t, nullSafe(s.getLocation()));
			td(t, fmt(s.getLastActiveAt()));
		}
		doc.add(t);
	}

	private void membershipsSection(Document doc, User user, boolean de) {
		List<Team> teams = me.teamsOf(user.getId());
		section(doc, (de ? "Teams" : "Teams") + " (" + teams.size() + ")");
		if (teams.isEmpty()) {
			doc.add(emptyNote(de));
		} else {
			PdfPTable t = new PdfPTable(new float[]{1, 3});
			t.setWidthPercentage(100);
			th(t, de ? "Schlüssel" : "Key");
			th(t, de ? "Name" : "Name");
			for (Team team : teams) {
				td(t, nullSafe(team.getKey()));
				td(t, nullSafe(team.getName()));
			}
			doc.add(t);
		}

		List<Project> projects = me.projectsOf(user);
		section(doc, (de ? "Projekte" : "Projects") + " (" + projects.size() + ")");
		if (projects.isEmpty()) {
			doc.add(emptyNote(de));
			return;
		}
		PdfPTable t = new PdfPTable(new float[]{1, 3, 2});
		t.setWidthPercentage(100);
		th(t, de ? "Schlüssel" : "Key");
		th(t, de ? "Name" : "Name");
		th(t, de ? "Rolle" : "Role");
		for (Project p : projects) {
			td(t, nullSafe(p.getKey()));
			td(t, nullSafe(p.getName()));
			td(t, me.projectRole(p, user.getId()));
		}
		doc.add(t);
	}

	private void issuesSection(Document doc, User user, boolean de) {
		List<Issue> reported = issues.findByReporterIdOrderByCreatedAtDesc(user.getId());
		List<Issue> assigned = issues.findByAssigneeIdOrderByCreatedAtDesc(user.getId());

		section(doc, (de ? "Von dir gemeldete Vorgänge" : "Issues you reported") + " (" + reported.size() + ")");
		issueTable(doc, reported, de);

		section(doc, (de ? "Dir zugewiesene Vorgänge" : "Issues assigned to you") + " (" + assigned.size() + ")");
		issueTable(doc, assigned, de);
	}

	private void issueTable(Document doc, List<Issue> list, boolean de) {
		if (list.isEmpty()) {
			doc.add(emptyNote(de));
			return;
		}
		PdfPTable t = new PdfPTable(new float[]{2, 5, 2, 2, 3});
		t.setWidthPercentage(100);
		th(t, "ID");
		th(t, de ? "Titel" : "Title");
		th(t, de ? "Typ" : "Type");
		th(t, de ? "Status" : "State");
		th(t, de ? "Erstellt" : "Created");
		for (Issue i : list) {
			td(t, nullSafe(i.getReadableId()));
			td(t, nullSafe(i.getTitle()));
			td(t, i.getType() == null ? "—" : i.getType().name());
			td(t, nullSafe(i.getState()));
			td(t, fmt(i.getCreatedAt()));
		}
		doc.add(t);
	}

	private void commentsSection(Document doc, User user, boolean de) {
		List<IssueComment> list = comments.findByAuthorIdOrderByCreatedAtDesc(user.getId());
		section(doc, (de ? "Von dir verfasste Kommentare" : "Comments you wrote") + " (" + list.size() + ")");
		if (list.isEmpty()) {
			doc.add(emptyNote(de));
			return;
		}
		PdfPTable t = new PdfPTable(new float[]{3, 7});
		t.setWidthPercentage(100);
		th(t, de ? "Erstellt" : "Created");
		th(t, de ? "Kommentar" : "Comment");
		for (IssueComment c : list) {
			td(t, fmt(c.getCreatedAt()));
			td(t, nullSafe(c.getText()));
		}
		doc.add(t);
	}

	private void activitySection(Document doc, User user, boolean de) {
		List<AuditLog> logs = auditLogs.findTop200ByActorIdOrderByTimestampDesc(user.getId());
		section(doc, (de ? "Kontoaktivität" : "Account activity") + " (" + logs.size() + ")");
		if (logs.isEmpty()) {
			doc.add(emptyNote(de));
			return;
		}
		PdfPTable t = new PdfPTable(new float[]{3, 4, 2});
		t.setWidthPercentage(100);
		th(t, de ? "Zeitpunkt" : "Time");
		th(t, de ? "Aktion" : "Action");
		th(t, de ? "Ergebnis" : "Outcome");
		for (AuditLog l : logs) {
			td(t, fmt(l.getTimestamp()));
			td(t, l.getAction() == null ? "—" : l.getAction().name());
			td(t, l.getOutcome() == null ? "—" : l.getOutcome().name());
		}
		doc.add(t);
	}

	private void footer(Document doc, boolean de) {
		doc.add(rule());
		Paragraph p = new Paragraph(de
				? "Dieser Export umfasst die personenbezogenen Daten, die Hinata zu deinem Konto speichert. "
						+ "Fragen zur Verarbeitung richtest du an den Verantwortlichen."
				: "This export contains the personal data Hinata stores about your account. "
						+ "Direct any questions about processing to the data controller.", BODY_MUTED);
		p.setSpacingBefore(8);
		doc.add(p);
	}

	// --- Building blocks ------------------------------------------------------

	private void section(Document doc, String title) {
		Paragraph p = new Paragraph(title, H_SECTION);
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
		PdfPCell k = new PdfPCell(new Phrase(key, BODY_MUTED));
		k.setBorder(Rectangle.BOTTOM);
		k.setBorderColor(LINE);
		k.setPadding(5);
		PdfPCell v = new PdfPCell(new Phrase(value == null || value.isBlank() ? "—" : value, BODY));
		v.setBorder(Rectangle.BOTTOM);
		v.setBorderColor(LINE);
		v.setPadding(5);
		t.addCell(k);
		t.addCell(v);
	}

	private void metaRow(PdfPTable t, String key, String value) {
		PdfPCell k = new PdfPCell(new Phrase(key, BODY_MUTED));
		k.setBorder(Rectangle.NO_BORDER);
		k.setPadding(2);
		PdfPCell v = new PdfPCell(new Phrase(value, BODY));
		v.setBorder(Rectangle.NO_BORDER);
		v.setPadding(2);
		t.addCell(k);
		t.addCell(v);
	}

	private void th(PdfPTable t, String label) {
		PdfPCell c = new PdfPCell(new Phrase(label, TH));
		c.setBackgroundColor(HEAD_BG);
		c.setBorderColor(LINE);
		c.setPadding(5);
		t.addCell(c);
	}

	private void td(PdfPTable t, String value) {
		PdfPCell c = new PdfPCell(new Phrase(value == null || value.isBlank() ? "—" : value, TD));
		c.setBorderColor(LINE);
		c.setPadding(5);
		c.setVerticalAlignment(Element.ALIGN_TOP);
		t.addCell(c);
	}

	private Paragraph emptyNote(boolean de) {
		Paragraph p = new Paragraph(de ? "Keine Einträge." : "No entries.", BODY_MUTED);
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

	private String yesNo(boolean value, boolean de) {
		if (de) return value ? "Ja" : "Nein";
		return value ? "Yes" : "No";
	}

	private String nullSafe(String s) {
		return s == null || s.isBlank() ? "—" : s;
	}
}
