package com.ahmadre.hinata.moderation.text;

import com.ahmadre.hinata.moderation.ModerationCategory;
import com.ahmadre.hinata.moderation.ModerationSurface;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The always-on tier: strips code out of the text, matches the bundled lexicon
 * against what remains, and reports a confidence per category.
 *
 * <p>Runs in-process with no network call, which is what makes it the default for
 * a self-hosted product — an instance behind an air gap gets the same protection
 * against the unambiguous cases as one with an external provider configured.
 *
 * <h2>How a set of term hits becomes one score</h2>
 *
 * <p>The strongest term in a category sets the score, and additional distinct
 * terms in the same category add a small, capped bonus. The alternative — summing
 * weights — is wrong in both directions: it lets three mild terms outrank one
 * unambiguous slur, and it makes a long document score higher than a short one
 * saying the same thing, so length alone would start refusing content.
 *
 * <p>Text recovered from code fences and stack traces is scored separately and
 * capped at {@link #TECHNICAL_SPAN_CEILING}, deliberately below any block
 * threshold. A slur inside a code block is real and should be seen by a human, but
 * refusing a paste of a log file because a variable was named badly is exactly the
 * failure this whole design exists to avoid.
 */
@Component
public class LexiconTextModerator implements TextModerator {

	/**
	 * Highest score a hit inside stripped-out technical text may produce.
	 *
	 * <p>Deliberately bracketed by two policy constants, and wrong if it leaves that
	 * bracket in either direction:
	 *
	 * <ul>
	 *   <li>at or above {@link com.ahmadre.hinata.moderation.ModerationPolicy#DEFAULT_FLAG_THRESHOLD},
	 *       so a slur buried in a code fence still reaches a human. Below it, hiding
	 *       abuse inside backticks would be a complete bypass rather than a
	 *       downgrade;</li>
	 *   <li>strictly below {@link com.ahmadre.hinata.moderation.ModerationPolicy#BLOCK_THRESHOLD_FLOOR},
	 *       so it can never refuse content. Above it, pasting a log file that
	 *       contains a badly named variable would reject a bug report.</li>
	 * </ul>
	 *
	 * <p>The one intended exception is an externally authored technical surface — a
	 * webhook commit message — where {@link com.ahmadre.hinata.moderation.ModerationPolicy#EXTERNAL_STRICTNESS}
	 * lowers the block threshold under this ceiling. That is deliberate: a rejected
	 * commit message is not lost work, and nobody in the organisation is accountable
	 * for what a webhook delivered.
	 */
	public static final int TECHNICAL_SPAN_CEILING = 60;

	/** Added per additional distinct term in the same category. */
	private static final int REPEAT_BONUS = 4;

	/** Ceiling on the total repeat bonus, so volume cannot substitute for certainty. */
	private static final int MAX_REPEAT_BONUS = 12;

	private final Lexicon lexicon;

	public LexiconTextModerator() {
		this(Lexicon.bundled());
	}

	/** For tests and for a future admin-supplied lexicon. */
	public LexiconTextModerator(Lexicon lexicon) {
		this.lexicon = lexicon;
	}

	@Override
	public String id() {
		return "lexicon";
	}

	@Override
	public Map<ModerationCategory, Integer> score(String text, ModerationSurface surface) {
		if (text == null || text.isBlank()) {
			return Map.of();
		}
		// Only surfaces that actually carry engineering prose get stripped. Stripping
		// a display name would just hand an attacker a way to hide inside one.
		if (!surface.technical()) {
			return aggregate(lexicon.scan(text), 100);
		}
		TechnicalTextStripper.Result split = TechnicalTextStripper.strip(text);
		Map<ModerationCategory, Integer> scores = aggregate(lexicon.scan(split.prose()), 100);
		Map<ModerationCategory, Integer> technical =
				aggregate(lexicon.scan(split.technical()), TECHNICAL_SPAN_CEILING);
		technical.forEach((category, score) -> scores.merge(category, score, Math::max));
		return scores;
	}

	/**
	 * Collapses term hits into one score per category, capped at [ceiling].
	 */
	private Map<ModerationCategory, Integer> aggregate(List<Lexicon.Hit> hits, int ceiling) {
		Map<ModerationCategory, Integer> best = new EnumMap<>(ModerationCategory.class);
		Map<ModerationCategory, java.util.Set<String>> distinct = new EnumMap<>(ModerationCategory.class);
		for (Lexicon.Hit hit : hits) {
			best.merge(hit.category(), hit.weight(), Math::max);
			distinct.computeIfAbsent(hit.category(), key -> new java.util.HashSet<>()).add(hit.term());
		}
		Map<ModerationCategory, Integer> scores = new EnumMap<>(ModerationCategory.class);
		best.forEach((category, weight) -> {
			int extra = Math.min(MAX_REPEAT_BONUS,
					(distinct.get(category).size() - 1) * REPEAT_BONUS);
			scores.put(category, Math.min(ceiling, Math.clamp(weight + extra, 0, 100)));
		});
		return scores;
	}

	@Override
	public Map<ModerationCategory, String> evidence(String text, ModerationSurface surface) {
		Map<ModerationCategory, java.util.SortedSet<String>> terms =
				new EnumMap<>(ModerationCategory.class);
		for (Lexicon.Hit hit : explain(text, surface)) {
			terms.computeIfAbsent(hit.category(), key -> new java.util.TreeSet<>()).add(hit.term());
		}
		Map<ModerationCategory, String> evidence = new EnumMap<>(ModerationCategory.class);
		terms.forEach((category, matched) -> evidence.put(category, String.join(", ", matched)));
		return evidence;
	}

	/** Evidence for the admin queue: which terms fired, for a moderator to judge. */
	public List<Lexicon.Hit> explain(String text, ModerationSurface surface) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		if (!surface.technical()) {
			return lexicon.scan(text);
		}
		TechnicalTextStripper.Result split = TechnicalTextStripper.strip(text);
		List<Lexicon.Hit> hits = new java.util.ArrayList<>(lexicon.scan(split.prose()));
		hits.addAll(lexicon.scan(split.technical()));
		return hits;
	}
}
