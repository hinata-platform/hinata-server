package com.ahmadre.hinata.richtext;

import com.ahmadre.hinata.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

	/** An image's caption is a document of its own: its links and its words count as the text's. */
	@Test
	void aCaptionIsCheckedLikeTheText() {
		String doc = """
				{"root":{"children":[{"children":[{"type":"image","version":1,"src":"https://example.org/a.png",\
				"altText":"","showCaption":true,"caption":{"editorState":{"root":{"children":[{"children":[\
				{"children":[{"detail":0,"format":0,"mode":"normal","style":"","text":"Unterschrift","type":"text",\
				"version":1}],"direction":null,"format":"","indent":0,"type":"link","version":1,"rel":null,\
				"target":null,"title":null,"url":"javascript:alert(1)"}],"direction":null,"format":"","indent":0,\
				"type":"paragraph","version":1}],"direction":null,"format":"","indent":0,"type":"root",\
				"version":1}}}}],"direction":null,"format":"","indent":0,"type":"paragraph","version":1}],\
				"direction":null,"format":"","indent":0,"type":"root","version":1}}""";

		RichText text = richText.fromLexical(doc);

		assertThat(text.doc()).doesNotContain("javascript");
		assertThat(text.text()).as("judged by the text check, found by the search").contains("Unterschrift");
	}

	/** A text is judged whole or refused: nothing past the bound slips by unread. */
	@Test
	void aTextLongerThanTheBoundIsRefusedNotCut() {
		String words = "Wort ".repeat(LexicalJson.MAX_TEXT_CHARS / 5 + 10);
		String doc = "{\"root\":{\"children\":[{\"children\":[{\"detail\":0,\"format\":0,\"mode\":\"normal\","
				+ "\"style\":\"\",\"text\":\"" + words + "\",\"type\":\"text\",\"version\":1}],\"direction\":null,"
				+ "\"format\":\"\",\"indent\":0,\"type\":\"paragraph\",\"version\":1}],\"direction\":null,"
				+ "\"format\":\"\",\"indent\":0,\"type\":\"root\",\"version\":1}}";

		assertThatThrownBy(() -> richText.fromLexical(doc)).isInstanceOfSatisfying(ApiException.class,
				refused -> assertThat(refused.getMessageKey()).isEqualTo("error.richtext.tooLarge"));
	}

	@Test
	void aLinkInsideAMailsColumnsIsCheckedToo() {
		var words = LayoutDocs.NODES.array();
		words.add(LayoutDocs.NODES.text("klick", 0));
		var content = LayoutDocs.NODES.array();
		content.add(LayoutDocs.NODES.link("javascript:alert(1)", words));
		var document = LayoutDocs.read(LayoutDocs.doc(LayoutDocs.columns("1fr 1fr",
				LayoutDocs.item(LayoutDocs.NODES.paragraph(content, 0)), LayoutDocs.item(LayoutDocs.paragraph("rechts")))));

		assertThat(LinkTargets.unwrapUnsafe(document)).isTrue();
		assertThat(LayoutDocs.nodes(LayoutDocs.write(document), "link")).isEmpty();
		assertThat(LexicalJson.plainText(document)).isEqualTo("klick\nrechts");
	}
}
