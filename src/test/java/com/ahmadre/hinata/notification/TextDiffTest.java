package com.ahmadre.hinata.notification;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The word diff is what turns "Description: changed" into something a watcher
 * can act on, so what it must never do is lie: no word may appear on a side it
 * was never on, and no edit may go unshown because it sat far from the start.
 */
class TextDiffTest {

	/** More segments than any of these produce — the cap has its own test. */
	private static final int NO_CAP = 1_000;

	/** Segments as "PART:text", which is the shape a template paints. */
	private static List<String> painted(String before, String after, int context) {
		return TextDiff.words(before, after, context, NO_CAP).stream()
				.map(segment -> segment.part() + ":" + segment.text())
				.toList();
	}

	@Test
	void aReplacedWordIsShownBetweenTheWordsAroundIt() {
		assertThat(painted("the week starts on Sunday", "the week starts on Monday", 6))
				.containsExactly("SAME:the week starts on", "REMOVED:Sunday", "ADDED:Monday");
	}

	@Test
	void anInsertionIsAddedOnly() {
		assertThat(painted("ship it", "ship it today", 6))
				.containsExactly("SAME:ship it", "ADDED:today");
	}

	@Test
	void aDeletionIsRemovedOnly() {
		assertThat(painted("ship it today", "ship it", 6))
				.containsExactly("SAME:ship it", "REMOVED:today");
	}

	/** Two texts that read the same produce nothing at all — the caller has to be
	 *  able to tell "no visible change" from a change it can show. */
	@Test
	void equalTextsDiffToNothing() {
		assertThat(TextDiff.words("same words", "same  words", 6, NO_CAP)).isEmpty();
		assertThat(TextDiff.words(null, null, 6, NO_CAP)).isEmpty();
		assertThat(TextDiff.words("", "   ", 6, NO_CAP)).isEmpty();
	}

	@Test
	void oneSidedTextIsWhollyAddedOrWhollyRemoved() {
		assertThat(painted(null, "brand new", 6)).containsExactly("ADDED:brand new");
		assertThat(painted("all gone", "", 6)).containsExactly("REMOVED:all gone");
	}

	/**
	 * An edit far from the start is still shown. This is the case a plain "first
	 * N characters of each side" would have missed entirely — the two sides would
	 * have looked identical and the mail would have said nothing.
	 */
	@Test
	void anEditAtTheEndOfALongTextIsStillFound() {
		String head = "word ".repeat(200);

		assertThat(painted(head + "before", head + "after", 3))
				.containsExactly("SAME:… word word word", "REMOVED:before", "ADDED:after");
	}

	/** Unchanged runs are cut to the requested context; the ellipsis says so. */
	@Test
	void unchangedRunsAreCondensedToTheirContext() {
		assertThat(painted("a b c d e f g h X", "a b c d e f g h Y", 2))
				.containsExactly("SAME:… g h", "REMOVED:X", "ADDED:Y");
	}

	/** The edges of the text have nothing beyond them to give context to, so a
	 *  leading run keeps only its tail and a trailing run only its head. */
	@Test
	void theOuterEdgesKeepContextOnOneSideOnly() {
		assertThat(painted("a b c d X e f g h", "a b c d Y e f g h", 1))
				.containsExactly("SAME:… d", "REMOVED:X", "ADDED:Y", "SAME:e …");
	}

	/** Line breaks collapse: three surfaces read this text and only one of them
	 *  could show a break at all. A reflowed paragraph is not a change. */
	@Test
	void lineBreaksAreNotChanges() {
		assertThat(TextDiff.words("first line\nsecond line", "first line second line", 6, NO_CAP)).isEmpty();
	}

	/** Two texts with nothing in common still diff — the coarse path takes over
	 *  from the alignment, and it must produce exactly one side each. */
	@Test
	void twoVeryLongUnrelatedTextsStillProduceBothSides() {
		String before = "alpha ".repeat(600).trim();
		String after = "beta ".repeat(600).trim();

		List<TextDiff.Segment> diff = TextDiff.words(before, after, 6, NO_CAP);

		assertThat(diff).hasSize(2);
		assertThat(diff.get(0).part()).isEqualTo(TextDiff.Part.REMOVED);
		assertThat(diff.get(1).part()).isEqualTo(TextDiff.Part.ADDED);
		assertThat(TextDiff.summaryBefore(diff)).isEqualTo(before);
		assertThat(TextDiff.summaryAfter(diff)).isEqualTo(after);
	}
}
