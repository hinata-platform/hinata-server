package com.ahmadre.hinata.richtext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ahmadre.hinata.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The plain-text projection carries the Mongo text index and every notification
 * teaser, so what it does and does not contain is a product decision, not an
 * implementation detail.
 */
class LexicalJsonTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final MarkdownToLexical converter = new MarkdownToLexical(new ObjectMapper());

	private String textOf(String markdown) {
		return LexicalJson.plainText(converter.convert(markdown));
	}

	// --- projection -----------------------------------------------------------

	@Test
	void keepsTheWordsAndDropsTheMarkup() {
		assertThat(textOf("Ein **fetter** und *kursiver* Satz."))
				.isEqualTo("Ein fetter und kursiver Satz.");
	}

	@Test
	void separatesBlocksByNewlineSoWordsDoNotFuse() {
		// "Titel" and "Absatz" running together would create a word that is not in
		// the document, and the search index would hold it.
		assertThat(textOf("# Titel\n\nAbsatz")).isEqualTo("Titel\nAbsatz");
	}

	@Test
	void inlineElementsRunTogetherWithTheirSurroundings() {
		assertThat(textOf("Siehe [die Doku](https://example.org) dazu."))
				.isEqualTo("Siehe die Doku dazu.");
	}

	@Test
	void listItemsEachLandOnTheirOwnLine() {
		assertThat(textOf("- eins\n- zwei")).isEqualTo("eins\nzwei");
	}

	@Test
	void codeBlockContentIsIndexed() {
		// Searching for a symbol you remember seeing in a snippet is a real use.
		assertThat(textOf("```dart\nvoid main() {}\n```")).contains("void main() {}");
	}

	@Test
	void tableCellsAreIndexed() {
		assertThat(textOf("| View | Groups by |\n|---|---|\n| Board | Status |"))
				.contains("View").contains("Groups by").contains("Board").contains("Status");
	}

	@Test
	void imageContributesItsAltText() {
		assertThat(textOf("![Ringelblumen](https://example.org/f.jpg)")).isEqualTo("Ringelblumen");
	}

	@Test
	void issueKeysAreIndexedButObjectIdsAreNot() {
		// This is the improvement over markdown storage: today the raw
		// {{user:<objectid>}} token goes into the text index as a hex word.
		String text = textOf("Siehe {{issue:HIN-5}} von {{user:507f1f77bcf86cd799439011}}.");

		assertThat(text).contains("HIN-5");
		assertThat(text).doesNotContain("507f1f77bcf86cd799439011");
	}

	@Test
	void calloutContentIsIndexed() {
		assertThat(textOf(":::info\nJede Änderung wird geprüft.\n:::"))
				.isEqualTo("Jede Änderung wird geprüft.");
	}

	@Test
	void lineBreaksSurvive() {
		assertThat(textOf("erste  \nzweite")).isEqualTo("erste\nzweite");
	}

	@Test
	void runsOfEmptyBlocksDoNotBecomeRunsOfNewlines() {
		assertThat(textOf("eins\n\n\n\nzwei")).isEqualTo("eins\nzwei");
	}

	@Test
	void overrideCanRenderANodeDifferently() {
		// How the notification layer turns a user smart link into @DisplayName
		// instead of leaving an opaque id out of the teaser entirely.
		JsonNode document = converter.convert("Hallo {{user:507f1f77bcf86cd799439011}}");

		String text = LexicalJson.plainText(document, (type, node) -> "smartlink".equals(type)
				? "@Jonas"
				: null);

		assertThat(text).isEqualTo("Hallo @Jonas");
	}

	@Test
	void unknownNodeTypesStillContributeTheirText() throws Exception {
		// The app must be free to add node types without a server release.
		String json = """
				{"root":{"children":[{"children":[{"detail":0,"format":0,"mode":"normal",
				"style":"","text":"drin","type":"text","version":1}],"direction":null,
				"format":"","indent":0,"type":"someFutureBlock","version":1}],
				"direction":null,"format":"","indent":0,"type":"root","version":1}}""";

		assertThat(LexicalJson.plainText(mapper.readTree(json))).isEqualTo("drin");
	}

	// --- smart links ----------------------------------------------------------

	@Test
	void findsMentionedUsersInDocumentOrder() {
		JsonNode document = converter.convert(
				"{{user:aaa111}} und {{user:bbb222}} und nochmal {{user:aaa111}}");

		assertThat(LexicalJson.smartLinkTargets(document, "user"))
				.containsExactly("aaa111", "bbb222");
	}

	@Test
	void doesNotConfuseKinds() {
		JsonNode document = converter.convert("{{issue:HIN-1}} {{user:aaa111}}");

		assertThat(LexicalJson.smartLinkTargets(document, "user")).containsExactly("aaa111");
		assertThat(LexicalJson.smartLinkTargets(document, "issue")).containsExactly("HIN-1");
	}

	// --- defensive reading ----------------------------------------------------

	@Test
	void rejectsInputThatIsNotADocument() {
		assertThatThrownBy(() -> LexicalJson.parse(mapper, "nicht json"))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.richtext.invalid");
		assertThatThrownBy(() -> LexicalJson.parse(mapper, "{\"nope\":1}"))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> LexicalJson.parse(mapper, "{\"root\":{\"type\":\"paragraph\"}}"))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> LexicalJson.parse(mapper, "[]"))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void rejectsAnOversizedDocument() {
		String huge = "{\"root\":{\"type\":\"root\",\"children\":[]," + " ".repeat(LexicalJson.MAX_JSON_CHARS)
				+ "\"x\":1}}";

		assertThatThrownBy(() -> LexicalJson.parse(mapper, huge))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.richtext.tooLarge");
	}

	/** A document nested {@code levels} deep, with a word at the bottom. */
	private String nested(int levels) {
		StringBuilder open = new StringBuilder();
		StringBuilder close = new StringBuilder();
		for (int i = 0; i < levels; i++) {
			open.append("{\"type\":\"paragraph\",\"version\":1,\"children\":[");
			close.append("]}");
		}
		return "{\"root\":{\"type\":\"root\",\"version\":1,\"children\":[" + open
				+ "{\"type\":\"text\",\"version\":1,\"text\":\"tief\"}" + close + "]}}";
	}

	@Test
	void absurdNestingIsRejectedAsABadRequestRatherThanCrashing() {
		// Jackson's own stream constraints stop this before the walk sees it. What
		// matters is that it surfaces as a 400 and not as a leaked parser error.
		assertThatThrownBy(() -> LexicalJson.parse(mapper, nested(20_000)))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.richtext.invalid");
	}

	@Test
	void deepNestingWithinTheParserLimitTerminatesAndStopsAtTheDepthBound() throws Exception {
		// Deeper than MAX_DEPTH but inside Jackson's limit — which counts the object
		// and its children array as two levels each, so 300 nodes is ~600 of its
		// 1000. A recursive reader would still be at risk here; the explicit
		// worklist simply stops at the bound.
		JsonNode document = mapper.readTree(nested(300));

		assertThat(LexicalJson.plainText(document)).isEmpty();
		assertThat(LexicalJson.smartLinkTargets(document, "user")).isEmpty();
	}

	@Test
	void nestingWithinTheDepthBoundIsStillRead() throws Exception {
		JsonNode document = mapper.readTree(nested(10));

		assertThat(LexicalJson.plainText(document)).isEqualTo("tief");
	}

	@Test
	void truncatesAnAbsurdlyLongTextRatherThanIndexingAllOfIt() throws Exception {
		String long1 = "wort ".repeat(100_000);
		String json = mapper.writeValueAsString(converter.convert(long1));

		String text = LexicalJson.plainText(mapper.readTree(json));

		assertThat(text.length()).isLessThanOrEqualTo(LexicalJson.MAX_TEXT_CHARS);
	}

	// --- emptiness ------------------------------------------------------------

	@Test
	void recognizesAnEmptyDocument() throws Exception {
		assertThat(LexicalJson.isBlank(mapper.readTree(LexicalJson.EMPTY_DOC))).isTrue();
		assertThat(LexicalJson.isBlank(converter.convert(""))).isTrue();
		assertThat(LexicalJson.isBlank(converter.convert("   "))).isTrue();
	}

	@Test
	void aDocumentThatOnlyHoldsAnImageIsNotBlank() {
		// No text, but plainly content — treating it as empty would drop it.
		assertThat(LexicalJson.isBlank(converter.convert("![](https://example.org/f.jpg)"))).isFalse();
	}

	@Test
	void aDocumentThatOnlyHoldsARuleIsNotBlank() {
		assertThat(LexicalJson.isBlank(converter.convert("---"))).isFalse();
	}

	@Test
	void theCanonicalEmptyDocumentParses() {
		assertThat(LexicalJson.parse(mapper, LexicalJson.EMPTY_DOC).path("root").path("children"))
				.hasSize(1);
	}
}
