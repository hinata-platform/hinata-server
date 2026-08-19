package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.audit.AuditAction;
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
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
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
	private AttachmentReader reader;

	@BeforeEach
	void setUp() {
		scopeGuard = mock(ScopeGuard.class);
		issueService = mock(IssueService.class);
		contents = mock(AttachmentContentService.class);
		limiter = mock(AttachmentReadLimiter.class);
		audit = mock(AuditService.class, RETURNS_DEEP_STUBS);
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
