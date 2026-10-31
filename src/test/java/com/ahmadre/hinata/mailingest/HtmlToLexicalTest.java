package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.richtext.LexicalJson;
import com.ahmadre.hinata.richtext.LexicalNodes;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.richtext.RichTextService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An HTML mail becomes a document with its layout (HIN-181): columns, block styles, text styles,
 * image sizes; and without what a reader never sees.
 */
class HtmlToLexicalTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private final HtmlToLexical converter = new HtmlToLexical(MAPPER);

	private static String fixture(String name) throws Exception {
		return Files.readString(Path.of("src/test/resources/mailingest/" + name));
	}

	private JsonNode document(String html) {
		return new LexicalNodes(MAPPER).document(converter.convert(html));
	}

	private static List<JsonNode> nodes(JsonNode node, String type) {
		List<JsonNode> found = new ArrayList<>();
		collect(node, type, found);
		return found;
	}

	private static void collect(JsonNode node, String type, List<JsonNode> out) {
		if (type.equals(node.path("type").asText())) out.add(node);
		node.forEach(child -> collect(child, type, out));
	}

	private static String style(JsonNode node) {
		return LexicalNodes.blockStyle(node);
	}

	private static List<String> imageNames(JsonNode document) {
		return nodes(document, "image").stream()
				.map(image -> image.path("src").asText().replaceAll(".*/", "")).toList();
	}

	// --- the two fixtures ----------------------------------------------------

	@Test
	void tableMail_keepsTheCentredCanvasWithItsWidth() throws Exception {
		JsonNode document = document(fixture("vodafone-style-table.html"));

		List<JsonNode> containers = nodes(document, "layout-container");
		assertThat(containers).anySatisfy(container -> assertThat(style(container))
				.contains("max-width:600px", "margin-left:auto", "margin-right:auto", "background-color:#ffffff"));
	}

	@Test
	void tableMail_turnsStepRowsIntoColumnsAsWideAsTheCells() throws Exception {
		JsonNode document = document(fixture("vodafone-style-table.html"));

		List<JsonNode> steps = nodes(document, "layout-container").stream()
				.filter(container -> container.path("templateColumns").asText().equals("280px 1fr")).toList();
		assertThat(steps).hasSize(2);
		JsonNode image = nodes(steps.getFirst().path("children").get(0), "image").getFirst();
		assertThat(image.path("width").asInt()).isEqualTo(280);
		assertThat(image.path("height").asInt()).isEqualTo(180);
		assertThat(LexicalJson.plainText(MAPPER.createObjectNode().set("root", steps.getFirst())))
				.contains("Verbinde Dein Gerät mit dem Internet.");
	}

	@Test
	void tableMail_dropsPreheaderMobileCopyAndTrackingPixel() throws Exception {
		JsonNode document = document(fixture("vodafone-style-table.html"));

		String text = LexicalJson.plainText(document);
		assertThat(text).doesNotContain("so aktivierst Du sie in fünf Schritten");
		assertThat(imageNames(document)).contains("header_desk.jpg").doesNotContain("header_mob.jpg")
				.noneMatch(name -> name.startsWith("open.gif"));
	}

	@Test
	void tableMail_keepsTheRedCentredHeadlineAndTheBoldSubheading() throws Exception {
		JsonNode document = document(fixture("vodafone-style-table.html"));

		JsonNode headline = nodes(document, "heading").getFirst();
		assertThat(headline.path("tag").asText()).isEqualTo("h1");
		assertThat(headline.path("format").asText()).isEqualTo("center");
		JsonNode headlineText = headline.path("children").get(0);
		assertThat(headlineText.path("style").asText()).contains("color:#e60000", "font-size:28px");
		JsonNode subheading = nodes(document, "text").stream()
				.filter(text -> text.path("text").asText().equals("So aktivierst Du Deine eSIM")).findFirst().orElseThrow();
		assertThat(subheading.path("format").asInt() & LexicalNodes.BOLD).isEqualTo(LexicalNodes.BOLD);
		assertThat(subheading.path("style").asText()).contains("font-size:24px");
	}

	@Test
	void tableMail_makesTheCtaAButtonAndKeepsTheCodeArea() throws Exception {
		JsonNode document = document(fixture("vodafone-style-table.html"));

		JsonNode button = nodes(document, "paragraph").stream()
				.filter(p -> style(p).contains("background-color:#e60000")).findFirst().orElseThrow();
		assertThat(button.path("format").asText()).isEqualTo("center");
		assertThat(style(button)).contains("border-radius:6px", "padding:12px 28px", "width:fit-content",
				"margin-left:auto", "margin-right:auto");
		assertThat(button.path("children").get(0).path("type").asText()).isEqualTo("link");
		assertThat(button.path("children").get(0).path("url").asText()).isEqualTo("https://example.com/hilfe?utm_source=mail");
		JsonNode code = nodes(document, "paragraph").stream()
				.filter(p -> style(p).contains("background-color:#f2f2f2")).findFirst().orElseThrow();
		assertThat(style(code)).contains("border:1px solid #cccccc", "border-radius:8px");
		assertThat(code.path("children").get(0).path("style").asText()).contains("font-family:'courier new',courier,monospace");
	}

	@Test
	void tableMail_keepsTheFooterIconsSideBySideAndTheLegalLinks() throws Exception {
		JsonNode document = document(fixture("vodafone-style-table.html"));

		JsonNode icons = nodes(document, "layout-container").stream()
				.filter(c -> c.path("templateColumns").asText().equals("48px 48px 48px")).findFirst().orElseThrow();
		assertThat(style(icons)).contains("max-width:144px", "margin-left:auto");
		assertThat(nodes(icons, "link")).hasSize(3);
		JsonNode legal = nodes(document, "paragraph").stream()
				.filter(p -> LexicalJson.plainText(MAPPER.createObjectNode().set("root", p)).startsWith("Kontakt | Impressum"))
				.findFirst().orElseThrow();
		assertThat(style(legal)).contains("background-color:#333333");
	}

	@Test
	void mjmlForward_keepsTheForwardHeaderAsTextAboveTheMail() throws Exception {
		JsonNode document = document(fixture("vodafone-mjml-forward.html"));

		JsonNode first = document.path("root").path("children").get(0);
		assertThat(LexicalJson.plainText(MAPPER.createObjectNode().set("root", first)))
				.contains("Ursprüngliche Nachricht");
		assertThat(LexicalJson.plainText(document)).contains("An: kunde@example.org", "Hallo Max");
	}

	@Test
	void mjmlForward_turnsMjColumnsIntoColumnsByTheirClassWidths() throws Exception {
		JsonNode document = document(fixture("vodafone-mjml-forward.html"));

		List<String> templates = nodes(document, "layout-container").stream()
				.map(c -> c.path("templateColumns").asText()).toList();
		assertThat(templates).contains("51% 49%", "50% 50%", "33% 67%", "25% 25% 25% 25%");
		assertThat(templates.stream().filter("51% 49%"::equals)).hasSize(5);
	}

	@Test
	void mjmlForward_keepsOneHeaderImageOfEachPairAndDropsTheScreenReaderHeadline() throws Exception {
		JsonNode document = document(fixture("vodafone-mjml-forward.html"));

		List<String> images = imageNames(document);
		assertThat(images).noneMatch(name -> name.contains("_mob")).anyMatch(name -> name.contains("_desk"));
		assertThat(nodes(document, "heading")).noneMatch(h -> h.path("tag").asText().equals("h1"));
	}

	@Test
	void mjmlForward_keepsALoneNarrowColumnsShareAsItsWidth() throws Exception {
		JsonNode icons = onlyContainer(document(fixture("vodafone-mjml-forward.html")), "25% 25% 25% 25%");

		assertThat(style(icons)).contains("width:50%", "margin-left:auto").doesNotContain("max-width:50%");
		assertThat(style(icons)).as("the dark band stays as wide as its section").doesNotContain("background-color");
	}

	@Test
	void mjmlForward_keepsTheDarkFooterAndTheRedButton() throws Exception {
		JsonNode document = document(fixture("vodafone-mjml-forward.html"));

		assertThat(nodes(document, "layout-container")).anySatisfy(c -> assertThat(style(c))
				.contains("background-color:#333333", "max-width:640px"));
		JsonNode button = nodes(document, "paragraph").stream()
				.filter(p -> style(p).contains("background-color:#e60000")).findFirst().orElseThrow();
		assertThat(style(button)).contains("padding:10px 25px", "width:fit-content").doesNotContain("padding:26px")
				.doesNotContain("margin-left:auto");
		assertThat(button.path("format").asText()).as("placed as its cell's surroundings, left").isEqualTo("left");
		assertThat(LexicalJson.plainText(MAPPER.createObjectNode().set("root", button))).isEqualTo("Hilfe & FAQ");
	}

	@Test
	void bothFixtures_areStorableDocuments() throws Exception {
		RichTextService service = new RichTextService();
		for (String name : List.of("vodafone-style-table.html", "vodafone-mjml-forward.html", "transactional-template.html")) {
			RichText text = service.fromDocument(document(fixture(name)), com.ahmadre.hinata.richtext.ExternalImages.KEEP);
			assertThat(text.doc()).as(name).isNotNull();
			assertThat(text.doc().length()).as(name).isLessThan(LexicalJson.MAX_JSON_CHARS);
		}
	}

	// --- rules one by one -----------------------------------------------------

	@Test
	void classRulesApplyButMediaQueriesDoNot() {
		JsonNode document = document("<style>.red{color:#e60000} @media (max-width:600px){.red{color:#00ff00}}"
				+ "</style><p class=\"red\">Rot</p>");

		assertThat(nodes(document, "text").getFirst().path("style").asText()).isEqualTo("color:#e60000");
	}

	@Test
	void aRealDataTableBecomesATable() {
		JsonNode document = document("<table border=\"1\"><tr><th>Tarif</th><th>Preis</th></tr>"
				+ "<tr><td>S</td><td>5 €</td></tr></table>");

		JsonNode table = nodes(document, "table").getFirst();
		assertThat(nodes(table, "tablerow")).hasSize(2);
		assertThat(nodes(table, "tablecell").getFirst().path("headerState").asInt()).isEqualTo(1);
		assertThat(nodes(document, "layout-container")).isEmpty();
	}

	@Test
	void unsafeCssAndHiddenTextNeverArrive() {
		JsonNode document = document("<div style=\"position:fixed;background-image:url(https://t.example/p.gif);"
				+ "background-color:#fafafa;padding:8px\">Sichtbar<span style=\"display:none\">Versteckt</span>"
				+ "<span style=\"mso-hide:all\">Outlook</span></div>");

		assertThat(LexicalJson.plainText(document)).isEqualTo("Sichtbar");
		assertThat(style(nodes(document, "paragraph").getFirst())).isEqualTo("background-color:#fafafa;padding:8px");
	}

	@Test
	void nestedSingleCellWrappersCollapseIntoOneBlock() {
		JsonNode document = document("<table><tr><td style=\"padding:10px\"><table><tr><td style=\"padding:5px;"
				+ "background-color:#eeeeee\"><p>Text</p></td></tr></table></td></tr></table>");

		assertThat(nodes(document, "layout-container")).isEmpty();
		JsonNode paragraph = nodes(document, "paragraph").getFirst();
		assertThat(style(paragraph)).contains("background-color:#eeeeee", "padding:5px", "margin-top:10px");
	}

	// --- columns that stack on a phone -----------------------------------------

	private static final String MJML_ROW = "<div style=\"margin:0 auto;max-width:640px\"><table><tr><td>"
			+ "<div class=\"mj-column-per-50\" style=\"display:inline-block\">Links</div>"
			+ "<div class=\"mj-column-per-50\" style=\"display:inline-block\">Rechts</div></td></tr></table></div>";

	private JsonNode onlyContainer(JsonNode document, String template) {
		return nodes(document, "layout-container").stream()
				.filter(c -> c.path("templateColumns").asText().equals(template)).findFirst().orElseThrow();
	}

	@Test
	void mjmlColumnsStackBelowMjmlsDefaultBreakpoint() {
		assertThat(style(onlyContainer(document(MJML_ROW), "50% 50%"))).contains("--stack-below:480px");
	}

	@Test
	void mjmlColumnsStackBelowTheBreakpointTheMailNames() {
		String html = "<style>@media only screen and (min-width:520px) { .mj-column-per-50 { width:50% !important } }"
				+ "</style>" + MJML_ROW;

		assertThat(style(onlyContainer(document(html), "50% 50%"))).contains("--stack-below:520px");
	}

	@Test
	void mjmlColumnsInAGroupStaySideBySide() {
		String grouped = "<div style=\"margin:0 auto;max-width:640px\"><table><tr><td>"
				+ "<div class=\"mj-column-per-100 mj-group-full-width\"><div class=\"mj-column-per-50\""
				+ " style=\"display:inline-block\">Links</div><div class=\"mj-column-per-50\" style=\"display:"
				+ "inline-block\">Rechts</div></div></td></tr></table></div>";

		assertThat(style(onlyContainer(document(grouped), "50% 50%"))).doesNotContain("--stack-below");
	}

	@Test
	void theRealMjmlMailsStepRowsStack() throws Exception {
		JsonNode document = document(fixture("vodafone-mjml-forward.html"));

		List<JsonNode> steps = nodes(document, "layout-container").stream()
				.filter(c -> c.path("templateColumns").asText().equals("51% 49%")).toList();
		assertThat(steps).hasSize(5);
		assertThat(steps).allSatisfy(c -> assertThat(style(c)).contains("--stack-below:480px"));
	}

	@Test
	void tableRowsOfContentStackButIconRowsDoNot() throws Exception {
		JsonNode document = document(fixture("vodafone-style-table.html"));

		assertThat(style(onlyContainer(document, "280px 1fr"))).contains("--stack-below:480px");
		assertThat(style(onlyContainer(document, "48px 48px 48px"))).doesNotContain("--stack-below");
	}

	@Test
	void contentColumnsCountWhatTakesTwoFifthsOfTheRow() {
		assertThat(HtmlToLexical.contentColumns("280px 1fr", 600)).isEqualTo(2);
		assertThat(HtmlToLexical.contentColumns("50% 50%", 600)).isEqualTo(2);
		assertThat(HtmlToLexical.contentColumns("1fr 1fr 1fr", 600)).isZero();
		assertThat(HtmlToLexical.contentColumns("48px 48px 48px", 600)).isZero();
		assertThat(HtmlToLexical.contentColumns("120px 1fr", 600)).isEqualTo(1);
	}

	// --- a hostile mail ----------------------------------------------------------

	@Test
	void aStyleSheetRepeatingOneRuleCannotGrowAnElementsStyle() {
		String body = "color:#e60000;" + "x".repeat(10_000);
		String hostile = "<style>" + "p,".repeat(499) + "p{" + body + "}</style>" + "<p>Text</p>".repeat(5);

		Map<org.jsoup.nodes.Element, String> sheet = HtmlToLexical.styleSheet(org.jsoup.Jsoup.parse(hostile));

		assertThat(sheet).hasSize(5);
		assertThat(sheet.values()).allSatisfy(css -> assertThat(css.length())
				.isLessThanOrEqualTo(8 * com.ahmadre.hinata.richtext.CssStyles.MAX_CHARS));
	}

	@Test
	void aHugeStyleSheetIsReadOnlyToItsBound() {
		String hostile = "<style>" + ".a{color:red}".repeat(20_000) + "</style><p class=\"a\">Text</p>";

		assertThat(HtmlToLexical.styleSheet(org.jsoup.Jsoup.parse(hostile)).values())
				.allSatisfy(css -> assertThat(css.length()).isLessThanOrEqualTo(8 * com.ahmadre.hinata.richtext.CssStyles.MAX_CHARS));
		assertThat(LexicalJson.plainText(document(hostile))).isEqualTo("Text");
	}

	@Test
	void unclosedCommentsInAStyleSheetCostLinearTime() {
		String hostile = "<style>" + "/* x ".repeat(12_000) + "</style><p>Text</p>";

		org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofMillis(500),
				() -> HtmlToLexical.styleSheet(org.jsoup.Jsoup.parse(hostile)));
	}

	@Test
	void anUnclosedMediaQueryCostsLinearTime() {
		String hostile = "<style>" + "@media ".repeat(150_000) + "</style><p>Text</p>";

		org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
				() -> assertThat(LexicalJson.plainText(document(hostile))).isEqualTo("Text"));
	}

	@Test
	void commentsAreStrippedAndTheBreakpointStillFound() {
		String html = "<style>/* .a{color:blue} */ .a{color:red} /* @media (min-width:999px) {} */"
				+ "@media only screen and (min-width:520px) { .x {} }</style><p class=\"a\">Text</p>" + MJML_ROW;

		JsonNode document = document(html);
		assertThat(nodes(document, "text").getFirst().path("style").asText()).isEqualTo("color:red");
		assertThat(style(onlyContainer(document, "50% 50%"))).contains("--stack-below:520px");
	}

	@Test
	void anEmptyBodyIsNoDocument() {
		assertThatThrownBy(() -> converter.convert("<html><head><style>p{}</style></head><body> </body></html>"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void deepNestingKeepsItsTextWithinTheBounds() {
		String html = "<div>".repeat(400) + "tief" + "</div>".repeat(400);

		ArrayNode blocks = converter.convert(html);

		assertThat(LexicalJson.plainText(new LexicalNodes(MAPPER).document(blocks))).isEqualTo("tief");
	}
}
