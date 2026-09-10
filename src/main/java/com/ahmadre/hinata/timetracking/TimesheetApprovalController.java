package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Timesheet submission and approval.
 *
 * <p>Behind {@link AdvancedTimeTrackingGate} like the rest of
 * {@code /api/v1/time}, and behind the policy on top of that: with
 * {@code approvalsEnabled} off every route here answers 404, because a feature
 * that is not switched on does not exist rather than being forbidden. That is the
 * same answer the module's own gate gives, so a client cannot tell — and does not
 * need to tell — which of the two switches is off.
 *
 * <p>Its own controller rather than methods on {@link TimeEntryController}: every
 * route there answers about the caller's own entries and offers no way to ask
 * otherwise, while half of these are about reading and deciding somebody else's
 * recorded working time. That is the boundary worth seeing at a glance when
 * reading who may read what.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time/approvals")
@RequiredArgsConstructor
public class TimesheetApprovalController {

	private final TimesheetApprovalService approvals;
	private final CurrentUser currentUser;

	// --- DTOs -------------------------------------------------------------------

	/**
	 * One submission as a client reads it.
	 *
	 * <p>{@code history} travels with it rather than on a route of its own: it is
	 * what makes a rejection legible, it is bounded at fifty entries, and an
	 * approver deciding a period is exactly the reader who needs to see how it got
	 * here.
	 */
	public record ApprovalResponse(String id, String userId, String projectId,
			LocalDate periodStart, LocalDate periodEnd, TimePolicy.ApprovalPeriod periodType,
			TimesheetApproval.Status status, int totalMinutes, Instant submittedAt,
			String decidedBy, Instant decidedAt, String note, List<EventResponse> history) {

		static ApprovalResponse of(TimesheetApproval approval) {
			return new ApprovalResponse(approval.getId(), approval.getUserId(),
					approval.getProjectId(), approval.getPeriodStart(), approval.getPeriodEnd(),
					approval.getPeriodType(), approval.getStatus(), approval.getTotalMinutes(),
					approval.getSubmittedAt(), approval.getDecidedBy(), approval.getDecidedAt(),
					approval.getNote(),
					approval.getHistory() == null ? List.of()
							: approval.getHistory().stream().map(EventResponse::of).toList());
		}
	}

	public record EventResponse(Instant at, String by, TimesheetApproval.Status from,
			TimesheetApproval.Status to, String note) {

		static EventResponse of(TimesheetApproval.Event event) {
			return new EventResponse(event.getAt(), event.getBy(), event.getFrom(), event.getTo(),
					event.getNote());
		}
	}

	/** A span to hand in. {@code projectIds} narrows it; absent means every project with hours. */
	@Data
	public static final class SubmitRequest {
		@NotNull(message = "error.time.invalidRange")
		private LocalDate periodStart;
		@NotNull(message = "error.time.invalidRange")
		private LocalDate periodEnd;
		@Size(max = 100, message = "error.time.tooManyProjects")
		private List<String> projectIds;
	}

	/** A decision's reason. Required for a rejection and for a reopen. */
	@Data
	public static final class NoteRequest {
		@Size(max = TimePolicy.LOCK_NOTE_MAX, message = "error.time.noteTooLong")
		private String note;
	}

	// --- routes -------------------------------------------------------------------

	/**
	 * The periods overlapping a window and where the caller stands in each.
	 *
	 * <p>The window is the client's, so a switcher asks for the one period it is
	 * drawing plus its neighbours rather than a year of them. Naming a project asks
	 * about that project's rhythm; naming none asks about the instance's.
	 */
	@GetMapping("/periods")
	public List<TimesheetApprovalService.PeriodView> periods(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String projectId) {
		return approvals.periods(from, to, projectId, currentUser.require());
	}

	/**
	 * The caller's own submissions, or the ones waiting for their decision.
	 *
	 * <p>One route with a {@code scope} rather than two, because the shape of the
	 * answer is identical and the difference is one rule. The rule itself is not
	 * here — {@code inbox} asks which projects the caller leads, {@code mine} asks
	 * nothing — so there is no chance of the two drifting into one query with a
	 * flag in it.
	 */
	@GetMapping
	public Page<ApprovalResponse> list(
			@RequestParam(defaultValue = "mine") String scope,
			@RequestParam(required = false) TimesheetApproval.Status status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		User user = currentUser.require();
		Page<TimesheetApproval> found = switch (scope) {
			case "mine" -> approvals.mine(status, page, size, user);
			case "inbox" -> approvals.inbox(status, page, size, user);
			default -> throw ApiException.badRequest("error.time.approvalScopeInvalid");
		};
		return found.map(ApprovalResponse::of);
	}

	@GetMapping("/{id}")
	public ApprovalResponse get(@PathVariable String id) {
		return ApprovalResponse.of(approvals.get(id, currentUser.require()));
	}

	/** The entries the submission covers — what an approver actually reads. */
	@GetMapping("/{id}/entries")
	public Page<TimeTrackingController.WorkItemResponse> entries(@PathVariable String id,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return approvals.entriesOf(id, page, size, currentUser.require())
				.map(TimeTrackingController.WorkItemResponse::from);
	}

	@PostMapping("/submit")
	public List<ApprovalResponse> submit(@RequestBody @Valid SubmitRequest request) {
		return approvals.submit(request.getPeriodStart(), request.getPeriodEnd(),
						request.getProjectIds(), currentUser.require())
				.stream().map(ApprovalResponse::of).toList();
	}

	@PostMapping("/{id}/withdraw")
	public ApprovalResponse withdraw(@PathVariable String id) {
		return ApprovalResponse.of(approvals.withdraw(id, currentUser.require()));
	}

	@PostMapping("/{id}/approve")
	public ApprovalResponse approve(@PathVariable String id,
			@RequestBody(required = false) @Valid NoteRequest request) {
		return ApprovalResponse.of(approvals.decide(id, TimesheetApproval.Status.APPROVED,
				request == null ? null : request.getNote(), currentUser.require()));
	}

	@PostMapping("/{id}/reject")
	public ApprovalResponse reject(@PathVariable String id,
			@RequestBody(required = false) @Valid NoteRequest request) {
		return ApprovalResponse.of(approvals.decide(id, TimesheetApproval.Status.REJECTED,
				request == null ? null : request.getNote(), currentUser.require()));
	}

	@PostMapping("/{id}/reopen")
	public ApprovalResponse reopen(@PathVariable String id,
			@RequestBody(required = false) @Valid NoteRequest request) {
		return ApprovalResponse.of(approvals.reopen(id,
				request == null ? null : request.getNote(), currentUser.require()));
	}
}
