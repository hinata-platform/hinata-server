package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.moderation.freeze.FrozenContentService;
import com.ahmadre.hinata.moderation.freeze.FrozenTargetType;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.UUID;

@Tag(name = "Attachments")
@RestController
@RequestMapping("/api/v1/issues/{issueId}/attachments")
@RequiredArgsConstructor
public class AttachmentController {

	private final IssueService issueService;
	private final StorageService storage;
	private final AttachmentStore store;
	private final AttachmentEvents events;
	private final FrozenContentService frozen;
	private final CurrentUser currentUser;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Issue upload(@PathVariable String issueId, @RequestParam("file") MultipartFile file) {
		// Authorize against the issue's project before touching storage (A01).
		Issue issue = issueService.getForUser(issueId, currentUser.require());
		String userId = currentUser.requireId();
		String objectKey = storage.upload(file);
		Issue.Attachment attachment = Issue.Attachment.builder()
				.id(UUID.randomUUID().toString())
				.fileName(file.getOriginalFilename())
				.contentType(file.getContentType())
				.size(file.getSize())
				.objectKey(objectKey)
				.uploaderId(userId)
				.uploadedAt(Instant.now())
				.build();
		// Atomic $push so parallel uploads to the same issue can't lose each other.
		Issue saved = store.add(issue.getId(), attachment);
		// Notify everyone viewing this issue so the new tile appears live.
		events.publishAdded(saved.getId(), attachment);
		return saved;
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
				.orElseThrow(() -> ApiException.notFound("attachment"));
		StorageService.StoredObject object = storage.getObject(attachment.getObjectKey())
				.orElseThrow(() -> ApiException.notFound("attachment"));
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
	 * Removes the attachment from the issue and deletes its bytes.
	 *
	 * <p>Refuses (409) when the attachment is frozen. The byte guard inside
	 * {@code StorageService.delete} is not enough on its own and never was: it skips
	 * the object, so the bytes survive, but {@code store.remove} has already pulled
	 * the subdocument — fileName, size, uploader, uploadedAt and the {@code objectKey}
	 * itself — out of {@code Issue.attachments}. What is left is a file in the bucket
	 * with nothing in the database pointing at it, which is evidence that technically
	 * exists and is practically unfindable. That is the outcome
	 * {@code FrozenContentService.assertNotFrozen}'s javadoc calls "technically
	 * present and practically unusable", and it is why a destructive path refuses
	 * rather than partially succeeding.
	 *
	 * <p>Both halves are asserted. The {@code ATTACHMENT} row covers a file an
	 * operator froze by name; the {@code OBJECT} row covers the same bytes reached
	 * from a freeze on the issue, the comment or an inline image that resolved to this
	 * key — a moderator freezing the parent issue must not leave the one destructive
	 * route to its files open.
	 */
	@DeleteMapping("/{attachmentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String issueId, @PathVariable String attachmentId) {
		Issue issue = issueService.getForUser(issueId, currentUser.require());
		Issue.Attachment attachment = issue.getAttachments().stream()
				.filter(a -> a.getId().equals(attachmentId))
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("attachment"));
		frozen.assertNotFrozen(FrozenTargetType.ATTACHMENT, attachmentId);
		// singletonList, not List.of: a legacy row can carry a null objectKey, which
		// List.of rejects with an NPE — turning a refusal into a 500 on the one path
		// that must answer 409.
		frozen.assertNoObjectFrozen(java.util.Collections.singletonList(attachment.getObjectKey()));
		Issue saved = store.remove(issue.getId(), attachmentId);
		storage.delete(attachment.getObjectKey());
		events.publishRemoved(saved.getId(), attachmentId);
	}
}
