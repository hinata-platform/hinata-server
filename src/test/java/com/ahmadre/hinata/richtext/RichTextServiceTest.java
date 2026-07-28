package com.ahmadre.hinata.richtext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ahmadre.hinata.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

/**
 * The service is the only writer of rich-text content, so the rules that must
 * hold at this boundary are product rules, not implementation details:
 *
 * <ul>
 *   <li>content the reader cannot read is a 400, never a silent empty save;</li>
 *   <li>anything the markdown side produces, the Lexical side can read back —
 *       otherwise a stored body is readable and permanently uneditable;</li>
 *   <li>a client that only knows the legacy field cannot flatten a document by
 *       echoing back the plain text it was given.</li>
 * </ul>
 */
class RichTextServiceTest {

	private final RichTextService service = new RichTextService();

	/** A document nested {@code levels} deep, with a word at the bottom. */
	private static String nested(int levels) {
		StringBuilder open = new StringBuilder();
		StringBuilder close = new StringBuilder();
		for (int i = 0; i < levels; i++) {
			open.append("{\"type\":\"paragraph\",\"version\":1,\"children\":[");
			close.append("]}");
		}
		return "{\"root\":{\"type\":\"root\",\"version\":1,\"children\":[" + open
				+ "{\"type\":\"text\",\"version\":1,\"text\":\"GEHEIMER INHALT\"}" + close + "]}}";
	}

	// --- "could not read it" must never become "stored nothing" ----------------

	@Test
	void aDocumentPastTheDepthBoundIsRejectedRatherThanStoredAsEmpty() {
		assertThatThrownBy(() -> service.fromLexical(nested(300)))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.richtext.tooLarge");
	}

	@Test
	void aDocumentPastTheNodeBoundIsRejectedRatherThanStoredAsEmpty() {
		StringBuilder children = new StringBuilder();
		for (int i = 0; i <= LexicalJson.MAX_NODES; i++) {
			if (i > 0) children.append(',');
			children.append("{\"type\":\"paragraph\",\"version\":1,\"children\":[]}");
		}
		String json = "{\"root\":{\"type\":\"root\",\"version\":1,\"children\":[" + children + "]}}";
		assertThat(json.length()).isLessThan(LexicalJson.MAX_JSON_CHARS);

		assertThatThrownBy(() -> service.fromLexical(json))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.richtext.tooLarge");
	}

	@Test
	void aDocumentInsideTheBoundsKeepsItsTextAndItsDocument() {
		RichText content = service.fromLexical(nested(10));

		assertThat(content.doc()).isNotNull();
		assertThat(content.text()).isEqualTo("GEHEIMER INHALT");
	}

	// --- the two directions have to agree -------------------------------------

	/**
	 * The read side refuses a document over {@link LexicalJson#MAX_JSON_CHARS}. If
	 * the write side accepted markdown that expands past it, the result would be an
	 * article that loads, cannot be saved again, and never can be.
	 */
	@Test
	void anythingFromMarkdownAcceptsIsSomethingFromLexicalCanReadBack() {
		// Worst-case expansion: every token its own formatted text node.
		String dense = "**a** ".repeat(RichTextService.MAX_MARKDOWN_CHARS / 6);
		assertThat(dense.length()).isLessThanOrEqualTo(RichTextService.MAX_MARKDOWN_CHARS);

		RichText converted = service.fromMarkdown(dense);

		assertThat(converted.doc()).isNotNull();
		assertThatCode(() -> service.fromLexical(converted.doc())).doesNotThrowAnyException();
	}

	@Test
	void markdownOverTheInputBoundIsRefusedRatherThanConverted() {
		String tooLong = "a".repeat(RichTextService.MAX_MARKDOWN_CHARS + 1);

		assertThatThrownBy(() -> service.fromMarkdown(tooLong))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.richtext.tooLarge");
	}

	/**
	 * Stored markdown predates the input bound, so the migration converts it under
	 * the read bound instead. A body that still expands past that bound is reported,
	 * not written: storing it would produce exactly the uneditable content above.
	 */
	@Test
	void storedMarkdownThatExpandsPastTheReadBoundIsRefusedRatherThanStored() {
		String dense = "**a** ".repeat(100_000 / 6);

		assertThatThrownBy(() -> service.fromStoredMarkdown(dense))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.richtext.tooLarge");
	}

	@Test
	void storedMarkdownOverTheLiveInputBoundIsStillConverted() {
		// A legacy body larger than a live caller may send must still migrate.
		String legacy = "Ein Absatz.\n\n".repeat(4_000);
		assertThat(legacy.length()).isGreaterThan(RichTextService.MAX_MARKDOWN_CHARS);

		assertThat(service.fromStoredMarkdown(legacy).doc()).isNotNull();
	}

	// --- an old client must not flatten a document ----------------------------

	@Test
	void aLegacyFieldEqualToTheStoredPlainTextIsNoChangeAtAll() {
		RichText stored = service.fromMarkdown("# Titel\n\nEin **fetter** Absatz.");

		// What an app build older than the document format sends back on save.
		RichText result = service.fromRequest(null, stored.text(), stored.doc(), stored.text());

		assertThat(result).as("no change → the stored document is left alone").isNull();
	}

	@Test
	void aLegacyFieldThatGenuinelyDiffersIsARealEditAndIsConverted() {
		RichText stored = service.fromMarkdown("# Titel\n\nEin **fetter** Absatz.");

		RichText result = service.fromRequest(null, "Etwas ganz anderes.", stored.doc(), stored.text());

		assertThat(result).isNotNull();
		assertThat(result.text()).isEqualTo("Etwas ganz anderes.");
	}

	@Test
	void aDocumentAlwaysWinsOverTheLegacyField() {
		RichText stored = service.fromMarkdown("alt");
		RichText fresh = service.fromMarkdown("neu");

		RichText result = service.fromRequest(fresh.doc(), "irgendwas", stored.doc(), stored.text());

		assertThat(result.text()).isEqualTo("neu");
	}

	@Test
	void aLegacyFieldOnAnEntityWithoutADocumentIsStillConverted() {
		RichText result = service.fromRequest(null, "**fett**", null, null);

		assertThat(result).isNotNull();
		assertThat(result.text()).isEqualTo("fett");
	}

	// --- the backlink index is derived, so it has to be clean -----------------

	@Test
	void onlyValuesThatAreActuallyIssueKeysBecomeBacklinks() {
		RichText content = service.fromMarkdown(
				"{{issue:HIN-1}} {{issue:nokey}} {{issue:HIN-1\", $ne: null, x: \"}} {{issue:mob-7}}");

		assertThat(content.issueKeys()).containsExactly("HIN-1", "MOB-7");
	}

	@Test
	void theBacklinkListIsCapped() {
		String many = IntStream.rangeClosed(1, 500)
				.mapToObj(i -> "{{issue:HIN-" + i + "}}")
				.reduce("", (a, b) -> a + " " + b);

		assertThat(service.fromMarkdown(many).issueKeys()).hasSize(200);
	}

	// --- concurrency ----------------------------------------------------------

	/**
	 * The mapper, the converter and flexmark's parser are all static and shared
	 * across every request thread. That is only safe because none of them is
	 * reconfigured after construction — a smoke test is cheap insurance against
	 * someone adding state to one of them later.
	 */
	@Test
	void conversionIsThreadSafe() throws Exception {
		List<String> inputs = List.of(
				"# Titel\n\nEin **fetter** Absatz mit {{issue:HIN-1}}.",
				":::info\nGeprüft von {{user:507f1f77bcf86cd799439011}}.\n:::",
				"- [x] eins\n- [ ] zwei\n\n| a | b |\n|---|---|\n| 1 | `2` |",
				"Wir brauchen List<String> statt List<Object>.");
		List<String> baseline = inputs.stream().map(md -> service.fromMarkdown(md).doc()).toList();

		try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
			List<Callable<String>> work = new ArrayList<>();
			for (int i = 0; i < 1_000; i++) {
				int index = i % inputs.size();
				work.add(() -> {
					RichText converted = service.fromMarkdown(inputs.get(index));
					return baseline.get(index).equals(converted.doc()) ? "" : "mismatch at " + index;
				});
			}
			for (Future<String> result : pool.invokeAll(work)) {
				assertThat(result.get()).isEmpty();
			}
		}
	}
}
