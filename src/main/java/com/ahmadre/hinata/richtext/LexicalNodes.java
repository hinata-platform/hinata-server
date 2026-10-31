package com.ahmadre.hinata.richtext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds Lexical nodes in the shapes upstream Lexical 0.48 writes, in canonical field order. Every
 * converter that produces a document goes through here, so a paragraph from markdown and one from
 * an HTML mail are the same paragraph.
 */
public final class LexicalNodes {

	/** Lexical's format bits, matching lexical_core's TextFormat. */
	public static final int BOLD = 1;
	public static final int ITALIC = 2;
	public static final int STRIKETHROUGH = 4;
	public static final int UNDERLINE = 8;
	public static final int CODE = 16;

	/** Lexical's default image max-width, so a converted image needs no repair. */
	public static final int IMAGE_MAX_WIDTH = 500;

	/** The key upstream Lexical serialises a node's NodeState under. */
	public static final String STATE = "$";

	private final ObjectMapper mapper;

	public LexicalNodes(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	public ArrayNode array() {
		return mapper.createArrayNode();
	}

	/** A document whose root holds [blocks]. */
	public ObjectNode document(ArrayNode blocks) {
		return LexicalJson.document(mapper, blocks);
	}

	/** An element node in canonical field order. */
	public ObjectNode element(String type, ArrayNode children, int indent) {
		ObjectNode node = mapper.createObjectNode();
		node.set("children", children);
		node.putNull("direction");
		node.put("format", "");
		node.put("indent", indent);
		node.put("type", type);
		node.put("version", 1);
		return node;
	}

	/**
	 * A paragraph, with its two <em>derived</em> fields.
	 *
	 * <p>{@code textFormat} and {@code textStyle} are not free-standing values: Lexical recomputes
	 * them from the first text child on every export, so a paragraph that starts in bold carries
	 * {@code textFormat: 1}. Writing a constant 0 produces documents the editor rewrites the
	 * moment it opens them.
	 */
	public ObjectNode paragraph(ArrayNode children, int indent) {
		ObjectNode node = element("paragraph", children, indent);
		int textFormat = 0;
		String textStyle = "";
		for (JsonNode child : children) {
			if (!"text".equals(child.path("type").asText(""))) continue;
			textFormat = child.path("format").asInt(0);
			textStyle = child.path("style").asText("");
			break;
		}
		node.put("textFormat", textFormat);
		node.put("textStyle", textStyle);
		return node;
	}

	/** A heading {@code h1}–{@code h6}. */
	public ObjectNode heading(int level, ArrayNode children) {
		ObjectNode element = element("heading", children, 0);
		element.put("tag", "h" + Math.clamp(level, 1, 6));
		return element;
	}

	public ObjectNode text(String value, int format) {
		return text(value, format, "");
	}

	/** A text node; [style] is the upstream inline CSS string, empty for none. */
	public ObjectNode text(String value, int format, String style) {
		ObjectNode node = mapper.createObjectNode();
		node.put("detail", 0);
		node.put("format", format);
		node.put("mode", "normal");
		node.put("style", style == null ? "" : style);
		node.put("text", value);
		node.put("type", "text");
		node.put("version", 1);
		return node;
	}

	public ArrayNode textOnly(String value, int format) {
		ArrayNode children = array();
		children.add(text(value, format));
		return children;
	}

	/** A childless node such as {@code linebreak} or {@code horizontalrule}. */
	public ObjectNode simple(String type) {
		ObjectNode node = mapper.createObjectNode();
		node.put("type", type);
		node.put("version", 1);
		return node;
	}

	/** A link to [url] holding [children]. */
	public ObjectNode link(String url, ArrayNode children) {
		ObjectNode element = element("link", children, 0);
		element.putNull("rel");
		element.putNull("target");
		element.putNull("title");
		element.put("url", url);
		return element;
	}

	/** A link to [url] showing [label]. */
	public ObjectNode linkTo(String url, String label, int format) {
		return link(url, textOnly(label, format));
	}

	/**
	 * An image as the playground writes one. [width] and [height] are the original's pixel sizes,
	 * 0 for unknown; [maxWidth] is the largest it is drawn at.
	 */
	public ObjectNode image(String src, String altText, int width, int height, int maxWidth) {
		ObjectNode node = mapper.createObjectNode();
		node.put("altText", altText == null ? "" : altText);
		ObjectNode caption = mapper.createObjectNode();
		caption.set("editorState", emptyCaptionState());
		node.set("caption", caption);
		node.put("height", Math.max(0, height));
		node.put("maxWidth", maxWidth);
		node.put("showCaption", false);
		node.put("src", src);
		node.put("type", "image");
		node.put("version", 1);
		node.put("width", Math.max(0, width));
		return node;
	}

	/** A nested editor state holding an empty paragraph, as the image node expects. */
	private ObjectNode emptyCaptionState() {
		ArrayNode blocks = array();
		blocks.add(paragraph(array(), 0));
		return document(blocks);
	}

	/** The playground's {@code layout-container}; [items] are {@link #layoutItem}s. */
	public ObjectNode layoutContainer(String templateColumns, ArrayNode items) {
		ObjectNode node = element("layout-container", items, 0);
		node.put("templateColumns", templateColumns);
		return node;
	}

	/** The playground's {@code layout-item}, a shadow root of blocks. */
	public ObjectNode layoutItem(ArrayNode blocks) {
		return element("layout-item", blocks, 0);
	}

	/** A table cell holding [blocks]; [header] marks a row-header cell. */
	public ObjectNode tableCell(ArrayNode blocks, boolean header, int colSpan) {
		ObjectNode element = element("tablecell", blocks, 0);
		element.putNull("backgroundColor");
		element.put("colSpan", Math.max(1, colSpan));
		element.put("headerState", header ? 1 : 0);
		element.put("rowSpan", 1);
		return element;
	}

	public ObjectNode tableRow(ArrayNode cells) {
		return element("tablerow", cells, 0);
	}

	public ObjectNode table(ArrayNode rows) {
		return element("table", rows, 0);
	}

	/**
	 * Sets the block style (NodeState key {@code style}) of [element]; nothing for an empty
	 * [style]. The caller passes a style {@link BlockStyle} already sanitised.
	 */
	public static void setBlockStyle(ObjectNode element, String style) {
		if (style == null || style.isEmpty()) return;
		JsonNode state = element.get(STATE);
		ObjectNode own = state instanceof ObjectNode existing ? existing : element.putObject(STATE);
		own.put("style", style);
	}

	/** The block style of [element], empty for none. */
	public static String blockStyle(JsonNode element) {
		JsonNode style = element.path(STATE).path("style");
		return style.isTextual() ? style.asText() : "";
	}
}
