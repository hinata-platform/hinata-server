package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The catalogue of absence types over HTTP.
 *
 * <p>Reading is open to every member — the picker needs it. Keeping is a keeper's, and the service
 * decides that, not this controller, so the admin screen and the seeder cannot end up with two
 * opinions.
 *
 * <p>Behind {@link AbsenceManagementGate}: with the module off, none of this exists.
 */
@Tag(name = "Absence management")
@RestController
@RequestMapping("/api/v1/time-off/types")
@RequiredArgsConstructor
public class TimeOffTypeController {

	private final TimeOffTypeService types;
	private final CurrentUser currentUser;

	/**
	 * A type on the wire.
	 *
	 * <p>{@code name} may be null, and that is the answer for one of the three built-ins nobody has
	 * renamed: the client renders the translated label for {@code systemKey}. {@code requiresApproval}
	 * is the <em>enforced</em> value rather than the stored flag — a sick type reads false here
	 * however the document was written (R11), because that is what the product will actually do.
	 */
	public record TimeOffTypeResponse(String id, String key, String name, String systemKey,
			String icon, Integer hue, TimeOffType.Kind kind, boolean paid,
			boolean countsAgainstBalance, boolean unlimited, boolean requiresApproval,
			TimeOffType.ApproverRule approverRule, boolean halfDaysAllowed, boolean fractionAllowed,
			Integer minNoticeDays, Integer maxConsecutiveDays, boolean negativeBalanceAllowed,
			Integer negativeLimitMilliDays, TimeOffType.Visibility visibility,
			TimeOffType.Accrual accrual, int allowanceMilliDays, int yearAnchorMonth,
			int yearAnchorDay, int waitingPeriodMonths, boolean prorateOnJoin, boolean prorateOnLeave,
			TimeOffType.Carryover carryover, Integer carryoverCapMilliDays, int carryoverExpiresMonth,
			int carryoverExpiresDay, boolean active) {

		static TimeOffTypeResponse from(TimeOffType type) {
			return new TimeOffTypeResponse(type.getId(), type.getKey(), type.getName(),
					type.getSystemKey(), TimeOffIcons.orDefault(type.getIcon()), type.getHue(),
					type.getKind(), Boolean.TRUE.equals(type.getPaid()), type.countsAgainstBalance(),
					type.isUnlimited(), type.requiresApproval(), type.approverRule(),
					Boolean.TRUE.equals(type.getHalfDaysAllowed()),
					Boolean.TRUE.equals(type.getFractionAllowed()), type.getMinNoticeDays(),
					type.getMaxConsecutiveDays(), type.negativeBalanceAllowed(),
					type.getNegativeLimitMilliDays(), type.visibility(), type.accrual(),
					type.allowanceMilliDays(), type.yearAnchor().getMonthValue(),
					type.yearAnchor().getDayOfMonth(), type.waitingPeriodMonths(),
					type.prorateOnJoin(), type.prorateOnLeave(), type.carryover(),
					type.getCarryoverCapMilliDays(), type.carryoverExpiresOn().getMonthValue(),
					type.carryoverExpiresOn().getDayOfMonth(), type.isActive());
		}
	}

	/** A new type, or an edit. On a patch every field is optional and what is absent is left alone. */
	public record TimeOffTypeRequest(
			@Size(max = TimeOffType.KEY_MAX) String key,
			@Size(max = TimeOffType.NAME_MAX) String name,
			@Size(max = 40) String icon,
			@Min(0) @Max(359) Integer hue,
			TimeOffType.Kind kind,
			Boolean paid,
			Boolean countsAgainstBalance,
			Boolean unlimited,
			Boolean approvalRequired,
			TimeOffType.ApproverRule approverRule,
			Boolean halfDaysAllowed,
			Boolean fractionAllowed,
			@Min(0) @Max(TimeOffTypeService.MIN_NOTICE_DAYS_MAX) Integer minNoticeDays,
			@Min(1) @Max(366) Integer maxConsecutiveDays,
			Boolean negativeBalanceAllowed,
			@Min(0) @Max(TimeOffType.ALLOWANCE_MAX_MILLI_DAYS) Integer negativeLimitMilliDays,
			TimeOffType.Visibility visibility,
			TimeOffType.Accrual accrual,
			@Min(0) @Max(TimeOffType.ALLOWANCE_MAX_MILLI_DAYS) Integer allowanceMilliDays,
			@Min(1) @Max(12) Integer yearAnchorMonth,
			@Min(1) @Max(31) Integer yearAnchorDay,
			@Min(0) @Max(12) Integer waitingPeriodMonths,
			Boolean prorateOnJoin,
			Boolean prorateOnLeave,
			TimeOffType.Carryover carryover,
			@Min(0) @Max(TimeOffType.ALLOWANCE_MAX_MILLI_DAYS) Integer carryoverCapMilliDays,
			@Min(1) @Max(12) Integer carryoverExpiresMonth,
			@Min(1) @Max(31) Integer carryoverExpiresDay,
			Boolean active) {

		TimeOffTypeService.Draft toDraft() {
			return new TimeOffTypeService.Draft(key, name, icon, hue, kind, paid,
					countsAgainstBalance, unlimited, approvalRequired, approverRule, halfDaysAllowed,
					fractionAllowed, minNoticeDays, maxConsecutiveDays, negativeBalanceAllowed,
					negativeLimitMilliDays, visibility, accrual, allowanceMilliDays, yearAnchorMonth,
					yearAnchorDay, waitingPeriodMonths, prorateOnJoin, prorateOnLeave, carryover,
					carryoverCapMilliDays, carryoverExpiresMonth, carryoverExpiresDay, active);
		}
	}

	/**
	 * The catalogue. {@code includeInactive} is honoured for a keeper and ignored for everybody
	 * else: a member's picker offers what is on offer, and a type somebody retired is not.
	 */
	@GetMapping
	public List<TimeOffTypeResponse> list(
			@RequestParam(defaultValue = "false") boolean includeInactive) {
		return types.list(currentUser.require(), includeInactive).stream()
				.map(TimeOffTypeResponse::from)
				.toList();
	}

	@GetMapping("/{id}")
	public TimeOffTypeResponse get(@PathVariable String id) {
		return TimeOffTypeResponse.from(types.require(currentUser.require(), id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TimeOffTypeResponse create(@Valid @RequestBody TimeOffTypeRequest request) {
		return TimeOffTypeResponse.from(types.create(currentUser.require(), request.toDraft()));
	}

	@PatchMapping("/{id}")
	public TimeOffTypeResponse update(@PathVariable String id,
			@Valid @RequestBody TimeOffTypeRequest request) {
		return TimeOffTypeResponse.from(types.update(currentUser.require(), id, request.toDraft()));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		types.delete(currentUser.require(), id);
	}
}
