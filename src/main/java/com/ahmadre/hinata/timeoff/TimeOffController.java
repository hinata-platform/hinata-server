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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Balances, the journal behind them, the grants that start them, and the two employment dates the
 * arithmetic needs.
 *
 * <p>Every read defaults to the caller. Naming somebody else is a keeper's business and the
 * service decides that, not this controller — an endpoint that checked for itself would be a
 * second opinion about who sees what.
 *
 * <p>Behind {@link AbsenceManagementGate}: with the module off, none of this exists.
 */
@Tag(name = "Absence management")
@RestController
@RequestMapping("/api/v1/time-off")
@RequiredArgsConstructor
public class TimeOffController {

	private final TimeOffBalanceService timeOff;
	private final TimeOffTypeService types;
	private final CurrentUser currentUser;
	private final Clock clock;

	/**
	 * One type's standing for a year, in thousandths of a working day.
	 *
	 * <p>Thousandths on the wire as well as in storage: a client that received 8.33 as a decimal
	 * would have to decide how to add it up, and two clients would decide differently.
	 */
	public record BalanceResponse(String typeId, int year, int entitledMilliDays,
			int accruedMilliDays, int carriedInMilliDays, int adjustedMilliDays, int takenMilliDays,
			int plannedMilliDays, int expiredMilliDays, int paidOutMilliDays, int remainingMilliDays,
			LocalDate expiresOn, boolean granted, boolean unlimited, String reason,
			boolean belowLegalMinimum, int legalMinimumMilliDays) {
	}

	/** Everything a balance screen needs in one answer, including the statutory floor to warn about. */
	public record BalancesResponse(String userId, int year, int workingDaysPerWeek,
			List<BalanceResponse> balances) {
	}

	public record LedgerEntryResponse(String id, String typeId, int year, String kind, int milliDays,
			LocalDate effectiveOn, String reason, String actorId, String refId) {

		static LedgerEntryResponse from(TimeOffLedgerEntry entry) {
			return new LedgerEntryResponse(entry.getId(), entry.getTypeId(), entry.getYear(),
					String.valueOf(entry.getKind()), entry.milliDays(), entry.getEffectiveOn(),
					entry.getReason(), entry.getActorId(), entry.getRefId());
		}
	}

	public record EntitlementResponse(String id, String userId, String typeId, int year,
			int allowanceMilliDays, int accruedMilliDays, String source, String note) {

		static EntitlementResponse from(TimeOffEntitlement entitlement) {
			return new EntitlementResponse(entitlement.getId(), entitlement.getUserId(),
					entitlement.getTypeId(), entitlement.getYear(), entitlement.allowanceMilliDays(),
					entitlement.accruedMilliDays(), String.valueOf(entitlement.getSource()),
					entitlement.getNote());
		}
	}

	public record GrantPreviewResponse(String userId, int accruedMilliDays, String reason,
			boolean alreadyGranted) {
	}

	public record EmploymentResponse(String userId, LocalDate hiredOn, LocalDate leftOn, String note) {
	}

	/** Where one person stands for one type and year, as a keeper's list shows it. */
	public record StandingResponse(String userId, int entitledMilliDays, int accruedMilliDays,
			int takenMilliDays, int plannedMilliDays, int remainingMilliDays, boolean granted,
			LocalDate hiredOn, LocalDate leftOn) {

		static StandingResponse from(TimeOffBalanceService.Standing standing) {
			return new StandingResponse(standing.userId(), standing.entitledMilliDays(),
					standing.accruedMilliDays(), standing.takenMilliDays(), standing.plannedMilliDays(),
					standing.remainingMilliDays(), standing.granted(), standing.hiredOn(),
					standing.leftOn());
		}
	}

	/** Whether the caller keeps absences for everybody. */
	public record AccessResponse(boolean keeper) {
	}

	/** A grant for one person, or — with {@code userIds} — for several at once. */
	public record GrantRequest(
			String userId,
			@Size(max = TimeOffBalanceService.BULK_MAX) List<String> userIds,
			@NotBlank String typeId,
			@NotNull @Min(1970) @Max(2200) Integer year,
			@Min(0) @Max(TimeOffType.ALLOWANCE_MAX_MILLI_DAYS) Integer allowanceMilliDays,
			@Size(max = 500) String note) {
	}

	/** A keeper's correction, up or down, with the reason that makes it answerable later. */
	public record AdjustRequest(
			@NotBlank String userId,
			@NotBlank String typeId,
			@NotNull @Min(1970) @Max(2200) Integer year,
			@NotNull Integer milliDays,
			LocalDate effectiveOn,
			@NotBlank @Size(max = TimeOffLedgerEntry.REASON_MAX) String reason) {
	}

	public record EmploymentRequest(LocalDate hiredOn, LocalDate leftOn,
			@Size(max = TimeOffEmployment.NOTE_MAX) String note) {
	}

	// --- who is asking ------------------------------------------------------------

	/**
	 * Whether the caller may keep the catalogue and other people's entitlements.
	 *
	 * <p>Its own answer rather than a field on something else, because the screens that need it
	 * ask nothing else first: a keeper who is not an administrator has no other way to find the
	 * pages, and deriving it from the admin role in the client would hide them from exactly the
	 * people an operator named.
	 */
	@GetMapping("/access")
	public AccessResponse access() {
		return new AccessResponse(timeOff.isKeeper(currentUser.require()));
	}

	// --- balances ---------------------------------------------------------------

	@GetMapping("/balances")
	public BalancesResponse balances(@RequestParam(required = false) String userId,
			@RequestParam(required = false) Integer year) {
		User viewer = currentUser.require();
		int leaveYear = year != null ? year : LocalDate.now(clock).getYear();
		List<TimeOffBalanceService.Balance> balances = timeOff.balances(viewer, userId, leaveYear);
		String subject = userId == null || userId.isBlank() ? viewer.getId() : userId;
		// One reading of the working week and one of the catalogue for the whole screen: the floor
		// is a property of the person's week, not of each type, and a query per row would make a
		// balance screen cost as many round trips as the operator has types.
		int workingDays = timeOff.workingDaysPerWeek(subject);
		int minimum = TimeOffLegalFloor.minimumMilliDays(workingDays);
		Map<String, TimeOffType> catalogue = types.list(viewer, true).stream()
				.collect(Collectors.toMap(TimeOffType::getId, type -> type, (first, second) -> first));
		List<BalanceResponse> rows = balances.stream()
				.map(balance -> {
					TimeOffType type = catalogue.get(balance.typeId());
					boolean belowMinimum = type != null && TimeOffLegalFloor.fallsShort(type, workingDays);
					return new BalanceResponse(balance.typeId(), balance.year(),
							balance.entitledMilliDays(), balance.accruedMilliDays(),
							balance.carriedInMilliDays(), balance.adjustedMilliDays(),
							balance.takenMilliDays(), balance.plannedMilliDays(),
							balance.expiredMilliDays(), balance.paidOutMilliDays(),
							balance.remainingMilliDays(), balance.expiresOn(), balance.granted(),
							balance.unlimited(), String.valueOf(balance.reason()), belowMinimum, minimum);
				})
				.toList();
		return new BalancesResponse(subject, leaveYear, workingDays, rows);
	}

	@GetMapping("/balances/{userId}/{typeId}/{year}/ledger")
	public Page<LedgerEntryResponse> ledger(@PathVariable String userId, @PathVariable String typeId,
			@PathVariable int year,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return timeOff.ledger(currentUser.require(), userId, typeId, year, page, size)
				.map(LedgerEntryResponse::from);
	}

	/**
	 * A page of the directory beside where each person stands for one type and year.
	 *
	 * <p>Paged and searchable, because the list is the whole organisation and the screen is the
	 * one somebody grants a year from. A keeper's only.
	 */
	@GetMapping("/entitlements/overview")
	public Page<StandingResponse> overview(@RequestParam String typeId,
			@RequestParam(required = false) Integer year,
			@RequestParam(required = false, defaultValue = "") String q,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		int leaveYear = year != null ? year : LocalDate.now(clock).getYear();
		return timeOff.standings(currentUser.require(), typeId, leaveYear, q, page, size)
				.map(StandingResponse::from);
	}

	// --- granting ------------------------------------------------------------------

	@PostMapping("/entitlements")
	@ResponseStatus(HttpStatus.CREATED)
	public EntitlementResponse grant(@Valid @RequestBody GrantRequest request) {
		return EntitlementResponse.from(timeOff.grant(currentUser.require(), request.userId(),
				request.typeId(), request.year(), request.allowanceMilliDays(), request.note()));
	}

	/** What a bulk grant would do. Always available before the grant itself, never a side effect. */
	@PostMapping("/entitlements/preview")
	public List<GrantPreviewResponse> preview(@Valid @RequestBody GrantRequest request) {
		return timeOff.previewGrant(currentUser.require(), request.typeId(), request.year(),
						request.userIds(), request.allowanceMilliDays()).stream()
				.map(row -> new GrantPreviewResponse(row.userId(), row.accruedMilliDays(),
						String.valueOf(row.reason()), row.alreadyGranted()))
				.toList();
	}

	@PostMapping("/entitlements/bulk")
	public List<EntitlementResponse> grantMany(@Valid @RequestBody GrantRequest request) {
		return timeOff.grantMany(currentUser.require(), request.typeId(), request.year(),
						request.userIds(), request.allowanceMilliDays()).stream()
				.map(EntitlementResponse::from)
				.toList();
	}

	@PostMapping("/ledger")
	@ResponseStatus(HttpStatus.CREATED)
	public LedgerEntryResponse adjust(@Valid @RequestBody AdjustRequest request) {
		return LedgerEntryResponse.from(timeOff.adjust(currentUser.require(), request.userId(),
				request.typeId(), request.year(), request.milliDays(), request.effectiveOn(),
				request.reason()));
	}

	// --- employment dates --------------------------------------------------------------

	@GetMapping("/employment/{userId}")
	public EmploymentResponse employment(@PathVariable String userId) {
		return timeOff.employmentOf(currentUser.require(), userId)
				.map(facts -> new EmploymentResponse(facts.getUserId(), facts.getHiredOn(),
						facts.getLeftOn(), facts.getNote()))
				.orElseGet(() -> new EmploymentResponse(userId, null, null, null));
	}

	@PutMapping("/employment/{userId}")
	public EmploymentResponse saveEmployment(@PathVariable String userId,
			@Valid @RequestBody EmploymentRequest request) {
		TimeOffEmployment saved = timeOff.saveEmployment(currentUser.require(), userId,
				request.hiredOn(), request.leftOn(), request.note());
		return new EmploymentResponse(saved.getUserId(), saved.getHiredOn(), saved.getLeftOn(),
				saved.getNote());
	}
}
