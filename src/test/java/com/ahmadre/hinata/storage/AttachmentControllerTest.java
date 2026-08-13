package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bulk attachment endpoints ("download all" / "delete all"). Both are pure
 * orchestration over mocked storage, so they run as plain unit tests.
 */
class AttachmentControllerTest {

	private IssueService issues;
	private StorageService storage;
	private AttachmentStore store;
	private AttachmentEvents events;
	private AttachmentController controller;

	@BeforeEach
	void setUp() {
		issues = mock(IssueService.class);
		storage = mock(StorageService.class);
		store = mock(AttachmentStore.class);
		events = mock(AttachmentEvents.class);
		CurrentUser currentUser = mock(CurrentUser.class);
		when(currentUser.require()).thenReturn(mock(User.class));
		controller = new AttachmentController(issues, storage, store, events,
				new ImagePreviewService(), currentUser);
	}

	private static Issue.Attachment attachment(String id, String fileName, String objectKey) {
		return Issue.Attachment.builder().id(id).fileName(fileName).objectKey(objectKey)
				.contentType("application/octet-stream").build();
	}

	private Issue issueWith(Issue.Attachment... attachments) {
		Issue issue = Issue.builder().id("i1").readableId("HIN-42")
				.attachments(new java.util.ArrayList<>(List.of(attachments))).build();
		when(issues.getForUser(anyString(), any())).thenReturn(issue);
		return issue;
	}

	/** Runs the streamed response body and unpacks the resulting archive. */
	private static Map<String, String> unzip(ResponseEntity<StreamingResponseBody> response) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		response.getBody().writeTo(out);
		Map<String, String> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
		return entries;
	}

	private void storedAs(String objectKey, String content) {
		when(storage.getObject(objectKey)).thenReturn(Optional.of(
				new StorageService.StoredObject(content.getBytes(StandardCharsets.UTF_8),
						"application/octet-stream")));
	}

	@Test
	void archiveNamesTheFileAfterTheIssue() {
		issueWith(attachment("a1", "note.txt", "k1"));
		storedAs("k1", "one");

		ResponseEntity<StreamingResponseBody> response = controller.archive("i1");

		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
				.contains("HIN-42-attachments.zip");
		assertThat(response.getHeaders().getContentType()).hasToString("application/zip");
	}

	@Test
	void archiveMappingAcceptsTheAppsJsonAcceptHeader() throws NoSuchMethodException {
		// The app sends a blanket `Accept: application/json`, so a
		// `produces = "application/zip"` on the mapping would make the route
		// unmatchable — Spring answers 406 before the handler ever runs.
		GetMapping mapping = AttachmentController.class.getMethod("archive", String.class)
				.getAnnotation(GetMapping.class);
		assertThat(mapping.produces()).isEmpty();
	}

	@Test
	void archiveStripsPathsAndDeduplicatesEntryNames() throws Exception {
		issueWith(
				// A crafted upload name must not be able to escape the extraction
				// directory of whoever opens the archive (zip slip).
				attachment("a1", "../../etc/passwd", "k1"),
				attachment("a2", "report.pdf", "k2"),
				attachment("a3", "report.pdf", "k3"));
		storedAs("k1", "one");
		storedAs("k2", "two");
		storedAs("k3", "three");

		Map<String, String> entries = unzip(controller.archive("i1"));

		assertThat(entries).containsOnlyKeys("passwd", "report.pdf", "report (2).pdf");
		assertThat(entries.get("passwd")).isEqualTo("one");
		// The second "report.pdf" keeps its own bytes instead of overwriting the first.
		assertThat(entries.get("report.pdf")).isEqualTo("two");
		assertThat(entries.get("report (2).pdf")).isEqualTo("three");
	}

	@Test
	void archiveSkipsUnreadableObjectsInsteadOfTruncating() throws Exception {
		issueWith(attachment("a1", "gone.txt", "k1"), attachment("a2", "here.txt", "k2"));
		when(storage.getObject("k1")).thenReturn(Optional.empty());
		storedAs("k2", "here");

		assertThat(unzip(controller.archive("i1"))).containsOnlyKeys("here.txt");
	}

	// --- thumbnails -----------------------------------------------------------

	private static byte[] pngBytes(int width, int height) throws Exception {
		java.awt.image.BufferedImage image =
				new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = image.createGraphics();
		g.setColor(new java.awt.Color(20, 140, 90));
		g.fillRect(0, 0, width, height);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static Issue.Attachment image(String id, String objectKey) {
		Issue.Attachment attachment = attachment(id, "photo.png", objectKey);
		attachment.setContentType("image/png");
		return attachment;
	}

	@Test
	void thumbnailServesTheStoredPreview() {
		issueWith(image("a1", "k1"));
		when(storage.getObject(ImagePreviewService.attachmentThumbnailKey("a1")))
				.thenReturn(Optional.of(new StorageService.StoredObject("thumb".getBytes(), "image/jpeg")));

		ResponseEntity<byte[]> response = controller.thumbnail("i1", "a1");

		assertThat(response.getBody()).isEqualTo("thumb".getBytes());
		assertThat(response.getHeaders().getCacheControl()).contains("max-age");
		// The full image must not be touched when a preview exists — that is the
		// entire point of the endpoint.
		verify(storage, never()).getObject("k1");
	}

	@Test
	void thumbnailIsGeneratedOnFirstViewForImagesThatPredateThem() throws Exception {
		byte[] original = pngBytes(1200, 600);
		issueWith(image("a1", "k1"));
		when(storage.getObject(ImagePreviewService.attachmentThumbnailKey("a1")))
				.thenReturn(Optional.empty());
		when(storage.getObject("k1"))
				.thenReturn(Optional.of(new StorageService.StoredObject(original, "image/png")));

		ResponseEntity<byte[]> response = controller.thumbnail("i1", "a1");

		// Served as a real preview, stored for the next viewer, and the BlurHash
		// recorded on the attachment so the placeholder works from then on.
		assertThat(response.getBody()).isNotEmpty().isNotEqualTo(original);
		verify(storage).putObject(eq(ImagePreviewService.attachmentThumbnailKey("a1")),
				any(byte[].class), eq("image/jpeg"));
		verify(store).setBlurHash(eq("i1"), eq("a1"), anyString());
	}

	@Test
	void thumbnailOfANonImageIsNotFound() {
		issueWith(attachment("a1", "report.pdf", "k1"));
		when(storage.getObject(anyString())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> controller.thumbnail("i1", "a1"))
				.isInstanceOf(ApiException.class);
		// No pointless read of a PDF that could never become a picture.
		verify(storage, never()).getObject("k1");
	}

	@Test
	void deletingAnAttachmentAlsoDropsItsThumbnail() {
		Issue issue = issueWith(image("a1", "k1"));
		when(store.remove(anyString(), anyString())).thenReturn(issue);

		controller.delete("i1", "a1");

		verify(storage).delete("k1");
		verify(storage).delete(ImagePreviewService.attachmentThumbnailKey("a1"));
	}

	@Test
	void bulkDeleteRemovesOnlyTheRequestedIds() {
		Issue issue = issueWith(attachment("a1", "one.txt", "k1"), attachment("a2", "two.txt", "k2"));
		when(store.removeAll(anyString(), any())).thenReturn(issue);

		controller.deleteAll("i1", List.of("a1"));

		verify(store).removeAll("i1", List.of("a1"));
		verify(storage).delete("k1");
		verify(storage, never()).delete("k2");
		verify(events).publishRemoved("i1", "a1");
		verify(events, never()).publishRemoved("i1", "a2");
	}

	@Test
	void bulkDeleteWithoutIdsRemovesEveryAttachment() {
		Issue issue = issueWith(attachment("a1", "one.txt", "k1"), attachment("a2", "two.txt", "k2"));
		when(store.removeAll(anyString(), any())).thenReturn(issue);

		controller.deleteAll("i1", null);

		verify(store).removeAll("i1", List.of("a1", "a2"));
		verify(storage).delete("k1");
		verify(storage).delete("k2");
	}

	@Test
	void bulkDeleteOfAlreadyGoneIdsIsANoOp() {
		issueWith(attachment("a1", "one.txt", "k1"));

		controller.deleteAll("i1", List.of("stale"));

		verify(store, never()).removeAll(anyString(), any());
		verify(storage, never()).delete(anyString());
	}
}
