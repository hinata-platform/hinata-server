package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.availability.TimeOff;
import com.ahmadre.hinata.availability.TimeOffGate;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Closes the direct road to an absence of a type somebody has to approve.
 *
 * <p>Entering an absence straight into the calendar is how this module worked before approvals
 * existed, and for most types it still is. But once an operator says vacation needs approving, the
 * older screen would otherwise be a way around the newer one — and a rule that any person can
 * sidestep by choosing a different screen is not a rule.
 *
 * <p><b>Three things go through anyway.</b> A type nobody has to approve, because there is nothing
 * to go around. Anything at all while absence management is off, because then no approval exists.
 * And whoever keeps absences for the organisation, because entering them for other people is the
 * job — asking them to file a request with themselves would be a loop with nothing at the end of
 * it.
 *
 * <p>Sickness is never caught here: {@code TimeOffType.requiresApproval()} answers false for the
 * sick kind whatever the stored flag says, so reporting it stays one step (§ 5 EFZG, R11).
 */
@Component
@RequiredArgsConstructor
public class TimeOffApprovalGuard implements TimeOffGate {

	private final TimeOffTypeRepository types;
	private final TimeOffSettings settings;
	private final TimeOffAccess access;

	@Override
	public void assertDirectEntry(TimeOff.Type type, String typeId, String subjectId, User actor) {
		if (!settings.enabled() || access.isKeeper(actor)) {
			return;
		}
		boolean needsApproval = typeOf(type, typeId)
				.map(TimeOffType::requiresApproval)
				.orElse(false);
		if (needsApproval) {
			throw TimeOffRefusal.approvalRequired();
		}
	}

	/**
	 * Refuses a direct change to an absence a request produced — for everybody, keepers included.
	 * A keeper who wants it gone cancels the request, which is always open to them, and the days
	 * come back with it; deleting the absence alone would leave them booked.
	 */
	@Override
	public void assertDirectChange(TimeOff absence, User actor) {
		if (settings.enabled() && absence.getRequestId() != null) {
			throw TimeOffRefusal.requestBacked();
		}
	}

	/**
	 * The operator type an absence is entered under: the one it names, or — for a client that
	 * names none — the built-in type of its plain kind. Without that second half, an absence sent
	 * as "vacation" with no id went through as if nobody had ever made vacation need approval.
	 */
	private java.util.Optional<TimeOffType> typeOf(TimeOff.Type type, String typeId) {
		if (typeId != null && !typeId.isBlank()) {
			return types.findById(typeId.strip());
		}
		if (type == null) {
			return java.util.Optional.empty();
		}
		return types.findBySystemKey(switch (type) {
			case VACATION -> TimeOffType.SYSTEM_VACATION;
			case SICK -> TimeOffType.SYSTEM_SICK;
			case OTHER -> TimeOffType.SYSTEM_OTHER;
		});
	}
}
