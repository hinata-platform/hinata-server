package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.RemovalScope;

import java.time.Instant;

/**
 * The statement of reasons for a decision (DSA Art. 17, HIN-171), worded in the reader's language
 * when it is read and never stored as text. The app draws the fields as they come (HIN-173); the
 * mail shows the same sentences.
 *
 * @param measure            what was done, to what, from when (Art. 17(3)(a))
 * @param scope              what of the content the removal took
 * @param until              when a suspension ends (HIN-172); null otherwise
 * @param basis              whether it was reported, found by moderation or flagged (Art. 17(3)(b))
 * @param facts              what moderation found, as it wrote it (Art. 17(3)(b), (d), (e))
 * @param automatedDetection whether the automatic check found it (Art. 17(3)(c))
 * @param automatedDecision  whether a machine decided: never, a person always does
 * @param automation         the sentence about automated means
 * @param groundText         the house rule or the law the decision rests on (Art. 17(3)(d), (e))
 * @param rulesUrl           the house rules in the version the decision applied
 * @param appealUntil        until when an appeal is possible (Art. 17(3)(f))
 * @param appealable         whether an appeal can still be filed
 * @param appealByReply      whether it is filed by replying to the mail, for an account that
 *                           cannot sign in
 * @param appeal             how to appeal, or how the appeal stands
 * @param courtsNote         that the courts are open regardless (Art. 17(3)(f))
 */
public record Statement(
		String caseId,
		String headline,
		String measure,
		RemovalScope scope,
		Instant until,
		String basis,
		String facts,
		boolean automatedDetection,
		boolean automatedDecision,
		String automation,
		Decision.Ground ground,
		String groundText,
		String ruleReference,
		String lawReference,
		String rulesUrl,
		Instant appealUntil,
		boolean appealable,
		boolean appealByReply,
		String appeal,
		Appeal.State appealState,
		String courtsNote,
		String messageToAuthor) {
}
