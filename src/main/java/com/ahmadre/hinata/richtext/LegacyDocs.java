package com.ahmadre.hinata.richtext;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The document an app published before HIN-181 is given (HIN-181, store compatibility).
 *
 * <p>An app in the stores is frozen until its successor is rolled out, and it reads
 * {@code descriptionDoc} with the node types it knows. A mail converted with its layout carries
 * two things it does not: {@code layout-container}/{@code layout-item}, which it cannot import,
 * and external images, which it would load straight from the sender's server (the reader's
 * address to every tracker in the mail) because it predates the image proxy. So such an app is
 * served the <em>legacy projection</em>: layout flattened into its blocks in reading order, and
 * every external image the link that HIN-177 made of it, labelled "External image". Block styles
 * ({@code "$"}) stay: the published port keeps NodeState it does not read and renders nothing of
 * it. The full document goes out next to it, in an additive field, for the apps that read it.
 */
public final class LegacyDocs {

	private LegacyDocs() {
	}

	/** The label when no instance language is known, as in tests. */
	public static final String DEFAULT_LABEL = "External image";

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final LexicalNodes NODES = new LexicalNodes(MAPPER);

	private static final Set<String> LAYOUT = Set.of("layout-container", "layout-item");

	/** Where a block may stand; a link standing for an image there is wrapped in a paragraph. */
	private static final Set<String> BLOCK_PARENTS = Set.of("root", "layout-container", "layout-item",
			"tablecell", "callout");

	private static volatile Supplier<String> label = () -> DEFAULT_LABEL;

	/** Documents parsed here so far; tests read it to see that a response parses a document once. */
	private static final java.util.concurrent.atomic.AtomicLong PARSES = new java.util.concurrent.atomic.AtomicLong();

	static long parses() {
		return PARSES.get();
	}

	/** Where the label of an external image comes from: the instance's language. */
	static void labelFrom(Supplier<String> source) {
		label = source;
	}

	/**
	 * The legacy projection of [stored]; [stored] itself, the same instance, when it has nothing a
	 * published app could not read, and for null or an unreadable document.
	 */
	public static String project(String stored) {
		return project(stored, label);
	}

	static String project(String stored, Supplier<String> labelSource) {
		return projected(stored, labelSource).doc();
	}

	/** A projection and the external image addresses it replaced, from one read of the document. */
	record Projected(String doc, Set<String> sources) {
	}

	static Projected projected(String stored, Supplier<String> labelSource) {
		if (!mayNeedProjection(stored)) return new Projected(stored, Set.of());
		JsonNode document = parse(stored);
		if (document == null || !document.path("root").isObject()) return new Projected(stored, Set.of());
		Projection projection = new Projection(labelSource);
		projection.children((ObjectNode) document.get("root"), false, 0);
		return new Projected(projection.changed ? write(document) : stored, projection.sources);
	}

	/**
	 * Whether [incoming], a document a client sent for a text stored as [stored], is that text's
	 * legacy projection coming back from an app that cannot show more, so that storing it would
	 * only destroy the layout and the images. True when [stored] has a projection of its own,
	 * [incoming] carries neither layout nor an external image (an app that reads layouts sends
	 * them back), and it equals the projection, as a document or, after the old app's editor
	 * normalised it, in its words.
	 */
	public static boolean isRoundTripOf(String incoming, String stored, String externalImageLabel) {
		if (incoming == null || stored == null) return false;
		String projected = project(stored, () -> externalImageLabel);
		if (projected == stored) return false;
		JsonNode sent = parse(incoming);
		JsonNode legacy = parse(projected);
		if (legacy == null) return false;
		if (sent == null || !sent.path("root").isObject() || hasLayoutOrExternalImage(sent)) return false;
		if (sent.equals(legacy)) return true;
		return LexicalJson.plainText(sent).equals(LexicalJson.plainText(legacy));
	}

	/**
	 * A cheap first look: whether [json] could hold a layout or an external image at all, with
	 * no false negative. An image address is looked for as {@code "src":"http(s)://"}, ignoring
	 * case, because a scheme may be spelled {@code HTTPS}; any escape ({@code \\u0068ttps})
	 * sends the document to the parser, which reads what it says.
	 * A miss costs a parse; nothing it rules out can need a projection.
	 */
	static boolean mayNeedProjection(String json) {
		if (json == null || json.isEmpty()) return false;
		if (json.contains("\\u")) return true;
		String lower = json.toLowerCase(Locale.ROOT);
		return lower.contains("layout-") || EXTERNAL_SRC.matcher(json).find();
	}

	/** An image address on another server, as it stands in canonical JSON. */
	private static final java.util.regex.Pattern EXTERNAL_SRC = java.util.regex.Pattern.compile(
			"\"src\"\\s*:\\s*\"\\s*https?://", java.util.regex.Pattern.CASE_INSENSITIVE);

	/** [json] read, counted; null for an unreadable document. */
	private static JsonNode parse(String json) {
		PARSES.incrementAndGet();
		try {
			return MAPPER.readTree(json);
		}
		catch (JsonProcessingException unreadable) {
			return null;
		}
	}
	/**
	 * The addresses of the external images [doc] shows, in document order, captions included;
	 * empty for a document without any and for an unreadable one. What the image proxy may fetch
	 * for an issue is exactly this set over its texts.
	 */
	public static Set<String> externalImageSources(String doc) {
		Set<String> sources = new java.util.LinkedHashSet<>();
		if (!mayNeedProjection(doc)) return sources;
		JsonNode document = parse(doc);
		if (document == null) return sources;
		List<JsonNode> work = new ArrayList<>();
		work.add(document.path("root"));
		int nodes = 0;
		while (!work.isEmpty() && ++nodes <= LexicalJson.MAX_NODES) {
			JsonNode node = work.removeLast();
			if ("image".equals(node.path("type").asText("")) && isExternal(node)) {
				sources.add(node.path("src").asText("").strip());
			}
			JsonNode caption = node.path("caption").path("editorState").path("root");
			if (caption.isObject()) work.add(caption);
			JsonNode children = node.get("children");
			if (children != null && children.isArray()) {
				for (int i = children.size() - 1; i >= 0; i--) work.add(children.get(i));
			}
		}
		return sources;
	}

	private static boolean hasLayoutOrExternalImage(JsonNode document) {
		List<JsonNode> work = new ArrayList<>();
		work.add(document.path("root"));
		int nodes = 0;
		while (!work.isEmpty()) {
			JsonNode node = work.removeLast();
			if (++nodes > LexicalJson.MAX_NODES) return true;
			String type = node.path("type").asText("");
			if (LAYOUT.contains(type) || "image".equals(type) && isExternal(node)) return true;
			JsonNode caption = node.path("caption").path("editorState").path("root");
			if (caption.isObject()) work.add(caption);
			JsonNode children = node.get("children");
			if (children != null && children.isArray()) children.forEach(work::add);
		}
		return false;
	}

	static boolean isExternal(JsonNode image) {
		String src = image.path("src").asText("").strip().toLowerCase(Locale.ROOT);
		return src.startsWith("https://") || src.startsWith("http://");
	}

	private static String write(JsonNode document) {
		try {
			return MAPPER.writeValueAsString(document);
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException("failed to serialize a Lexical document", e);
		}
	}

	/** One walk of a document, flattening as it goes. */
	private static final class Projection {

		private final Supplier<String> labelSource;
		private final Set<String> sources = new java.util.LinkedHashSet<>();
		private String label;
		private boolean changed;

		Projection(Supplier<String> labelSource) {
			this.labelSource = labelSource;
		}

		private String label() {
			if (label == null) label = labelSource.get();
			return label;
		}

		/** Rewrites the children of [parent] in place. */
		void children(ObjectNode parent, boolean insideLink, int depth) {
			JsonNode caption = parent.path("caption").path("editorState").path("root");
			if (caption instanceof ObjectNode captionRoot && depth < LexicalJson.MAX_DEPTH) {
				children(captionRoot, false, depth + 1);
			}
			if (!(parent.get("children") instanceof ArrayNode children)) return;
			String parentType = parent.path("type").asText("");
			boolean link = insideLink || "link".equals(parentType) || "autolink".equals(parentType);
			ArrayNode out = MAPPER.createArrayNode();
			for (JsonNode child : children) {
				if (!(child instanceof ObjectNode node)) {
					out.add(child);
					continue;
				}
				String type = node.path("type").asText("");
				if (LAYOUT.contains(type)) {
					changed = true;
					// Its blocks, themselves flattened, take its place.
					if (depth < LexicalJson.MAX_DEPTH) children(node, link, depth + 1);
					if (node.get("children") instanceof ArrayNode blocks) blocks.forEach(out::add);
					continue;
				}
				if ("image".equals(type) && isExternal(node)) {
					changed = true;
					sources.add(node.path("src").asText("").strip());
					out.add(standIn(node, link, BLOCK_PARENTS.contains(parentType)));
					continue;
				}
				if (depth < LexicalJson.MAX_DEPTH) children(node, link, depth + 1);
				out.add(node);
			}
			parent.set("children", out);
		}

		/** What HIN-177 made of an external image: a link to it, or its label inside a link. */
		private ObjectNode standIn(ObjectNode image, boolean insideLink, boolean asBlock) {
			ObjectNode inline = insideLink ? NODES.text(label(), 0)
					: NODES.linkTo(image.path("src").asText("").strip(), label(), 0);
			if (!asBlock) return inline;
			ArrayNode content = NODES.array();
			content.add(inline);
			return NODES.paragraph(content, 0);
		}
	}

	/**
	 * Both client forms of one stored document, drawn once per entity however often a response
	 * asks: the entity's getters read through it while it is written out.
	 */
	public static final class Memo {

		private String source;
		private Projected projection;

		/** Whether [stored] shows an external image; from the same read as its projection. */
		public boolean hasExternalImages(String stored) {
			if (stored == null) return false;
			read(stored);
			return !projection.sources().isEmpty();
		}

		/**
		 * What a client gets in the document's own field: the legacy projection of [stored], or
		 * [stored] itself for a request that reads layouts ({@link DocForm#LAYOUT}).
		 */
		public String forClient(String stored) {
			return DocForm.current() == DocForm.LAYOUT ? stored : legacy(stored);
		}

		/** What a client gets in the additive field: {@link #layout}, and nothing for {@link DocForm#LAYOUT}. */
		public String layoutForClient(String stored) {
			return DocForm.current() == DocForm.LAYOUT ? null : layout(stored);
		}

		/** The legacy projection of [stored]. */
		public String legacy(String stored) {
			if (stored == null) return null;
			return read(stored).doc();
		}

		private Projected read(String stored) {
			// The same instance is the same document: an entity's field only changes by assignment.
			if (stored != source) {
				projection = projected(stored, label);
				source = stored;
			}
			return projection;
		}

		/** [stored] when its projection differs from it, else null: the additive field. */
		public String layout(String stored) {
			String legacy = legacy(stored);
			return legacy == null || legacy.equals(stored) ? null : stored;
		}
	}
}
