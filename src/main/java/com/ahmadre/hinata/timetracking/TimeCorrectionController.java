package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.TimePolicy;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
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

import java.time.LocalDate;
import java.util.List;

/**
 * Correction requests, their answers, and days opened for one person. The ask about an
 * entry itself stays where the entry is — {@code POST /time/entries/{id}/correction-request}.
 * See {@link TimeCorrectionService}.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time")
@RequiredArgsConstructor
public class TimeCorrectionController {

	private final TimeCorrectionService corrections;
	private final CurrentUser currentUser;

	/** Days to be opened, and why. Bounded like a lock exception. */
	@Data
	public static final class BackfillAsk {
		@NotNull(message = "error.time.lockExceptionInvalid")
		private LocalDate from;
		@NotNull(message = "error.time.lockExceptionInvalid")
		private LocalDate to;
		@Size(max = TimePolicy.LOCK_NOTE_MAX, message = "error.time.noteTooLong")
		private String note;
	}

	/** The requests this caller can answer, newest first. */
	@GetMapping("/correction-requests")
	public Page<TimeCorrectionService.CorrectionRequest> inbox(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return corrections.inbox(page, size, currentUser.require());
	}

	@PostMapping("/correction-requests/{id}/answer")
	public TimeCorrectionService.CorrectionRequest answer(@PathVariable String id,
			@RequestBody @Valid TimesheetApprovalController.NoteRequest request) {
		return corrections.answer(id, request.getNote(), currentUser.require());
	}

	/** Answers by opening the days for the person who asked. Administrators only. */
	@PostMapping("/correction-requests/{id}/grant")
	public TimeCorrectionService.CorrectionRequest grant(@PathVariable String id,
			@RequestBody @Valid TimesheetApprovalController.NoteRequest request) {
		return corrections.grant(id, request.getNote(), currentUser.require());
	}

	/** The caller's own requests about one of their entries, with the answers. */
	@GetMapping("/entries/{id}/correction-requests")
	public List<TimeCorrectionService.CorrectionRequest> forEntry(@PathVariable String id) {
		return corrections.forEntry(id, currentUser.require());
	}

	@PostMapping("/backfill-requests")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public void requestBackfill(@RequestBody @Valid BackfillAsk request) {
		corrections.requestBackfill(request.getFrom(), request.getTo(), request.getNote(),
				currentUser.require());
	}

	/** Days currently opened for somebody. Administrators only. */
	@GetMapping("/backfill-grants")
	public Page<TimeCorrectionService.Grant> grants(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return corrections.activeGrants(page, size, currentUser.require());
	}

	@DeleteMapping("/backfill-grants/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revoke(@PathVariable String id) {
		corrections.revokeGrant(id, currentUser.require());
	}
}
