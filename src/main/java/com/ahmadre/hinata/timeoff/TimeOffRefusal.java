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
