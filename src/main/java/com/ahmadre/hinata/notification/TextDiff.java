package com.ahmadre.hinata.notification;

import java.util.ArrayList;
import java.util.List;

/**
 * A word-level diff of two pieces of plain text, as the segments a reader sees:
 * what stayed, what went (red), what arrived (green).
 *
 * <p>This exists because "Description: changed" is not information. A watcher
 * reading that has learned only that they now have to open the issue to find out
 * what happened — which is precisely the trip the notification was supposed to
 * save them. Showing the words that moved lets most changes be triaged straight
 * from the inbox.
 *
 * <p>Words rather than characters: a character diff of prose produces a confetti
 * of one-letter fragments that is harder to read than the two versions side by
 * side. Words are the unit people actually edit in.
 *
 * <p>Nothing here knows about HTML, colour or e-mail. It returns segments; the
 * templates decide what red and green look like, and the one-line push summary
 * flattens the very same segments into an arrow.
 */
public final class TextDiff {

	/** What happened to a run of words. */
	public enum Part {
		/** Unchanged context, shown in the ordinary ink. */
		SAME,
		/** Present before, gone after — the "red" half of the diff. */
		REMOVED,
		/** Absent before, present after — the "green" half. */
		ADDED
	}

	/**
	 * One run of text with a single fate. The {@code added}/{@code removed}/
	 * {@code same} accessors exist for the templates: Thymeleaf compares enums
	 * only through fully-qualified {@code T(...)} expressions, which would put a
	 * Java class name into three e-mail templates.
	 */
	public record Segment(Part part, String text) {

		public boolean same() {
			return part == Part.SAME;
		}

		public boolean removed() {
			return part == Part.REMOVED;
		}

		public boolean added() {
			return part == Part.ADDED;
		}
	}

	/**
	 * Ceiling on the quadratic table the longest-common-subsequence walk fills.
	 * The inputs are already clipped upstream (a description excerpt is at most
	 * {@code IssueChangeDiff.TEXT_MAX} characters), so this is the second belt on
	 * the same trousers: whatever slips through gets the coarse "all of the old,
	 * then all of the new" diff instead of a mail send that stalls on a table
	 * nobody sized.
	 */
	private static final int MAX_CELLS = 250_000;

	/** Stands in for the words a long unchanged run is condensed away to. */
	private static final String ELLIPSIS = "…";

	private TextDiff() {
	}

	/**
	 * The diff of {@code before} and {@code after}, with unchanged runs longer
	 * than {@code context} words condensed to {@code context} words on each side
	 * of the change.
	 *
	 * <p>Condensing is what keeps a one-word fix to a long description from
	 * mailing the whole description back: the reader wants the sentence that
	 * moved, with enough around it to place it, not the document.
	 *
	 * @return an empty list when both sides are blank, or when they are equal —
	 *         "nothing visibly changed" is a real answer, and the caller has to be
	 *         able to tell it from a change it can show
	 */
	public static List<Segment> words(String before, String after, int context) {
		List<String> a = tokens(before);
		List<String> b = tokens(after);
		if (a.isEmpty() && b.isEmpty()) return List.of();
		if (a.equals(b)) return List.of();
		return join(condense(runs(a, b), Math.max(0, context)));
	}

	/**
	 * Flattens segments back into the text as it was: everything the reader would
	 * have seen before the change. Empty when the field was empty before.
	 */
	public static String before(List<Segment> segments) {
		return flatten(segments, Part.ADDED);
	}

	/** Flattens segments into the text as it is now. */
	public static String after(List<Segment> segments) {
		return flatten(segments, Part.REMOVED);
	}

	private static String flatten(List<Segment> segments, Part skip) {
		StringBuilder text = new StringBuilder();
		for (Segment segment : segments) {
			if (segment.part() == skip) continue;
			if (!text.isEmpty()) text.append(' ');
			text.append(segment.text());
		}
		return text.toString();
	}

	// --- the diff itself -------------------------------------------------------

	/** A run of tokens sharing one fate — {@link Segment} before it is worded. */
	private record Run(Part part, List<String> tokens) {
	}

	/**
	 * The two token lists as runs, in reading order.
	 *
	 * <p>The common prefix and suffix are peeled off first. That is not only an
	 * optimisation: a typed edit is almost always a small change inside a long
	 * unchanged text, so peeling turns the usual case into a tiny table, and it is
	 * what keeps the quadratic walk below off the hot path entirely.
	 */
	private static List<Run> runs(List<String> a, List<String> b) {
		int n = a.size();
		int m = b.size();
		int prefix = 0;
		while (prefix < n && prefix < m && a.get(prefix).equals(b.get(prefix))) prefix++;
		int suffix = 0;
		while (suffix < n - prefix && suffix < m - prefix
				&& a.get(n - 1 - suffix).equals(b.get(m - 1 - suffix))) {
			suffix++;
		}
		List<String> midA = a.subList(prefix, n - suffix);
		List<String> midB = b.subList(prefix, m - suffix);

		List<Run> runs = new ArrayList<>();
		add(runs, Part.SAME, a.subList(0, prefix));
		if ((long) midA.size() * midB.size() > MAX_CELLS) {
			// Too big to align word by word. Saying "all of this went, all of that
			// arrived" is still true, still readable, and cannot be mistaken for a
			// finer answer than we have.
			add(runs, Part.REMOVED, midA);
			add(runs, Part.ADDED, midB);
		}
		else {
			runs.addAll(align(midA, midB));
		}
		add(runs, Part.SAME, a.subList(n - suffix, n));
		return runs;
	}

	/** Classic longest-common-subsequence alignment of two short token lists. */
	private static List<Run> align(List<String> a, List<String> b) {
		int n = a.size();
		int m = b.size();
		// lcs[i][j] = length of the longest common subsequence of a[i..] and b[j..],
		// so the walk below can read forwards and keep the output in reading order.
		int[][] lcs = new int[n + 1][m + 1];
		for (int i = n - 1; i >= 0; i--) {
			for (int j = m - 1; j >= 0; j--) {
				lcs[i][j] = a.get(i).equals(b.get(j))
						? lcs[i + 1][j + 1] + 1
						: Math.max(lcs[i + 1][j], lcs[i][j + 1]);
			}
		}
		List<Run> runs = new ArrayList<>();
		int i = 0;
		int j = 0;
		while (i < n && j < m) {
			if (a.get(i).equals(b.get(j))) {
				append(runs, Part.SAME, a.get(i++));
				j++;
			}
			else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
				append(runs, Part.REMOVED, a.get(i++));
			}
			else {
				append(runs, Part.ADDED, b.get(j++));
			}
		}
		while (i < n) append(runs, Part.REMOVED, a.get(i++));
		while (j < m) append(runs, Part.ADDED, b.get(j++));
		return runs;
	}

	private static void append(List<Run> runs, Part part, String token) {
		if (!runs.isEmpty() && runs.getLast().part() == part) {
			runs.getLast().tokens().add(token);
			return;
		}
		runs.add(new Run(part, new ArrayList<>(List.of(token))));
	}

	private static void add(List<Run> runs, Part part, List<String> tokens) {
		if (tokens.isEmpty()) return;
		if (!runs.isEmpty() && runs.getLast().part() == part) {
			runs.getLast().tokens().addAll(tokens);
			return;
		}
		runs.add(new Run(part, new ArrayList<>(tokens)));
	}

	/**
	 * Cuts unchanged runs down to {@code context} words on each side of whatever
	 * they sit between. A leading run keeps only its tail and a trailing run only
	 * its head — context points at the change, and there is nothing on the far
	 * side of the document's edge to point at.
	 */
	private static List<Run> condense(List<Run> runs, int context) {
		List<Run> condensed = new ArrayList<>(runs.size());
		for (int index = 0; index < runs.size(); index++) {
			Run run = runs.get(index);
			if (run.part() != Part.SAME) {
				condensed.add(run);
				continue;
			}
			boolean openLeft = index > 0;
			boolean openRight = index < runs.size() - 1;
			List<String> tokens = run.tokens();
			int keepLeft = openLeft ? context : 0;
			int keepRight = openRight ? context : 0;
			if (tokens.size() <= keepLeft + keepRight) {
				condensed.add(run);
				continue;
			}
			List<String> kept = new ArrayList<>(keepLeft + keepRight + 1);
			kept.addAll(tokens.subList(0, keepLeft));
			kept.add(ELLIPSIS);
			kept.addAll(tokens.subList(tokens.size() - keepRight, tokens.size()));
			condensed.add(new Run(Part.SAME, kept));
		}
		return condensed;
	}

	// --- words in, words out ---------------------------------------------------

	/**
	 * Text as the diff sees it: its words, with every run of whitespace treated
	 * as one separator.
	 *
	 * <p>Line breaks are deliberately <em>not</em> preserved. The output has to
	 * survive three surfaces — an HTML mail, a single-line push body and a bell
	 * entry — and only the first of those can show a break at all, and then only
	 * with a {@code white-space} rule that Outlook does not honour. Collapsing
	 * them here means one text reads correctly everywhere, and it has the side
	 * benefit that a reflowed paragraph is not reported as a change.
	 */
	private static List<String> tokens(String text) {
		List<String> tokens = new ArrayList<>();
		if (text == null || text.isBlank()) return tokens;
		int index = 0;
		int length = text.length();
		while (index < length) {
			while (index < length && Character.isWhitespace(text.charAt(index))) index++;
			int start = index;
			while (index < length && !Character.isWhitespace(text.charAt(index))) index++;
			if (index > start) tokens.add(text.substring(start, index));
		}
		return tokens;
	}

	/** Runs back into segments, their words rejoined by single spaces. */
	private static List<Segment> join(List<Run> runs) {
		List<Segment> segments = new ArrayList<>(runs.size());
		for (Run run : runs) {
			String text = String.join(" ", run.tokens());
			if (!text.isEmpty()) segments.add(new Segment(run.part(), text));
		}
		return segments;
	}
}
