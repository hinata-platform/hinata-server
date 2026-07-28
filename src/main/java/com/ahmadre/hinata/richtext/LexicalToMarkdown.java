package com.ahmadre.hinata.richtext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a Lexical document back to hinata-dialect Markdown — the inverse of
 * {@link MarkdownToLexical}.
 *
 * <p>It exists for the one consumer that cannot use a document: an AI agent over
 * MCP. Agents read and write markdown, and the read tools used to hand them the
 * <em>derived plain text</em> while calling it markdown. The standard agent loop
 * is read → change one paragraph → write back, so that turned every agent edit
 * into a flattening of the whole article: headings, lists, tables, code blocks
 * and links replaced by a paragraph per line. Giving the model real markdown is
 * what makes that loop lossless.
 *
 * <p>The contract this holds is a <em>fixed point</em>, not textual identity:
 * for any markdown this codebase accepts, {@code markdown → document → markdown →
 * document} produces the same document twice. {@code RichTextCorpusFixedPointTest}
 * asserts it for every construct in the conformance corpus.
 *
 * <p>Two deliberate limitations, both consequences of markdown rather than of
 * this code:
 *
 * <ul>
 *   <li><b>No backslash escaping.</b> flexmark keeps the backslash in the text it
 *       produces ({@code a\*b} parses to the literal text {@code a\*b}), so
 *       escaping would corrupt content on the very first round trip instead of
 *       protecting it. Text that came from markdown therefore round-trips
 *       exactly; a document authored in the editor whose text happens to contain
 *       markdown syntax may be re-read as that syntax.</li>
 *   <li><b>Table cells.</b> A row is one line, so a newline inside a cell becomes
 *       a space. A literal {@code |} has no spelling that survives — flexmark
 *       splits on {@code \|} as readily as on {@code |} — so it is emitted
 *       unchanged, which keeps every character at the cost of an extra cell
 *       boundary.</li>
 * </ul>
 *
 * <p>Bounds mirror {@link LexicalJson}: the same node budget and depth limit, and
 * an output ceiling, because a stored document is untrusted input here exactly as
 * it is there. Descent is recursive rather than worklist-driven, which is safe
 * because the depth bound is checked <em>before</em> descending and is
 * {@value LexicalJson#MAX_DEPTH} — the stack cannot grow past that.
 */
public final class LexicalToMarkdown {

	private LexicalToMarkdown() {
	}

	/** Text format bits, matching {@link MarkdownToLexical}'s and lexical_core's. */
	private static final int BOLD = 1;
	private static final int ITALIC = 2;
	private static final int STRIKETHROUGH = 4;
	private static final int CODE = 16;

	/**
	 * The order delimiters nest in. Code sits innermost because a markdown code
	 * span cannot contain emphasis markers, only the other way round.
	 */
	private static final int[] BITS = { BOLD, ITALIC, STRIKETHROUGH, CODE };

	private static final String[] DELIMITERS = { "**", "*", "~~", "`" };

	/** Indent per list nesting level: wide enough to nest under an ordered marker. */
	private static final String LIST_INDENT = "    ";

	/**
	 * A mapper this class owns, for the same reason {@link RichTextService} owns
	 * one: reading a stored document must not move when an HTTP setting does.
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Markdown for a stored document, falling back to {@code fallback} when there
	 * is no document or it cannot be read.
	 *
	 * <p>The fallback matters: a row that predates the migration, and a voice
	 * comment, have no document at all. Returning the derived plain text there is
	 * honest — it is what the field holds. It is returned verbatim, {@code null}
	 * included, so a field that was absent stays absent rather than turning into an
	 * empty string on the wire.
	 */
	public static String fromStored(String json, String fallback) {
		if (json == null || json.isBlank()) return fallback;
		try {
			return convert(MAPPER.readTree(json));
		}
		catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
			return fallback;
		}
	}

	/** Markdown for a parsed document. Never {@code null}. */
	public static String convert(JsonNode document) {
		JsonNode root = document == null ? null : document.get("root");
		if (root == null) return "";
		Out out = new Out();
		out.append(blocks(root, 0, out));
		return out.finish();
	}

	// --- blocks ---------------------------------------------------------------

	/** The children of an element rendered as blocks, separated by a blank line. */
	private static String blocks(JsonNode parent, int depth, Out budget) {
		List<String> rendered = new ArrayList<>();
		for (JsonNode child : children(parent)) {
			if (budget.exhausted()) break;
			String block = block(child, depth, budget);
			if (!block.isEmpty()) rendered.add(block);
		}
		return String.join("\n\n", rendered);
	}

	private static String block(JsonNode node, int depth, Out budget) {
		if (node == null || !node.isObject()) return "";
		if (depth > LexicalJson.MAX_DEPTH || budget.spend()) return "";
		String type = node.path("type").asText("");
		return switch (type) {
			case "paragraph" -> inline(node, depth, budget);
			case "heading" -> heading(node, depth, budget);
			case "quote" -> quote(node, depth, budget);
			case "code" -> code(node);
			case "horizontalrule" -> "---";
			case "list" -> list(node, 0, depth, budget);
			case "table" -> table(node, depth, budget);
			case "callout" -> callout(node, depth, budget);
			case "image" -> image(node);
			default -> unknownBlock(node, depth, budget);
		};
	}

	private static String heading(JsonNode node, int depth, Out budget) {
		String tag = node.path("tag").asText("h1");
		int level = 1;
		if (tag.length() == 2 && tag.charAt(0) == 'h' && Character.isDigit(tag.charAt(1))) {
			level = Math.clamp(tag.charAt(1) - '0', 1, 6);
		}
		return "#".repeat(level) + " " + inline(node, depth, budget);
	}

	/**
	 * A quote holds inline content directly, with {@code linebreak} nodes standing
	 * for the paragraph boundaries markdown had. Each run becomes its own quoted
	 * paragraph, which is what re-parsing turns back into the same linebreak nodes.
	 */
	private static String quote(JsonNode node, int depth, Out budget) {
		Inline inline = new Inline(budget);
		List<String> paragraphs = new ArrayList<>();
		for (JsonNode child : children(node)) {
			if ("linebreak".equals(child.path("type").asText(""))) {
				paragraphs.add(inline.take());
				continue;
			}
			inline.node(child, depth + 1, budget);
		}
		paragraphs.add(inline.take());
		List<String> lines = new ArrayList<>();
		for (String paragraph : paragraphs) {
			if (!lines.isEmpty()) lines.add(">");
			for (String line : paragraph.split("\n", -1)) lines.add("> " + line);
		}
		return String.join("\n", lines);
	}

	private static String code(JsonNode node) {
		StringBuilder content = new StringBuilder();
		for (JsonNode child : children(node)) content.append(child.path("text").asText(""));
		// A fence has to be longer than the longest backtick run it encloses.
		int longest = 0;
		int run = 0;
		for (int i = 0; i < content.length(); i++) {
			run = content.charAt(i) == '`' ? run + 1 : 0;
			longest = Math.max(longest, run);
		}
		String fence = "`".repeat(Math.max(3, longest + 1));
		return fence + node.path("language").asText("") + "\n" + content + "\n" + fence;
	}

	private static String callout(JsonNode node, int depth, Out budget) {
		String kind = node.path("kind").asText("info");
		return ":::" + kind + "\n" + blocks(node, depth + 1, budget) + "\n:::";
	}

	/**
	 * A list, including the sibling items that hold nested lists. The importer
	 * writes a nested list as an item of its own containing only that list, so an
	 * item with no inline content contributes no marker — only its indent.
	 */
	private static String list(JsonNode node, int indent, int depth, Out budget) {
		if (indent > LexicalJson.MAX_DEPTH) return "";
		boolean ordered = "number".equals(node.path("listType").asText(""));
		boolean check = "check".equals(node.path("listType").asText(""));
		List<String> lines = new ArrayList<>();
		for (JsonNode item : children(node)) {
			if (budget.exhausted()) break;
			JsonNode nested = onlyChildList(item);
			if (nested != null) {
				String inner = list(nested, indent + 1, depth + 1, budget);
				if (!inner.isEmpty()) lines.add(inner);
				continue;
			}
			String marker = "- ";
			if (check) marker = item.path("checked").asBoolean(false) ? "- [x] " : "- [ ] ";
			else if (ordered) marker = item.path("value").asInt(1) + ". ";
			lines.add(LIST_INDENT.repeat(indent) + marker + inline(item, depth + 1, budget));
		}
		return String.join("\n", lines);
	}

	/** The single list an item wraps, or {@code null} when the item has content. */
	private static JsonNode onlyChildList(JsonNode item) {
		JsonNode found = null;
		for (JsonNode child : children(item)) {
			if (!"list".equals(child.path("type").asText(""))) return null;
			if (found != null) return null;
			found = child;
		}
		return found;
	}

	private static String table(JsonNode node, int depth, Out budget) {
		List<String> lines = new ArrayList<>();
		int columns = 0;
		for (JsonNode row : children(node)) {
			if (budget.exhausted()) break;
			List<String> cells = new ArrayList<>();
			for (JsonNode cell : children(row)) {
				cells.add(cell(cell, depth + 2, budget));
			}
			columns = Math.max(columns, cells.size());
			lines.add("| " + String.join(" | ", cells) + " |");
			// GFM needs the delimiter row directly under the header row, and a
			// markdown table always has one.
			if (lines.size() == 1) {
				lines.add("|" + " --- |".repeat(Math.max(1, cells.size())));
			}
		}
		if (columns == 0) return "";
		return String.join("\n", lines);
	}

	/**
	 * One cell, flattened to a single line: a table row cannot contain one.
	 *
	 * <p>A literal {@code |} is emitted as it is. flexmark's table parser splits on
	 * {@code \|} exactly as it splits on {@code |} and does not decode
	 * {@code &#124;} either, so there is no spelling of a pipe that survives a
	 * cell. Emitting the character unchanged at least keeps every character of the
	 * content; escaping would add a stray backslash and split the cell anyway.
	 */
	private static String cell(JsonNode node, int depth, Out budget) {
		StringBuilder content = new StringBuilder();
		for (JsonNode child : children(node)) {
			if (content.length() > 0) content.append(' ');
			content.append(inline(child, depth, budget));
		}
		return content.toString().replace("\n", " ").strip();
	}

	private static String image(JsonNode node) {
		return "![" + node.path("altText").asText("") + "](" + node.path("src").asText("") + ")";
	}

	/**
	 * A block type this exporter does not model. Structural, like the reader: an
	 * element holding elements is rendered as blocks, anything else as one line of
	 * inline content. Nothing is dropped for want of a case label.
	 */
	private static String unknownBlock(JsonNode node, int depth, Out budget) {
		for (JsonNode child : children(node)) {
			if (child.has("children")) return blocks(node, depth + 1, budget);
		}
		return inline(node, depth, budget);
	}

	// --- inlines --------------------------------------------------------------

	private static String inline(JsonNode parent, int depth, Out budget) {
		Inline inline = new Inline(budget);
		for (JsonNode child : children(parent)) inline.node(child, depth + 1, budget);
		return inline.take();
	}

	/**
	 * Renders inline content, tracking which emphasis delimiters are currently
	 * open so a run of text spanning several nodes is wrapped once rather than
	 * per node. Wrapping per node produces {@code **fett mit **} — a closing
	 * delimiter after a space, which CommonMark does not accept as one, so the
	 * document would come back different from the one that was exported.
	 */
	private static final class Inline {

		private final StringBuilder out = new StringBuilder();
		private final Out budget;
		private int open;

		private Inline(Out budget) {
			this.budget = budget;
		}

		/** Everything rendered so far, with the open delimiters closed. */
		String take() {
			closeTo(0);
			String rendered = out.toString();
			out.setLength(0);
			return rendered;
		}

		void node(JsonNode node, int depth, Out budget) {
			if (node == null || !node.isObject()) return;
			if (depth > LexicalJson.MAX_DEPTH || budget.spend()) return;
			String type = node.path("type").asText("");
			switch (type) {
				case "text" -> text(node.path("text").asText(""), node.path("format").asInt(0));
				// A hard break, a smart link and an image all survive inside emphasis,
				// so the open delimiters deliberately stay open across them: closing
				// and reopening would move the surrounding spaces out of the span.
				case "linebreak" -> out.append("  \n");
				case "tab" -> out.append('\t');
				case "smartlink" -> out.append("{{").append(node.path("kind").asText(""))
						.append(':').append(node.path("targetId").asText("")).append("}}");
				case "image" -> out.append(image(node));
				case "link", "autolink" -> link(node, depth);
				default -> unknown(node, depth);
			}
		}

		private void link(JsonNode node, int depth) {
			int entry = open;
			out.append('[');
			for (JsonNode child : children(node)) node(child, depth + 1, budget);
			// Whatever the link text opened has to close before the bracket.
			closeTo(entry);
			openTo(entry);
			if (out.charAt(out.length() - 1) == '[') {
				// A link with no readable children still needs something to click.
				out.append(node.path("url").asText(""));
			}
			out.append("](").append(node.path("url").asText(""));
			String title = node.path("title").asText("");
			if (!title.isEmpty()) out.append(" \"").append(title).append('"');
			out.append(')');
		}

		private void unknown(JsonNode node, int depth) {
			if (node.has("children")) {
				for (JsonNode child : children(node)) node(child, depth + 1, budget);
				return;
			}
			for (String field : new String[] { "text", "altText", "label" }) {
				JsonNode value = node.get(field);
				if (value != null && value.isTextual() && !value.asText().isEmpty()) {
					out.append(value.asText());
					return;
				}
			}
		}

		private void text(String value, int format) {
			if (value.isEmpty()) return;
			closeTo(format);
			if (open == format) {
				out.append(value);
				return;
			}
			// Leading whitespace belongs outside the delimiter about to open, for
			// the same reason trailing whitespace does when one closes.
			int lead = 0;
			while (lead < value.length() && isSpace(value.charAt(lead))) lead++;
			out.append(value, 0, lead);
			openTo(format);
			out.append(value, lead, value.length());
		}

		/** Closes every open bit not in {@code target}, innermost first. */
		private void closeTo(int target) {
			for (int i = BITS.length - 1; i >= 0; i--) {
				int bit = BITS[i];
				if ((open & bit) != 0 && (target & bit) == 0) {
					insertBeforeTrailingSpace(DELIMITERS[i]);
					open &= ~bit;
				}
			}
		}

		/** Opens every bit of {@code target} not yet open, outermost first. */
		private void openTo(int target) {
			for (int i = 0; i < BITS.length; i++) {
				int bit = BITS[i];
				if ((target & bit) != 0 && (open & bit) == 0) {
					out.append(DELIMITERS[i]);
					open |= bit;
				}
			}
		}

		/**
		 * A closing delimiter may not sit after a space — CommonMark would not read
		 * it as one — so it goes in front of whatever whitespace was written last.
		 */
		private void insertBeforeTrailingSpace(String delimiter) {
			int at = out.length();
			while (at > 0 && isSpace(out.charAt(at - 1))) at--;
			out.insert(at, delimiter);
		}

		private static boolean isSpace(char c) {
			return c == ' ' || c == '\t' || c == '\n';
		}
	}

	// --- bounds ---------------------------------------------------------------

	/** Shared node budget and output ceiling for one conversion. */
	private static final class Out {

		private final StringBuilder sink = new StringBuilder();
		private int nodes;

		/** Counts one node; {@code true} once the budget is gone. */
		boolean spend() {
			return ++nodes > LexicalJson.MAX_NODES;
		}

		boolean exhausted() {
			return nodes > LexicalJson.MAX_NODES || sink.length() > LexicalJson.MAX_JSON_CHARS;
		}

		void append(String value) {
			sink.append(value);
		}

		String finish() {
			String rendered = sink.toString().strip();
			return rendered.length() > LexicalJson.MAX_JSON_CHARS
					? rendered.substring(0, LexicalJson.MAX_JSON_CHARS)
					: rendered;
		}
	}

	private static Iterable<JsonNode> children(JsonNode node) {
		JsonNode children = node == null ? null : node.get("children");
		return children != null && children.isArray() ? children : List.of();
	}
}
