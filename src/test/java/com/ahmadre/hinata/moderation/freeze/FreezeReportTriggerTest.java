package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.article.ArticleRepository;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.moderation.ModerationCategory;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.moderation.escalation.ModerationEscalation;
import com.ahmadre.hinata.moderation.report.ContentReport;
import com.ahmadre.hinata.moderation.report.ContentReportRepository;
import com.ahmadre.hinata.moderation.report.ContentReportService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a report does, and — more importantly — what it does not do.
 *
 * <p>{@code ContentReportService}'s own javadoc says no content is hidden on a
 * report, because "a system that lets a handful of them remove a colleague's work
 * is a system whose moderation can be aimed". Freezing on a single unverified
 * report contradicts that, deliberately: leaving suspected child sexual content up
 * until an admin wakes is the worse outcome. The tests here pin the boundary that
 * makes the exception defensible rather than a hole — one category, a tight daily
 * budget, and an attributable row.
 */
class FreezeReportTriggerTest {

	private ContentReportRepository reports;
	private IssueService issues;
	private IssueCommentRepository comments;
	private FrozenContentRepository registry;
	private FrozenContentService frozen;
	private NotificationService notifications;
	private RecordingEscalation escalation;
	private ContentReportService service;

	private final List<FrozenContent> stored = new ArrayList<>();
	private final User reporter = user("u-reporter");
	private final User author = user("u-author");

	@BeforeEach
	void setUp() {
		reports = mock(ContentReportRepository.class);
		issues = mock(IssueService.class);
		comments = mock(IssueCommentRepository.class);
		registry = mock(FrozenContentRepository.class);
		notifications = mock(NotificationService.class);
		escalation = new RecordingEscalation();

		Issue issue = Issue.builder().id("i-1").projectId("p-1").readableId("HIN-1")
				.reporterId("u-author").attachments(new ArrayList<>()).createdAt(Instant.now()).build();
		when(issues.getForUser(eq("i-1"), any())).thenReturn(issue);
		when(comments.findById("c-1")).thenReturn(Optional.of(IssueComment.builder()
				.id("c-1").issueId("i-1").authorId("u-author").text("reported")
				.createdAt(Instant.now()).build()));
		when(reports.existsByReporterIdAndTargetTypeAndTargetIdAndState(any(), any(), any(), any()))
				.thenReturn(false);
		when(reports.save(any())).thenAnswer(call -> {
			ContentReport row = call.getArgument(0);
			row.setId("r-1");
			return row;
		});
		when(registry.findByUnfrozenAtIsNull()).thenReturn(List.of());
		when(registry.findByTargetTypeAndTargetId(any(), any())).thenReturn(Optional.empty());
		when(registry.save(any())).thenAnswer(call -> {
			FrozenContent row = call.getArgument(0);
			stored.add(row);
			doReturn(List.copyOf(stored)).when(registry).findByUnfrozenAtIsNull();
			return row;
		});
		UserRepository users = mock(UserRepository.class);
		when(users.findByRolesContainingAndActiveIsTrue(Role.ADMIN))
				.thenReturn(List.of(user("u-admin")));

		frozen = new FrozenContentService(registry, mock(AuditService.class, RETURNS_DEEP_STUBS));
		frozen.refresh();

		service = new ContentReportService(reports, issues, comments,
				mock(ArticleRepository.class), mock(ProjectService.class), mock(TeamService.class),
				users, mock(ModerationService.class), notifications,
				mock(AuditService.class, RETURNS_DEEP_STUBS), frozen, List.of(escalation));
	}

	// --- what freezes ------------------------------------------------------------

	@Test
	void aChildSexualContentReportFreezesItsTarget() {
		service.file(reporter, ContentReport.TargetType.COMMENT, "c-1", null,
				ContentReport.ReportReason.SEXUAL_MINORS, null);

		assertThat(frozen.isFrozen(FrozenTargetType.COMMENT, "c-1")).isTrue();
	}

	/**
	 * Freeze first, then the report row. Freezing and then failing to file leaves
	 * content unreachable with no report, which is safe and visible in the audit log;
	 * filing and then failing to freeze leaves suspected material up, which is the
	 * outcome the whole mechanism exists to prevent.
	 */
	@Test
	void theFreezeHappensBeforeTheReportRowIsSaved() {
		// doThrow, not when(...): re-stubbing with when() would *invoke* the existing
		// answer with a null argument first.
		doThrow(new IllegalStateException("mongo down")).when(reports).save(any());

		assertThatThrownBy(() -> service.file(reporter, ContentReport.TargetType.COMMENT, "c-1",
				null, ContentReport.ReportReason.SEXUAL_MINORS, null))
				.isInstanceOf(IllegalStateException.class);

		assertThat(frozen.isFrozen(FrozenTargetType.COMMENT, "c-1")).isTrue();
	}

	/** Every freeze names the account that caused it, and the report it came with. */
	@Test
	void theFreezeIsAttributableToItsReporterAndReport() {
		service.file(reporter, ContentReport.TargetType.COMMENT, "c-1", null,
				ContentReport.ReportReason.SEXUAL_MINORS, null);

		assertThat(stored).isNotEmpty();
		assertThat(stored.getFirst().getReporterId()).isEqualTo("u-reporter");
		assertThat(stored.getFirst().getReportId()).isEqualTo("r-1");
		assertThat(stored.getFirst().getCategory()).isEqualTo(ModerationCategory.SEXUAL_MINORS);
	}

	// --- what does not freeze -----------------------------------------------------

	/**
	 * {@code urgent()} covers malware too, and freezing on it would be an aimable
	 * weapon for no safety gain: a malicious file is refused at upload and never
	 * persisted, and the malware verdict "comes from a scanner rather than a
	 * judgement about expression, so it is never routed to a content moderator".
	 * There is nothing to preserve and nothing for a human to decide.
	 */
	@Test
	void aMalwareReportDoesNotFreezeEvenThoughItIsUrgent() {
		service.file(reporter, ContentReport.TargetType.COMMENT, "c-1", null,
				ContentReport.ReportReason.MALWARE, null);

		assertThat(frozen.isFrozen(FrozenTargetType.COMMENT, "c-1")).isFalse();
		assertThat(stored).isEmpty();
	}

	@Test
	void anOrdinaryHarassmentReportDoesNotFreeze() {
		service.file(reporter, ContentReport.TargetType.COMMENT, "c-1", null,
				ContentReport.ReportReason.HARASSMENT, null);

		assertThat(frozen.isFrozen(FrozenTargetType.COMMENT, "c-1")).isFalse();
	}

	/**
	 * Over the daily freeze budget the report is still filed in full — it simply does
	 * not freeze. Discarding the notice would be the worst of both worlds: no freeze
	 * and no record that somebody tried to raise one.
	 */
	@Test
	void overTheDailyFreezeBudgetTheReportIsStillFiledButDoesNotFreeze() {
		for (int i = 0; i < ContentReportService.FREEZING_REPORTS_PER_DAY; i++) {
			String commentId = "c-budget-" + i;
			when(comments.findById(commentId)).thenReturn(Optional.of(IssueComment.builder()
					.id(commentId).issueId("i-1").authorId("u-author").text("x")
					.createdAt(Instant.now()).build()));
			service.file(reporter, ContentReport.TargetType.COMMENT, commentId, null,
					ContentReport.ReportReason.SEXUAL_MINORS, null);
		}

		ContentReport overBudget = service.file(reporter, ContentReport.TargetType.COMMENT, "c-1",
				null, ContentReport.ReportReason.SEXUAL_MINORS, null);

		assertThat(overBudget).isNotNull();
		assertThat(overBudget.getState()).isEqualTo(ContentReport.State.OPEN);
		assertThat(frozen.isFrozen(FrozenTargetType.COMMENT, "c-1")).isFalse();
	}

	// --- notification + escalation --------------------------------------------------

	/**
	 * The admin notice about a frozen target carries no label. The label is an
	 * article title or a file name — for this category potentially the violating
	 * material itself — and this notice becomes a persisted row, an SMTP mail and a
	 * push body on every admin's lock screen, none of which can be recalled.
	 */
	@Test
	void theAdminNotificationForAFrozenTargetCarriesNoLabel() {
		service.file(reporter, ContentReport.TargetType.COMMENT, "c-1", null,
				ContentReport.ReportReason.SEXUAL_MINORS, null);

		ArgumentCaptor<String> label = ArgumentCaptor.forClass(String.class);
		verify(notifications).notifyAdminsContentReported(any(), any(), label.capture(),
				anyBoolean(), anyString());
		assertThat(label.getValue()).isNull();
	}

	/** An unfrozen report still tells the moderator what it is about. */
	@Test
	void theAdminNotificationForAnUnfrozenTargetKeepsItsLabel() {
		service.file(reporter, ContentReport.TargetType.COMMENT, "c-1", null,
				ContentReport.ReportReason.HARASSMENT, null);

		ArgumentCaptor<String> label = ArgumentCaptor.forClass(String.class);
		verify(notifications).notifyAdminsContentReported(any(), any(), label.capture(),
				anyBoolean(), anyString());
		assertThat(label.getValue()).isEqualTo("HIN-1");
	}

	/**
	 * Escalation triggers on {@code urgent()} — malware included — because telling a
	 * human that something needs looking at is right for a malware report even though
	 * there is nothing to freeze. Removing someone's content is not.
	 */
	@Test
	void anUrgentReportEscalatesEvenWhenItDoesNotFreeze() {
		service.file(reporter, ContentReport.TargetType.COMMENT, "c-1", null,
				ContentReport.ReportReason.MALWARE, null);

		assertThat(escalation.events).hasSize(1);
		assertThat(escalation.events.getFirst().category()).isEqualTo(ModerationCategory.MALWARE);
		assertThat(escalation.events.getFirst().reference()).isEqualTo("comment:c-1");
	}

	@Test
	void anOrdinaryReportDoesNotEscalate() {
		service.file(reporter, ContentReport.TargetType.COMMENT, "c-1", null,
				ContentReport.ReportReason.SPAM, null);

		assertThat(escalation.events).isEmpty();
	}

	/** The escalation carries no note and no label — only a pointer. */
	@Test
	void theEscalationForAReportCarriesNeitherTheNoteNorTheLabel() {
		service.file(reporter, ContentReport.TargetType.COMMENT, "c-1", null,
				ContentReport.ReportReason.SEXUAL_MINORS, "the reporter's own words");

		assertThat(escalation.events).hasSize(1);
		assertThat(escalation.events.getFirst().toString())
				.doesNotContain("the reporter's own words")
				.doesNotContain("HIN-1");
	}

	// --- unfreeze ---------------------------------------------------------------------

	@Test
	void unfreezingWithoutANoteIsRefused() {
		service.file(reporter, ContentReport.TargetType.COMMENT, "c-1", null,
				ContentReport.ReportReason.SEXUAL_MINORS, null);

		assertThatThrownBy(() -> frozen.unfreeze(user("u-admin"), FrozenTargetType.COMMENT, "c-1", null))
				.isInstanceOf(ApiException.class);
		assertThat(frozen.isFrozen(FrozenTargetType.COMMENT, "c-1")).isTrue();
	}

	/**
	 * Dismissing the report does not release the content. "This report was
	 * malicious" and "this content is safe to show" are different claims, and a
	 * moderator can only make the first — they have not seen the content and must
	 * not. Keeping them separate is what stops "dismiss" becoming the one-click way
	 * to look.
	 */
	@Test
	void dismissingTheReportDoesNotUnfreeze() {
		ContentReport report = service.file(reporter, ContentReport.TargetType.COMMENT, "c-1", null,
				ContentReport.ReportReason.SEXUAL_MINORS, null);
		when(reports.findById("r-1")).thenReturn(Optional.of(report));

		service.handle(user("u-admin"), "r-1", ContentReport.State.DISMISSED, "malicious reporter");

		assertThat(frozen.isFrozen(FrozenTargetType.COMMENT, "c-1")).isTrue();
		verify(registry, never()).delete(any());
	}

	// --- helpers ----------------------------------------------------------------------

	private static final class RecordingEscalation implements ModerationEscalation {

		private final List<Event> events = new ArrayList<>();

		@Override
		public void escalate(Event event) {
			events.add(event);
		}

		@Override
		public String id() {
			return "recording";
		}
	}

	private static User user(String id) {
		return User.builder().id(id).displayName(id).email(id + "@example.org").active(true)
				.roles(Set.of("u-admin".equals(id) ? Role.ADMIN : Role.MEMBER)).build();
	}
}
