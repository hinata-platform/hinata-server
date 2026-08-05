package com.ahmadre.hinata.moderation.text;

import com.ahmadre.hinata.moderation.ModerationCategory;
import com.ahmadre.hinata.moderation.ModerationSurface;

import java.util.Map;

/**
 * Scores a piece of text against the policy categories.
 *
 * <p>Implementations are tiers, not alternatives: the built-in
 * {@link LexiconTextModerator} always runs and needs no network, and an optional
 * external one runs after it when an operator has configured it. That ordering is
 * what lets a self-hosted, air-gapped instance still refuse the unambiguous cases
 * — the product never depends on a third party being reachable to accept a
 * comment.
 *
 * <p>An implementation returns raw per-category confidence and takes no decision:
 * turning a score into allow/flag/block is
 * {@link com.ahmadre.hinata.moderation.ModerationPolicy}'s job alone, so no
 * provider client can quietly become the place policy lives.
 */
public interface TextModerator {

	/**
	 * Confidence per category for [text] on [surface], where a missing category
	 * means "did not fire". Values are 0-100.
	 *
	 * <p>Must not throw for hostile input, and must be safe to call on the request
	 * thread — a moderator that can block indefinitely belongs behind the async
	 * assessor, not here.
	 */
	Map<ModerationCategory, Integer> score(String text, ModerationSurface surface);

	/**
	 * Why each category fired, for the moderator reviewing the item — the matched
	 * term, the provider's own label, whatever this tier can honestly say.
	 *
	 * <p>Called only when {@link #score} already returned a match, so the common
	 * path where nothing fires costs nothing. The result reaches the moderation
	 * record and the admin queue; it is stripped before the author ever sees it, or
	 * the refusal message would tell them exactly what to rephrase.
	 */
	default Map<ModerationCategory, String> evidence(String text, ModerationSurface surface) {
		return Map.of();
	}

	/** Identifier recorded on the verdict, so a stored decision names its origin. */
	String id();

	/**
	 * Whether this tier is currently usable. A tier that reports false is skipped
	 * and marks the verdict degraded rather than failing the write.
	 */
	default boolean available() {
		return true;
	}
}
