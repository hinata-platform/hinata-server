package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.storage.AttachmentContent;
import com.ahmadre.hinata.user.User;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What actually reaches the client. Two things must always hold: the internal
 * storage object key never appears in a response, and a file that cannot be
 * shown comes back as an explanation rather than as a tool error — an agent
 * retries errors and moves on from facts.
 */
class AttachmentToolsTest {

	private static final String OBJECT_KEY = "0f1c9a20-secret-object-key";

	private AttachmentReader reader;
	private IssueService issueService;
	private AttachmentTools tools;

	@BeforeEach
	void setUp() {
		reader = mock(AttachmentReader.class);
		issueService = mock(IssueService.class);
		CurrentUser currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(User.builder().id("u1").build());
		tools = new AttachmentTools(mock(ScopeGuard.class), currentUser, issueService, reader);
	}

	private static Issue.Attachment attachment() {
		return Issue.Attachment.builder().id("a1").fileName("shot.png").contentType("image/png")
				.size(2048).objectKey(OBJECT_KEY).uploaderId("u2").build();
	}

	private static Issue issue() {
		return Issue.builder().id("i1").readableId("HIN-1").attachments(List.of(attachment())).build();
	}

	private void renders(AttachmentContent content) {
		when(reader.read(anyString(), anyString(), any()))
				.thenReturn(new AttachmentReader.Rendered(issue(), attachment(), content));
	}

	private static String allText(CallToolResult result) {
		return result.content().stream()
				.filter(TextContent.class::isInstance)
				.map(c -> ((TextContent) c).text())
				.reduce("", (a, b) -> a + "\n" + b);
	}

	@Test
	void anImageComesBackAsImageContent() {
		byte[] bytes = { 1, 2, 3, 4 };
		renders(new AttachmentContent.Image(bytes, "image/jpeg", 1600, 900, 3200, 1800));

		CallToolResult result = tools.getAttachment("HIN-1", "a1", null);

		ImageContent image = (ImageContent) result.content().stream()
				.filter(ImageContent.class::isInstance).findFirst().orElseThrow();
		assertThat(image.mimeType()).isEqualTo("image/jpeg");
		assertThat(Base64.getDecoder().decode(image.data())).isEqualTo(bytes);
		// The caller must be able to tell it is looking at a reduced copy.
		assertThat(allText(result)).contains("1600×900").contains("3200×1800");
		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
	}

	@Test
	void extractedTextIsMarkedWhenItStopsShort() {
		renders(new AttachmentContent.Text("first page…", true));

		CallToolResult result = tools.getAttachment("HIN-1", "a1", null);

		assertThat(allText(result)).contains("TRUNCATED").contains("first page…");
	}

	@Test
	void anUnshowableTypeExplainsItselfInsteadOfFailing() {
		renders(new AttachmentContent.Unavailable(AttachmentContent.Reason.TYPE_NOT_RENDERABLE));

		CallToolResult result = tools.getAttachment("HIN-1", "a1", null);

		assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
		assertThat(result.content()).noneMatch(ImageContent.class::isInstance);
		assertThat(allText(result)).contains("No content returned");
	}

	@Test
	void anOversizedFileSaysNothingWasTruncated() {
		renders(new AttachmentContent.Unavailable(AttachmentContent.Reason.TOO_LARGE));

		assertThat(allText(tools.getAttachment("HIN-1", "a1", null)))
				.contains("Nothing was truncated");
	}

	@Test
	void theInternalStorageKeyNeverLeavesTheServer() {
		renders(new AttachmentContent.Image(new byte[] { 9 }, "image/png", 100, 100, 100, 100));

		CallToolResult result = tools.getAttachment("HIN-1", "a1", null);

		assertThat(result.content().stream().map(Content::toString))
				.noneMatch(text -> text.contains(OBJECT_KEY));
	}

	@Test
	void listingAnIssueWithoutAttachmentsIsEmptyRatherThanACrash() {
		when(issueService.getForUser(anyString(), any()))
				.thenReturn(Issue.builder().id("i1").readableId("HIN-1").attachments(null).build());

		assertThat(tools.listAttachments("HIN-1")).isEmpty();
	}

	@Test
	void aListedAttachmentCarriesItsMetadataButNotItsObjectKey() {
		when(issueService.getForUser(anyString(), any())).thenReturn(issue());

		List<AttachmentTools.AttachmentView> views = tools.listAttachments("HIN-1");

		assertThat(views).singleElement().satisfies(view -> {
			assertThat(view.id()).isEqualTo("a1");
			assertThat(view.fileName()).isEqualTo("shot.png");
			assertThat(view.size()).isEqualTo(2048);
		});
		assertThat(views.toString()).doesNotContain(OBJECT_KEY);
	}
}
