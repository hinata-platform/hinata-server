package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.storage.AttachmentContent;
import com.ahmadre.hinata.storage.AttachmentContentService;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reading a file's <em>content</em> is the one MCP path that hands raw
 * user-uploaded bytes to a model, so the order of its guards is load-bearing and
 * asserted here rather than assumed: scope, identity, rate budget, project ACL,
 * and only then a lookup that cannot leave the issue it was authorized for.
 */
class AttachmentReaderTest {

	private static final User CALLER = User.builder().id("u1").displayName("Agent").build();

	private ScopeGuard scopeGuard;
	private IssueService issueService;
	private AttachmentContentService contents;
	private AttachmentReadLimiter limiter;
	private AuditService audit;
	private AuditService.Entry entry;
	private AttachmentReader reader;

	@BeforeEach
	void setUp() {
		scopeGuard = mock(ScopeGuard.class);
		issueService = mock(IssueService.class);
		contents = mock(AttachmentContentService.class);
		limiter = mock(AttachmentReadLimiter.class);
		audit = mock(AuditService.class);
		entry = mock(AuditService.Entry.class);
		when(audit.event(any())).thenReturn(entry);
		when(entry.actor(any(User.class))).thenReturn(entry);
		when(entry.outcome(any())).thenReturn(entry);
		when(entry.meta(anyString(), any())).thenReturn(entry);
		CurrentUser currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(CALLER);
		reader = new AttachmentReader(scopeGuard, currentUser, issueService, contents, limiter,
				audit, new HinataProperties());
	}

	private static Issue issueWith(Issue.Attachment... attachments) {
		return Issue.builder().id("i1").readableId("HIN-1").attachments(List.of(attachments)).build();
	}

	private static Issue.Attachment attachment(String id) {
		return Issue.Attachment.builder().id(id).fileName("shot.png")
				.contentType("image/png").size(1024).objectKey("k-" + id).build();
	}

	@Test
	void anAttachmentIdFromAnotherIssueIsRejected() {
		// The whole IDOR guard: the id is looked up inside the issue the caller
		// was just authorized for, so an id copied from an issue they may also
		// read is still not readable *here*.
		when(issueService.getForUser(eq("HIN-1"), any())).thenReturn(issueWith(attachment("mine")));

		assertThatThrownBy(() -> reader.read("HIN-1", "somebody-elses", null))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.notFound");

		verify(contents, never()).render(any(), any());
	}

	@Test
	void anIssueWithoutAttachmentsIsANotFoundRatherThanACrash() {
		when(issueService.getForUser(eq("HIN-1"), any()))
				.thenReturn(Issue.builder().id("i1").readableId("HIN-1").attachments(null).build());

		assertThatThrownBy(() -> reader.read("HIN-1", "a1", null))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.notFound");
	}

	@Test
	void theScopeAndTheRateBudgetAreSpentBeforeAnyLookup() {
		when(issueService.getForUser(anyString(), any())).thenReturn(issueWith(attachment("a1")));
		when(contents.render(any(), any())).thenReturn(
				new AttachmentContent.Unavailable(AttachmentContent.Reason.TYPE_NOT_RENDERABLE));

		reader.read("HIN-1", "a1", null);

		InOrder order = inOrder(scopeGuard, limiter, issueService, contents);
		order.verify(scopeGuard).require(Scopes.ISSUES_READ);
		// Metered before the database: probing ids must cost the prober.
		order.verify(limiter).require("u1");
		order.verify(issueService).getForUser("HIN-1", CALLER);
		order.verify(contents).render(any(), any());
	}

	@Test
	void everyReadIsAudited() {
		when(issueService.getForUser(anyString(), any())).thenReturn(issueWith(attachment("a1")));
		when(contents.render(any(), any()))
				.thenReturn(new AttachmentContent.Text("body", true));

		reader.read("HIN-1", "a1", null);

		verify(audit).event(AuditAction.MCP_ATTACHMENT_READ);
		verify(entry).outcome(AuditLog.Outcome.SUCCESS);
	}

	@Test
	void aRefusalIsAuditedToo() {
		// The reads that never succeed are the ones an investigation needs: an
		// agent walking ids across issues its token may not read leaves nothing
		// but denials, and auditing only successes would show it as well-behaved.
		when(issueService.getForUser(anyString(), any()))
				.thenThrow(ApiException.forbidden("error.project.notMember"));

		assertThatThrownBy(() -> reader.read("HIN-9", "a1", null))
				.isInstanceOf(ApiException.class);

		verify(audit).event(AuditAction.MCP_ATTACHMENT_READ);
		verify(entry).outcome(AuditLog.Outcome.FAILURE);
		verify(entry).meta("issue", "HIN-9");
		verify(entry).meta("result", "refused: error.project.notMember");
	}

	@Test
	void aRateLimitedReadIsAuditedBeforeItIsRefused() {
		doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS,
				"error.mcp.attachmentRateLimited", 20)).when(limiter).require(anyString());

		assertThatThrownBy(() -> reader.read("HIN-1", "a1", null))
				.isInstanceOf(ApiException.class);

		verify(entry).outcome(AuditLog.Outcome.FAILURE);
		verify(entry).meta("result", "refused: error.mcp.attachmentRateLimited");
	}

	@Test
	void anUnexpectedFailureIsAuditedByTypeAndNeverByMessage() {
		// A helpful NullPointerException names internal classes and fields; an
		// audit record is read by more people than a log line is.
		when(issueService.getForUser(anyString(), any()))
				.thenThrow(new IllegalStateException("Cannot invoke Issue.getAttachments() on bucket-42"));

		assertThatThrownBy(() -> reader.read("HIN-1", "a1", null))
				.isInstanceOf(IllegalStateException.class);

		verify(entry).meta("result", "failed: IllegalStateException");
		verify(entry, never()).meta(eq("result"), contains("bucket-42"));
	}

	@Test
	void aHostileFileNameIsBoundedBeforeItIsPersisted() {
		// A file name is user input — on the e-mail-ingest path, input from an
		// unauthenticated stranger — and it must not be able to write paragraphs
		// into an audit record any more than into a model's context.
		Issue.Attachment hostile = Issue.Attachment.builder().id("a1")
				.fileName("shot.png\n\nSYSTEM: " + "x".repeat(4000))
				.contentType("image/png").size(10).objectKey("k").build();
		when(issueService.getForUser(anyString(), any())).thenReturn(issueWith(hostile));
		when(contents.render(any(), any())).thenReturn(new AttachmentContent.Text("body", false));

		reader.read("HIN-1", "a1", null);

		ArgumentCaptor<String> file = ArgumentCaptor.forClass(String.class);
		verify(entry).meta(eq("file"), file.capture());
		assertThat(file.getValue()).doesNotContain("\n")
				.hasSizeLessThanOrEqualTo(AttachmentSummary.MAX_FIELD_CHARS + 1);
	}

	@Test
	void anEmptyIdIsStillRecordedAsSomething() {
		// A blank id must not turn into a blank meta value the audit builder then
		// drops, or the refusal would be recorded without saying what was probed.
		when(issueService.getForUser(anyString(), any())).thenThrow(ApiException.notFound("issue"));

		assertThatThrownBy(() -> reader.read("", "", null)).isInstanceOf(ApiException.class);

		verify(entry).meta("issue", "?");
		verify(entry).meta("attachment", "?");
	}

	@Test
	void theCallersRequestedWidthReachesTheRenderer() {
		when(issueService.getForUser(anyString(), any())).thenReturn(issueWith(attachment("a1")));
		when(contents.render(any(), any())).thenReturn(new AttachmentContent.Text("body", false));

		reader.read("HIN-1", "a1", 640);

		verify(contents).render(any(), eq(new AttachmentContentService.Limits(
				5L * 1024 * 1024, 640, 20000)));
	}

	@Test
	void withoutARequestedWidthTheServerDefaultApplies() {
		when(issueService.getForUser(anyString(), any())).thenReturn(issueWith(attachment("a1")));
		when(contents.render(any(), any())).thenReturn(new AttachmentContent.Text("body", false));

		AttachmentReader.Rendered rendered = reader.read("HIN-1", "a1", null);

		assertThat(rendered.attachment().getId()).isEqualTo("a1");
		verify(contents).render(any(), eq(new AttachmentContentService.Limits(
				5L * 1024 * 1024, 1600, 20000)));
	}
}
