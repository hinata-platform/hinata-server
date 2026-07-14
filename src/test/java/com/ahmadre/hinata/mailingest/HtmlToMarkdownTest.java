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
	void returnsEmptyForBlankInput() {
		assertThat(HtmlToMarkdown.convert(null)).isEmpty();
		assertThat(HtmlToMarkdown.convert("   ")).isEmpty();
	}
}
