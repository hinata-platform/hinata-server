package com.ahmadre.hinata.git;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Atlassian-style <em>smart commits</em> from a commit message. Commands
 * bind to the most recently seen issue key, so multiple keys and multiple
 * commands per message are supported:
 *
 * <pre>
 *   KEY-123 #comment ready for QA      → a comment authored by the committer
 *   KEY-123 #time 2h 30m               → logs work on the issue
 *   KEY-123 #done                      → any other #word is a workflow transition
 * </pre>
 *
 * Pure and side-effect free — {@link GitService#applySmartCommits} turns the
 * parsed {@link Command}s into real comments / worklogs / transitions.
 */
public final class SmartCommitParser {

	private SmartCommitParser() {
	}

	public enum Type {
		COMMENT, TIME, TRANSITION
	}

	/** One parsed command. {@code value} is the comment text, raw duration, or transition word. */
	public record Command(String issueKey, Type type, String value) {
	}

	private static final Pattern KEY = Pattern.compile("^[A-Z][A-Z0-9]+-\\d+$");
	/** A whole duration token: one or more {@code <n><unit>} groups, e.g. 2h, 30m, 2h30m. */
	private static final Pattern DURATION_TOKEN = Pattern.compile("^(\\d+[wdhm])+$", Pattern.CASE_INSENSITIVE);
	private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([wdhm])", Pattern.CASE_INSENSITIVE);
	/** Trim surrounding punctuation so "[HN-2]:", "(HN-2)" or "HN-2:" still bind as keys. */
	private static final Pattern LEADING_PUNCT = Pattern.compile("^[^A-Za-z0-9]+");
	private static final Pattern TRAILING_PUNCT = Pattern.compile("[^A-Za-z0-9]+$");

	public static List<Command> parse(String message) {
		List<Command> out = new ArrayList<>();
		if (message == null || message.isBlank()) {
			return out;
		}
		String[] tokens = message.trim().split("\\s+");
		String key = null;
		int i = 0;
		while (i < tokens.length) {
			String token = tokens[i];
			String asKey = asKey(token);
			if (asKey != null) {
				key = asKey;
				i++;
				continue;
			}
			if (isCommand(token) && key != null) {
				String cmd = token.substring(1).toLowerCase();
				if (cmd.equals("comment")) {
					StringBuilder text = new StringBuilder();
					i++;
					while (i < tokens.length && !isCommand(tokens[i]) && asKey(tokens[i]) == null) {
						if (text.length() > 0) {
							text.append(' ');
						}
						text.append(tokens[i]);
						i++;
					}
					if (text.length() > 0) {
						out.add(new Command(key, Type.COMMENT, text.toString()));
					}
					continue;
				}
				if (cmd.equals("time")) {
					StringBuilder dur = new StringBuilder();
					i++;
					while (i < tokens.length && DURATION_TOKEN.matcher(tokens[i]).matches()) {
						if (dur.length() > 0) {
							dur.append(' ');
						}
						dur.append(tokens[i]);
						i++;
					}
					if (dur.length() > 0) {
						out.add(new Command(key, Type.TIME, dur.toString()));
					}
					continue;
				}
				// any other #word → a workflow transition
				out.add(new Command(key, Type.TRANSITION, cmd));
				i++;
				continue;
			}
			i++;
		}
		return out;
	}

	private static boolean isCommand(String token) {
		return token.length() > 1 && token.charAt(0) == '#';
	}

	/**
	 * Returns the issue key carried by a token, tolerating wrapping punctuation
	 * ("[HN-2]:", "(HN-2)", "HN-2:" → "HN-2"), or {@code null} if it isn't a key.
	 */
	private static String asKey(String token) {
		String trimmed = LEADING_PUNCT.matcher(token).replaceFirst("");
		trimmed = TRAILING_PUNCT.matcher(trimmed).replaceFirst("").toUpperCase();
		return KEY.matcher(trimmed).matches() ? trimmed : null;
	}

	/**
	 * Converts a smart-commit duration ("2h 30m", "1d 4h", "90m", "2h30m") into
	 * minutes. Uses the Jira-conventional {@code 1w = 5d}, {@code 1d = 8h}.
	 */
	public static int minutes(String duration) {
		if (duration == null) {
			return 0;
		}
		int total = 0;
		Matcher m = DURATION_PART.matcher(duration.toLowerCase());
		while (m.find()) {
			int n = Integer.parseInt(m.group(1));
			total += switch (m.group(2)) {
				case "w" -> n * 5 * 8 * 60;
				case "d" -> n * 8 * 60;
				case "h" -> n * 60;
				case "m" -> n;
				default -> 0;
			};
		}
		return total;
	}
}
