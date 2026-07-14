package com.ahmadre.hinata.mailingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlToMarkdownTest {

	@Test
	void stripsStyleBlocksInsteadOfLeakingCssAsText() {
		String html = """
				<html><head><style>#Eh-f{display:inline-block !important;} table{border:0;}</style></head>
				<body><p>Ihre Anfrage wurde <b>beantwortet</b>.</p></body></html>""";

		String md = HtmlToMarkdown.convert(html);

		assertThat(md).contains("Ihre Anfrage wurde **beantwortet**.");
		assertThat(md).doesNotContain("display:inline-block");
		assertThat(md).doesNotContain("#Eh-f");
	}

	@Test
	void dropsScriptAndTrackingMetadata() {
		String html = "<html><head><script>track('open')</script></head>"
				+ "<body>Hallo Welt</body></html>";

		String md = HtmlToMarkdown.convert(html);

		assertThat(md).isEqualTo("Hallo Welt");
		assertThat(md).doesNotContain("track");
	}

	@Test
	void convertsLinksAndListsToMarkdown() {
		String html = "<p>See <a href=\"https://example.com\">the site</a>:</p>"
				+ "<ul><li>one</li><li>two</li></ul>";

		String md = HtmlToMarkdown.convert(html);

		assertThat(md).contains("[the site](https://example.com)");
		assertThat(md).contains("one");
		assertThat(md).contains("two");
	}

	@Test
	void decodesHtmlEntities() {
		String md = HtmlToMarkdown.convert("<p>Rock&nbsp;&amp;&nbsp;Roll &lt;3</p>");

		assertThat(md).contains("Rock");
		assertThat(md).contains("&");
		assertThat(md).doesNotContain("&nbsp;");
		assertThat(md).doesNotContain("&amp;");
	}

	@Test
	void flattensLayoutTablesInsteadOfProducingDashWalls() {
		// Newsletter-style nested spacing tables with mostly empty cells — the shape
		// that turned VM-11's description into a wall of "---" lines.
		String html = """
				<table role="presentation" width="100%">
				  <tr><td>&nbsp;</td><td>
				    <table><tr><td><h1>Ihre Anfrage wurde beantwortet</h1></td></tr>
				    <tr><td>Hallo, bitte um Rückmeldung.</td></tr>
				    <tr><td>&nbsp;</td></tr>
				    <tr><td>&nbsp;</td></tr></table>
				  </td><td>&nbsp;</td></tr>
				</table>""";

		String md = HtmlToMarkdown.convert(html);

		assertThat(md).contains("Ihre Anfrage wurde beantwortet");
		assertThat(md).contains("Hallo, bitte um Rückmeldung.");
		// No Markdown table delimiter / horizontal-rule noise survives.
		assertThat(md).doesNotContain("---");
		long ruleLines = md.lines()
				.map(String::strip)
				.filter(l -> l.length() >= 3)
				.filter(l -> l.chars().allMatch(c -> c == '-' || c == '|' || c == ':' || c == ' '))
				.count();
		assertThat(ruleLines).isZero();
	}

	@Test
	void stripsElementIdAttributeNoise() {
		// Newsletter markup ids like id="Eio" would surface as "{#Eio}" tokens.
		String md = HtmlToMarkdown.convert("<p id=\"Eio\">Kundennummer: 12345</p>");

		assertThat(md).contains("Kundennummer: 12345");
		assertThat(md).doesNotContain("{#");
		assertThat(md).doesNotContain("Eio");
	}

	@Test
	void dropsTrackingPixelsAndSpacerImages() {
		String html = "<p>Hallo</p>"
				+ "<img src=\"https://track.example.com/o?em=dm9yc3RhbmQ=\" width=\"1\" height=\"1\">"
				+ "<img src=\"https://track.example.com/spacer.gif\" style=\"display:none\">";

		String md = HtmlToMarkdown.convert(html);

		assertThat(md).contains("Hallo");
		assertThat(md).doesNotContain("track.example.com");
		assertThat(md).doesNotContain("image");
	}

	@Test
	void dropsEmptyTrackingAnchorsInsteadOfBareLinks() {
		String html = "<a href=\"https://track.example.com/o?em=dm9yc3RhbmQ=\"></a>"
				+ "<p>Kundennummer: 42</p>";

		String md = HtmlToMarkdown.convert(html);

		assertThat(md).contains("Kundennummer: 42");
		assertThat(md).doesNotContain("track.example.com");
		assertThat(md).doesNotContain("[]("); // no empty-label link
	}

	@Test
	void keepsGenuineContentImages() {
		String html = "<p>Logo:</p>"
				+ "<img src=\"https://cdn.example.com/logo.png\" alt=\"Firmenlogo\" width=\"320\">";

		String md = HtmlToMarkdown.convert(html);

		assertThat(md).contains("https://cdn.example.com/logo.png");
		assertThat(md).contains("![Firmenlogo]");
	}

	@Test
	void keepsLinkedContentImage() {
		String html = "<a href=\"https://example.com\">"
				+ "<img src=\"https://cdn.example.com/banner.png\" alt=\"Banner\" width=\"600\"></a>";

		String md = HtmlToMarkdown.convert(html);

		assertThat(md).contains("https://cdn.example.com/banner.png");
		assertThat(md).contains("https://example.com");
	}

	@Test
	void returnsEmptyForBlankInput() {
		assertThat(HtmlToMarkdown.convert(null)).isEmpty();
		assertThat(HtmlToMarkdown.convert("   ")).isEmpty();
	}
}
