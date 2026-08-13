package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Tag(name = "Attachments")
@RestController
@RequestMapping("/api/v1/issues/{issueId}/attachments")
@RequiredArgsConstructor
public class AttachmentController {

	private static final String ATTACHMENT = "attachment";
	private static final String ZIP_MIME = "application/zip";

	private final IssueService issueService;
	private final StorageService storage;
	private final AttachmentStore store;
	private final AttachmentEvents events;
	private final ImagePreviewService previews;
	private final CurrentUser currentUser;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Issue upload(@PathVariable String issueId, @RequestParam("file") MultipartFile file) {
		// Authorize against the issue's project before touching storage (A01).
		Issue issue = issueService.getForUser(issueId, currentUser.require());
		String userId = currentUser.requireId();
		String objectKey = storage.upload(file);
		String attachmentId = UUID.randomUUID().toString();
		Issue.Attachment attachment = Issue.Attachment.builder()
				.id(attachmentId)
				.fileName(file.getOriginalFilename())
				.contentType(file.getContentType())
				.size(file.getSize())
				.objectKey(objectKey)
				.uploaderId(userId)
				.uploadedAt(Instant.now())
				.blurHash(storePreview(attachmentId, file))
				.build();
		// Atomic $push so parallel uploads to the same issue can't lose each other.
		Issue saved = store.add(issue.getId(), attachment);
		// Notify everyone viewing this issue so the new tile appears live.
		events.publishAdded(saved.getId(), attachment);
		return saved;
	}

	/**
	 * Generates and stores the thumbnail for a freshly uploaded image, returning
	 * its BlurHash (null when the upload is not a decodable image). Never lets a
	 * preview problem fail the upload: the file itself is already stored, and a
	 * client without a preview simply falls back to the full image.
	 */
	private String storePreview(String attachmentId, MultipartFile file) {
		if (!isPreviewable(file.getContentType())) {
			return null;
		}
		try {
			Optional<ImagePreviewService.Preview> preview = previews.create(file.getBytes());
			if (preview.isEmpty()) {
				return null;
			}
			storage.putObject(ImagePreviewService.attachmentThumbnailKey(attachmentId),
					preview.get().bytes(), preview.get().contentType());
			return preview.get().blurHash();
		}
		catch (IOException | RuntimeException ex) {
			log.warn("Preview for attachment {} failed: {}", attachmentId, ex.toString());
			return null;
		}
	}

	/**
	 * The small, re-encoded preview of an image attachment — what a grid of tiles
	 * loads instead of the originals. Falls back to the full image for pictures
	 * uploaded before previews existed (and for formats this JVM cannot decode),
	 * so the endpoint always answers with something renderable.
	 */
	@GetMapping("/{attachmentId}/thumbnail")
	public ResponseEntity<byte[]> thumbnail(@PathVariable String issueId,
			@PathVariable String attachmentId) {
		Issue issue = issueService.getForUser(issueId, currentUser.require());
		Issue.Attachment attachment = issue.getAttachments().stream()
				.filter(a -> a.getId().equals(attachmentId))
				.findFirst()
				.orElseThrow(() -> ApiException.notFound(ATTACHMENT));
		StorageService.StoredObject object = storage
				.getObject(ImagePreviewService.attachmentThumbnailKey(attachmentId))
				.orElseGet(() -> generateMissingPreview(issue, attachment));
		if (object == null) {
			throw ApiException.notFound(ATTACHMENT);
		}
		String contentType = object.contentType() == null || object.contentType().isBlank()
				? MediaType.APPLICATION_OCTET_STREAM_VALUE : object.contentType();
		return ResponseEntity.ok()
				// Immutable: the key contains the attachment id and an attachment's
				// bytes never change, so a client may keep this until it is deleted.
				.cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate().immutable())
				.contentType(MediaType.parseMediaType(contentType))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
				.header("X-Content-Type-Options", "nosniff")
				.body(object.data());
	}

	/**
	 * Builds the preview for an image that has none yet — everything uploaded
	 * before previews existed, plus anything whose generation failed at the time.
	 * Doing it on first view instead of in a boot-time migration keeps startup
	 * free of a job that would re-encode every picture in the installation, and
	 * limits the work to images somebody actually looks at.
	 *
	 * <p>Returns the preview bytes, the original when the format cannot be
	 * decoded (webp), or null when there is nothing to show.
	 */
	private StorageService.StoredObject generateMissingPreview(Issue issue,
			Issue.Attachment attachment) {
		if (!isPreviewable(attachment.getContentType())) {
			return null;
		}
		StorageService.StoredObject original = storage.getObject(attachment.getObjectKey())
				.orElse(null);
		if (original == null) {
			return null;
		}
		Optional<ImagePreviewService.Preview> preview = previews.create(original.data());
		if (preview.isEmpty()) {
			return original;
		}
		try {
			storage.putObject(ImagePreviewService.attachmentThumbnailKey(attachment.getId()),
					preview.get().bytes(), preview.get().contentType());
			if (attachment.getBlurHash() == null) {
				store.setBlurHash(issue.getId(), attachment.getId(), preview.get().blurHash());
			}
		}
		catch (RuntimeException ex) {
			// Storing the result is an optimisation for the next viewer; failing at
			// it must not deny this one the preview that is already in hand.
			log.warn("Storing late preview for attachment {} failed: {}",
					attachment.getId(), ex.toString());
		}
		return new StorageService.StoredObject(preview.get().bytes(), preview.get().contentType());
	}

	/**
	 * Whether a preview is worth attempting for this content type: pictures, and
	 * PDFs (whose first page is rendered). A Word file or an archive has no page
	 * to draw, and reading a 20 MB ZIP back out of storage to discover that is
	 * exactly the work this check avoids.
	 */
	private static boolean isPreviewable(String contentType) {
		if (contentType == null) {
			return false;
		}
		String type = contentType.toLowerCase();
		return type.startsWith("image/") || type.startsWith("application/pdf");
	}

	/**
	 * Live stream of attachment changes ({@code added} / {@code removed}) for an
	 * issue, so multiple uploads and teammates' changes show up in real time.
	 */
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@PathVariable String issueId) {
		Issue issue = issueService.getForUser(issueId, currentUser.require());
		return events.subscribe(issue.getId());
	}

	/**
	 * Streams the attachment's bytes through the server (authorized per-issue),
	 * so the browser never has to reach the object store directly — presigned
	 * URLs point at the *internal* storage endpoint and aren't reachable from a
	 * client. Used for both downloads and inline previews; the client fetches
	 * this with its bearer token and saves/renders the bytes.
	 */
	@GetMapping("/{attachmentId}/download")
	public ResponseEntity<byte[]> download(@PathVariable String issueId,
			@PathVariable String attachmentId) {
		Issue issue = issueService.getForUser(issueId, currentUser.require());
		Issue.Attachment attachment = issue.getAttachments().stream()
				.filter(a -> a.getId().equals(attachmentId))
				.findFirst()
				.orElseThrow(() -> ApiException.notFound(ATTACHMENT));
		StorageService.StoredObject object = storage.getObject(attachment.getObjectKey())
				.orElseThrow(() -> ApiException.notFound(ATTACHMENT));
		String fileName = attachment.getFileName() != null ? attachment.getFileName() : "download";
		// attachment; filename*=UTF-8'' so umlauts/special chars survive.
		ContentDisposition disposition = ContentDisposition.attachment()
				.filename(fileName, java.nio.charset.StandardCharsets.UTF_8)
				.build();
		String contentType = attachment.getContentType() != null
				? attachment.getContentType() : object.contentType();
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
				.contentType(MediaType.parseMediaType(contentType))
				.body(object.data());
	}

	/**
	 * Streams every attachment of the issue as one ZIP archive ("download all").
	 * The entries are written one object at a time, so only a single attachment
	 * is ever held in memory. Entry names are derived from the stored file names
	 * but stripped of any path component — a crafted upload name must not be able
	 * to escape the extraction directory on whoever opens the archive (zip slip).
	 */
	// No `produces` here on purpose: the app sends a blanket `Accept:
	// application/json`, so restricting the mapping to application/zip would make
	// it unmatchable (406 before the handler runs). The response still declares
	// the ZIP content type below.
	@GetMapping("/archive")
	public ResponseEntity<StreamingResponseBody> archive(@PathVariable String issueId) {
		Issue issue = issueService.getForUser(issueId, currentUser.require());
		List<Issue.Attachment> attachments = issue.getAttachments() == null
				? List.of() : List.copyOf(issue.getAttachments());
		if (attachments.isEmpty()) {
			throw ApiException.notFound(ATTACHMENT);
		}
		String base = issue.getReadableId() != null && !issue.getReadableId().isBlank()
				? issue.getReadableId() : "issue";
		ContentDisposition disposition = ContentDisposition.attachment()
				.filename(base + "-attachments.zip", StandardCharsets.UTF_8)
				.build();
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
				.contentType(MediaType.parseMediaType(ZIP_MIME))
				.body(out -> writeArchive(attachments, out));
	}

	private void writeArchive(List<Issue.Attachment> attachments, OutputStream out) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
			// Attachments are mostly already-compressed media; the cheapest level
			// keeps a large archive from burning CPU for a few percent of size.
			zip.setLevel(Deflater.BEST_SPEED);
			Set<String> used = new HashSet<>();
			for (Issue.Attachment attachment : attachments) {
				StorageService.StoredObject object = readForArchive(attachment);
				if (object != null) {
					zip.putNextEntry(new ZipEntry(uniqueEntryName(used, attachment.getFileName())));
					zip.write(object.data());
					zip.closeEntry();
				}
			}
		}
	}

	/**
	 * Reads one attachment's bytes for the archive, or null when the object is
	 * gone or unreadable. The response is already committed at this point, so a
	 * single bad object must not abort (and truncate) the whole archive.
	 */
	private StorageService.StoredObject readForArchive(Issue.Attachment attachment) {
		try {
			return storage.getObject(attachment.getObjectKey()).orElse(null);
		}
		catch (RuntimeException ex) {
			log.warn("Skipping attachment {} in archive: {}", attachment.getId(), ex.getMessage());
			return null;
		}
	}

	/**
	 * A safe, collision-free ZIP entry name for [fileName]: directory components
	 * and control characters are dropped, and a numeric suffix is appended when
	 * two attachments share a name (otherwise the second would overwrite the
	 * first on extraction).
	 */
	private static String uniqueEntryName(Set<String> used, String fileName) {
		String name = fileName == null ? "" : fileName;
		int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		name = name.substring(slash + 1).replaceAll("[\\p{Cntrl}]", "").trim();
		if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
			name = "file";
		}
		String candidate = name;
		int dot = name.lastIndexOf('.');
		String stem = dot > 0 ? name.substring(0, dot) : name;
		String extension = dot > 0 ? name.substring(dot) : "";
		for (int i = 2; !used.add(candidate); i++) {
			candidate = stem + " (" + i + ")" + extension;
		}
		return candidate;
	}

	@DeleteMapping("/{attachmentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String issueId, @PathVariable String attachmentId) {
		Issue issue = issueService.getForUser(issueId, currentUser.require());
		Issue.Attachment attachment = issue.getAttachments().stream()
				.filter(a -> a.getId().equals(attachmentId))
				.findFirst()
				.orElseThrow(() -> ApiException.notFound(ATTACHMENT));
		Issue saved = store.remove(issue.getId(), attachmentId);
		deleteObjects(attachment);
		events.publishRemoved(saved.getId(), attachmentId);
	}

	/** Drops an attachment's stored bytes and its derived thumbnail. */
	private void deleteObjects(Issue.Attachment attachment) {
		storage.delete(attachment.getObjectKey());
		// Unconditional: a thumbnail that was never generated is a no-op delete,
		// and skipping it based on the content type would leak the object for an
		// attachment whose type was recorded differently than it was previewed.
		storage.delete(ImagePreviewService.attachmentThumbnailKey(attachment.getId()));
	}

	/**
	 * Bulk delete ("delete all"). [ids] carries the attachments the client
	 * actually sees; only those are removed, so a file uploaded by someone else
	 * between the client's last render and this call survives. Omitting [ids]
	 * removes every attachment of the issue. Idempotent: ids that are already
	 * gone are simply skipped.
	 */
	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAll(@PathVariable String issueId,
			@RequestParam(name = "ids", required = false) List<String> ids) {
		Issue issue = issueService.getForUser(issueId, currentUser.require());
		List<Issue.Attachment> present = issue.getAttachments() == null
				? List.of() : issue.getAttachments();
		List<Issue.Attachment> targets = ids == null || ids.isEmpty()
				? List.copyOf(present)
				: present.stream().filter(a -> ids.contains(a.getId())).toList();
		if (targets.isEmpty()) {
			return;
		}
		List<String> targetIds = targets.stream().map(Issue.Attachment::getId).toList();
		Issue saved = store.removeAll(issue.getId(), targetIds);
		for (Issue.Attachment attachment : targets) {
			deleteObjects(attachment);
			events.publishRemoved(saved.getId(), attachment.getId());
		}
	}
}
