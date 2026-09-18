package com.ahmadre.hinata.timeoff;

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
	public void assertDirectEntry(String typeId, String subjectId, User actor) {
		if (!settings.enabled() || typeId == null || typeId.isBlank()) {
			return;
		}
		if (access.isKeeper(actor)) {
			return;
		}
		boolean needsApproval = types.findById(typeId)
				.map(TimeOffType::requiresApproval)
				.orElse(false);
		if (needsApproval) {
			throw TimeOffRefusal.approvalRequired();
		}
	}
}
