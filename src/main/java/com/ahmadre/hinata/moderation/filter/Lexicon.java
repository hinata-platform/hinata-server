package com.ahmadre.hinata.moderation.filter;

import com.ahmadre.hinata.content.ReportCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whole-word matching of the shipped terms and an administrator's own (HIN-176), over German and
 * English.
 *
 * <p>Never a substring search: German puts ordinary words inside other ordinary words
 * ("Analyse", "Klassenzimmer", "Dickicht"), and a filter that refuses them is a filter the team
 * switches off. Words are normalised (NFKC, case, invisible marks, a few unambiguous leetspeak
 * digits, stretched letters), {@code @} and {@code $} are folded before the text is split, or
 * {@code f@ggot} would split into two harmless halves.
 *
 * <p>Two repairs against the August branch. A phrase wins over the exception for its first word,
 * so "kill yourself" fires although "kill" alone is a technical word; there it never did. And the
 * window for phrases comes from the longest rule loaded, never a constant: a window of three made
 * every four-word rule ("ich bringe dich um") match nothing, silently.
 *
 * <p>Linear in the input and independent of the size of the lists: a hash lookup per word and per
 * phrase start, no regular expression over the terms, so hostile input cannot make it slow.
 */
@Slf4j
final class Lexicon {

	/** Anything but letters and digits splits; umlauts and ß are letters, so German words stay whole. */
	private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]++");

	/** Leetspeak that is also a separator, folded before splitting. */
	private static final Pattern SEPARATOR_LEET = Pattern.compile("[@$]");

	/** Zero-width and combining marks, a cheap way to break a word up visually. */
	private static final Pattern INVISIBLE = Pattern.compile("[\\p{Mn}\\p{Cf}\\u200B-\\u200D\\uFEFF]++");

	static final List<String> TERM_FILES = List.of("terms-en.txt", "terms-de.txt");
	static final List<String> EXCEPTION_FILES = List.of("exceptions-en.txt", "exceptions-de.txt",
			"exceptions-technical.txt");

	/** One rule; [own] when an administrator added it, which only ever refuses. */
	record Rule(String term, ReportCategory category, int weight, boolean own) {
	}

	/** One find, in the order of the text. */
	record Hit(String term, ReportCategory category, int weight, boolean own) {
	}

	/** The finds in a text, and the exception words in it that a rule starts with. */
	record Scan(List<Hit> hits, Set<String> exempt) {
	}

	private final Map<String, Rule> rules;
	private final Set<String> allowed;

	/** Words some rule starts with, so an exception that matters can be named in the admin's test. */
	private final Set<String> ruleStarts;

	/** Words a phrase of two words or more starts with, rule or exception: only there a phrase is tried. */
	private final Set<String> phraseStarts;

	private final int maxPhraseWords;

	private Lexicon(Map<String, Rule> rules, Set<String> allowed) {
		this.rules = Map.copyOf(rules);
		this.allowed = Set.copyOf(allowed);
		Set<String> starts = new HashSet<>();
		Set<String> phrases = new HashSet<>();
		int longest = 1;
		for (String term : rules.keySet()) {
			String[] words = term.split(" ");
			starts.add(words[0]);
			if (words.length > 1) {
				phrases.add(words[0]);
			}
			longest = Math.max(longest, words.length);
		}
		for (String word : allowed) {
			int space = word.indexOf(' ');
			if (space > 0) {
				phrases.add(word.substring(0, space));
				longest = Math.max(longest, word.split(" ").length);
			}
		}
		this.ruleStarts = Set.copyOf(starts);
		this.phraseStarts = Set.copyOf(phrases);
		this.maxPhraseWords = longest;
	}

	/** The shipped lists, from {@code classpath:moderation/}. A missing file is logged, never fatal. */
	static Lexicon bundled() {
		Map<String, Rule> rules = new HashMap<>();
		for (String file : TERM_FILES) {
			for (String line : readLines(file)) {
				parseRule(line).ifPresent(rule -> rules.merge(rule.term(), rule,
						(a, b) -> a.weight() >= b.weight() ? a : b));
			}
		}
		Set<String> allowed = new HashSet<>();
		for (String file : EXCEPTION_FILES) {
			for (String line : readLines(file)) {
				allowed.add(normalizePhrase(line));
			}
		}
		allowed.remove("");
		log.info("Text check lexicon loaded: {} rules, {} exceptions", rules.size(), allowed.size());
		return new Lexicon(rules, allowed);
	}

	/** Explicit rules, for tests. */
	static Lexicon of(Collection<Rule> rules, Collection<String> allowed) {
		Map<String, Rule> byTerm = new HashMap<>();
		for (Rule rule : rules) {
			String term = normalizePhrase(rule.term());
			byTerm.put(term, new Rule(term, rule.category(), rule.weight(), rule.own()));
		}
		Set<String> normalized = new HashSet<>();
		for (String word : allowed) {
			normalized.add(normalizePhrase(word));
		}
		return new Lexicon(byTerm, normalized);
	}

	/**
	 * This lexicon with an administrator's own terms, which refuse and never flag, and own
	 * exceptions. An own term replaces a shipped rule of the same words; an own exception never
	 * clears a rule about the safety of children.
	 */
	Lexicon with(Collection<String> ownTerms, Collection<String> ownExceptions) {
		Map<String, Rule> merged = new HashMap<>(rules);
		for (String term : ownTerms) {
			String normalized = normalizePhrase(term);
			if (!normalized.isEmpty()) {
				merged.put(normalized, new Rule(normalized, ReportCategory.OTHER, 100, true));
			}
		}
		Set<String> mergedAllowed = new HashSet<>(allowed);
		for (String word : ownExceptions) {
			String normalized = normalizePhrase(word);
			Rule rule = merged.get(normalized);
			if (!normalized.isEmpty() && (rule == null || rule.category() != ReportCategory.CHILD_SAFETY)) {
				mergedAllowed.add(normalized);
			}
		}
		return new Lexicon(merged, mergedAllowed);
	}

	int ruleCount() {
		return rules.size();
	}

	/** Every shipped rule, for the test that each one fires. */
	Collection<Rule> rules() {
		return rules.values();
	}

	/**
	 * Every rule that fires in [text], longest phrase first. An exception word or phrase is skipped,
	 * but only after the phrases starting at it were tried: a phrase is more specific than any one
	 * of its words.
	 */
	Scan scan(String text) {
		if (text == null || text.isBlank() || rules.isEmpty()) {
			return new Scan(List.of(), Set.of());
		}
		List<String> words = words(text);
		List<Hit> hits = new ArrayList<>();
		Set<String> exempt = new LinkedHashSet<>();
		for (int i = 0; i < words.size(); i++) {
			int consumed = phraseAt(words, i, hits);
			if (consumed > 0) {
				i += consumed - 1;
				continue;
			}
			String word = words.get(i);
			Rule rule = rules.get(word);
			if (allowed.contains(word) && (rule == null || rule.category() != ReportCategory.CHILD_SAFETY)) {
				if (ruleStarts.contains(word)) {
					exempt.add(word);
				}
				continue;
			}
			if (rule != null) {
				hits.add(new Hit(rule.term(), rule.category(), rule.weight(), rule.own()));
			}
		}
		return new Scan(List.copyOf(hits), Set.copyOf(exempt));
	}

	/** The longest phrase of two words or more at [from], as words consumed; 0 when none applies. */
	private int phraseAt(List<String> words, int from, List<Hit> hits) {
		if (!phraseStarts.contains(words.get(from))) {
			return 0;
		}
		for (int span = Math.min(maxPhraseWords, words.size() - from); span >= 2; span--) {
			String candidate = String.join(" ", words.subList(from, from + span));
			Rule rule = rules.get(candidate);
			if (rule != null && (rule.category() == ReportCategory.CHILD_SAFETY || !allowed.contains(candidate))) {
				hits.add(new Hit(rule.term(), rule.category(), rule.weight(), rule.own()));
				return span;
			}
			if (allowed.contains(candidate)) {
				return span;
			}
		}
		return 0;
	}

	private static List<String> words(String text) {
		// Marks that break a word up visually go before the split, or "fag\u200Bgot" would be two
		// harmless halves; composed first, so an accent written as a separate mark stays an accent.
		String whole = Normalizer.isNormalized(text, Normalizer.Form.NFKC) ? text
				: Normalizer.normalize(text, Normalizer.Form.NFKC);
		whole = INVISIBLE.matcher(whole).replaceAll("");
		String[] raw = TOKEN_SPLIT.split(foldSeparatorLeet(whole));
		List<String> words = new ArrayList<>(raw.length);
		for (String token : raw) {
			if (!token.isEmpty()) {
				words.add(fold(token));
			}
		}
		return words;
	}

	/**
	 * {@code @} and {@code $} as letters, across the whole text before it is split: they are
	 * separators, so by the time a word is normalised the evasion has already worked. That
	 * {@code user@example.com} becomes one word is harmless, it matches nothing.
	 */
	private static String foldSeparatorLeet(String text) {
		if (text.indexOf('@') < 0 && text.indexOf('$') < 0) {
			return text;
		}
		return SEPARATOR_LEET.matcher(text).replaceAll(match -> "@".equals(match.group()) ? "a" : "s");
	}

	/** A phrase as the lists and the scan see it: its words normalised, one space between. */
	static String normalizePhrase(String phrase) {
		if (phrase == null) {
			return "";
		}
		return String.join(" ", words(phrase.strip()));
	}

	/**
	 * Folds case and form, drops invisible marks, maps a few unambiguous leetspeak digits and
	 * collapses stretched letters. A word of digits alone is left as it is, so a version or an
	 * error code never turns into a word.
	 */
	static String normalize(String token) {
		String value = Normalizer.normalize(token, Normalizer.Form.NFKC);
		return fold(INVISIBLE.matcher(value).replaceAll("").strip());
	}

	/** One word of text already composed and cleared of marks: case, leetspeak digits, stretched letters. */
	private static String fold(String token) {
		String value = token.toLowerCase(Locale.ROOT);
		boolean digitsOnly = true;
		for (int i = 0; i < value.length() && digitsOnly; i++) {
			digitsOnly = Character.isDigit(value.charAt(i));
		}
		if (!digitsOnly) {
			value = value.replace('4', 'a').replace('3', 'e').replace('1', 'i').replace('0', 'o')
					.replace('5', 's').replace('7', 't');
		}
		return collapseStretched(value);
	}

	/** Three or more of one letter count once: {@code fuuuck} is {@code fuck}; two stay two. */
	private static String collapseStretched(String value) {
		int length = value.length();
		StringBuilder out = null;
		int i = 0;
		while (i < length) {
			char c = value.charAt(i);
			int run = 1;
			while (i + run < length && value.charAt(i + run) == c) {
				run++;
			}
			if (run >= 3 && Character.isLetter(c)) {
				if (out == null) {
					out = new StringBuilder(length).append(value, 0, i);
				}
				out.append(c);
			}
			else if (out != null) {
				out.append(value, i, i + run);
			}
			i += run;
		}
		return out == null ? value : out.toString();
	}

	private static Optional<Rule> parseRule(String line) {
		String[] parts = line.split("\\t++");
		if (parts.length < 3) {
			log.warn("Skipping a malformed text check rule: {}", line);
			return Optional.empty();
		}
		try {
			ReportCategory category = ReportCategory.valueOf(parts[0].strip().toUpperCase(Locale.ROOT));
			int weight = Math.clamp(Integer.parseInt(parts[1].strip()), 1, 100);
			String term = normalizePhrase(parts[2]);
			return term.isEmpty() ? Optional.empty() : Optional.of(new Rule(term, category, weight, false));
		}
		catch (IllegalArgumentException ex) {
			log.warn("Skipping an unreadable text check rule '{}': {}", line, ex.getMessage());
			return Optional.empty();
		}
	}

	/** A shipped list without blank lines and {@code #} comments. */
	private static List<String> readLines(String file) {
		ClassPathResource resource = new ClassPathResource("moderation/" + file);
		if (!resource.exists()) {
			log.warn("Text check list {} is missing from the classpath", file);
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
			log.error("Could not read text check list {}: {}", file, ex.getMessage());
		}
		return lines;
	}
}
