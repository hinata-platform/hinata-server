package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Asking for time off, deciding it, taking it back — and reporting sickness, which is none of
 * those.
 *
 * <p>Every route here is somebody acting for themselves or on a request they were named on. Who
 * may do what is the service's question and not this one's: a controller that checked as well
 * would be a second opinion, and the day the two disagreed the weaker one would be the rule.
 *
 * <p>Behind {@link AbsenceManagementGate}: with the module off, none of this exists — including
 * the sick report, which has no meaning without a catalogue to file it under.
 */
@Tag(name = "Absence management")
@RestController
@RequestMapping("/api/v1/time-off")
@RequiredArgsConstructor
public class TimeOffRequestController {

	private final TimeOffRequestService requests;
	private final CurrentUser currentUser;

	// --- what goes out ------------------------------------------------------------

	/**
	 * A request as a list or a detail screen reads it.
	 *
	 * <p>Thousandths on the wire for the same reason as a balance: a client handed 8.33 would have
	 * to decide how to add it up, and two clients would decide differently.
	 *
	 * <p>[balanceShort] is a yes or no and never a figure. A lead deciding leave for their team
	 * learns whether the days are there; how many somebody has left is the person's own (R2, R10).
	 */
	public record RequestResponse(String id, String userId, String personName, String typeId,
			String typeKey, String typeSystemKey, LocalDate from, LocalDate to,
			Integer firstDayMilliDays, Integer lastDayMilliDays, int milliDays, Integer workingDays,
			Integer holidays, String note, String status, List<String> approverIds, String decidedBy,
			Instant decidedAt, String decisionNote, String substituteId, String timeOffId,
			boolean balanceShort, boolean shortNotice, int clashes, List<EventResponse> history,
			Instant createdAt, Instant updatedAt) {

		static RequestResponse from(TimeOffRequestService.View view) {
			TimeOffRequest request = view.request();
			return new RequestResponse(request.getId(), request.getUserId(), view.personName(),
					request.getTypeId(), view.typeKey(), view.typeSystemKey(), request.getFrom(),
					request.getTo(), request.getFirstDayMilliDays(), request.getLastDayMilliDays(),
					request.milliDays(), request.getWorkingDays(), request.getHolidays(),
					request.getNote(), name(request.getStatus()), List.copyOf(request.getApproverIds()),
					request.getDecidedBy(), request.getDecidedAt(), request.getDecisionNote(),
					request.getSubstituteId(), request.getTimeOffId(), view.balanceShort(),
					view.shortNotice(), view.clashes(),
					request.getHistory().stream().map(EventResponse::from).toList(),
					request.getCreatedAt(), request.getUpdatedAt());
		}
	}

	/** One step of the story. The ledger id stays here — a client has no use for it. */
	public record EventResponse(Instant at, String by, String from, String to, String note) {

		static EventResponse from(TimeOffRequest.Event event) {
			return new EventResponse(event.getAt(), event.getBy(), name(event.getFrom()),
					name(event.getTo()), event.getNote());
		}
	}

	/** What a span would cost, while somebody is still picking the dates. */
	public record PreviewResponse(int milliDays, int workingDays, int holidays, int daysOff,
			boolean balanceShort, boolean shortNotice) {
	}

	/** Somebody else away across the same span: who and when, never what kind of absence. */
	public record ClashResponse(String userId, String name, LocalDate from, LocalDate to,
			boolean approved) {
	}

	/** What reporting sickness produced, including any leave § 9 BUrlG handed back. */
	public record SickResponse(String absenceId, int milliDays, int returnedMilliDays) {
	}

	/**
	 * An enum's name on the wire, or a real JSON null.
	 *
	 * <p>{@code String.valueOf(null)} is the string "null", which a client cannot tell from a
	 * status called null — and the one that read it built a translation key out of it and printed
	 * it on the screen (found in A1's live check).
	 */
	private static String name(Enum<?> value) {
		return value == null ? null : value.name();
	}

	// --- what comes in ------------------------------------------------------------

	/**
	 * A request, or the preview of one.
	 *
	 * <p>[firstDayMilliDays] and [lastDayMilliDays] are the thousandths the edge days count for;
	 * left out they are whole days. Only the edges, because only the edges can be partial.
	 */
	public record SubmitRequest(
			@NotBlank String typeId,
			@NotNull LocalDate from,
			LocalDate to,
			@Min(1) @Max(TimeOffType.DAY) Integer firstDayMilliDays,
			@Min(1) @Max(TimeOffType.DAY) Integer lastDayMilliDays,
			@Size(max = TimeOffRequest.NOTE_MAX) String note,
			String substituteId) {

		TimeOffRequestService.Draft toDraft() {
			return new TimeOffRequestService.Draft(typeId, from, to, firstDayMilliDays,
					lastDayMilliDays, note, substituteId);
		}
	}

	/** A word on a decision. Required on a rejection, and the service is where that is enforced. */
	public record DecisionRequest(@Size(max = TimeOffRequest.NOTE_MAX) String note) {
	}

	/**
	 * A sick report. [to] left out means the one day, and nothing here is required but the date.
	 *
	 * <p>No reason, no certificate, no field that could carry one: since 2023 an employer collects
	 * the certificate from the health insurer under § 109 SGB IV, and a place to upload one in a
	 * project tool would be Art. 9 data nobody asked for (R11).
	 */
	public record SickRequest(@NotNull LocalDate from, LocalDate to, Boolean halfDay, String typeId) {
	}

	// --- reading ------------------------------------------------------------------

	/** Somebody's own requests. Never anybody else's: there is no parameter for one. */
	@GetMapping("/requests")
	public Page<RequestResponse> mine(@RequestParam(required = false) String status,
			@RequestParam(required = false) @Min(1970) @Max(2200) Integer year,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return requests.mine(currentUser.require(), status(status), year, page, size)
				.map(RequestResponse::from);
	}

	/** What the caller has to decide. */
	@GetMapping("/requests/inbox")
	public Page<RequestResponse> inbox(@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return requests.inbox(currentUser.require(), status(status), page, size)
				.map(RequestResponse::from);
	}

	/**
	 * Who else is away across a span, for a decider weighing one request against the team it
	 * leaves behind (§ 7 Abs. 1 BUrlG).
	 *
	 * <p>Before {@code /requests/{id}} in the file and in the mapping order, because
	 * {@code conflicts} would otherwise be read as an id.
	 */
	@GetMapping("/requests/conflicts")
	public List<ClashResponse> conflicts(@RequestParam LocalDate from, @RequestParam LocalDate to) {
		return requests.conflicts(currentUser.require(), from, to).stream()
				.map(clash -> new ClashResponse(clash.userId(), clash.name(), clash.from(), clash.to(),
						clash.approved()))
				.toList();
	}

	/** One request, for anybody it concerns. Everybody else gets 404 rather than 403. */
	@GetMapping("/requests/{id}")
	public RequestResponse get(@PathVariable String id) {
		return RequestResponse.from(requests.view(id, currentUser.require()));
	}

	// --- writing ------------------------------------------------------------------

	/** What a span would cost. Read-only, and available before anybody commits to anything. */
	@PostMapping("/requests/preview")
	public PreviewResponse preview(@Valid @RequestBody SubmitRequest request) {
		TimeOffRequestService.Preview preview = requests.preview(currentUser.require(), request.toDraft());
		return new PreviewResponse(preview.milliDays(), preview.workingDays(), preview.holidays(),
				preview.daysOff(), preview.balanceShort(), preview.shortNotice());
	}

	@PostMapping("/requests")
	@ResponseStatus(HttpStatus.CREATED)
	public RequestResponse submit(@Valid @RequestBody SubmitRequest request) {
		User person = currentUser.require();
		return RequestResponse.from(requests.view(requests.submit(person, request.toDraft()), person));
	}

	/** Changes a request that is still waiting. Only the person who made it; 409 once decided. */
	@PatchMapping("/requests/{id}")
	public RequestResponse edit(@PathVariable String id, @Valid @RequestBody SubmitRequest request) {
		User person = currentUser.require();
		return RequestResponse.from(requests.view(requests.edit(id, person, request.toDraft()), person));
	}

	@PostMapping("/requests/{id}/approve")
	public RequestResponse approve(@PathVariable String id, @Valid @RequestBody(required = false) DecisionRequest body) {
		User decider = currentUser.require();
		return RequestResponse.from(
				requests.view(requests.approve(id, body == null ? null : body.note(), decider), decider));
	}

	@PostMapping("/requests/{id}/reject")
	public RequestResponse reject(@PathVariable String id, @Valid @RequestBody DecisionRequest body) {
		User decider = currentUser.require();
		return RequestResponse.from(
				requests.view(requests.reject(id, body == null ? null : body.note(), decider), decider));
	}

	@PostMapping("/requests/{id}/withdraw")
	public RequestResponse withdraw(@PathVariable String id) {
		User person = currentUser.require();
		return RequestResponse.from(requests.view(requests.withdraw(id, person), person));
	}

	@PostMapping("/requests/{id}/cancel")
	public RequestResponse cancel(@PathVariable String id, @Valid @RequestBody(required = false) DecisionRequest body) {
		User actor = currentUser.require();
		return RequestResponse.from(
				requests.view(requests.cancel(id, body == null ? null : body.note(), actor), actor));
	}

	/**
	 * Reports sickness. One step, effective at once, and nothing here can refuse it.
	 *
	 * <p>Not under {@code /requests}, because it is not one: § 5 EFZG knows a notification and not
	 * a permission, and a route that shared the path would eventually share a rule (R11).
	 */
	@PostMapping("/sick")
	@ResponseStatus(HttpStatus.CREATED)
	public SickResponse reportSick(@Valid @RequestBody SickRequest body) {
		TimeOffRequestService.Sick sick = requests.reportSick(currentUser.require(), body.from(),
				body.to(), Boolean.TRUE.equals(body.halfDay()), body.typeId());
		return new SickResponse(sick.absenceId(), sick.milliDays(), sick.returnedMilliDays());
	}

	/**
	 * A status filter, or null for all of them.
	 *
	 * <p>An unknown word is null rather than a 400: a filter chip from a newer client asking for a
	 * status this server has never heard of should show everything, not an error page. The same
	 * call the rest of the module makes.
	 */
	private static TimeOffRequest.Status status(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return TimeOffRequest.Status.valueOf(value.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException unknown) {
			return null;
		}
	}
}
