package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.MonthDay;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The catalogue of absence types: what an operator offers, and the rules that come with each.
 *
 * <p>Reading is open — a picker needs the list, and "this organisation offers parental leave" is
 * nobody's personal data. Keeping is a keeper's ({@link TimeOffAccess}).
 *
 * <p>Two refusals are worth knowing about before reading the code:
 *
 * <ul>
 * <li><b>A sick type is never subject to approval.</b> Storing {@code approvalRequired} on a type
 * of the {@code SICK} kind is refused outright rather than quietly ignored, so the screen can say
 * why: § 5 EFZG gives an employee a duty to notify, not to ask, and a product that let somebody
 * reject a sick note would be offering a conversation the law does not allow (R11).</li>
 * <li><b>A type in use is switched off, not deleted.</b> Entitlements and bookings point at it by
 * id, and deleting the row would leave a balance nobody can explain. Deactivating keeps the
 * history readable and takes it out of the picker, which is what "we do not offer this any more"
 * actually means.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TimeOffTypeService {

	/** A key is a slug: lower-case, starts with a letter or digit, no spaces. */
	private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");

	/** Longest notice a type may ask for: a year. Beyond that it is not a notice period. */
	static final int MIN_NOTICE_DAYS_MAX = 365;

	private final TimeOffTypeRepository types;
	private final TimeOffLedgerRepository ledger;
	private final TimeOffEntitlementRepository entitlements;
	private final TimeOffAccess access;
	private final AuditService audit;
	private final Clock clock;

	/**
	 * A type as it arrives from a client. Every field is optional on a patch; on a create the
	 * defaults in {@link TimeOffType} apply to whatever is missing.
	 */
	@Builder(toBuilder = true)
	public record Draft(String key, String name, String icon, Integer hue, TimeOffType.Kind kind,
			Boolean paid, Boolean countsAgainstBalance, Boolean unlimited, Boolean approvalRequired,
			TimeOffType.ApproverRule approverRule, Boolean halfDaysAllowed, Boolean fractionAllowed,
			Integer minNoticeDays, Integer maxConsecutiveDays, Boolean negativeBalanceAllowed,
			Integer negativeLimitMilliDays, TimeOffType.Visibility visibility,
			TimeOffType.Accrual accrual, Integer allowanceMilliDays, Integer yearAnchorMonth,
			Integer yearAnchorDay, Integer waitingPeriodMonths, Boolean prorateOnJoin,
			Boolean prorateOnLeave, TimeOffType.Carryover carryover, Integer carryoverCapMilliDays,
			Integer carryoverExpiresMonth, Integer carryoverExpiresDay, Boolean active) {
	}

	// --- reading ---------------------------------------------------------------

	/** The catalogue. Everything for a keeper; only what is on offer for everybody else. */
	public List<TimeOffType> list(User viewer, boolean includeInactive) {
		if (includeInactive && access.isKeeper(viewer)) {
			return types.findAll(TimeOffTypeRepository.BY_NAME);
		}
		// `active != false` rather than `active == true`: a document written before the field
		// existed has no opinion, and "no opinion" means on offer.
		return types.findByActiveNot(false, TimeOffTypeRepository.BY_NAME);
	}

	public TimeOffType require(String id) {
		return types.findById(id).orElseThrow(() -> ApiException.notFound("timeOffType"));
	}

	public Optional<TimeOffType> byKey(String key) {
		return types.findByKey(TimeOffType.normalizeKey(key));
	}

	// --- keeping ---------------------------------------------------------------

	public TimeOffType create(User actor, Draft draft) {
		access.requireKeeper(actor);
		if (types.count() >= TimeOffType.TYPES_MAX) {
			throw ApiException.badRequest("error.timeOff.typesTooMany", TimeOffType.TYPES_MAX);
		}
		String key = TimeOffType.normalizeKey(draft.key());
		if (key == null || !KEY.matcher(key).matches()) {
			throw ApiException.badRequest("error.timeOff.typeKeyInvalid");
		}
		if (draft.kind() == null) {
			throw ApiException.badRequest("error.timeOff.typeKindRequired");
		}
		String name = cleanName(draft.name());
		if (name == null) {
			// Only the three built-ins may go without one, and they are not created here.
			throw ApiException.badRequest("error.timeOff.typeNameRequired");
		}
		TimeOffType type = TimeOffType.builder()
				.key(key)
				.name(name)
				.kind(draft.kind())
				.createdBy(actor.getId())
				.updatedBy(actor.getId())
				.updatedAt(clock.instant())
				.active(true)
				.build();
		apply(type, draft);
		assertCoherent(type);
		TimeOffType saved = save(type);
		record(actor, saved, "created");
		return saved;
	}

	public TimeOffType update(User actor, String id, Draft patch) {
		access.requireKeeper(actor);
		TimeOffType type = require(id);
		if (patch.key() != null && !patch.key().equals(type.getKey())) {
			// The key is what entitlements, bookings and the system types are recognised by. A
			// rename that moved it would silently detach a year of history from its type.
			throw ApiException.badRequest("error.timeOff.typeKeyImmutable");
		}
		if (patch.kind() != null && patch.kind() != type.getKind() && type.isSystem()) {
			throw ApiException.badRequest("error.timeOff.typeSystemKind");
		}
		if (patch.name() != null) {
			String name = cleanName(patch.name());
			if (name == null && !type.isSystem()) {
				throw ApiException.badRequest("error.timeOff.typeNameRequired");
			}
			// A system type whose name is cleared goes back to its built-in label, which is a
			// thing an operator may legitimately want after trying one of their own.
			type.setName(name);
		}
		if (patch.kind() != null) {
			type.setKind(patch.kind());
		}
		apply(type, patch);
		type.setUpdatedBy(actor.getId());
		type.setUpdatedAt(clock.instant());
		assertCoherent(type);
		TimeOffType saved = save(type);
		record(actor, saved, "updated");
		return saved;
	}

	/**
	 * Removes a type nothing points at; refuses the system types and anything with history.
	 *
	 * <p>The refusal names the way out — deactivate — because that is what the person meant.
	 */
	public void delete(User actor, String id) {
		access.requireKeeper(actor);
		TimeOffType type = require(id);
		if (type.isSystem()) {
			throw ApiException.conflict("error.timeOff.typeSystemUndeletable");
		}
		if (ledger.existsByTypeId(id) || entitlements.existsByTypeId(id)) {
			throw ApiException.conflict("error.timeOff.typeInUse");
		}
		types.delete(type);
		record(actor, type, "deleted");
	}

	// --- the rules ---------------------------------------------------------------

	/** Copies whatever the draft states onto the type, leaving the rest as it was. */
	private static void apply(TimeOffType type, Draft draft) {
		if (draft.icon() != null) {
			type.setIcon(TimeOffIcons.orDefault(draft.icon()));
		}
		if (draft.hue() != null) {
			type.setHue(Math.floorMod(draft.hue(), 360));
		}
		if (draft.paid() != null) {
			type.setPaid(draft.paid());
		}
		if (draft.countsAgainstBalance() != null) {
			type.setCountsAgainstBalance(draft.countsAgainstBalance());
		}
		if (draft.unlimited() != null) {
			type.setUnlimited(draft.unlimited());
		}
		if (draft.approvalRequired() != null) {
			type.setApprovalRequired(draft.approvalRequired());
		}
		if (draft.approverRule() != null) {
			type.setApproverRule(draft.approverRule());
		}
		if (draft.halfDaysAllowed() != null) {
			type.setHalfDaysAllowed(draft.halfDaysAllowed());
		}
		if (draft.fractionAllowed() != null) {
			type.setFractionAllowed(draft.fractionAllowed());
		}
		if (draft.minNoticeDays() != null) {
			type.setMinNoticeDays(draft.minNoticeDays());
		}
		if (draft.maxConsecutiveDays() != null) {
			type.setMaxConsecutiveDays(draft.maxConsecutiveDays());
		}
		if (draft.negativeBalanceAllowed() != null) {
			type.setNegativeBalanceAllowed(draft.negativeBalanceAllowed());
		}
		if (draft.negativeLimitMilliDays() != null) {
			type.setNegativeLimitMilliDays(draft.negativeLimitMilliDays());
		}
		if (draft.visibility() != null) {
			type.setVisibility(draft.visibility());
		}
		if (draft.accrual() != null) {
			type.setAccrual(draft.accrual());
		}
		if (draft.allowanceMilliDays() != null) {
			type.setAllowanceMilliDays(draft.allowanceMilliDays());
		}
		if (draft.yearAnchorMonth() != null) {
			type.setYearAnchorMonth(draft.yearAnchorMonth());
		}
		if (draft.yearAnchorDay() != null) {
			type.setYearAnchorDay(draft.yearAnchorDay());
		}
		if (draft.waitingPeriodMonths() != null) {
			type.setWaitingPeriodMonths(draft.waitingPeriodMonths());
		}
		if (draft.prorateOnJoin() != null) {
			type.setProrateOnJoin(draft.prorateOnJoin());
		}
		if (draft.prorateOnLeave() != null) {
			type.setProrateOnLeave(draft.prorateOnLeave());
		}
		if (draft.carryover() != null) {
			type.setCarryover(draft.carryover());
		}
		if (draft.carryoverCapMilliDays() != null) {
			type.setCarryoverCapMilliDays(draft.carryoverCapMilliDays());
		}
		if (draft.carryoverExpiresMonth() != null) {
			type.setCarryoverExpiresMonth(draft.carryoverExpiresMonth());
		}
		if (draft.carryoverExpiresDay() != null) {
			type.setCarryoverExpiresDay(draft.carryoverExpiresDay());
		}
		if (draft.active() != null) {
			type.setActive(draft.active());
		}
	}

	/** Everything a field annotation cannot say, checked on the whole document after an edit. */
	private static void assertCoherent(TimeOffType type) {
		if (type.getKind() == TimeOffType.Kind.SICK && Boolean.TRUE.equals(type.getApprovalRequired())) {
			// Refused rather than silently corrected: the screen has to be able to explain it.
			throw ApiException.badRequest("error.timeOff.sickNeedsNoApproval");
		}
		int allowance = type.allowanceMilliDays();
		if (allowance < 0 || allowance > TimeOffType.ALLOWANCE_MAX_MILLI_DAYS) {
			throw ApiException.badRequest("error.timeOff.allowanceInvalid");
		}
		if (type.isUnlimited() && (allowance > 0 || type.countsAgainstBalance()
				|| type.accrual() != TimeOffType.Accrual.NONE
				|| type.carryover() != TimeOffType.Carryover.NONE)) {
			// An unlimited type has no balance to accrue to, carry over or count against. Letting
			// the two states coexist would produce a balance screen nobody could read.
			throw ApiException.badRequest("error.timeOff.unlimitedHasNoBalance");
		}
		if (type.countsAgainstBalance() && type.accrual() == TimeOffType.Accrual.NONE
				&& allowance == 0) {
			throw ApiException.badRequest("error.timeOff.balanceNeedsAllowance");
		}
		if (type.carryover() == TimeOffType.Carryover.CAPPED
				&& (type.getCarryoverCapMilliDays() == null || type.getCarryoverCapMilliDays() < 0)) {
			throw ApiException.badRequest("error.timeOff.carryoverCapMissing");
		}
		if (type.getNegativeLimitMilliDays() != null && type.getNegativeLimitMilliDays() < 0) {
			throw ApiException.badRequest("error.timeOff.negativeLimitInvalid");
		}
		if (type.getMaxConsecutiveDays() != null && type.getMaxConsecutiveDays() < 1) {
			throw ApiException.badRequest("error.timeOff.maxConsecutiveInvalid");
		}
		if (type.getMinNoticeDays() != null
				&& (type.getMinNoticeDays() < 0 || type.getMinNoticeDays() > MIN_NOTICE_DAYS_MAX)) {
			throw ApiException.badRequest("error.timeOff.minNoticeInvalid", MIN_NOTICE_DAYS_MAX);
		}
		if (type.waitingPeriodMonths() < 0 || type.waitingPeriodMonths() > 12) {
			throw ApiException.badRequest("error.timeOff.waitingPeriodInvalid");
		}
		assertMonthDay(type.getYearAnchorMonth(), type.getYearAnchorDay());
		assertMonthDay(type.getCarryoverExpiresMonth(), type.getCarryoverExpiresDay());
	}

	/**
	 * A stored day that is not a day — 31 February, month 13 — is refused on the way in.
	 *
	 * <p>{@link TimeOffType#yearAnchor()} falls back rather than throwing, because it is read on
	 * the path of every balance and a job that stopped over an odd document would be worse. That
	 * fallback is a safety net, not a licence: nothing should ever store one.
	 */
	private static void assertMonthDay(Integer month, Integer day) {
		if (month == null && day == null) {
			return;
		}
		if (month == null || day == null) {
			throw ApiException.badRequest("error.timeOff.monthDayInvalid");
		}
		try {
			MonthDay.of(month, day);
		}
		catch (DateTimeException invalid) {
			throw ApiException.badRequest("error.timeOff.monthDayInvalid");
		}
	}

	private static String cleanName(String name) {
		if (name == null) {
			return null;
		}
		String trimmed = name.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		return trimmed.length() > TimeOffType.NAME_MAX
				? trimmed.substring(0, TimeOffType.NAME_MAX) : trimmed;
	}

	private TimeOffType save(TimeOffType type) {
		try {
			return types.save(type);
		}
		catch (DuplicateKeyException taken) {
			// Two keepers coining the same key from two screens. The index decided; the loser gets
			// a 409 rather than a second row the balances would split across.
			throw ApiException.conflict("error.timeOff.typeKeyTaken");
		}
	}

	/**
	 * What an operator did to the catalogue. The key and the kind, never a balance and never a
	 * person: this is instance configuration, like tags and holiday calendars.
	 */
	private void record(User actor, TimeOffType type, String change) {
		audit.event(AuditAction.TIME_OFF_TYPE_CHANGED).actor(actor)
				.meta("change", change)
				.meta("key", String.valueOf(type.getKey()))
				.meta("kind", String.valueOf(type.getKind()))
				.log();
	}
}
