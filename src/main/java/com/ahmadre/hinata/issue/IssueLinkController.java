package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Tag(name = "Issue links")
@RestController
@RequestMapping("/api/v1/issues/{issueId}/links")
@RequiredArgsConstructor
public class IssueLinkController {

	private final IssueLinkService linkService;
	private final IssueLinkEvents events;
	private final IssueService issueService;
	private final CurrentUser currentUser;

	/**
	 * @param type      the link type (BLOCKS, DUPLICATES, …)
	 * @param outward   whether this issue is the source/subject of the verb
	 *                  (e.g. "blocks" = true, "is blocked by" = false)
	 * @param targetIds the issues to link to (ids or readable ids)
	 */
	public record CreateLinksRequest(
			@NotNull IssueLinkType type,
			boolean outward,
			@NotEmpty List<String> targetIds) {
	}

	@GetMapping
	public List<IssueLinkService.LinkView> links(@PathVariable String issueId) {
		return linkService.linksOf(issueId, currentUser.require());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public List<IssueLinkService.LinkView> create(@PathVariable String issueId,
			@RequestBody @jakarta.validation.Valid CreateLinksRequest request) {
		return linkService.addLinks(issueId, request.type(), request.outward(),
				request.targetIds(), currentUser.require());
	}

	@DeleteMapping("/{linkId}")
	public List<IssueLinkService.LinkView> delete(@PathVariable String issueId,
			@PathVariable String linkId) {
		return linkService.deleteLink(issueId, linkId, currentUser.require());
	}

	/**
	 * Live stream of link changes for an issue, so links added/removed here or on
	 * the issue at the other end of a link show up in real time.
	 */
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@PathVariable String issueId) {
		User viewer = currentUser.require();
		Issue issue = issueService.getForUser(issueId, viewer);
		return events.subscribe(issue.getId(), viewer.getId());
	}
}
