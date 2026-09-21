package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The yearly run and what surrounds it (HIN-119): what the run did, who was not told, the
 * proposals after a long illness, the notices a person received, and the settlement of somebody
 * leaving. Keepers' pages, except the notices, which the person reads about themselves.
 *
 * <p>Behind {@link AbsenceManagementGate}.
 */
@Tag(name = "Absence management")
@RestController
@RequestMapping("/api/v1/time-off")
@RequiredArgsConstructor
public class TimeOffYearController {

	private final TimeOffYearDesk desk;
	private final TimeOffNotices notices;
	private final UserRepository users;
	private final CurrentUser currentUser;

	/** The last run, if there was one, and how many proposals wait. */
	public record YearRunResponse(RunResponse lastRun, long openProposals) {
	}

	public record RunResponse(String day, Instant startedAt, Instant finishedAt, int accrued, int carried,
			int expired, int held, int proposals, boolean failed) {

		static RunResponse from(TimeOffYearRunRecord run) {
			return new RunResponse(run.getId(), run.getStartedAt(), run.getFinishedAt(), run.getAccrued(),
					run.getCarried(), run.getExpired(), run.getHeld(), run.getProposals(), run.isFailed());
		}
	}

	public record ProposalResponse(String id, String userId, String name, String typeId, int year, Integer heldIn,
			int milliDays, Integer sickDays, LocalDate windowFrom, LocalDate windowTo, String status,
			Instant createdAt) {
	}

	public record NoticeResponse(String id, String typeId, int year, String kind, int remainingMilliDays,
			LocalDate expiresOn, Instant sentAt, boolean manual) {

		static NoticeResponse from(TimeOffNotice notice) {
			return new NoticeResponse(notice.getId(), notice.getTypeId(), notice.getYear(),
					notice.getKind() == null ? null : notice.getKind().name(), notice.remainingMilliDays(),
					notice.getExpiresOn(), notice.getSentAt(), notice.getKind() == TimeOffNotice.Kind.MANUAL);
		}
	}

	public record SettlementResponse(String typeId, int year, String reason, int accruedMilliDays,
			int bookedMilliDays, int correctionMilliDays, int remainingMilliDays, int payoutMilliDays) {
	}

	public record DecisionRequest(@NotBlank @Size(max = TimeOffProposal.REASON_MAX) String reason) {
	}

	public record NoticeRequest(@NotBlank String userId, @NotBlank String typeId, @NotNull Integer year) {
	}

	public record PayoutRequest(@NotBlank String userId, @NotBlank String typeId, @NotNull Integer year,
			@NotNull @Min(1) @Max(TimeOffType.ALLOWANCE_MAX_MILLI_DAYS) Integer milliDays,
			@NotBlank @Size(max = TimeOffLedgerEntry.REASON_MAX) String reason) {
	}

	// --- the run -----------------------------------------------------------------------

	@Operation(summary = "What the yearly run did last, and how many proposals wait")
	@GetMapping("/year-run")
	public YearRunResponse yearRun() {
		User actor = currentUser.require();
		RunResponse last = desk.lastRun(actor).map(RunResponse::from).orElse(null);
		return new YearRunResponse(last, desk.openProposals(actor, 0, 1).getTotalElements());
	}

	@Operation(summary = "People whose days would lapse without having been told")
	@GetMapping("/year-run/missing")
	public Page<TimeOffNotices.Missing> missing(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return notices.missing(currentUser.require(), page, size);
	}

	@Operation(summary = "Proposed lapses after a long illness, waiting for a keeper")
	@GetMapping("/year-run/proposals")
	public Page<ProposalResponse> proposals(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		Page<TimeOffProposal> open = desk.openProposals(currentUser.require(), page, size);
		Map<String, String> names = new HashMap<>();
		for (User person : users.findAllById(open.getContent().stream().map(TimeOffProposal::getUserId).toList())) {
			names.put(person.getId(), person.getDisplayName());
		}
		return open.map(proposal -> response(proposal, names.get(proposal.getUserId())));
	}

	@Operation(summary = "Confirm a proposed lapse, with a reason")
	@PostMapping("/year-run/proposals/{id}/confirm")
	public ProposalResponse confirm(@PathVariable String id, @Valid @RequestBody DecisionRequest request) {
		return response(desk.confirm(currentUser.require(), id, request.reason()), null);
	}

	@Operation(summary = "Dismiss a proposed lapse, with a reason")
	@PostMapping("/year-run/proposals/{id}/dismiss")
	public ProposalResponse dismiss(@PathVariable String id, @Valid @RequestBody DecisionRequest request) {
		return response(desk.dismiss(currentUser.require(), id, request.reason()), null);
	}

	private static ProposalResponse response(TimeOffProposal proposal, String name) {
		return new ProposalResponse(proposal.getId(), proposal.getUserId(), name, proposal.getTypeId(),
				proposal.getYear(), proposal.getHeldIn(), proposal.milliDays(), proposal.getSickDays(),
				proposal.getWindowFrom(), proposal.getWindowTo(), String.valueOf(proposal.getStatus()),
				proposal.getCreatedAt());
	}

	// --- notices -----------------------------------------------------------------------

	@Operation(summary = "The expiry notices one person received, newest first")
	@GetMapping("/notices")
	public Page<NoticeResponse> notices(@RequestParam(required = false) String userId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
		return notices.of(currentUser.require(), userId, page, size).map(NoticeResponse::from);
	}

	@Operation(summary = "Send an expiry notice now")
	@PostMapping("/notices")
	@ResponseStatus(HttpStatus.CREATED)
	public NoticeResponse sendNotice(@Valid @RequestBody NoticeRequest request) {
		return NoticeResponse.from(notices.sendNow(currentUser.require(), request.userId(), request.typeId(),
				request.year()));
	}

	// --- leaving ------------------------------------------------------------------------

	@Operation(summary = "What settling somebody's leave would take, with their leaving date")
	@GetMapping("/employment/{userId}/settlement")
	public List<SettlementResponse> settlement(@PathVariable String userId) {
		return desk.settlement(currentUser.require(), userId).stream()
				.map(row -> new SettlementResponse(row.typeId(), row.year(),
						row.reason() == null ? null : row.reason().name(), row.accruedMilliDays(),
						row.bookedMilliDays(), row.correctionMilliDays(), row.remainingMilliDays(),
						row.payoutMilliDays()))
				.toList();
	}

	@Operation(summary = "Book a payout in days")
	@PostMapping("/payouts")
	@ResponseStatus(HttpStatus.CREATED)
	public TimeOffController.LedgerEntryResponse payout(@Valid @RequestBody PayoutRequest request) {
		return TimeOffController.LedgerEntryResponse.from(desk.payout(currentUser.require(), request.userId(),
				request.typeId(), request.year(), request.milliDays(), request.reason()));
	}
}
