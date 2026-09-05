package com.ahmadre.hinata.me;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.audit.AuditLogRepository;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.BrandLogoService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataExportPdfServiceTest {

	private MeService me;
	private SessionService sessions;
	private IssueRepository issues;
	private IssueCommentRepository comments;
	private AuditLogRepository auditLogs;
	private SettingsService settings;
	private BrandLogoService brandLogo;
	private DataExportPdfService service;

	private User user;
	private ServerSettings serverSettings;

	@BeforeEach
	void setUp() {
		me = mock(MeService.class);
		sessions = mock(SessionService.class);
		issues = mock(IssueRepository.class);
		comments = mock(IssueCommentRepository.class);
		auditLogs = mock(AuditLogRepository.class);
		settings = mock(SettingsService.class);
		brandLogo = mock(BrandLogoService.class);
		service = new DataExportPdfService(me, sessions, issues, comments, auditLogs, settings,
				brandLogo, com.ahmadre.hinata.common.UserWordsFixture.real());

		serverSettings = new ServerSettings();
		when(settings.get()).thenReturn(serverSettings);
		when(brandLogo.raster()).thenReturn(Optional.empty());

		user = User.builder().id("u1").username("ada").displayName("Ada Lovelace")
				.email("ada@example.org").locale("en").roles(Set.of(Role.MEMBER))
				.createdAt(Instant.parse("2026-01-01T10:00:00Z")).build();

		when(me.notificationPreferences(user)).thenReturn(NotificationPreferences.defaults());
		when(me.teamsOf("u1")).thenReturn(List.of());
		when(me.projectsOf(user)).thenReturn(List.of());
		when(sessions.list("u1")).thenReturn(List.of());
		when(issues.findByReporterIdOrderByCreatedAtDesc("u1")).thenReturn(List.of());
		when(issues.findByAssigneeIdsContainsOrderByCreatedAtDesc("u1")).thenReturn(List.of());
		when(comments.findByAuthorIdOrderByCreatedAtDesc("u1")).thenReturn(List.of());
		when(auditLogs.findTop200ByActorIdOrderByTimestampDesc("u1")).thenReturn(List.of());
	}

	@Test
	void buildsValidNonEmptyPdf() {
		byte[] pdf = service.build(user);

		assertThat(pdf).isNotEmpty();
		assertThat(pdf.length).isGreaterThan(800);
		assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
	}

	@Test
	void includesPopulatedRecords() {
		Project project = Project.builder().key("HIN").name("Hinata").build();
		Team team = Team.builder().key("CORE").name("Core Team").build();
		Issue issue = Issue.builder().readableId("HIN-1").title("Fix export").state("Open")
				.type(Issue.Type.BUG).createdAt(Instant.parse("2026-02-01T09:00:00Z")).build();
		IssueComment comment = IssueComment.builder().text("Looks good to me")
				.createdAt(Instant.parse("2026-02-02T09:00:00Z")).build();
		AuditLog log = AuditLog.builder().action(AuditAction.DATA_EXPORT_REQUESTED)
				.timestamp(Instant.parse("2026-02-03T09:00:00Z")).build();

		when(me.teamsOf("u1")).thenReturn(List.of(team));
		when(me.projectsOf(user)).thenReturn(List.of(project));
		when(me.projectRole(project, "u1")).thenReturn("Member");
		when(issues.findByReporterIdOrderByCreatedAtDesc("u1")).thenReturn(List.of(issue));
		when(comments.findByAuthorIdOrderByCreatedAtDesc("u1")).thenReturn(List.of(comment));
		when(auditLogs.findTop200ByActorIdOrderByTimestampDesc("u1")).thenReturn(List.of(log));

		byte[] pdf = service.build(user);

		assertThat(pdf).isNotEmpty();
		assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
	}

	@Test
	void buildsFilesystemSafeFileName() {
		assertThat(service.fileName(user)).startsWith("hinata-data-export-ada-").endsWith(".pdf");
	}

	@Test
	void namesTheFileAfterTheOrganization() {
		serverSettings.setOrganizationName("AStA der Hochschule Niederrhein");

		String name = service.fileName(user);

		assertThat(name).startsWith("asta-der-hochschule-niederrhein-data-export-ada-").endsWith(".pdf");
		assertThat(name).matches("[a-z0-9][A-Za-z0-9._-]*\\.pdf");
	}

	@Test
	void foldsAccentsOutOfTheFileName() {
		serverSettings.setOrganizationName("Müller & Söhne GmbH");

		assertThat(service.fileName(user)).startsWith("muller-sohne-gmbh-data-export-ada-");
	}

	@Test
	void embedsTheOrganizationLogoWhenThereIsOne() {
		when(brandLogo.raster()).thenReturn(Optional.of(png(240, 80)));

		byte[] withLogo = service.build(user);

		assertThat(new String(withLogo, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
		assertThat(new String(withLogo, StandardCharsets.ISO_8859_1)).contains("/Image");
	}

	@Test
	void rendersWithoutALogoAndWithoutAnOrganization() {
		// Nothing configured at all: still a document, headed by the product.
		byte[] plain = service.build(user);

		assertThat(new String(plain, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
		assertThat(new String(plain, StandardCharsets.ISO_8859_1)).doesNotContain("/Image");
	}

	@Test
	void survivesUnreadableLogoBytes() {
		// A raster that no decoder accepts must cost the letterhead, not the export.
		when(brandLogo.raster()).thenReturn(Optional.of(new byte[] { 1, 2, 3, 4 }));

		byte[] pdf = service.build(user);

		assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
	}

	private static byte[] png(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		var g = image.createGraphics();
		g.setColor(Color.ORANGE);
		g.fillRect(0, 0, width, height);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(image, "png", out);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		return out.toByteArray();
	}
}
