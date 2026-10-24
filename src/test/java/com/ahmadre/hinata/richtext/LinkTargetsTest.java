package com.ahmadre.hinata.richtext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a stored link may lead (HIN-176), on every way in: a document from the app, markdown from
 * a tool or a mail, and texts stored before the check.
 */
class LinkTargetsTest {

	private final RichTextService richText = new RichTextService();

	@ParameterizedTest
	@ValueSource(strings = {"https://example.org/a", "HTTP://example.org", "mailto:kim@example.org",
			"/issues/ACME-1", "/knowledge/a1?x=1", "/"})
	void theseMayBeLinked(String url) {
		assertThat(LinkTargets.isSafe(url)).as(url).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"javascript:alert(1)", "JaVaScRiPt:alert(1)", "java\tscript:alert(1)", " javascript:x",
			"data:text/html;base64,PHNjcmlwdD4=", "vbscript:x", "//evil.example", "/\\evil.example", "\\\\evil.example",
			"file:///etc/passwd", "issues/ACME-1", "", "#top"})
	void theseLoseTheirLink(String url) {
		assertThat(LinkTargets.isSafe(url)).as(url).isFalse();
	}

	@Test
	void markdownKeepsTheWordsAndDropsTheLink() {
		RichText text = richText.fromMarkdown("Klick [hier](javascript:alert(1)) oder [dort](https://example.org).");

		assertThat(text.doc()).doesNotContain("javascript").contains("https://example.org");
		assertThat(text.text()).contains("Klick hier oder dort.");
	}

	@Test
	void aDocumentFromTheAppIsCleanedToo() {
		String doc = """
				{"root":{"children":[{"children":[{"children":[{"detail":0,"format":0,"mode":"normal","style":"",\
				"text":"hier","type":"text","version":1}],"direction":null,"format":"","indent":0,"type":"link",\
				"version":1,"rel":null,"target":null,"title":null,"url":"//evil.example"}],"direction":null,"format":"",\
				"indent":0,"type":"paragraph","version":1,"textFormat":0,"textStyle":""}],"direction":null,"format":"",\
				"indent":0,"type":"root","version":1}}""";

		RichText text = richText.fromLexical(doc);

		assertThat(text.doc()).doesNotContain("evil.example", "\"link\"").contains("\"text\":\"hier\"");
		assertThat(richText.withSafeLinks(doc)).isEqualTo(text.doc());
		assertThat(richText.withSafeLinks(text.doc())).as("nothing left to clean").isNull();
	}
}
