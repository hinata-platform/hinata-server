package com.ahmadre.hinata.richtext;

import com.ahmadre.hinata.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Settles every image of a document before it is stored (HIN-177), so no image somebody sees is
 * loaded from where its author pointed. An image of this server's media stays. A {@code data:}
 * image becomes media like an upload. Anything else, and anything the gate refuses, gives way to
 * a short line that says an image was not kept.
 *
 * <p>An external {@code http(s)} image depends on the text ({@link ExternalImages}). Until
 * HIN-181 it always became a link, because showing it would hand the address of every reader to
 * a foreign server. An issue's texts now keep it as an image: no reader's app fetches it, the
 * server does, through the issue's image proxy and only after the reader chose to load it, so the
 * reader's address stays private and the mail keeps its layout. Every other text still turns it
 * into a link.
 */
final class ImagesInText {

	/** Most {@code data:} images one save turns into media. */
	static final int MAX_DATA_IMAGES = 20;

	/** Most images one document shows; more are refused before a single one is looked at. */
	static final int MAX_IMAGES = 200;

	/** The address of an image in this server's media, as the app writes it. */
	private static final Pattern MEDIA_PATH = Pattern.compile("/api/v1/media/[0-9a-fA-F-]{36}(/thumbnail)?");

	/** Lexical's format bit for italic text. */
	private static final int ITALIC = 2;

	private final MarkdownToLexical nodes;
	private final EmbeddedImages images;

	ImagesInText(MarkdownToLexical nodes, EmbeddedImages images) {
		this.nodes = nodes;
		this.images = images;
	}

	/**
	 * Normalises the address of every image of [document] in place: no surrounding space, the
	 * scheme in lower case, so that an address reads the same to every check that follows and to
	 * every reader ({@code HTTPS://} is {@code https://}). Says whether one changed.
	 */
	static boolean normalizeSources(JsonNode document) {
		List<JsonNode> work = new ArrayList<>();
		work.add(document.path("root"));
		boolean changed = false;
		int nodes = 0;
		while (!work.isEmpty() && ++nodes <= LexicalJson.MAX_NODES) {
			JsonNode node = work.removeLast();
			if (node instanceof ObjectNode image && "image".equals(node.path("type").asText())
					&& node.path("src").isTextual()) {
				String src = node.path("src").asText();
				String normal = normalSource(src);
				if (!normal.equals(src)) {
					image.put("src", normal);
					changed = true;
				}
			}
			JsonNode caption = node.path("caption").path("editorState").path("root");
			if (caption.isObject()) work.add(caption);
			JsonNode children = node.get("children");
			if (children != null && children.isArray()) children.forEach(work::add);
		}
		return changed;
	}

	private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

	private static String normalSource(String src) {
		String stripped = src.strip();
		var scheme = SCHEME.matcher(stripped);
		return scheme.find() ? scheme.group().toLowerCase(Locale.ROOT) + stripped.substring(scheme.end()) : stripped;
	}

	/** What a link standing for an external image says, in the instance's language. */
	String externalImageLabel() {
		return images.externalImageLabel();
	}

	/** One image node and the place it sits in. */
	private record Found(ArrayNode siblings, int index, ObjectNode image, boolean insideLink) {
	}

	/**
	 * Settles the images of [document] in place, external ones as [external] says, and says whether
	 * anything changed. Refuses more than {@link #MAX_IMAGES} images (kept external ones count),
	 * or more than {@link #MAX_DATA_IMAGES} {@code data:} ones,
	 * before it stores a single one.
	 */
	boolean settle(JsonNode document, ExternalImages external) {
		List<Found> found = new ArrayList<>();
		collect(document.path("root"), false, found);
		if (found.isEmpty()) {
			return false;
		}
		if (found.size() > MAX_IMAGES) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "error.richtext.tooManyImages", MAX_IMAGES);
		}
		long data = found.stream().filter(f -> isData(sourceOf(f.image()))).count();
		if (data > MAX_DATA_IMAGES) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "error.moderation.tooManyImages",
					MAX_DATA_IMAGES);
		}
		Words words = new Words();
		boolean changed = false;
		for (Found image : found) {
			changed |= settle(image, words, external);
		}
		return changed;
	}

	/** The words an image gives way to, looked up once per document and only when needed. */
	private final class Words {

		private String externalImage;
		private String notKept;

		String externalImage() {
			if (externalImage == null) {
				externalImage = images.externalImageLabel();
			}
			return externalImage;
		}

		ObjectNode notKept() {
			if (notKept == null) {
				notKept = images.imageNotKeptText();
			}
			return nodes.text(notKept, ITALIC);
		}
	}

	private void collect(JsonNode node, boolean insideLink, List<Found> out) {
		if (node.path("caption").path("editorState").has("root")) {
			// A caption is a document of its own, and may hold images too.
			collect(node.path("caption").path("editorState").path("root"), false, out);
		}
		if (!(node.get("children") instanceof ArrayNode children)) {
			return;
		}
		boolean link = insideLink || "link".equals(node.path("type").asText());
		for (int i = 0; i < children.size(); i++) {
			JsonNode child = children.get(i);
			if (child instanceof ObjectNode image && "image".equals(child.path("type").asText())) {
				out.add(new Found(children, i, image, link));
			}
			collect(child, link, out);
		}
	}

	private boolean settle(Found found, Words words, ExternalImages external) {
		String source = sourceOf(found.image());
		if (MEDIA_PATH.matcher(source).matches()) {
			return false;
		}
		if (isData(source)) {
			Optional<String> stored = decode(source).flatMap(image -> images.store(image.bytes(), image.type()));
			if (stored.isPresent()) {
				found.image().put("src", stored.get());
				return true;
			}
			return replace(found, words.notKept());
		}
		String lower = source.toLowerCase(Locale.ROOT);
		if (lower.startsWith("https://") || lower.startsWith("http://")) {
			String own = ownMediaPath(source);
			if (own != null) {
				found.image().put("src", own);
				return true;
			}
			if (external == ExternalImages.KEEP) {
				return false;
			}
			// A link inside a link is no link; the outer one stays and this says what was here.
			return replace(found, found.insideLink() ? nodes.text(words.externalImage(), 0)
					: nodes.linkTo(source, words.externalImage(), 0));
		}
		return replace(found, words.notKept());
	}

	/** The path of an image in this server's media that [url] names absolutely; null for anything else. */
	private String ownMediaPath(String url) {
		try {
			String path = java.net.URI.create(url).getRawPath();
			return path != null && MEDIA_PATH.matcher(path).matches() && images.isOwn(url) ? path : null;
		}
		catch (IllegalArgumentException malformed) {
			return null;
		}
	}

	private static boolean replace(Found found, ObjectNode replacement) {
		found.siblings().set(found.index(), replacement);
		return true;
	}

	private static String sourceOf(ObjectNode image) {
		return image.path("src").asText("").strip();
	}

	private static boolean isData(String source) {
		return source.regionMatches(true, 0, "data:", 0, 5);
	}

	/** The bytes of a {@code data:} image and the type it declares. */
	private record DataImage(String type, byte[] bytes) {
	}

	/** Only base64 is read: an image spelled in percent-encoding is SVG in practice, which is refused anyway. */
	private static Optional<DataImage> decode(String source) {
		int comma = source.indexOf(',');
		if (comma < 0) {
			return Optional.empty();
		}
		String[] header = source.substring(5, comma).split(";");
		boolean base64 = header.length > 1 && "base64".equalsIgnoreCase(header[header.length - 1].strip());
		if (!base64) {
			return Optional.empty();
		}
		try {
			byte[] bytes = Base64.getDecoder().decode(source.substring(comma + 1).replaceAll("\\s", ""));
			return Optional.of(new DataImage(header[0].strip(), bytes));
		}
		catch (IllegalArgumentException malformed) {
			return Optional.empty();
		}
	}
}
