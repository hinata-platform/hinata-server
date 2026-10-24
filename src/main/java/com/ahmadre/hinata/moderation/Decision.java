package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;

import java.time.Instant;

/**
 * What a moderator decided on a case (HIN-171), and why. Stored with the case; the statement to the
 * person responsible is built from it when read.
 *
 * <p>The flags are boxed: a record with a primitive cannot be read from a projection that leaves
 * it out, nor from a decision stored before the flag existed.
 *
 * @param suspension  how long the person responsible is suspended, with or without a removal
 * @param confirmedBy who confirmed a suspension of a person with a role (HIN-172)
 */
public record Decision(
		Outcome outcome,
		RemovalScope removal,
		Ground ground,
		ReportCategory category,
		String facts,
		String ruleReference,
		String lawReference,
		String responsibleId,
		Boolean deceptiveCommercial,
		String messageToAuthor,
		String internalNote,
		Boolean lawEnforcementNotified,
		Basis basis,
		Boolean automatedDetection,
		Integer rulesVersion,
		String decidedBy,
		Instant decidedAt,
		String quarantineId,
		SuspensionLength suspension,
		String confirmedBy) {

	public enum Outcome {
		NO_VIOLATION,
		UNFOUNDED,
		ACTION,
		HANDLED
	}

	public enum Ground {
		RULES,
		LAW
	}

	/** How long a suspension decided with an action keeps the person out (HIN-172). */
	public enum SuspensionLength {
		DAYS_7,
		DAYS_30,
		INDEFINITE
	}

	public enum Basis {
		NOTICE,
		OWN_INITIATIVE,
		AUTOMATED_DETECTION
	}

	/** The same decision, confirmed by [personId] as the second pair of eyes (HIN-172). */
	Decision confirmedBy(String personId) {
		return new Decision(outcome, removal, ground, category, facts, ruleReference, lawReference, responsibleId,
				deceptiveCommercial, messageToAuthor, internalNote, lawEnforcementNotified, basis, automatedDetection,
				rulesVersion, decidedBy, decidedAt, quarantineId, suspension, personId);
	}

	/** Whether anything was done to content or a person. */
	public boolean acted() {
		return outcome == Outcome.ACTION || outcome == Outcome.HANDLED;
	}
}
