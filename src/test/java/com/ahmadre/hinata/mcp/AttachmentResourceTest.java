package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.storage.AttachmentContent;
import com.ahmadre.hinata.user.User;
import io.modelcontextprotocol.spec.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code hinata://} resources are a second door onto the same data as the
 * tools, and a door is only as good as its lock. Two things are asserted here:
 * that reading an attachment by URI goes through the one authorized path rather
 * than around it, and that each resource spends the same capability scope the
 * equivalent tool spends — otherwise a token issued for one thing could read
 * another simply by asking for a URI instead of calling a tool.
 */
class AttachmentResourceTest {

	private static final User CALLER = User.builder().id("u1").build();

	private ScopeGuard scopeGuard;
	private AttachmentReader reader;
	private IssueService issueService;
	private KnowledgeReadTools knowledge;
	private HinataResources resources;

	@BeforeEach
	void setUp() {
		scopeGuard = mock(ScopeGuard.class);
		reader = mock(AttachmentReader.class);
		issueService = mock(IssueService.class);
		knowledge = mock(KnowledgeReadTools.class);
		CurrentUser currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(CALLER);
		resources = new HinataResources(scopeGuard, currentUser, issueService, reader,
				mock(ProjectService.class), mock(ProjectRepository.class), knowledge);
	}

	private static Issue.Attachment attachment() {
		return Issue.Attachment.builder().id("a1").fileName("shot.png").contentType("image/png")
				.size(2048).objectKey("internal-object-key").build();
	}

	private void renders(AttachmentContent content) {
		when(reader.read(anyString(), anyString(), any())).thenReturn(new AttachmentReader.Rendered(
				Issue.builder().id("i1").readableId("HIN-1").attachments(List.of(attachment())).build(),
				attachment(), content));
	}

	@Test
	void theResourceReadsThroughTheOneAuthorizedPath() {
		// AttachmentReader owns the scope gate, the project ACL, the rate budget
		// and the audit record; a resource that fetched bytes any other way would
		// have none of them.
		renders(new AttachmentContent.Unavailable(AttachmentContent.Reason.MISSING));

		resources.attachment("HIN-1", "a1");

		verify(reader).read("HIN-1", "a1", null);
	}

	@Test
	void anImageResourceStillCarriesTheUntrustedNotice() {
		// A picture pasted into a ticket can carry writing, and writing a model
		// reads is writing that can try to instruct it. The blob must not travel
		// without the framing the tool call gives the same bytes.
		renders(new AttachmentContent.Image(new byte[] { 1, 2, 3 }, "image/png", 100, 100, 100, 100));

		ReadResourceResult result = resources.attachment("HIN-1", "a1");

		assertThat(result.contents()).anyMatch(BlobResourceContents.class::isInstance);
		String prose = result.contents().stream()
				.filter(TextResourceContents.class::isInstance)
				.map(c -> ((TextResourceContents) c).text())
				.findFirst().orElse("");
		assertThat(prose).contains("untrusted").contains("shot.png");
		assertThat(prose).doesNotContain("internal-object-key");
	}

	@Test
	void theIssueResourceSpendsTheSameScopeAsTheIssueTool() {
		when(issueService.getForUser(anyString(), any()))
				.thenReturn(Issue.builder().id("i1").readableId("HIN-1").build());

		resources.issue("HIN-1");

		verify(scopeGuard).require(Scopes.ISSUES_READ);
	}

	@Test
	void theArticleResourceSpendsTheSameScopeAsTheArticleTool() {
		when(knowledge.requireVisible(anyString(), any()))
				.thenReturn(Article.builder().id("kb1").title("Runbook").build());

		resources.article("kb1");

		verify(scopeGuard).require(Scopes.KB_READ);
	}
}
