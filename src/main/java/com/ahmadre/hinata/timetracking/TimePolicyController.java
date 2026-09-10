package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.TimePolicy;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * What the module currently demands, told to the people it applies to.
 *
 * <p>The policies live in the admin settings, which only an administrator may
 * read — and every one of the rules this stage enforces is a rule an ordinary
 * member runs into: a field they must fill in, a day they can no longer edit, a
 * tag they may not coin. A client that could not read them would have to
 * discover each one by being refused, which is both a poor editor and the
 * opposite of what R3 asks for. So the enforced rules are published to anyone
 * signed in, and nothing else is: no retention, no visibility switches, no
 * notice text — those belong to the transparency panel of HIN-89, which frames
 * them.
 *
 * <p>Resolved through {@link TimeTrackingSettings} rather than read from the
 * stored block, so what a client marks in the editor is what the write gate
 * enforces. Behind {@link AdvancedTimeTrackingGate} like the rest of
 * {@code /api/v1/time}.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time/policy")
@RequiredArgsConstructor
public class TimePolicyController {

	private final TimeTrackingSettings settings;
	private final CurrentUser currentUser;

	public record RequiredFieldsResponse(boolean project, boolean issue, boolean description,
			boolean tag) {
	}

	public record RoundingResponse(TimePolicy.Rounding mode, int increment) {
	}

	/**
	 * One span reopened inside the freeze.
	 *
	 * <p>Published to everyone signed in, note and all, and that is the point: a day
	 * that is open again inside a closed month is a rule people run into, and an
	 * exception nobody could see would be indistinguishable from a bug. The note is
	 * an administrator's sentence about a period, never about a person.
	 */
	public record LockExceptionResponse(String id, LocalDate from, LocalDate to, String note) {
	}

	/**
	 * The submission rhythm, as the people who submit are told it.
	 *
	 * <p>Just the parameters, not the periods: turning them into dates is the
	 * server's arithmetic and arrives through {@code GET /time/approvals/periods}.
	 * This is here so a client knows <em>whether</em> there is a rhythm at all
	 * (FREE asks for a span instead of offering arrows) without first asking for a
	 * window of periods.
	 */
	public record ApprovalPeriodResponse(TimePolicy.ApprovalPeriod type, String weekStartsOn,
			LocalDate anchorDate, Integer days) {
	}

	/**
	 * {@code lockBefore} is null when nothing is frozen, which is an answer and
	 * not a gap. {@code rounding} is published although it changes no entry: it
	 * is how the reports of stage 12 will present them, and somebody comparing a
	 * report to their own list is entitled to know why the two differ.
	 *
	 * <p>{@code approvalsEnabled} and {@code approvalPeriod} are here for the same
	 * reason everything else is: they decide whether a period can be handed in and
	 * whether doing so will freeze it. A client that had to discover the first by
	 * getting a 404 from the approvals route would offer an action that does not
	 * exist, which is the opposite of what publishing the rules is for.
	 */
	public record TimePolicyResponse(RequiredFieldsResponse requiredFields, LocalDate lockBefore,
			List<LockExceptionResponse> lockExceptions, RoundingResponse rounding,
			boolean limitTagAccess, boolean defaultBillable, boolean approvalsEnabled,
			ApprovalPeriodResponse approvalPeriod) {
	}

	@GetMapping
	public TimePolicyResponse policy() {
		currentUser.require();
		TimeTrackingSettings.RequiredFields required = settings.requiredFields();
		TimeTrackingSettings.Rounding rounding = settings.rounding();
		TimeTrackingSettings.ApprovalPeriod period = settings.approvalPeriod();
		return new TimePolicyResponse(
				new RequiredFieldsResponse(required.project(), required.issue(),
						required.description(), required.tag()),
				settings.lockBefore(),
				settings.lockExceptions().stream()
						.map(exception -> new LockExceptionResponse(exception.getId(),
								exception.getFrom(), exception.getTo(), exception.getNote()))
						.toList(),
				new RoundingResponse(rounding.mode(), rounding.increment()),
				settings.limitTagAccess(),
				settings.defaultBillable(),
				settings.approvalsEnabled(),
				new ApprovalPeriodResponse(period.type(),
						period.weekStartsOn() == null ? null : period.weekStartsOn().name(),
						period.anchorDate(), period.days()));
	}
}
