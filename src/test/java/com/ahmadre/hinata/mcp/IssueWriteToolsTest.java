package com.ahmadre.hinata.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.UserWordsFixture;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentOrigin;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * MCP is an authenticated write path like any other, so it has to reject what
 * the HTTP path rejects. It is also the path with no bean validation at all —
 * {@code @McpToolParam} is not validated — which is why the guards it needs live
 * in the service it calls rather than on its parameters.
 */
class IssueWriteToolsTest {

	private IssueService issueService;
	private IssueWriteTools tools;

	@BeforeEach
	void setUp() {
		issueService = mock(IssueService.class);
		CurrentUser currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(User.builder().id("u1").displayName("Agent").build());
		tools = new IssueWriteTools(issueService, new RichTextService(), currentUser,
				mock(ScopeGuard.class), mock(AuditService.class, RETURNS_DEEP_STUBS),
				new McpErrors(UserWordsFixture.real()));
	}

	@Test
	void addCommentRefusesTextThatCarriesNoContent() {
		assertThatThrownBy(() -> tools.add_comment("HIN-1", "   "))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.comment.empty");

		verify(issueService, never()).addComment(anyString(), any(RichText.class), any(), any(), any());
	}

	/** What an agent wrote is filed as written through MCP, in its person's name (HIN-26). */
	@Test
	void aCommentWrittenThroughMcpSaysSo() {
		when(issueService.addComment(eq("HIN-1"), any(RichText.class), isNull(), any(User.class),
				eq(ContentOrigin.MCP))).thenReturn(IssueComment.builder().id("c1").issueId("i1").authorId("u1")
						.text("Hallo").build());

		tools.add_comment("HIN-1", "Hallo");

		verify(issueService).addComment(eq("HIN-1"), any(RichText.class), isNull(), any(User.class),
				eq(ContentOrigin.MCP));
	}

	/**
	 * The dangerous half: {@code edit_comment} with blank text would not create an
	 * empty comment, it would <em>blank an existing one</em> — a delete wearing an
	 * edit's name, and one the HTTP path has always refused.
	 */
	@Test
	void editCommentRefusesToBlankAnExistingComment() {
		assertThatThrownBy(() -> tools.edit_comment("HIN-1", "c1", ""))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.comment.empty");

		verify(issueService, never()).editComment(anyString(), anyString(), any(RichText.class), any());
	}

	@Test
	void markdownOverTheInputBoundIsRefusedOnAnUnvalidatedToolParameter() {
		String huge = "a".repeat(RichTextService.MAX_MARKDOWN_CHARS + 1);

		assertThatThrownBy(() -> tools.add_comment("HIN-1", huge))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.richtext.tooLarge");
	}
}
