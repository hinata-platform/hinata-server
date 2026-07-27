package com.ahmadre.hinata.richtext;

import com.ahmadre.hinata.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads Lexical documents defensively and projects them to plain text.
 *
 * <p>A stored document is untrusted input: it arrives from a client, from an
 * e-mail, or from an AI agent over MCP. Every walk here is therefore
 * <em>bounded</em> — nesting depth, node count, document size — and iterative
 * over an explicit worklist rather than recursive, so a hostile document cannot
 * exhaust the stack. The bounds are far above anything a human writes: the
 * deepest document in the conformance corpus nests 27 levels, against a limit
 * of {@value #MAX_DEPTH}.
 *
 * <p>The server deliberately does <em>not</em> model Lexical's node types. It
 * only ever needs to read text out of a document and write one in, so it treats
 * the tree generically: anything with {@code children} is an element, anything
 * carrying a text-ish field contributes text. A node type the server has never
 * heard of therefore still contributes its text and still survives a round trip
 * untouched, which is what keeps the app free to add node types without a server
 * release.
 */
public final class LexicalJson {

	private LexicalJson() {
	}

	/** Maximum characters of Lexical JSON accepted for a single field. */
	public static final int MAX_JSON_CHARS = 1_000_000;

	/** Maximum nodes in one document. */
	public static final int MAX_NODES = 20_000;

	/** Maximum nesting depth below the root. */
	public static final int MAX_DEPTH = 64;

	/** Maximum characters of derived plain text kept for indexing. */
	public static final int MAX_TEXT_CHARS = 200_000;

	/**
	 * Element types that flow inline with surrounding text. Everything else with
	 * children is treated as a block and separated by a newline in the plain-text
	 * projection — including types this server does not know, which is the
	 * conservative choice: a spurious line break costs nothing, while running two
	 * unrelated blocks together would create words that are not in the document.
	 */
	private static final Set<String> INLINE_TYPES = Set.of(
			"link", "autolink", "mark", "mention", "hashtag", "smartlink");

	/** Serialized canonical empty document: a root holding one empty paragraph. */
	public static final String EMPTY_DOC = """
			{"root":{"children":[{"children":[],"direction":null,"format":"","indent":0,\
			"type":"paragraph","version":1,"textFormat":0,"textStyle":""}],"direction":null,\
			"format":"","indent":0,"type":"root","version":1}}""";

	/**
	 * Supplies plain text for a node, overriding the default rules. Returning
	 * {@code null} falls through to the defaults; returning {@code ""} suppresses
	 * the node's text entirely.
	 *
	 * <p>The notification layer uses this to render a {@code smartlink} of kind
	 * {@code user} as {@code @DisplayName} instead of an opaque id.
	 */
	@FunctionalInterface
	public interface NodeText {
		String textFor(String type, JsonNode node);
	}

	/** The default rules, used when no override is supplied. */
	private static final NodeText DEFAULTS = (type, node) -> null;

	// --- reading -------------------------------------------------------------

	/**
	 * Parses and structurally validates a Lexical document.
	 *
	 * @throws ApiException 400 when the input is not a readable Lexical document
	 */
	public static JsonNode parse(ObjectMapper mapper, String json) {
		if (json == null || json.isBlank()) throw invalid();
		if (json.length() > MAX_JSON_CHARS) throw tooLarge();
		JsonNode document;
		try {
			document = mapper.readTree(json);
		} catch (JsonProcessingException e) {
			throw invalid();
		}
		if (document == null || !document.isObject()) throw invalid();
		JsonNode root = document.get("root");
		if (root == null || !root.isObject()) throw invalid();
		if (!"root".equals(root.path("type").asText(""))) throw invalid();
		if (!root.path("children").isArray()) throw invalid();
		return document;
	}

	/**
	 * Whether the document holds no readable content — no text and no node that
	 * renders something on its own (an image, a horizontal rule, an embed).
	 */
	public static boolean isBlank(JsonNode document) {
		if (!plainText(document).isBlank()) return false;
		Deque<JsonNode> work = new ArrayDeque<>();
		work.push(document.path("root"));
		int nodes = 0;
		while (!work.isEmpty() && ++nodes <= MAX_NODES) {
			JsonNode node = work.pop();
			JsonNode children = node.get("children");
			if (children != null && children.isArray()) {
				for (JsonNode child : children) work.push(child);
			} else if (!node.path("type").asText("").equals("root")) {
				// A childless node that is not a text node stands for content of its
				// own — a decorator such as an image or an embed.
				if (!node.has("text")) return false;
			}
		}
		return true;
	}

	// --- plain-text projection -----------------------------------------------

	/** Plain text of the document, using the default extraction rules. */
	public static String plainText(JsonNode document) {
		return plainText(document, DEFAULTS);
	}

	/**
	 * Plain text of the document: the words a human would read, with blocks
	 * separated by newlines and inline elements running together.
	 *
	 * <p>This is what carries the Mongo text index, so it deliberately contains
	 * no markup and no identifiers — a {@code smartlink} contributes its label
	 * when it has one and nothing when it does not, which keeps ObjectIds out of
	 * the search index instead of putting them in as today's markdown does.
	 */
	public static String plainText(JsonNode document, NodeText override) {
		JsonNode root = document == null ? null : document.get("root");
		if (root == null) return "";
		StringBuilder out = new StringBuilder();
		Deque<Frame> work = new ArrayDeque<>();
		work.push(new Frame(root, 0));
		int nodes = 0;
		while (!work.isEmpty()) {
			Frame frame = work.pop();
			if (frame == BLOCK_END) {
				appendBreak(out);
				continue;
			}
			JsonNode node = frame.node();
			if (node == null || !node.isObject()) continue;
			if (++nodes > MAX_NODES || frame.depth() > MAX_DEPTH) break;

			String type = node.path("type").asText("");
			String own = override.textFor(type, node);
			if (own == null) own = defaultText(type, node);
			if (!own.isEmpty()) {
				if (out.length() >= MAX_TEXT_CHARS) break;
				out.append(own);
			}

			JsonNode children = node.get("children");
			if (children == null || !children.isArray()) continue;
			if (!INLINE_TYPES.contains(type)) work.push(BLOCK_END);
			for (int i = children.size() - 1; i >= 0; i--) {
				work.push(new Frame(children.get(i), frame.depth() + 1));
			}
		}
		String text = out.toString().strip();
		return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS).strip() : text;
	}

	/**
	 * Text a node contributes on its own, ignoring its children. Structural, not
	 * type-aware: any node carrying one of the text-bearing fields contributes it,
	 * so a node type the server does not know still lands in the search index.
	 */
	private static String defaultText(String type, JsonNode node) {
		if ("linebreak".equals(type)) return "\n";
		if ("tab".equals(type)) return "\t";
		for (String field : new String[] { "text", "altText", "label" }) {
			JsonNode value = node.get(field);
			if (value != null && value.isTextual() && !value.asText().isEmpty()) {
				return value.asText();
			}
		}
		return "";
	}

	/** Appends a single newline, collapsing runs and never leading with one. */
	private static void appendBreak(StringBuilder out) {
		if (out.isEmpty()) return;
		if (out.charAt(out.length() - 1) == '\n') return;
		out.append('\n');
	}

	// --- smart links ----------------------------------------------------------

	/**
	 * Ids referenced by {@code smartlink} nodes of the given kind, in document
	 * order. This is how the notification layer finds who a comment mentions now
	 * that a mention is a node rather than a {@code {{user:<id>}}} token.
	 */
	public static Set<String> smartLinkTargets(JsonNode document, String kind) {
		Set<String> ids = new LinkedHashSet<>();
		JsonNode root = document == null ? null : document.get("root");
		if (root == null) return ids;
		Deque<Frame> work = new ArrayDeque<>();
		work.push(new Frame(root, 0));
		int nodes = 0;
		while (!work.isEmpty()) {
			Frame frame = work.pop();
			JsonNode node = frame.node();
			if (node == null || !node.isObject()) continue;
			if (++nodes > MAX_NODES || frame.depth() > MAX_DEPTH) break;
			if ("smartlink".equals(node.path("type").asText(""))
					&& kind.equals(node.path("kind").asText(""))) {
				String id = node.path("targetId").asText("").trim();
				if (!id.isEmpty()) ids.add(id);
			}
			JsonNode children = node.get("children");
			if (children == null || !children.isArray()) continue;
			for (int i = children.size() - 1; i >= 0; i--) {
				work.push(new Frame(children.get(i), frame.depth() + 1));
			}
		}
		return ids;
	}

	// --- writing --------------------------------------------------------------

	/** A root element wrapping the given blocks, in canonical field order. */
	static ObjectNode document(ObjectMapper mapper, ArrayNode blocks) {
		ObjectNode root = mapper.createObjectNode();
		root.set("children", blocks);
		root.putNull("direction");
		root.put("format", "");
		root.put("indent", 0);
		root.put("type", "root");
		root.put("version", 1);
		ObjectNode document = mapper.createObjectNode();
		document.set("root", root);
		return document;
	}

	/** A frame of the explicit worklist; {@link #BLOCK_END} closes a block. */
	private record Frame(JsonNode node, int depth) {
	}

	private static final Frame BLOCK_END = new Frame(null, -1);

	private static ApiException invalid() {
		return ApiException.badRequest("error.richtext.invalid");
	}

	private static ApiException tooLarge() {
		return ApiException.badRequest("error.richtext.tooLarge");
	}
}
