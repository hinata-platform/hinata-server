package com.ahmadre.hinata.moderation;

/**
 * What {@link ModerationStats} counts for the yearly figures (DSA Art. 15, HIN-178). Counted when
 * it happens, never at the year's end: decided cases are deleted after six months. Each one is
 * counted by the keys the template of the Commission asks for (Implementing Regulation
 * (EU) 2024/2835, Annex I), so the figures need no case to be read back.
 */
public enum StatMetric {
	/** A notice: a report by a member or by somebody without an account. Key: its category. */
	NOTICE,
	/** An action decided on a case notices brought. Key: {@code CATEGORY:GROUND}. */
	NOTICE_ACTION,
	/** Hours from a case's first notice to its action. Key: {@code CATEGORY:HOURS}, see {@link ModerationStats#hoursBucket}. */
	NOTICE_HOURS,
	/** A measure decided on moderation's own initiative, after its own look or the text check's. Key: {@code GROUND:CATEGORY}. */
	OWN_MEASURE,
	/** What those measures restricted. Key: {@code GROUND:CATEGORY:RESTRICTION}. */
	OWN_RESTRICTION,
	/** An appeal filed. Key: what the decision restricted, see {@link ModerationStats.Complaint}. */
	APPEAL_FILED,
	/** An appeal decided. Key: {@code COMPLAINT:RESULT}. */
	APPEAL_RESULT,
	/** Hours from an appeal to its decision. Key: {@code COMPLAINT:HOURS}. */
	APPEAL_HOURS,
	/** A text the check refused to store, a measure by automated means alone. Key: its category. */
	TEXT_REFUSED,
	/** A text the check put before moderation. Key: its category. */
	TEXT_FLAGGED,
	/** An upload the gate refused, a measure by automated means alone. Key: why. */
	UPLOAD_REFUSED
}
