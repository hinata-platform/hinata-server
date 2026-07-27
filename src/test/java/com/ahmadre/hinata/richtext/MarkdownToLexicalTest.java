package com.ahmadre.hinata.richtext;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The wire format is a contract with the Flutter editor, so these assertions are
 * against the exact shapes in the conformance corpus
 * ({@code lexical_core/test/fixtures}) rather than against whatever this
 * converter happens to emit. A field renamed here and not there produces a
 * document the app silently fails to open.
 */
class MarkdownToLexicalTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final MarkdownToLexical converter = new MarkdownToLexical(new ObjectMapper());

	private JsonNode blocks(String markdown) {
		return converter.convert(markdown).path("root").path("children");
	}

	private JsonNode block(String markdown) {
		return blocks(markdown).path(0);
	}

	// --- structure ------------------------------------------------------------

	@Test
	void wrapsBlocksInACanonicalRoot() {
		JsonNode root = converter.convert("Hallo").path("root");

		assertThat(root.path("type").asText()).isEqualTo("root");
		assertThat(root.path("version").asInt()).isEqualTo(1);
		assertThat(root.path("format").asText()).isEmpty();
		assertThat(root.path("indent").asInt()).isZero();
		assertThat(root.path("direction").isNull()).isTrue();
	}

	@Test
	void blankInputBecomesTheEmptyDocumentRatherThanNothing() {
		JsonNode children = blocks("   \n  ");

		assertThat(children).hasSize(1);
		assertThat(children.path(0).path("type").asText()).isEqualTo("paragraph");
		assertThat(children.path(0).path("children")).isEmpty();
	}

	@Test
	void paragraphCarriesTheDerivedTextFormatFields() {
		JsonNode paragraph = block("Hallo Welt");

		assertThat(paragraph.path("type").asText()).isEqualTo("paragraph");
		assertThat(paragraph.path("textFormat").asInt()).isZero();
		assertThat(paragraph.path("textStyle").asText()).isEmpty();
		JsonNode text = paragraph.path("children").path(0);
		assertThat(text.path("type").asText()).isEqualTo("text");
		assertThat(text.path("text").asText()).isEqualTo("Hallo Welt");
		assertThat(text.path("detail").asInt()).isZero();
		assertThat(text.path("mode").asText()).isEqualTo("normal");
		assertThat(text.path("style").asText()).isEmpty();
		assertThat(text.path("version").asInt()).isEqualTo(1);
	}

	// --- the ceiling the old renderer could not clear ------------------------

	@Test
	void nestsInlineFormatting() {
		// The app's regex alternation could not express this: one alternative wins
		// and the inner span is rendered as literal backticks.
		JsonNode children = block("**fett mit `code` drin**").path("children");

		assertThat(children).hasSize(3);
		assertThat(children.path(0).path("text").asText()).isEqualTo("fett mit ");
		assertThat(children.path(0).path("format").asInt()).isEqualTo(1);
		assertThat(children.path(1).path("text").asText()).isEqualTo("code");
		assertThat(children.path(1).path("format").asInt()).isEqualTo(1 | 16);
		assertThat(children.path(2).path("text").asText()).isEqualTo(" drin");
		assertThat(children.path(2).path("format").asInt()).isEqualTo(1);
	}

	@Test
	void keepsEmphasisInsideLinkText() {
		JsonNode link = block("[ein *kursiver* Link](https://example.org)").path("children").path(0);

		assertThat(link.path("type").asText()).isEqualTo("link");
		assertThat(link.path("url").asText()).isEqualTo("https://example.org");
		assertThat(link.path("children").path(1).path("format").asInt()).isEqualTo(2);
		assertThat(link.path("children").path(1).path("text").asText()).isEqualTo("kursiver");
	}

	@Test
	void mergesAdjacentTextOfTheSameFormat() {
		// Lexical normalizes this on import; doing it here means a converted
		// document already matches what the editor would save.
		JsonNode children = block("ganz *normaler* text").path("children");

		assertThat(children).hasSize(3);
	}

	// --- blocks ---------------------------------------------------------------

	@Test
	void headingsCarryTheirTag() {
		assertThat(block("# Eins").path("tag").asText()).isEqualTo("h1");
		assertThat(block("###### Sechs").path("tag").asText()).isEqualTo("h6");
		assertThat(block("### Drei").path("type").asText()).isEqualTo("heading");
	}

	@Test
	void fencedCodeKeepsItsLanguageAndLiteralContent() {
		JsonNode code = block("```dart\nvoid main() {\n  print(\"hi\");\n}\n```");

		assertThat(code.path("type").asText()).isEqualTo("code");
		assertThat(code.path("language").asText()).isEqualTo("dart");
		assertThat(code.path("children").path(0).path("text").asText())
				.isEqualTo("void main() {\n  print(\"hi\");\n}");
	}

	@Test
	void codeBlockContentIsNotInlineParsed() {
		JsonNode code = block("```\n**nicht fett** und {{issue:HIN-1}}\n```");

		assertThat(code.path("children")).hasSize(1);
		assertThat(code.path("children").path(0).path("text").asText())
				.isEqualTo("**nicht fett** und {{issue:HIN-1}}");
		assertThat(code.path("children").path(0).path("format").asInt()).isZero();
	}

	@Test
	void quoteHoldsInlineChildrenDirectly() {
		// Lexical's quote is not a container of paragraphs; nesting one inside
		// would produce a tree the editor rejects.
		JsonNode quote = block("> Ein Zitat");

		assertThat(quote.path("type").asText()).isEqualTo("quote");
		assertThat(quote.path("children").path(0).path("type").asText()).isEqualTo("text");
		assertThat(quote.path("children").path(0).path("text").asText()).isEqualTo("Ein Zitat");
	}

	@Test
	void bulletListMatchesTheFixtureShape() {
		JsonNode list = block("- erster Punkt\n- zweiter Punkt");

		assertThat(list.path("type").asText()).isEqualTo("list");
		assertThat(list.path("listType").asText()).isEqualTo("bullet");
		assertThat(list.path("tag").asText()).isEqualTo("ul");
		assertThat(list.path("start").asInt()).isEqualTo(1);
		assertThat(list.path("children").path(0).path("value").asInt()).isEqualTo(1);
		assertThat(list.path("children").path(1).path("value").asInt()).isEqualTo(2);
		assertThat(list.path("children").path(0).path("type").asText()).isEqualTo("listitem");
	}

	@Test
	void orderedListKeepsItsStartNumber() {
		JsonNode list = block("3. drei\n4. vier");

		assertThat(list.path("listType").asText()).isEqualTo("number");
		assertThat(list.path("tag").asText()).isEqualTo("ol");
		assertThat(list.path("start").asInt()).isEqualTo(3);
		assertThat(list.path("children").path(0).path("value").asInt()).isEqualTo(3);
	}

	@Test
	void nestedListLivesInsideItsParentItemAndIndents() {
		JsonNode list = block("- außen\n  - innen");

		JsonNode outerItems = list.path("children");
		JsonNode nested = outerItems.path(outerItems.size() - 1).path("children").path(0);
		assertThat(nested.path("type").asText()).isEqualTo("list");
		assertThat(nested.path("children").path(0).path("indent").asInt()).isEqualTo(1);
		assertThat(nested.path("children").path(0).path("children").path(0).path("text").asText())
				.isEqualTo("innen");
	}

	@Test
	void taskListBecomesACheckList() {
		JsonNode list = block("- [x] erledigt\n- [ ] offen");

		assertThat(list.path("listType").asText()).isEqualTo("check");
		assertThat(list.path("children").path(0).path("checked").asBoolean()).isTrue();
		assertThat(list.path("children").path(1).path("checked").asBoolean()).isFalse();
	}

	@Test
	void thematicBreakBecomesAHorizontalRule() {
		assertThat(block("---").path("type").asText()).isEqualTo("horizontalrule");
	}

	@Test
	void tableRowsAndHeaderStateMatchTheFixture() {
		JsonNode table = block("| View | Groups by |\n|---|---|\n| Board | Status |");

		assertThat(table.path("type").asText()).isEqualTo("table");
		assertThat(table.path("children")).hasSize(2);
		JsonNode headerCell = table.path("children").path(0).path("children").path(0);
		assertThat(headerCell.path("type").asText()).isEqualTo("tablecell");
		assertThat(headerCell.path("headerState").asInt()).isEqualTo(1);
		assertThat(headerCell.path("colSpan").asInt()).isEqualTo(1);
		assertThat(headerCell.path("rowSpan").asInt()).isEqualTo(1);
		assertThat(headerCell.path("backgroundColor").isNull()).isTrue();
		// A cell wraps its content in a paragraph, as the fixture does.
		assertThat(headerCell.path("children").path(0).path("type").asText()).isEqualTo("paragraph");
		assertThat(table.path("children").path(1).path("children").path(0).path("headerState").asInt())
				.isZero();
	}

	// --- the hinata dialect ---------------------------------------------------

	@Test
	void calloutBecomesItsOwnBlock() {
		JsonNode callout = block(":::info\nEvery production change is reviewed.\n:::");

		assertThat(callout.path("type").asText()).isEqualTo("callout");
		assertThat(callout.path("kind").asText()).isEqualTo("info");
		assertThat(callout.path("children").path(0).path("type").asText()).isEqualTo("paragraph");
	}

	@Test
	void calloutKeepsBlockStructureInside() {
		JsonNode callout = block(":::warn\n- eins\n- zwei\n:::");

		assertThat(callout.path("children").path(0).path("type").asText()).isEqualTo("list");
	}

	@Test
	void unterminatedCalloutStaysOrdinaryText() {
		// Swallowing the rest of a document over a missing closing fence would be
		// the worst possible reading of a typo.
		JsonNode children = blocks(":::tip\nDer Rest des Dokuments");

		assertThat(children.path(0).path("type").asText()).isNotEqualTo("callout");
		assertThat(converter.convert(":::tip\nDer Rest des Dokuments").toString())
				.contains("Der Rest des Dokuments");
	}

	@Test
	void smartLinkTokensBecomeNodes() {
		JsonNode children = block("Siehe {{issue:HIN-5}} von {{user:507f1f77bcf86cd799439011}}.")
				.path("children");

		assertThat(children.path(0).path("text").asText()).isEqualTo("Siehe ");
		JsonNode issue = children.path(1);
		assertThat(issue.path("type").asText()).isEqualTo("smartlink");
		assertThat(issue.path("kind").asText()).isEqualTo("issue");
		assertThat(issue.path("targetId").asText()).isEqualTo("HIN-5");
		assertThat(issue.path("label").asText()).isEqualTo("HIN-5");
		JsonNode user = children.path(3);
		assertThat(user.path("kind").asText()).isEqualTo("user");
		assertThat(user.path("targetId").asText()).isEqualTo("507f1f77bcf86cd799439011");
		// No readable label, so none is invented — this is what keeps ObjectIds out
		// of the search index.
		assertThat(user.path("label").isNull()).isTrue();
	}

	// --- fidelity -------------------------------------------------------------

	@Test
	void softLineBreakBecomesASpace() {
		// The app's renderer joined paragraph lines with ' '. Emitting a line break
		// instead would visibly reflow every document that already exists.
		JsonNode children = block("erste Zeile\nzweite Zeile").path("children");

		assertThat(children).hasSize(1);
		assertThat(children.path(0).path("text").asText()).isEqualTo("erste Zeile zweite Zeile");
	}

	@Test
	void hardLineBreakSurvivesAsALineBreakNode() {
		JsonNode children = block("erste Zeile  \nzweite Zeile").path("children");

		assertThat(children.path(1).path("type").asText()).isEqualTo("linebreak");
	}

	@Test
	void imageCarriesTheFieldsTheDecoratorExpects() {
		JsonNode image = block("![Ringelblumen](https://example.org/f.jpg)")
				.path("children").path(0);

		assertThat(image.path("type").asText()).isEqualTo("image");
		assertThat(image.path("src").asText()).isEqualTo("https://example.org/f.jpg");
		assertThat(image.path("altText").asText()).isEqualTo("Ringelblumen");
		assertThat(image.path("maxWidth").asInt()).isEqualTo(500);
		assertThat(image.path("showCaption").asBoolean()).isFalse();
		assertThat(image.path("caption").path("editorState").path("root").path("type").asText())
				.isEqualTo("root");
	}

	@Test
	void rawHtmlIsKeptAsTextRatherThanDropped() {
		String out = converter.convert("<div data-x=\"1\">Inhalt</div>").toString();

		assertThat(out).contains("Inhalt");
	}

	@Test
	void strikethroughCarriesItsBit() {
		JsonNode children = block("~~weg~~").path("children");

		assertThat(children.path(0).path("format").asInt()).isEqualTo(4);
	}

	@Test
	void everyElementCarriesVersionAndDirection() {
		JsonNode document = converter.convert("""
				# Titel

				Ein Absatz mit **fett**.

				- eins
				- zwei

				> Zitat

				| a | b |
				|---|---|
				| 1 | 2 |
				""");

		assertNodeInvariants(document.path("root"));
	}

	/** Every element must carry the fields the importer reads, at every depth. */
	private void assertNodeInvariants(JsonNode node) {
		assertThat(node.path("type").asText()).isNotEmpty();
		assertThat(node.path("version").asInt()).isEqualTo(1);
		if (node.has("children")) {
			assertThat(node.has("direction")).isTrue();
			assertThat(node.has("format")).isTrue();
			assertThat(node.has("indent")).isTrue();
			for (JsonNode child : node.path("children")) assertNodeInvariants(child);
		}
	}

	@Test
	void documentSerializesAndReparsesUnchanged() throws Exception {
		String markdown = "## Release\n\nSiehe {{issue:HIN-5}}.\n\n- [x] fertig\n";
		String json = mapper.writeValueAsString(converter.convert(markdown));

		assertThat(mapper.readTree(json)).isEqualTo(converter.convert(markdown));
	}
}
