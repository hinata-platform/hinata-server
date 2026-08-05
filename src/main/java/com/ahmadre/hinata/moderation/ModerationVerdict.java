package com.ahmadre.hinata.moderation;

import java.util.List;

/**
 * The outcome of running one piece of content through the pipeline.
 *
 * <p>Carries the confidence and the tier that produced it, not just a decision,
 * because those two fields are what every downstream obligation needs: the
 * user-facing statement of reasons has to say whether automated means were used
 * (DSA Art. 17(3)(c)), the admin queue has to sort by how sure the machine was,
 * and a threshold can only be re-tuned later if the original score was kept.
 *
 * <p>{@link #matches()} may hold several categories — content is routinely both
 * harassing and hateful — and {@link #primary()} is the one shown to the user, so
 * a refusal names the strongest reason rather than an arbitrary first hit.
 */
public record ModerationVerdict(
		ModerationDecision decision,
		List<Match> matches,
		ModerationTier tier,
		boolean degraded) {

	/** One category that fired, with the confidence behind it. */
	public record Match(ModerationCategory category, int score, String evidence) {

		public Match {
			score = Math.clamp(score, 0, 100);
		}
	}

	/** Which stage of the pipeline produced the verdict. */
	public enum ModerationTier {
		/** Nothing ran — the policy has moderation switched off for this surface. */
		DISABLED,
		/** The in-process deterministic gate: lexicon, MIME, size, magic bytes. */
		GATE,
		/** The local image classifier. */
		LOCAL_MODEL,
		/** An external provider (only ever reached when one is configured). */
		EXTERNAL,
		/** A human moderator overrode the machine. */
		HUMAN
	}

	public ModerationVerdict {
		matches = matches == null ? List.of() : List.copyOf(matches);
	}

	/** A clean pass with nothing to record. */
	public static ModerationVerdict allow(ModerationTier tier) {
		return new ModerationVerdict(ModerationDecision.ALLOW, List.of(), tier, false);
	}

	/** The verdict for a surface the policy does not moderate. */
	public static ModerationVerdict disabled() {
		return new ModerationVerdict(ModerationDecision.ALLOW, List.of(), ModerationTier.DISABLED, false);
	}

	/**
	 * The strongest match — the category a refusal names and the queue sorts on.
	 * Ties break towards the category declared first in {@link ModerationCategory},
	 * which is ordered most- to least-severe, so a piece of content that is both
	 * sexual and harassing is reported as the former.
	 */
	public Match primary() {
		return matches.stream()
				.max((a, b) -> a.score() != b.score()
						? Integer.compare(a.score(), b.score())
						: Integer.compare(b.category().ordinal(), a.category().ordinal()))
				.orElse(null);
	}

	/** The category a refusal names, or {@code null} when nothing fired. */
	public ModerationCategory primaryCategory() {
		Match match = primary();
		return match != null ? match.category() : null;
	}

	public boolean isBlocking() {
		return decision.isBlocking();
	}

	/** Whether this verdict belongs in the moderator queue. */
	public boolean needsReview() {
		return decision == ModerationDecision.FLAG || degraded;
	}

	/**
	 * This verdict marked as produced while a tier was unavailable. A degraded
	 * verdict is still a real verdict — it just also lands in the queue, so a
	 * provider outage becomes a queryable backlog rather than a log line nobody
	 * reads.
	 */
	public ModerationVerdict degrade() {
		return new ModerationVerdict(decision, matches, tier, true);
	}

	/**
	 * This verdict with the evidence removed — the form that may cross the API
	 * boundary to the author.
	 *
	 * <p>The scores and categories stay, because the author is entitled to know what
	 * their content was judged to be. The matched term does not, because handing it
	 * back turns the refusal into a search interface for the ruleset: rephrase,
	 * resubmit, read the next term, repeat. The full evidence stays on the
	 * moderation record, where the person reviewing the decision can see it.
	 */
	public ModerationVerdict redacted() {
		if (matches.stream().allMatch(match -> match.evidence() == null)) {
			return this;
		}
		return new ModerationVerdict(decision,
				matches.stream()
						.map(match -> new Match(match.category(), match.score(), null))
						.toList(),
				tier, degraded);
	}
}
