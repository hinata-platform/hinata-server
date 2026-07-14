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
	void returnsEmptyForBlankInput() {
		assertThat(HtmlToMarkdown.convert(null)).isEmpty();
		assertThat(HtmlToMarkdown.convert("   ")).isEmpty();
	}
}
