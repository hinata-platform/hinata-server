package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TimePolicy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "No", in the vocabulary the rest of the module already speaks.
 *
 * <p>HIN-88 settled this: every refusal carries a machine-readable {@code reason}, the
 * {@code holder} who could lift it and the {@code remedy} that leads somewhere, beside the
 * localised sentence. The client then has one component for being told no, instead of a screen per
 * mechanism and a person guessing which of three ways out applies to them.
 *
 * <p>Absence management adds exactly one word to that vocabulary — {@code approvalRequired} — and
 * borrows the rest. A refusal whose remedy is {@link TimePolicy.LockRemedy#REQUEST} is the only
 * one on the list the person can act on themselves, and the app turns it into the button that
 * opens the request form.
 */
final class TimeOffRefusal {

	private TimeOffRefusal() {
	}

	/**
	 * 409 for entering an absence of a type somebody has to approve.
	 *
	 * <p>A conflict rather than a forbidding: the person may have this absence, they have simply
	 * taken the wrong road to it. 403 would say they may not, which is not true and sends them to
	 * ask an administrator for a right they already hold.
	 */
	static ApiException approvalRequired() {
		return ApiException.conflict("error.timeOff.approvalRequired",
				details(TimePolicy.LockReason.APPROVAL_REQUIRED, TimePolicy.LockHolder.APPROVER,
						TimePolicy.LockRemedy.REQUEST));
	}

	/**
	 * 409 for leave the balance will not cover, on a type whose rules forbid going under.
	 *
	 * <p>The same three words as every other refusal, so the client shows the same component: the
	 * days are not there, whoever keeps absences is the one who could put them there, and the way
	 * out is a grant rather than a reopen — nothing was closed, the claim was never that large. It
	 * never says how many days are missing: a decider learns yes or no and the figure itself stays
	 * the person's (R2, R10).
	 *
	 * <p>Raised at the decision rather than at the filing. A grant may well arrive in between, and
	 * refusing on the way in would turn a question of timing into a wall.
	 */
	static ApiException balanceExceeded() {
		return ApiException.conflict("error.timeOff.balanceExceeded",
				details(TimePolicy.LockReason.BALANCE_EXCEEDED, TimePolicy.LockHolder.KEEPER,
						TimePolicy.LockRemedy.GRANT));
	}

	/**
	 * 409 for editing or deleting, the direct way, an absence a request produced.
	 *
	 * <p>The days of that absence are booked against a balance by the request, so the way to change
	 * it is the request: cancel it, and ask again. Whoever decided it is the one it goes back to.
	 */
	static ApiException requestBacked() {
		return ApiException.conflict("error.timeOff.requestBacked",
				details(TimePolicy.LockReason.REQUEST_BACKED, TimePolicy.LockHolder.APPROVER,
						TimePolicy.LockRemedy.CANCEL_REQUEST));
	}

	private static Map<String, String> details(TimePolicy.LockReason reason, TimePolicy.LockHolder holder,
			TimePolicy.LockRemedy remedy) {
		Map<String, String> details = new LinkedHashMap<>();
		details.put("reason", wire(reason.name()));
		details.put("holder", wire(holder.name()));
		details.put("remedy", wire(remedy.name()));
		return details;
	}

	/**
	 * {@code SCREAMING_SNAKE} to {@code lowerCamel}, so the wire reads like the rest of the API.
	 *
	 * <p>The same conversion {@code timetracking.TimeLocks} makes, copied rather than shared: its
	 * version is private to a class this module must not reach into, and three lines of
	 * case-folding are a smaller thing to have twice than a dependency in the wrong direction.
	 */
	private static String wire(String name) {
		StringBuilder out = new StringBuilder(name.length());
		boolean upper = false;
		for (char c : name.toCharArray()) {
			if (c == '_') {
				upper = true;
				continue;
			}
			out.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
			upper = false;
		}
		return out.toString();
	}
}
