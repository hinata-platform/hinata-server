package com.ahmadre.hinata.richtext;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * The exporter's contract is a fixed point, not textual identity: an agent may
 * read markdown, change one paragraph and write it back, and everything it did
 * <em>not</em> touch must come back as the same document. Textual identity is a
 * stronger promise than markdown can keep (there are several ways to write the
 * same list), and a weaker one than the loop needs.
 */
class LexicalToMarkdownTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final MarkdownToLexical converter = new MarkdownToLexical(new ObjectMapper());

	/** markdown → document. */
	private JsonNode doc(String markdown) {
		return converter.convert(markdown);
	}

	/** document → markdown. */
	private String md(String markdown) {
		return LexicalToMarkdown.convert(doc(markdown));
	}

	private void isFixedPoint(String markdown) {
		JsonNode first = doc(markdown);
		String exported = LexicalToMarkdown.convert(first);
		JsonNode second = doc(exported);

		assertThat(second)
				.as("round trip changed the document.%nexported markdown:%n%s", exported)
				.isEqualTo(first);
	}

	// --- the whole conformance corpus -----------------------------------------

	@Test
	void everyCorpusCaseIsAFixedPointUnderARoundTrip() {
		for (Map.Entry<String, String> entry : RichTextCorpusTest.corpus().entrySet()) {
			JsonNode first = doc(entry.getValue());
			String exported = LexicalToMarkdown.convert(first);
			JsonNode second = doc(exported);

			assertThat(second)
					.as("%s changed under markdown → doc → markdown → doc.%nexported:%n%s",
							entry.getKey(), exported)
					.isEqualTo(first);
		}
	}

	@Test
	void everyCorpusCaseExportsSomethingReadable() {
		for (Map.Entry<String, String> entry : RichTextCorpusTest.corpus().entrySet()) {
			assertThat(LexicalToMarkdown.convert(doc(entry.getValue())))
					.as(entry.getKey())
					.isNotBlank();
		}
	}

	// --- constructs, so a failure above says which one -------------------------

	@Test
	void headingsKeepTheirLevel() {
		assertThat(md("### Drei")).isEqualTo("### Drei");
		assertThat(md("###### Sechs")).isEqualTo("###### Sechs");
	}

	@Test
	void inlineFormattingIsWrappedOncePerRunNotOncePerNode() {
		// Wrapping per node yields `**fett mit **`, whose closing delimiter follows a
		// space — CommonMark does not read that as a close, and the round trip breaks.
		assertThat(md("**fett**, *kursiv*, ~~weg~~, `code` und **fett mit `code` drin**"))
				.isEqualTo("**fett**, *kursiv*, ~~weg~~, `code` und **fett mit `code` drin**");
	}

	@Test
	void aSmartLinkInsideEmphasisDoesNotSplitTheEmphasis() {
		// Closing and reopening around the token would move the spaces out of the
		// bold span, and the re-read document would differ.
		isFixedPoint("**fett {{issue:HIN-1}} weiter fett**");
		assertThat(md("**fett {{issue:HIN-1}} weiter fett**"))
				.isEqualTo("**fett {{issue:HIN-1}} weiter fett**");
	}

	@Test
	void smartLinksKeepTheirKindAndTarget() {
		assertThat(md("Siehe {{issue:HIN-5}}, {{doc:507f1f77bcf86cd799439011}} und {{user:abc}}."))
				.isEqualTo("Siehe {{issue:HIN-5}}, {{doc:507f1f77bcf86cd799439011}} und {{user:abc}}.");
	}

	@Test
	void calloutsComeBackAsFences() {
		assertThat(md(":::warn\n- eins\n- zwei\n:::"))
				.isEqualTo(":::warn\n- eins\n- zwei\n:::");
	}

	@Test
	void nestedListsKeepTheirNesting() {
		isFixedPoint("- außen\n  - innen\n    - ganz innen\n- zurück");
		isFixedPoint("1. eins\n   1. eins-eins\n2. zwei");
	}

	@Test
	void taskListsKeepTheirCheckboxes() {
		assertThat(md("- [x] erledigt\n- [ ] offen")).isEqualTo("- [x] erledigt\n- [ ] offen");
	}

	@Test
	void tablesKeepTheirHeaderAndCells() {
		isFixedPoint("| View | Groups by |\n|---|---|\n| Board | `Status` |");
		assertThat(md("| a | b |\n|---|---|\n| 1 | 2 |"))
				.isEqualTo("| a | b |\n| --- | --- |\n| 1 | 2 |");
	}

	@Test
	void aCellIsFlattenedToOneLineBecauseARowIsOneLine() {
		// A hard break inside a cell would otherwise end the row mid-table.
		String source = "| a | b |\n|---|---|\n| erste<br>zweite | 2 |";
		String exported = LexicalToMarkdown.convert(doc(source));

		assertThat(exported.lines().count()).isEqualTo(3);
		assertThat(exported).contains("erste<br>zweite");
		isFixedPoint(source);
	}

	@Test
	void codeBlocksKeepTheirLanguageAndContentVerbatim() {
		assertThat(md("```dart\nvoid main() {\n  print(\"hi\");\n}\n```"))
				.isEqualTo("```dart\nvoid main() {\n  print(\"hi\");\n}\n```");
	}

	@Test
	void aCodeBlockContainingAFenceGetsALongerFence() {
		String source = "````\n```\nnicht das Ende\n```\n````";

		isFixedPoint(source);
		assertThat(md(source)).startsWith("````").endsWith("````");
	}

	@Test
	void quotesKeepTheirParagraphBoundaries() {
		isFixedPoint("> Ein Zitat mit **Betonung**.");
		isFixedPoint("> erste\n>\n> zweite");
	}

	@Test
	void linksKeepTheirUrlAndTheirInnerFormatting() {
		assertThat(md("[ein *kursiver* Link](https://x.de)"))
				.isEqualTo("[ein *kursiver* Link](https://x.de)");
		assertThat(md("[t](https://x.de \"Titel\")")).isEqualTo("[t](https://x.de \"Titel\")");
	}

	@Test
	void imagesKeepTheirAltTextAndSource() {
		assertThat(md("![Ringelblumen](https://example.org/f.jpg)"))
				.isEqualTo("![Ringelblumen](https://example.org/f.jpg)");
	}

	@Test
	void inlineHtmlComesBackAsTheTextItIs() {
		assertThat(md("Wir brauchen List<String> statt List<Object>."))
				.isEqualTo("Wir brauchen List<String> statt List<Object>.");
		isFixedPoint("Das <Widget> rendert nicht, und der <br> fehlt.");
	}

	@Test
	void aHardLineBreakSurvives() {
		isFixedPoint("erste  \nzweite");
	}

	@Test
	void aHorizontalRuleSurvives() {
		assertThat(md("davor\n\n---\n\ndanach")).isEqualTo("davor\n\n---\n\ndanach");
	}

	// --- defensive reading ----------------------------------------------------

	@Test
	void aStoredDocumentThatCannotBeReadFallsBackToItsPlainText() {
		assertThat(LexicalToMarkdown.fromStored("{ not json", "der abgeleitete Text"))
				.isEqualTo("der abgeleitete Text");
		assertThat(LexicalToMarkdown.fromStored(null, "der abgeleitete Text"))
				.isEqualTo("der abgeleitete Text");
		// Verbatim, null included: a field that was absent stays absent rather than
		// becoming an empty string on the wire.
		assertThat(LexicalToMarkdown.fromStored("", null)).isNull();
	}

	@Test
	void aStoredDocumentIsRenderedRatherThanFallenBackOn() {
		String stored = new RichTextService().fromMarkdown("# Titel\n\n- eins").doc();

		assertThat(LexicalToMarkdown.fromStored(stored, "Titel eins"))
				.isEqualTo("# Titel\n\n- eins");
	}

	/**
	 * A stored document is untrusted input here exactly as it is for the reader,
	 * so an absurdly deep one must stop rather than exhaust the stack.
	 */
	@Test
	void anAbsurdlyDeepDocumentIsBoundedRatherThanFatal() throws Exception {
		StringBuilder open = new StringBuilder();
		StringBuilder close = new StringBuilder();
		for (int i = 0; i < 400; i++) {
			open.append("{\"type\":\"quote\",\"version\":1,\"children\":[");
			close.append("]}");
		}
		String json = "{\"root\":{\"type\":\"root\",\"version\":1,\"children\":[" + open
				+ "{\"type\":\"text\",\"version\":1,\"text\":\"tief\"}" + close + "]}}";

		assertThat(LexicalToMarkdown.convert(mapper.readTree(json))).doesNotContain("tief");
	}

	@Test
	void anUnknownNodeTypeStillContributesItsText() throws Exception {
		String json = """
				{"root":{"children":[{"children":[{"detail":0,"format":0,"mode":"normal",\
				"style":"","text":"drin","type":"text","version":1}],"direction":null,\
				"format":"","indent":0,"type":"someFutureBlock","version":1}],\
				"direction":null,"format":"","indent":0,"type":"root","version":1}}""";

		assertThat(LexicalToMarkdown.convert(mapper.readTree(json))).isEqualTo("drin");
	}
}
