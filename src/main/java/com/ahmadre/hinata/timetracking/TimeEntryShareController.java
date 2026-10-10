package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Shared entries (HIN-95): offering a copy of one's entry to colleagues, and answering such an
 * offer. Behind {@link AdvancedTimeTrackingGate} like everything under {@code /api/v1/time}.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time")
@RequiredArgsConstructor
public class TimeEntryShareController {

	private final TimeEntryShareService shares;
	private final CurrentUser currentUser;

	public record ShareRequest(
			@NotEmpty @Size(max = TimeEntryShareService.RECIPIENTS_MAX) List<@Size(max = 64) String> userIds) {
	}

	public record AcceptRequest(@Size(max = 64) String projectId, @Size(max = 64) String issueId,
			@Size(max = 20) List<@Size(max = TimeTag.MAX_NAME) String> tags) {
	}

	/** The people an entry of {@code projectId} may be offered to, paged and searchable by name. */
	@GetMapping("/share-candidates")
	public Page<TimeEntryShareService.Candidate> candidates(@RequestParam String projectId,
			@RequestParam(defaultValue = "") @Size(max = 100) String q,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return shares.candidates(projectId, q, page, size, currentUser.require());
	}

	/** Offers the caller's own entry to project members; answers with every invitation of the entry. */
	@PostMapping("/entries/{id}/share")
	public List<TimeEntryShareService.ShareView> share(@PathVariable String id,
			@Valid @RequestBody ShareRequest body) {
		return shares.share(id, body.userIds(), currentUser.require());
	}

	/** Every invitation of the caller's own entry. */
	@GetMapping("/entries/{id}/shares")
	public List<TimeEntryShareService.ShareView> sharesOfEntry(@PathVariable String id) {
		return shares.sharesOfEntry(id, currentUser.require());
	}

	/** Takes an unanswered invitation back. */
	@DeleteMapping("/entries/{id}/share/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revoke(@PathVariable String id, @PathVariable String userId) {
		shares.revoke(id, userId, currentUser.require());
	}

	/** {@code box=inbox}: open invitations to the caller; {@code box=sent}: what the caller offered. */
	@GetMapping("/shares")
	public Page<TimeEntryShareService.ShareView> page(@RequestParam(defaultValue = "inbox") String box,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		TimeEntryShareService.Box which = switch (box.toLowerCase(Locale.ROOT)) {
			case "sent" -> TimeEntryShareService.Box.SENT;
			case "inbox" -> TimeEntryShareService.Box.INBOX;
			default -> throw ApiException.badRequest("error.time.share.box");
		};
		return shares.page(which, page, size, currentUser.require());
	}

	/** Files the copy in the caller's account; idempotent. */
	@PostMapping("/shares/{id}/accept")
	public TimeTrackingController.WorkItemResponse accept(@PathVariable String id,
			@Valid @RequestBody(required = false) AcceptRequest body) {
		TimeEntryShareService.Acceptance acceptance = body == null ? null
				: new TimeEntryShareService.Acceptance(body.projectId(), body.issueId(), body.tags());
		return TimeTrackingController.WorkItemResponse.from(shares.accept(id, acceptance, currentUser.require()));
	}

	/** Says no, silently. */
	@PostMapping("/shares/{id}/decline")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void decline(@PathVariable String id) {
		shares.decline(id, currentUser.require());
	}
}
