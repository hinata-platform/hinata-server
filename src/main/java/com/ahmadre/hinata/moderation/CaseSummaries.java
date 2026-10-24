package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.moderation.CaseViews.CaseState;
import com.ahmadre.hinata.moderation.CaseViews.CaseSummary;
import com.ahmadre.hinata.moderation.CaseViews.PersonRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * A case as one row of a list (HIN-170), built from the case alone, never from its content. Whether
 * it is overdue is decided here, on the server's clock: a device clock a few minutes off must not
 * count a case differently from the pills.
 */
@Component
@RequiredArgsConstructor
class CaseSummaries {

	private final Clock clock;

	CaseSummary of(ModerationCase c, PersonRef assignee, boolean reportedByMe) {
		Decision decision = c.getDecision();
		boolean overdue = c.getStatus() == CaseStatus.OPEN && c.getFirstReportedAt() != null
				&& c.getFirstReportedAt().isBefore(clock.instant().minus(ResponseTimes.OVERDUE_AFTER));
		return new CaseSummary(c.getId(), stateOf(c), c.isUrgent(), overdue,
				c.getTarget() == null ? null : c.getTarget().kind(), c.getCategories(), c.getSources(),
				c.getReportCount(), c.getFirstReportedAt(), c.getLocation(), c.getListExcerpt(), assignee, reportedByMe,
				decision == null ? null : decision.outcome(), c.measures().removal() && c.getRestoration() == null,
				c.getSuspension() != null && c.getSuspension().running(), c.getRev());
	}

	/** The one state of a case, as the list shows it. */
	static CaseState stateOf(ModerationCase c) {
		Appeal appeal = c.getAppeal();
		if (appeal != null && appeal.state() == Appeal.State.PENDING) {
			return CaseState.APPEAL;
		}
		if (c.getStatus() == CaseStatus.OPEN) {
			if (c.getPendingDecision() != null) {
				return CaseState.PENDING_CONFIRMATION;
			}
			return c.getAssigneeId() != null ? CaseState.IN_REVIEW : c.isUrgent() ? CaseState.URGENT : CaseState.OPEN;
		}
		boolean undone = appeal != null && appeal.state() == Appeal.State.REVERSED || c.getRestoration() != null;
		return c.getDecision() != null && c.getDecision().acted() && !undone ? CaseState.ACTED : CaseState.NO_ACTION;
	}
}
