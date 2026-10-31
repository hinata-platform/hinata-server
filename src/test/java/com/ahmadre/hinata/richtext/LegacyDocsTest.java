package com.ahmadre.hinata.richtext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.ahmadre.hinata.richtext.LayoutDocs.NODES;
import static com.ahmadre.hinata.richtext.LayoutDocs.columns;
import static com.ahmadre.hinata.richtext.LayoutDocs.doc;
import static com.ahmadre.hinata.richtext.LayoutDocs.imageParagraph;
import static com.ahmadre.hinata.richtext.LayoutDocs.item;
import static com.ahmadre.hinata.richtext.LayoutDocs.nodes;
import static com.ahmadre.hinata.richtext.LayoutDocs.paragraph;
import static com.ahmadre.hinata.richtext.LayoutDocs.read;
import static org.assertj.core.api.Assertions.assertThat;

/** What an app published before HIN-181 is given, and what it may give back. */
class LegacyDocsTest {

	private static final String LABEL = "Externes Bild";
	private static final String MEDIA = "/api/v1/media/0b9f3c4e-5d6a-4b7c-8d9e-0f1a2b3c4d5e";

	@Test
	void project_returnsTheSameDocumentWhenAnOldAppCanReadItAll() {
		String plain = doc(paragraph("Hallo"), imageParagraph(MEDIA));

		assertThat(LegacyDocs.project(plain, () -> LABEL)).isSameAs(plain);
	}

	@Test
	void project_flattensColumnsIntoTheirBlocksInReadingOrder() {
		ObjectNode styled = paragraph("Schritt 1");
		LexicalNodes.setBlockStyle(styled, "background-color:#f4f4f4");
		String stored = doc(paragraph("Kopf"),
				columns("1fr 1fr", item(paragraph("links")), item(styled, columns("1fr", item(paragraph("innen"))))),
				paragraph("Fuß"));

		String legacy = LegacyDocs.project(stored, () -> LABEL);

		assertThat(nodes(legacy, "layout-container")).isEmpty();
		assertThat(nodes(legacy, "layout-item")).isEmpty();
		JsonNode blocks = read(legacy).path("root").path("children");
		assertThat(blocks).extracting(b -> b.path("children").get(0).path("text").asText())
				.containsExactly("Kopf", "links", "Schritt 1", "innen", "Fuß");
		assertThat(blocks.get(2).path("$").path("style").asText()).as("NodeState stays")
				.isEqualTo("background-color:#f4f4f4");
	}

	@Test
	void project_turnsAnExternalImageIntoTheLinkHin177MadeOfIt() {
		String stored = doc(imageParagraph("https://mail.example.com/logo.png"), imageParagraph(MEDIA));

		String legacy = LegacyDocs.project(stored, () -> LABEL);

		List<JsonNode> links = nodes(legacy, "link");
		assertThat(links).hasSize(1);
		assertThat(links.getFirst().path("url").asText()).isEqualTo("https://mail.example.com/logo.png");
		assertThat(links.getFirst().path("children").get(0).path("text").asText()).isEqualTo(LABEL);
		assertThat(nodes(legacy, "image")).extracting(i -> i.path("src").asText()).containsExactly(MEDIA);
	}

	@Test
	void project_givesAnImageInsideALinkItsLabelOnly() {
		ArrayNode inLink = NODES.array();
		inLink.add(NODES.image("https://mail.example.com/app.png", "App", 32, 32, 32));
		ArrayNode content = NODES.array();
		content.add(NODES.link("https://example.com/app", inLink));
		String stored = doc(NODES.paragraph(content, 0));

		String legacy = LegacyDocs.project(stored, () -> LABEL);

		List<JsonNode> links = nodes(legacy, "link");
		assertThat(links).hasSize(1);
		assertThat(links.getFirst().path("url").asText()).isEqualTo("https://example.com/app");
		assertThat(links.getFirst().path("children").get(0).path("text").asText()).isEqualTo(LABEL);
	}

	@Test
	void project_wrapsAnImageStandingAsABlockInAParagraph() {
		ArrayNode blocks = NODES.array();
		blocks.add(NODES.image("https://mail.example.com/hero.jpg", "", 600, 200, 600));
		String stored = LayoutDocs.write(NODES.document(blocks));

		JsonNode first = read(LegacyDocs.project(stored, () -> LABEL)).path("root").path("children").get(0);

		assertThat(first.path("type").asText()).isEqualTo("paragraph");
		assertThat(first.path("children").get(0).path("type").asText()).isEqualTo("link");
	}

	@Test
	void memo_givesTheFullDocumentOnlyWhenItDiffers() {
		String plain = doc(paragraph("Hallo"));
		String layout = doc(columns("1fr", item(paragraph("Hallo"))));
		LegacyDocs.Memo memo = new LegacyDocs.Memo();

		assertThat(memo.legacy(plain)).isSameAs(plain);
		assertThat(memo.layout(plain)).isNull();
		assertThat(memo.layout(layout)).isSameAs(layout);
		assertThat(nodes(memo.legacy(layout), "layout-container")).isEmpty();
		assertThat(memo.legacy(null)).isNull();
	}

	@Test
	void isRoundTripOf_recognisesTheProjectionComingBack() {
		String stored = doc(columns("1fr 1fr", item(imageParagraph("https://mail.example.com/a.png")),
				item(paragraph("Text"))));
		String legacy = LegacyDocs.project(stored, () -> LABEL);

		assertThat(LegacyDocs.isRoundTripOf(legacy, stored, LABEL)).isTrue();
	}

	@Test
	void isRoundTripOf_takesTheSameWordsAfterAnOldEditorNormalisedThem() {
		String stored = doc(columns("1fr", item(paragraph("Text"))));
		ObjectNode normalised = (ObjectNode) read(LegacyDocs.project(stored, () -> LABEL));
		((ObjectNode) normalised.path("root").path("children").get(0)).put("textFormat", 0).put("format", "left");

		assertThat(LegacyDocs.isRoundTripOf(LayoutDocs.write(normalised), stored, LABEL)).isTrue();
	}

	@Test
	void isRoundTripOf_takesARealEditAsAnEdit() {
		String stored = doc(columns("1fr", item(paragraph("Text"))));

		assertThat(LegacyDocs.isRoundTripOf(doc(paragraph("Text, geändert")), stored, LABEL)).isFalse();
	}

	@Test
	void isRoundTripOf_neverSwallowsADocumentWithLayout() {
		String stored = doc(columns("1fr", item(paragraph("Text"))));

		assertThat(LegacyDocs.isRoundTripOf(stored, stored, LABEL)).as("a new app's save").isFalse();
	}

	@Test
	void isRoundTripOf_isNeverTrueForATextWithoutAProjection() {
		String stored = doc(paragraph("Text"));

		assertThat(LegacyDocs.isRoundTripOf(stored, stored, LABEL)).isFalse();
	}

	@Test
	void project_findsAnExternalImageHoweverItsAddressIsSpelled() {
		String upper = doc(imageParagraph("HTTPS://tracker.example/pixel.png"));
		String escaped = upper.replace("HTTPS://", "\\u0068ttps://");

		for (String stored : new String[] { upper, escaped }) {
			String legacy = LegacyDocs.project(stored, () -> LABEL);
			assertThat(nodes(legacy, "image")).as(stored).isEmpty();
			assertThat(nodes(legacy, "link")).as(stored).hasSize(1);
		}
	}

	@Test
	void mayNeedProjection_looksOnlyForAnExternalImageAddressOrALayout() {
		String linkOnly = doc(imageParagraph(MEDIA), paragraph("Siehe https://example.org"));

		assertThat(LegacyDocs.mayNeedProjection(linkOnly)).isFalse();
		assertThat(LegacyDocs.mayNeedProjection(doc(imageParagraph("https://mail.example.com/a.png")))).isTrue();
		assertThat(LegacyDocs.mayNeedProjection(doc(imageParagraph("HTTP://mail.example.com/a.png")))).isTrue();
		assertThat(LegacyDocs.mayNeedProjection(doc(columns("1fr", item(paragraph("x")))))).isTrue();
	}

	@Test
	void memo_readsADocumentOnceForBothItsProjectionAndItsImages() {
		String stored = doc(columns("1fr", item(imageParagraph("https://mail.example.com/a.png"))));
		LegacyDocs.Memo memo = new LegacyDocs.Memo();
		long before = LegacyDocs.parses();

		memo.legacy(stored);
		memo.layout(stored);
		assertThat(memo.hasExternalImages(stored)).isTrue();

		assertThat(LegacyDocs.parses() - before).isEqualTo(1);
	}
}
