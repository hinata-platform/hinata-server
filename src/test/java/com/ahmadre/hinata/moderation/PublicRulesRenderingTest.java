package com.ahmadre.hinata.moderation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The house rules on the public page (HIN-175): an instance's own Markdown may hold anything, and
 * the page shows only text, headings, lists and links to {@code http}, {@code https} and
 * {@code mailto}. No raw HTML, no script link, no image from a foreign server.
 */
class PublicRulesRenderingTest {

	@Test
	void rawHtmlIsShownAsTextNeverRun() {
		String html = PublicModerationPages.markdownToHtml("Hallo <script>alert(1)</script>\n\n<iframe src=\"x\"></iframe>");

		assertThat(html).doesNotContain("<script", "<iframe").contains("&lt;script&gt;");
	}

	@Test
	void onlyWebAndMailLinksStayLinks() {
		String html = PublicModerationPages.markdownToHtml(
				"[a](https://ok.example) [b](mailto:m@ok.example) [c](javascript:alert(1)) [d](//evil.example) [e](/issues/X)");

		assertThat(html).contains("href=\"https://ok.example\"", "href=\"mailto:m@ok.example\"")
				.doesNotContain("javascript:", "href=\"//evil.example\"", "href=\"/issues/X\"");
	}

	@Test
	void anImageIsLeftAsItsText() {
		String html = PublicModerationPages.markdownToHtml("![Logo des Vereins](https://tracker.example/pixel.png)");

		assertThat(html).doesNotContain("<img", "tracker.example").contains("Logo des Vereins");
	}
}
