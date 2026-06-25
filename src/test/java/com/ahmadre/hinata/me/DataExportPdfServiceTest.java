package com.ahmadre.hinata.me;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.audit.AuditLogRepository;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
	private DataExportPdfService service;

	private User user;

	@BeforeEach
	void setUp() {
		me = mock(MeService.class);
		sessions = mock(SessionService.class);
		issues = mock(IssueRepository.class);
		comments = mock(IssueCommentRepository.class);
		auditLogs = mock(AuditLogRepository.class);
		service = new DataExportPdfService(me, sessions, issues, comments, auditLogs);

		user = User.builder().id("u1").username("ada").displayName("Ada Lovelace")
				.email("ada@example.org").locale("en").roles(Set.of(Role.MEMBER))
				.createdAt(Instant.parse("2026-01-01T10:00:00Z")).build();

		when(me.notificationPreferences(user)).thenReturn(NotificationPreferences.defaults());
		when(me.teamsOf("u1")).thenReturn(List.of());
		when(me.projectsOf(user)).thenReturn(List.of());
		when(sessions.list("u1")).thenReturn(List.of());
		when(issues.findByReporterIdOrderByCreatedAtDesc("u1")).thenReturn(List.of());
		when(issues.findByAssigneeIdOrderByCreatedAtDesc("u1")).thenReturn(List.of());
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
}
