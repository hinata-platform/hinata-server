package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Issues")
@RestController
@RequestMapping("/api/v1/issues")
@RequiredArgsConstructor
public class IssueController {

	private final IssueService issueService;
	private final CommentEvents commentEvents;
	private final CurrentUser currentUser;

	public record CreateIssueRequest(
			@NotBlank String projectId,
			@NotBlank @Size(max = 300) String title,
			@Size(max = 30000) String description,
			Issue.Type type,
			Issue.Priority priority,
			String state,
			String assigneeId,
			List<String> assigneeIds,
			String parentId,
			String sprintId,
			List<String> tags,
			LocalDate startDate,
			LocalDate dueDate,
			Integer estimateMinutes,
			Integer storyPoints) {
	}

	public record UpdateIssueRequest(
			@Size(max = 300) String title,
			@Size(max = 30000) String description,
			Issue.Type type,
			Issue.Priority priority,
			String state,
			String assigneeId,
			List<String> assigneeIds,
			String parentId,
			String sprintId,
			List<String> tags,
			List<String> dependsOnIds,
			LocalDate startDate,
			LocalDate dueDate,
			Integer estimateMinutes,
			Integer storyPoints,
			Double rank,
			// Explicit clear flags — JSON null on a field is "no change",
			// so clearing a value requires its own signal.
			Boolean clearStartDate,
			Boolean clearDueDate,
			Boolean clearStoryPoints) {
	}

	public record CommentRequest(@NotBlank @Size(max = 10000) String text, String replyToId) {
	}

	public record ReactionRequest(@NotBlank @Size(max = 32) String emoji) {
	}

	public record PinRequest(boolean pinned) {
	}

	@GetMapping
	public Page<Issue> search(
			@RequestParam(required = false) String projectId,
			@RequestParam(required = false) String state,
			@RequestParam(required = false) String assigneeId,
			@RequestParam(required = false) String sprintId,
			@RequestParam(required = false) String type,
			@RequestParam(required = false) String query,
			@RequestParam(defaultValue = "false") boolean noSprint,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return issueService.search(projectId, state, assigneeId, sprintId, type, query, noSprint,
				page, size, currentUser.require());
	}

	@GetMapping("/{id}")
	public Issue get(@PathVariable String id) {
		return issueService.getForUser(id, currentUser.require());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Issue create(@RequestBody @Valid CreateIssueRequest request) {
		List<String> assigneeIds = request.assigneeIds();
		if (assigneeIds == null) {
			assigneeIds = (request.assigneeId() != null && !request.assigneeId().isBlank())
					? List.of(request.assigneeId()) : List.of();
		}
		Issue issue = Issue.builder()
				.projectId(request.projectId())
				.title(request.title())
				.description(request.description())
				.type(request.type() != null ? request.type() : Issue.Type.TASK)
				.priority(request.priority() != null ? request.priority() : Issue.Priority.NORMAL)
				.state(request.state())
				.assigneeIds(assigneeIds)
				.parentId(request.parentId())
				.sprintId(request.sprintId())
				.tags(request.tags() != null ? request.tags() : List.of())
				.startDate(request.startDate())
				.dueDate(request.dueDate())
				.estimateMinutes(request.estimateMinutes())
				.storyPoints(request.storyPoints())
				.build();
		return issueService.create(issue, currentUser.require());
	}

	@PatchMapping("/{id}")
	public Issue update(@PathVariable String id, @RequestBody @Valid UpdateIssueRequest request) {
		return issueService.update(id, issue -> {
			if (request.title() != null) issue.setTitle(request.title());
			if (request.description() != null) issue.setDescription(request.description());
			if (request.type() != null) issue.setType(request.type());
			if (request.priority() != null) issue.setPriority(request.priority());
			if (request.state() != null) issue.setState(request.state());
			if (request.assigneeIds() != null) {
				issue.setAssigneeIds(request.assigneeIds());
			} else if (request.assigneeId() != null) {
				issue.setAssigneeId(request.assigneeId().isBlank() ? null : request.assigneeId());
			}
			if (request.parentId() != null) {
				issue.setParentId(request.parentId().isBlank() ? null : request.parentId());
			}
			if (request.sprintId() != null) {
				issue.setSprintId(request.sprintId().isBlank() ? null : request.sprintId());
			}
			if (request.tags() != null) issue.setTags(request.tags());
			if (request.dependsOnIds() != null) issue.setDependsOnIds(request.dependsOnIds());
			if (Boolean.TRUE.equals(request.clearStartDate())) {
				issue.setStartDate(null);
			} else if (request.startDate() != null) {
				issue.setStartDate(request.startDate());
			}
			if (Boolean.TRUE.equals(request.clearDueDate())) {
				issue.setDueDate(null);
			} else if (request.dueDate() != null) {
				issue.setDueDate(request.dueDate());
			}
			if (request.estimateMinutes() != null) issue.setEstimateMinutes(request.estimateMinutes());
			if (Boolean.TRUE.equals(request.clearStoryPoints())) {
				issue.setStoryPoints(null);
			} else if (request.storyPoints() != null) {
				issue.setStoryPoints(request.storyPoints());
			}
			if (request.rank() != null) issue.setRank(request.rank());
		}, currentUser.require());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		issueService.delete(id, currentUser.require());
	}

	@GetMapping("/{id}/activity")
	public Page<IssueActivity> activity(@PathVariable String id,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "30") int size) {
		return issueService.activityOf(id, page, size, currentUser.require());
	}

	/** Breadcrumb ancestors + direct children for the issue hierarchy view. */
	@GetMapping("/{id}/hierarchy")
	public IssueService.Hierarchy hierarchy(@PathVariable String id) {
		return issueService.hierarchyOf(id, currentUser.require());
	}

	@GetMapping("/{id}/comments")
	public Page<IssueComment> comments(@PathVariable String id,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "30") int size,
			@RequestParam(defaultValue = "newest") String sort) {
		return issueService.commentsOf(id, page, size, sort, currentUser.require());
	}

	/** One page of a root comment's replies, oldest-first (flat, lazily loaded). */
	@GetMapping("/{id}/comments/{rootId}/replies")
	public Page<IssueComment> replies(@PathVariable String id, @PathVariable String rootId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return issueService.repliesOf(id, rootId, page, size, currentUser.require());
	}

	@PostMapping("/{id}/comments")
	@ResponseStatus(HttpStatus.CREATED)
	public IssueComment comment(@PathVariable String id, @RequestBody @Valid CommentRequest request) {
		return issueService.addComment(id, request.text(), request.replyToId(), currentUser.require());
	}

	@PatchMapping("/{id}/comments/{commentId}")
	public IssueComment editComment(@PathVariable String id, @PathVariable String commentId,
			@RequestBody @Valid CommentRequest request) {
		return issueService.editComment(id, commentId, request.text(), currentUser.require());
	}

	@DeleteMapping("/{id}/comments/{commentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteComment(@PathVariable String id, @PathVariable String commentId) {
		issueService.deleteComment(id, commentId, currentUser.require());
	}

	/** Toggle the caller's emoji reaction (WhatsApp-style: one per user). */
	@PutMapping("/{id}/comments/{commentId}/reactions")
	public IssueComment react(@PathVariable String id, @PathVariable String commentId,
			@RequestBody @Valid ReactionRequest request) {
		return issueService.reactToComment(id, commentId, request.emoji(), currentUser.require());
	}

	/** Pin/unpin a comment to the top of the thread. Any project member may do so. */
	@PutMapping("/{id}/comments/{commentId}/pin")
	public IssueComment pin(@PathVariable String id, @PathVariable String commentId,
			@RequestBody @Valid PinRequest request) {
		return issueService.setPinned(id, commentId, request.pinned(), currentUser.require());
	}

	/** Pinned comments of the thread, in pin order (surfaced above the feed). */
	@GetMapping("/{id}/comments/pinned")
	public List<IssueComment> pinnedComments(@PathVariable String id) {
		return issueService.pinnedComments(id, currentUser.require());
	}

	/**
	 * Live stream of comment-thread changes for an issue, so comments added,
	 * edited, deleted, reacted-to or pinned by anyone show up in real time.
	 */
	@GetMapping(value = "/{id}/comments/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter commentStream(@PathVariable String id) {
		Issue issue = issueService.getForUser(id, currentUser.require());
		return commentEvents.subscribe(issue.getId());
	}

	/**
	 * Posts a recorded voice message as a comment. The audio blob is multipart;
	 * {@code durationMs} and {@code peaks} (comma-separated 0–100 amplitudes)
	 * carry the pre-computed waveform so the feed can render it without decoding.
	 */
	@PostMapping(value = "/{id}/comments/voice", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public IssueComment voiceComment(@PathVariable String id,
			@RequestParam("file") org.springframework.web.multipart.MultipartFile file,
			@RequestParam(defaultValue = "0") int durationMs,
			@RequestParam(required = false) String peaks,
			@RequestParam(required = false) String replyToId) {
		List<Integer> parsedPeaks = new java.util.ArrayList<>();
		if (peaks != null && !peaks.isBlank()) {
			for (String part : peaks.split(",")) {
				String trimmed = part.trim();
				if (!trimmed.isEmpty()) {
					try {
						parsedPeaks.add(Integer.parseInt(trimmed));
					}
					catch (NumberFormatException ignored) {
						// Skip malformed peak values; the waveform degrades gracefully.
					}
				}
			}
		}
		return issueService.addVoiceComment(id, file, durationMs, parsedPeaks, replyToId,
				currentUser.require());
	}

	/**
	 * Streams a voice comment's audio bytes through the server (authorized
	 * per-issue), so the client never reaches the object store directly. Served
	 * inline for the in-app audio player.
	 */
	@GetMapping("/{id}/comments/{commentId}/voice")
	public ResponseEntity<byte[]> voiceAudio(@PathVariable String id, @PathVariable String commentId) {
		com.ahmadre.hinata.storage.StorageService.StoredObject object =
				issueService.loadVoice(id, commentId, currentUser.require());
		return ResponseEntity.ok()
				.header(HttpHeaders.ACCEPT_RANGES, "none")
				.contentType(MediaType.parseMediaType(object.contentType()))
				.body(object.data());
	}
}
