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
		String quarantineId) {

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

	public enum Basis {
		NOTICE,
		OWN_INITIATIVE,
		AUTOMATED_DETECTION
	}

	/** Whether anything was done to content or a person. */
	public boolean acted() {
		return outcome == Outcome.ACTION || outcome == Outcome.HANDLED;
	}
}
