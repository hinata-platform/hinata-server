package com.ahmadre.hinata.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
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
				mock(ScopeGuard.class), mock(AuditService.class, RETURNS_DEEP_STUBS));
	}

	@Test
	void addCommentRefusesTextThatCarriesNoContent() {
		assertThatThrownBy(() -> tools.add_comment("HIN-1", "   "))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.comment.empty");

		verify(issueService, never()).addComment(anyString(), any(RichText.class), any());
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
