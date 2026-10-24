package com.ahmadre.hinata.moderation;

/**
 * What {@link ModerationStats} counts for the yearly figures (DSA Art. 15, HIN-178). Counted when
 * it happens, never at the year's end: decided cases are deleted after six months.
 */
public enum StatMetric {
	REPORT_CATEGORY,
	REPORT_SOURCE,
	DECISION_OUTCOME,
	DECISION_GROUND,
	DECISION_CATEGORY,
	DECISION_AUTOMATED_DETECTION,
	HOURS_TO_DECISION,
	APPEAL_FILED,
	APPEAL_RESULT,
	SUSPENSION_DURATION,
	TEXT_REFUSED,
	TEXT_FLAGGED,
	UPLOAD_REFUSED
}
