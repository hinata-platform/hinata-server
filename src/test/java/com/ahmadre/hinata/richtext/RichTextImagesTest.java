package com.ahmadre.hinata.richtext;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.TestImages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every image somebody sees in a text went through the upload gate (HIN-177). An external image is
 * a link: showing it would tell a foreign server who reads the page. A {@code data:} image becomes
 * media. What cannot be kept leaves a line that says so.
 */
class RichTextImagesTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String MEDIA = "/api/v1/media/" + UUID.randomUUID();

	/** Keeps what the gate would keep: an image up to a kilobyte. */
	private static final class Images implements EmbeddedImages {

		private final List<byte[]> stored = new ArrayList<>();

		@Override
		public Optional<String> store(byte[] data, String contentType) {
			if (data.length > 1024) {
				return Optional.empty();
			}
			stored.add(data);
			return Optional.of("/api/v1/media/" + UUID.randomUUID());
		}

		@Override
		public boolean isOwn(String url) {
			return url.startsWith("https://hinata.example.org/");
		}

		@Override
		public String externalImageLabel() {
			return "Externes Bild";
		}

		@Override
		public String imageNotKeptText() {
			return "Bild nicht übernommen";
		}
	}

	private final Images images = new Images();
	private final RichTextService service = new RichTextService(images);

	/** A document of one image with [src], as the app writes one. */
	private static String withImage(String src) {
		return new RichTextService().fromMarkdown("Vorher ![Logo](" + src + ") nachher").doc();
	}

	private static List<JsonNode> nodes(String json, String type) throws Exception {
		List<JsonNode> found = new ArrayList<>();
		collect(MAPPER.readTree(json), type, found);
		return found;
	}

	private static void collect(JsonNode node, String type, List<JsonNode> out) {
		if (type.equals(node.path("type").asText())) {
			out.add(node);
		}
		node.forEach(child -> collect(child, type, out));
	}

	private static String dataUri(byte[] bytes) {
		return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
	}

	@Test
	void anExternalImageBecomesALink() throws Exception {
		RichText text = service.fromLexical(withImage("https://tracker.example/pixel.png?u=42"));

		assertThat(nodes(text.doc(), "image")).isEmpty();
		List<JsonNode> links = nodes(text.doc(), "link");
		assertThat(links).hasSize(1);
		assertThat(links.getFirst().path("url").asText()).isEqualTo("https://tracker.example/pixel.png?u=42");
		assertThat(text.text()).contains("Externes Bild");
	}

	@Test
	void aDataImageBecomesMedia() throws Exception {
		byte[] png = TestImages.png(4, 4);

		RichText text = service.fromLexical(withImage(dataUri(png)));

		List<JsonNode> kept = nodes(text.doc(), "image");
		assertThat(kept).hasSize(1);
		assertThat(kept.getFirst().path("src").asText()).startsWith("/api/v1/media/");
		assertThat(images.stored).containsExactly(png);
	}

	@Test
	void aDataImageTheGateRefusesLeavesALine() throws Exception {
		RichText text = service.fromLexical(withImage(dataUri(TestImages.png(400, 400))));

		assertThat(nodes(text.doc(), "image")).isEmpty();
		assertThat(text.text()).contains("Bild nicht übernommen");
		assertThat(images.stored).isEmpty();
	}

	@Test
	void theTwentyFirstDataImageRefusesTheSaveBeforeAnyIsStored() {
		String one = "![x](" + dataUri(TestImages.png(2, 2)) + ")";
		String twenty = String.join(" ", java.util.Collections.nCopies(ImagesInText.MAX_DATA_IMAGES, one));
		assertThat(service.fromMarkdown(twenty).doc()).isNotNull();
		images.stored.clear();

		assertThatThrownBy(() -> service.fromMarkdown(twenty + " " + one))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
					assertThat(ex.getMessageKey()).isEqualTo("error.moderation.tooManyImages");
				});
		assertThat(images.stored).isEmpty();
	}

	@Test
	void anImageOfThisServerIsKeptByItsPath() throws Exception {
		String unchanged = withImage(MEDIA);
		assertThat(service.fromLexical(unchanged).doc()).as("nothing to settle, nothing rewritten")
				.isEqualTo(unchanged);

		RichText absolute = service.fromLexical(withImage("https://hinata.example.org" + MEDIA));
		assertThat(nodes(absolute.doc(), "image").getFirst().path("src").asText()).isEqualTo(MEDIA);

		// The same path on somebody else's server is somebody else's image.
		RichText foreign = service.fromLexical(withImage("https://elsewhere.example" + MEDIA));
		assertThat(nodes(foreign.doc(), "image")).isEmpty();
		assertThat(nodes(foreign.doc(), "link")).hasSize(1);
	}

	/** A relative address is fetched with the reader's token, so only media may be one. */
	@Test
	void anyOtherSourceIsLeftOut() throws Exception {
		for (String source : List.of("/api/v1/admin/users", "javascript:alert(1)", "cid:logo@mail", "file:///etc/passwd")) {
			RichText text = service.fromLexical(withImage(source));
			assertThat(nodes(text.doc(), "image")).as(source).isEmpty();
			assertThat(text.text()).as(source).contains("Bild nicht übernommen");
		}
	}

	@Test
	void anExternalImageInsideALinkBecomesItsLabel() throws Exception {
		String doc = new RichTextService().fromMarkdown("[![Logo](https://cdn.example/logo.png)](https://example.org)")
				.doc();

		RichText text = service.fromLexical(doc);

		List<JsonNode> links = nodes(text.doc(), "link");
		assertThat(links).hasSize(1);
		assertThat(links.getFirst().path("url").asText()).isEqualTo("https://example.org");
		assertThat(nodes(text.doc(), "image")).isEmpty();
		assertThat(text.text()).contains("Externes Bild");
	}

	@Test
	void anImageInACaptionIsSettledToo() throws Exception {
		ObjectNode document = (ObjectNode) MAPPER.readTree(withImage(MEDIA));
		List<JsonNode> found = new ArrayList<>();
		collect(document, "image", found);
		ObjectNode image = (ObjectNode) found.getFirst();
		ObjectNode inCaption = image.deepCopy().put("src", "https://tracker.example/caption.png");
		((ArrayNode) image.path("caption").path("editorState").path("root").path("children").get(0)
				.path("children")).add(inCaption);

		RichText text = service.fromLexical(MAPPER.writeValueAsString(document));

		assertThat(nodes(text.doc(), "image")).hasSize(1);
		assertThat(nodes(text.doc(), "link")).hasSize(1);
	}

	@Test
	void markdownFromMailOrAnAgentIsSettledAndAStoredBodyIsNot() throws Exception {
		assertThat(nodes(service.fromMarkdown("![Logo](https://cdn.example/logo.png)").doc(), "link")).hasSize(1);
		assertThat(nodes(service.fromStoredMarkdown("![Logo](https://cdn.example/logo.png)").doc(), "image"))
				.hasSize(1);
	}
}
