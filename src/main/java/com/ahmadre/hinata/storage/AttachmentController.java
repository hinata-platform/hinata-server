package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;
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

	@GetMapping("/{attachmentId}/download-url")
	public Map<String, String> downloadUrl(@PathVariable String issueId,
			@PathVariable String attachmentId) {
		Issue issue = issueService.getForUser(issueId, currentUser.require());
		Issue.Attachment attachment = issue.getAttachments().stream()
				.filter(a -> a.getId().equals(attachmentId))
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("attachment"));
		return Map.of("url", storage.presignedDownloadUrl(
				attachment.getObjectKey(), attachment.getFileName()));
	}

	@DeleteMapping("/{attachmentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String issueId, @PathVariable String attachmentId) {
		Issue issue = issueService.getForUser(issueId, currentUser.require());
		Issue.Attachment attachment = issue.getAttachments().stream()
				.filter(a -> a.getId().equals(attachmentId))
				.findFirst()
				.orElseThrow(() -> ApiException.notFound("attachment"));
		Issue saved = store.remove(issue.getId(), attachmentId);
		storage.delete(attachment.getObjectKey());
		events.publishRemoved(saved.getId(), attachmentId);
	}
}
