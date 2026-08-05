package com.ahmadre.hinata.moderation.escalation;

import com.ahmadre.hinata.moderation.ModerationCategory;
import com.ahmadre.hinata.moderation.ModerationSurface;

import java.time.Instant;

/**
 * Tells somebody outside the product that an item was frozen and needs a human
 * with authority the product does not have.
 *
 * <p>This exists because freezing is only half an answer. The two events that
 * trigger it — a match against an accredited body's hash list, and a user
 * reporting child sexual content — both end in the same place: material is
 * preserved, nobody in the product may look at it, and a designated person has
 * to contact an authority. A queue row cannot carry that, because the whole
 * point of a freeze is that the queue must not open it.
 *
 * <p>An interface for the same reason {@link
 * com.ahmadre.hinata.moderation.ModerationRecorder} is one: the gate sits on the
 * write path of every upload and stays free of HTTP clients, retry state and
 * repositories. Injected as a list, empty by default — a self-hosted install
 * with nobody to escalate to is a supported configuration and must not have to
 * configure a webhook to accept an attachment.
 *
 * <h2>What may be in a payload</h2>
 *
 * <p>A pointer and a classification. Never content, never bytes, never the
 * provider's hash reference. The destination is a third party's endpoint over
 * somebody else's network, which makes it the last place a copy of the material
 * — or the handle that identifies it inside a hash programme — should exist.
 * {@link Event} is the only shape that crosses this boundary, and it holds
 * nothing else.
 */
public interface ModerationEscalation {

	/**
	 * What is escalated.
	 *
	 * @param recordId  the moderation record or content report this came from, so
	 *                  the recipient and the operator are talking about the same
	 *                  row months later
	 * @param category  the policy category — a classification, not a finding
	 * @param surface   where the content entered the system
	 * @param reference an in-product locator for the frozen target
	 *                  ({@code comment:64f…}), which is a pointer, not a link:
	 *                  a URL would be an invitation to open it
	 * @param at        when the freeze happened
	 */
	record Event(String recordId, ModerationCategory category, ModerationSurface surface,
			String reference, Instant at) {
	}

	/**
	 * Delivers [event], at least once.
	 *
	 * <p>At-least-once rather than exactly-once because the recipient is a
	 * third-party endpoint and a duplicate notice about frozen material costs one
	 * person a second look, while a dropped one costs the notice entirely.
	 *
	 * <p>Must not throw. A delivery failure is audited by the implementation and is
	 * never a reason to fail the refusal or the freeze that raised it — those have
	 * already happened, and the content is already unreachable.
	 */
	void escalate(Event event);

	/** Identifier for logs and audit metadata, so a failure names its destination. */
	String id();
}
