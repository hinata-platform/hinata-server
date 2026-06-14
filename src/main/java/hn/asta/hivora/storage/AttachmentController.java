package hn.asta.hivora.storage;

import hn.asta.hivora.auth.CurrentUser;
import hn.asta.hivora.common.ApiException;
import hn.asta.hivora.issue.Issue;
import hn.asta.hivora.issue.IssueService;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
	private final CurrentUser currentUser;

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Issue upload(@PathVariable String issueId, @RequestParam("file") MultipartFile file) {
		// Authorize against the issue's project before touching storage (A01).
		issueService.getForUser(issueId, currentUser.require());
		String userId = currentUser.requireId();
		String objectKey = storage.upload(file);
		return issueService.update(issueId, issue -> issue.getAttachments().add(
				Issue.Attachment.builder()
						.id(UUID.randomUUID().toString())
						.fileName(file.getOriginalFilename())
						.contentType(file.getContentType())
						.size(file.getSize())
						.objectKey(objectKey)
						.uploaderId(userId)
						.uploadedAt(Instant.now())
						.build()),
				currentUser.require());
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
		issueService.update(issueId,
				updated -> updated.getAttachments().removeIf(a -> a.getId().equals(attachmentId)),
				currentUser.require());
		storage.delete(attachment.getObjectKey());
	}
}
