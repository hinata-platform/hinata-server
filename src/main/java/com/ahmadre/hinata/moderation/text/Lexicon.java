package com.ahmadre.hinata.moderation.text;

import com.ahmadre.hinata.moderation.ModerationCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The deterministic term matcher: an allowlist-first, whole-token lexicon over
 * German and English.
 *
 * <h2>Why this is not a substring search</h2>
 *
 * <p>The obvious implementation — {@code text.contains(term)} over a list of bad
 * words — is not merely imprecise in German, it is unusable. German compounds put
 * ordinary words inside other ordinary words:
 *
 * <pre>
 *   Analyse, analysieren, Kanal, Kanalisation   contain "anal"
 *   Klassenzimmer, Zimmermann                   contain "assen"/"imme"
 *   Sexualität, Sexualkunde, Homosexualität     contain "sexual"
 *   Schwanzmeise, Fickmühle                     are real German words
 *   Dickicht, Dickmilch                         contain "dick"
 * </pre>
 *
 * <p>A substring filter refuses every one of them. So matching here is on whole
 * tokens only, and the allowlist is consulted <em>before</em> the denylist so a
 * legitimate word is cleared even when it contains a listed term.
 *
 * <h2>Obfuscation, and why it is handled conservatively</h2>
 *
 * <p>Tokens are normalised — case folded, elongations collapsed
 * ({@code fuuuuck} → {@code fuck}), a small set of unambiguous leet substitutions
 * applied, and zero-width and combining characters removed. Normalisation is
 * deliberately narrow: every rule that folds more characters together also folds
 * more innocent words onto listed ones, and in a tool where the cost of a false
 * positive is a colleague's lost work, recall bought that way is not worth it. The
 * evasion that survives is what the human queue is for.
 *
 * <h2>Complexity</h2>
 *
 * <p>Matching is a hash lookup per token plus two n-gram lookups, i.e. linear in
 * the input and independent of list size — so the list can grow without the write
 * path getting slower. No regular expression is ever run against the term list,
 * which is what keeps hostile input from turning this into a ReDoS.
 */
@Slf4j
public final class Lexicon {

	/**
	 * Splits on anything that is not a letter or digit. Umlauts and ß are letters
	 * under {@code \p{L}}, so German words survive intact.
	 */
	private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]++");

	/**
	 * Leet substitutions for characters that are <em>also</em> token separators, and
	 * so have to be folded before the text is split rather than per token.
	 *
	 * <p>Missing this is a complete bypass, not a degradation: {@code f@ggot} splits
	 * into {@code f} and {@code ggot} and matches nothing at all, however good the
	 * rest of the pipeline is.
	 */
	private static final Pattern SEPARATOR_LEET = Pattern.compile("[@$]");

	/** Zero-width and combining marks, a cheap way to break up a word visually. */
	private static final Pattern INVISIBLE = Pattern.compile("[\\p{Mn}\\p{Cf}\\u200B-\\u200D\\uFEFF]++");

	/** Three or more of the same letter collapse to one: {@code fuuuck} → {@code fuck}. */
	private static final Pattern ELONGATION = Pattern.compile("(\\p{L})\\1{2,}");

	/** One rule: a term (or phrase) and what it means. */
	public record Rule(String term, ModerationCategory category, int weight) {
	}

	/** What the lexicon found in a piece of text. */
	public record Hit(ModerationCategory category, int weight, String term) {
	}

	private final Map<String, Rule> rules;
	private final Set<String> allowed;

	/**
	 * Longest phrase, in tokens, that any loaded rule spans — derived from the rules
	 * rather than fixed.
	 *
	 * <p>A constant here is a trap: the scan window and the term list are two places
	 * that have to agree, and when they drift, a rule longer than the window matches
	 * nothing and reports no error. "ich bringe dich um" and "i will kill you" are
	 * both four tokens, so a window of three silently disables the most serious
	 * entries in the file while every test on shorter rules keeps passing.
	 */
	private final int maxPhraseTokens;

	private Lexicon(Map<String, Rule> rules, Set<String> allowed) {
		this.rules = Map.copyOf(rules);
		this.allowed = Set.copyOf(allowed);
		this.maxPhraseTokens = rules.keySet().stream()
				.mapToInt(term -> (int) term.chars().filter(Character::isSpaceChar).count() + 1)
				.max()
				.orElse(1);
	}

	/** Number of loaded denylist rules — used by the startup log and by tests. */
	public int ruleCount() {
		return rules.size();
	}

	/** Number of loaded allowlist terms. */
	public int allowedCount() {
		return allowed.size();
	}

	/**
	 * Loads the bundled lexicon from {@code classpath:moderation/}.
	 *
	 * <p>A missing or unreadable file is logged and skipped rather than thrown: a
	 * packaging mistake must not stop the server from booting, and an empty lexicon
	 * degrades to "nothing matches", which the surrounding pipeline already treats
	 * as a degraded verdict.
	 */
	public static Lexicon bundled() {
		Set<String> allowed = new HashSet<>();
		for (String file : List.of("allowlist-en.txt", "allowlist-de.txt", "allowlist-technical.txt")) {
			allowed.addAll(readTerms(file));
		}
		Map<String, Rule> rules = new HashMap<>();
		for (String file : List.of("denylist-en.txt", "denylist-de.txt")) {
			for (String line : readLines(file)) {
				parseRule(line).ifPresent(rule -> rules.merge(rule.term(), rule,
						(a, b) -> a.weight() >= b.weight() ? a : b));
			}
		}
		log.info("Moderation lexicon loaded: {} rules, {} allowlisted terms", rules.size(), allowed.size());
		return new Lexicon(rules, allowed);
	}

	/** An explicit lexicon, for tests and for future admin-supplied term lists. */
	public static Lexicon of(List<Rule> rules, Set<String> allowed) {
		Map<String, Rule> byTerm = new HashMap<>();
		for (Rule rule : rules) {
			byTerm.put(normalize(rule.term()), new Rule(normalize(rule.term()), rule.category(), rule.weight()));
		}
		Set<String> normalizedAllowed = new HashSet<>();
		for (String term : allowed) {
			normalizedAllowed.add(normalize(term));
		}
		return new Lexicon(byTerm, normalizedAllowed);
	}

	/**
	 * Every rule that fires in [text].
	 *
	 * <p>A token on the allowlist is skipped entirely — it can neither match a rule
	 * itself nor take part in a phrase — which is what makes "Analyse" safe next to
	 * a rule for "anal".
	 */
	public List<Hit> scan(String text) {
		if (text == null || text.isBlank() || rules.isEmpty()) {
			return List.of();
		}
		// Fold the separator-shaped leet characters before splitting, or a token
		// boundary an attacker inserted survives the whole pipeline.
		String[] raw = TOKEN_SPLIT.split(foldSeparatorLeet(text));
		List<String> tokens = new ArrayList<>(raw.length);
		for (String token : raw) {
			if (!token.isEmpty()) {
				tokens.add(normalize(token));
			}
		}
		List<Hit> hits = new ArrayList<>();
		for (int i = 0; i < tokens.size(); i++) {
			if (allowed.contains(tokens.get(i))) {
				continue;
			}
			// Longest phrase first, so "sexual assault" is reported as itself rather
			// than as two weaker single-word hits.
			for (int span = Math.min(maxPhraseTokens, tokens.size() - i); span >= 1; span--) {
				String candidate = join(tokens, i, span);
				if (allowed.contains(candidate)) {
					break;
				}
				Rule rule = rules.get(candidate);
				if (rule != null) {
					hits.add(new Hit(rule.category(), rule.weight(), rule.term()));
					i += span - 1;
					break;
				}
			}
		}
		return hits;
	}

	private static String join(List<String> tokens, int from, int span) {
		if (span == 1) {
			return tokens.get(from);
		}
		StringBuilder joined = new StringBuilder();
		for (int i = from; i < from + span; i++) {
			if (i > from) {
				joined.append(' ');
			}
			joined.append(tokens.get(i));
		}
		return joined.toString();
	}

	/**
	 * Case-folds, strips invisible characters, collapses elongations and applies a
	 * short list of unambiguous leet substitutions.
	 *
	 * <p>{@code 1}/{@code !} → {@code i} and {@code 0} → {@code o} are deliberately
	 * <em>not</em> applied to tokens that are wholly numeric, so a version string or
	 * an error code is not rewritten into a word.
	 */
	/**
	 * Folds {@code @} and {@code $} to letters across a whole string, before it is
	 * split into tokens.
	 *
	 * <p>Applied to the input rather than to each token because these two characters
	 * are token separators: by the time {@link #normalize} sees a token, the split
	 * has already happened and the evasion has already worked. The side effect —
	 * {@code user@example.com} folding to one token — is harmless, since the result
	 * matches no rule.
	 */
	private static String foldSeparatorLeet(String text) {
		if (text.indexOf('@') < 0 && text.indexOf('$') < 0) {
			return text;
		}
		return SEPARATOR_LEET.matcher(text).replaceAll(match -> "@".equals(match.group()) ? "a" : "s");
	}

	static String normalize(String token) {
		String value = Normalizer.normalize(token, Normalizer.Form.NFKC);
		value = INVISIBLE.matcher(value).replaceAll("");
		value = value.toLowerCase(Locale.ROOT);
		if (!value.chars().allMatch(Character::isDigit)) {
			value = value
					.replace('4', 'a')
					.replace('3', 'e')
					.replace('1', 'i')
					.replace('0', 'o')
					.replace('5', 's')
					.replace('7', 't')
					.replace('$', 's')
					.replace('@', 'a');
		}
		value = ELONGATION.matcher(value).replaceAll("$1");
		return value.strip();
	}

	private static java.util.Optional<Rule> parseRule(String line) {
		String[] parts = line.split("\\t++|\\s{2,}");
		if (parts.length < 3) {
			log.warn("Skipping malformed lexicon line: {}", line);
			return java.util.Optional.empty();
		}
		try {
			ModerationCategory category = ModerationCategory.valueOf(parts[0].strip().toUpperCase(Locale.ROOT));
			int weight = Math.clamp(Integer.parseInt(parts[1].strip()), 1, 100);
			String term = normalize(parts[2].strip());
			return term.isBlank() ? java.util.Optional.empty()
					: java.util.Optional.of(new Rule(term, category, weight));
		}
		catch (IllegalArgumentException ex) {
			log.warn("Skipping unparseable lexicon line '{}': {}", line, ex.getMessage());
			return java.util.Optional.empty();
		}
	}

	private static Set<String> readTerms(String file) {
		Set<String> terms = new HashSet<>();
		for (String line : readLines(file)) {
			terms.add(normalize(line));
		}
		terms.remove("");
		return terms;
	}

	/** Reads a bundled list, dropping blank lines and {@code #} comments. */
	private static List<String> readLines(String file) {
		ClassPathResource resource = new ClassPathResource("moderation/" + file);
		if (!resource.exists()) {
			log.warn("Moderation list {} is missing from the classpath", file);
			return List.of();
		}
		List<String> lines = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String trimmed = line.strip();
				if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
					lines.add(trimmed);
				}
			}
		}
		catch (IOException ex) {
			log.error("Could not read moderation list {}: {}", file, ex.getMessage());
		}
		return lines;
	}
}
