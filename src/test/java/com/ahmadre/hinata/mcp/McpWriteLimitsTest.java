package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.article.ArticleService;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.board.SprintService;
import com.ahmadre.hinata.common.UserWordsFixture;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MCP tool parameters are not bean-validated, so the write tools hold the same bounds the REST
 * records do themselves (HIN-26, HIN-167), and refuse before anything is looked up or stored.
 */
class McpWriteLimitsTest {

	private final CurrentUser currentUser = mock(CurrentUser.class);
	private final IssueService issues = mock(IssueService.class);
	private final ArticleService articles = mock(ArticleService.class);
	private final KnowledgeReadTools knowledgeRead = mock(KnowledgeReadTools.class);
	private final SprintService sprints = mock(SprintService.class);
	private final RichTextService richText = mock(RichTextService.class);
	private final McpErrors errors = new McpErrors(UserWordsFixture.real());
	private final AuditService audit = mock(AuditService.class, RETURNS_DEEP_STUBS);

	private final IssueWriteTools issueTools = new IssueWriteTools(issues, richText, currentUser,
			mock(ScopeGuard.class), audit, errors);
	private final KnowledgeWriteTools pageTools = new KnowledgeWriteTools(articles, richText, currentUser,
			mock(ScopeGuard.class), audit, knowledgeRead, errors);
	private final SprintWriteTools sprintTools = new SprintWriteTools(mock(ScopeGuard.class), currentUser, sprints,
			audit, errors);

	@BeforeEach
	void signIn() {
		when(currentUser.require()).thenReturn(User.builder().id("u1").build());
	}

	@Test
	void titlesOfIssuesAndPagesHoldThreeHundredCharacters() {
		String title = "t".repeat(301);

		assertThatThrownBy(() -> issueTools.create_issue("p1", title, null, null, null, null, null, null, null,
				null, null, null, null, null))
				.isInstanceOf(McpErrors.Refusal.class).hasMessageContaining("300");
		assertThatThrownBy(() -> issueTools.update_issue("HIN-1", title, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null))
				.isInstanceOf(McpErrors.Refusal.class);
		assertThatThrownBy(() -> pageTools.create_kb_article(title, "body", null, null, null, null, null, null))
				.isInstanceOf(McpErrors.Refusal.class);
		assertThatThrownBy(() -> pageTools.update_kb_article("a1", title, null, null, null, null))
				.isInstanceOf(McpErrors.Refusal.class);
		verifyNoInteractions(issues, articles, knowledgeRead);
	}

	@Test
	void aPageKeepsTheSpaceAndTagBoundsOfTheApp() {
		assertThatThrownBy(() -> pageTools.create_kb_article("Title", "body", null, null, null, null,
				"s".repeat(61), null))
				.isInstanceOf(McpErrors.Refusal.class);
		assertThatThrownBy(() -> pageTools.update_kb_article("a1", null, null, null, null,
				Collections.nCopies(31, "tag")))
				.isInstanceOf(McpErrors.Refusal.class);
		assertThatThrownBy(() -> pageTools.update_kb_article("a1", null, null, null, null, List.of("t".repeat(41))))
				.isInstanceOf(McpErrors.Refusal.class);
		verifyNoInteractions(articles, knowledgeRead);
	}

	@Test
	void aSprintNameHoldsOneHundredTwentyCharactersAndAGoalTwoThousand() {
		String name = "n".repeat(121);
		String goal = "g".repeat(2001);

		assertThatThrownBy(() -> sprintTools.create_sprint("b1", name, null, null, null, null))
				.isInstanceOf(McpErrors.Refusal.class).hasMessageContaining("120");
		assertThatThrownBy(() -> sprintTools.create_sprint("b1", "Sprint 1", goal, null, null, null))
				.isInstanceOf(McpErrors.Refusal.class).hasMessageContaining("2000");
		assertThatThrownBy(() -> sprintTools.update_sprint("s1", name, null, null, null, null))
				.isInstanceOf(McpErrors.Refusal.class);
		assertThatThrownBy(() -> sprintTools.update_sprint("s1", null, goal, null, null, null))
				.isInstanceOf(McpErrors.Refusal.class);
		assertThatThrownBy(() -> sprintTools.start_sprint("s1", goal, null))
				.isInstanceOf(McpErrors.Refusal.class);
		verifyNoInteractions(sprints);
	}
}
