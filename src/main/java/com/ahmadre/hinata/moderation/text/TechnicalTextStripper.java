package com.ahmadre.hinata.moderation.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits engineering prose away from the code, logs and identifiers embedded in
 * it, so only the prose is judged as language.
 *
 * <p>This is the single highest-leverage component in moderation, and it is worth
 * being explicit about why, because it is easy to mistake for a nicety. Almost
 * every false positive a filter produces in a developer tool originates in text
 * that is not prose at all:
 *
 * <pre>
 *   kill -9 1234                     a command
 *   master/slave replication         a config topology
 *   nuke the cache                   an idiom about a cache
 *   CVE-2021-44228 exploit chain     a vulnerability reference
 *   POST /api/v1/execute             a route
 *   sexPreference: String            a field name in a schema
 *   at com.foo.Killer.abort(:31)     a stack frame
 * </pre>
 *
 * <p>Every one of those scores on a violence or sexual axis in a general-purpose
 * classifier, and none of them is abuse. Removing them before scoring is
 * deterministic, cheap and testable — unlike raising thresholds, which loses real
 * abuse at the same rate it saves false positives.
 *
 * <p><b>The evasion this creates, and what is done about it.</b> If code is not
 * judged, a fenced block is a place to hide. So nothing is thrown away: the
 * removed spans are returned as {@link Result#technical()} and scored separately
 * at a much higher threshold, and never auto-blocked. Someone burying a slur in a
 * code fence is caught by a human via the queue, not by the machine refusing a
 * stack trace.
 *
 * <p><b>Every pattern here is linear-time by construction.</b> No alternation
 * inside a quantifier, no nested quantifiers, and every character class is
 * negated-and-bounded rather than lazy, so none of them can backtrack
 * catastrophically on hostile input — this runs on the write path of every comment
 * in the product, on strings an attacker chooses.
 */
public final class TechnicalTextStripper {

	/**
	 * A document split into the language a human wrote and the machine text they
	 * pasted around it.
	 */
	public record Result(String prose, String technical) {

		/** Whether any prose survived — an all-code document has nothing to judge. */
		public boolean hasProse() {
			return !prose.isBlank();
		}
	}

	/** Fenced code blocks, including the language tag. Non-greedy, bounded to the fence. */
	private static final Pattern FENCED_CODE = Pattern.compile("```[^`]*+```", Pattern.DOTALL);

	/** Indented code blocks: four leading spaces or a tab, whole line. */
	private static final Pattern INDENTED_CODE = Pattern.compile("(?m)^(?: {4}|\t)[^\n]*+$");

	/** Inline code spans. */
	private static final Pattern INLINE_CODE = Pattern.compile("`[^`\n]++`");

	/**
	 * JVM / Python / JS stack frames. Anchored on the frame shape rather than on a
	 * keyword so it survives localisation of the surrounding message.
	 */
	private static final Pattern STACK_FRAME = Pattern.compile(
			"(?m)^\\s*+(?:at\\s++[\\w$.]++\\([^)\n]*+\\)"
					+ "|File\\s\"[^\"\n]*+\",\\sline\\s\\d++"
					+ "|Caused by:[^\n]*+"
					+ "|[\\w.$]++Exception[^\n]*+"
					+ "|[\\w.$]++Error:[^\n]*+)$");

	/**
	 * Log lines: an ISO-ish timestamp or a bracketed level at the start of a line.
	 * A log line is machine output even when it contains prose-looking words.
	 */
	private static final Pattern LOG_LINE = Pattern.compile(
			"(?m)^\\s*+(?:\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[^\n]*+"
					+ "|\\[(?:DEBUG|INFO|WARN|WARNING|ERROR|FATAL|TRACE)][^\n]*+"
					+ "|(?:DEBUG|INFO|WARN|WARNING|ERROR|FATAL|TRACE)\\s{2,}[^\n]*+)$");

	/** Shell prompts and diff/patch lines. */
	private static final Pattern COMMAND_LINE = Pattern.compile(
			"(?m)^\\s*+(?:[$#>]\\s++[^\n]*+|[+-]{3}\\s[^\n]*+|@@[^\n]*+@@[^\n]*+)$");

	/** URLs, including bare www hosts. */
	private static final Pattern URL = Pattern.compile(
			"(?i)\\b(?:https?://|ftp://|www\\.)[^\\s<>\"')\\]]++");

	/** Absolute and relative file paths, POSIX and Windows. */
	private static final Pattern FILE_PATH = Pattern.compile(
			"(?:[A-Za-z]:\\\\[^\\s\"'<>|]++"
					+ "|(?:\\.{1,2})?/(?:[\\w.@+-]++/)++[\\w.@+-]*+"
					+ "|\\b[\\w-]++\\.(?:java|dart|kt|kts|ts|tsx|js|jsx|py|rb|go|rs|c|h|cpp|cs|php|swift"
					+ "|yml|yaml|json|xml|html|css|scss|sql|sh|gradle|properties|lock|md|toml|ini)\\b)");

	/** HTML/XML tags — markup is not prose. */
	private static final Pattern MARKUP_TAG = Pattern.compile("</?[A-Za-z][\\w:-]*+(?:\\s[^<>]*+)?/?>");

	/** UUIDs. */
	private static final Pattern UUID = Pattern.compile(
			"(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

	/** Git SHAs, hashes and other long hex runs. */
	private static final Pattern HEX_BLOB = Pattern.compile("(?i)\\b(?:0x)?[0-9a-f]{7,}\\b");

	/** Base64-ish blobs: long unbroken runs of the alphabet. */
	private static final Pattern BASE64_BLOB = Pattern.compile("\\b[A-Za-z0-9+/]{40,}={0,2}\\b");

	/**
	 * Identifiers: camelCase, PascalCase, snake_case, kebab-case, SCREAMING_CASE,
	 * and dotted package paths. This is what removes {@code sexPreference},
	 * {@code KillProcessHandler} and {@code master_slave_config} — the single
	 * largest class of false positive after code fences.
	 *
	 * <p>Requires an internal case change or separator, so an ordinary lowercase
	 * word is never treated as an identifier and prose stays intact.
	 */
	private static final Pattern IDENTIFIER = Pattern.compile(
			"\\b(?:[a-z]++(?:[A-Z][a-z\\d]*+)++"
					+ "|[A-Z][a-z\\d]++(?:[A-Z][a-z\\d]*+)++"
					+ "|[A-Za-z]++(?:_[A-Za-z\\d]++)++"
					+ "|[a-z\\d]++(?:-[a-z\\d]++){2,}"
					+ "|[A-Za-z]++(?:\\.[A-Za-z][A-Za-z\\d]*+){2,})\\b");

	/** CLI flags: {@code -9}, {@code --force}, {@code -rf}. */
	private static final Pattern CLI_FLAG = Pattern.compile("(?<![\\w-])--?[A-Za-z\\d][\\w-]*+\\b");

	/** Issue keys and CVE references. */
	private static final Pattern TICKET_REF = Pattern.compile("(?i)\\b(?:CVE-\\d{4}-\\d{4,}|[A-Z]{2,10}-\\d++)\\b");

	/**
	 * Applied in order. Order matters: fences and indented blocks go first so their
	 * contents are captured whole rather than shredded by the finer patterns, and
	 * identifiers go late so they cannot eat a word another pattern needed.
	 */
	private static final Pattern[] PATTERNS = {
			FENCED_CODE, INDENTED_CODE, STACK_FRAME, LOG_LINE, COMMAND_LINE,
			INLINE_CODE, URL, FILE_PATH, MARKUP_TAG, UUID, HEX_BLOB, BASE64_BLOB,
			TICKET_REF, CLI_FLAG, IDENTIFIER,
	};

	/** Collapses the whitespace left where spans were removed. */
	private static final Pattern EXCESS_SPACE = Pattern.compile("[ \t]{2,}");

	private static final Pattern EXCESS_NEWLINE = Pattern.compile("\n{3,}");

	private TechnicalTextStripper() {
	}

	/**
	 * Splits [input] into prose and the technical spans removed from it.
	 *
	 * <p>Returns the input unchanged as prose when it is blank, and never returns
	 * null for either half.
	 */
	public static Result strip(String input) {
		if (input == null || input.isBlank()) {
			return new Result("", "");
		}
		StringBuilder technical = new StringBuilder();
		String working = input;
		for (Pattern pattern : PATTERNS) {
			working = removeAll(pattern, working, technical);
		}
		return new Result(tidy(working), technical.toString().strip());
	}

	/**
	 * Replaces every match of [pattern] with a space, appending what was removed to
	 * [sink]. A space rather than an empty string so that removing an identifier
	 * cannot fuse its neighbours into a word that was never written — the exact way
	 * a stripper invents a slur that nobody typed.
	 */
	private static String removeAll(Pattern pattern, String input, StringBuilder sink) {
		Matcher matcher = pattern.matcher(input);
		if (!matcher.find()) {
			return input;
		}
		StringBuilder out = new StringBuilder(input.length());
		int last = 0;
		do {
			out.append(input, last, matcher.start()).append(' ');
			sink.append(matcher.group()).append('\n');
			last = matcher.end();
		}
		while (matcher.find());
		out.append(input, last, input.length());
		return out.toString();
	}

	private static String tidy(String value) {
		String collapsed = EXCESS_SPACE.matcher(value).replaceAll(" ");
		return EXCESS_NEWLINE.matcher(collapsed).replaceAll("\n\n").strip();
	}
}
