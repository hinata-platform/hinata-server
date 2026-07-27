package com.ahmadre.hinata.richtext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.ListBlock;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListItem;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TableSeparator;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Markdown to a Lexical document.
 *
 * <p>Markdown is no longer a storage format, but it is still an <em>input</em>
 * format: AI agents write it over MCP, and inbound e-mail becomes it. Everything
 * arriving that way is converted here and persisted as Lexical JSON like
 * everything else, so the database holds exactly one representation.
 *
 * <p>Parsing is flexmark's, which is a real CommonMark parser rather than the
 * flat regex alternation the app used to render with — so {@code **bold with
 * `code`**} and a link with emphasized text survive, which they previously did
 * not. On top of CommonMark this understands the two dialect constructs hinata
 * has always written:
 *
 * <ul>
 *   <li>{@code :::info} / {@code :::warn} / {@code :::note} / {@code :::tip}
 *       callout blocks, handled by a line pre-pass because they are not
 *       CommonMark;</li>
 *   <li>{@code {{issue:…}}}, {@code {{doc:…}}} and {@code {{user:…}}} smart-link
 *       tokens, which become {@code smartlink} nodes.</li>
 * </ul>
 *
 * <p>Two deliberate fidelity decisions:
 *
 * <ul>
 *   <li>A soft line break becomes a <em>space</em>, matching CommonMark and
 *       matching what the app's renderer did ({@code buf.join(' ')}). Emitting a
 *       line break instead would visibly reflow every document that exists.</li>
 *   <li>Nothing is ever dropped. A construct this converter does not model falls
 *       back to its literal source text rather than vanishing — losing content on
 *       a format migration is the one outcome that cannot be undone.</li>
 * </ul>
 */
public final class MarkdownToLexical {

	// Text format bits, matching lexical_core's TextFormat.
	private static final int BOLD = 1;
	private static final int ITALIC = 2;
	private static final int STRIKETHROUGH = 4;
	private static final int CODE = 16;

	/** Lexical's default image max-width, so a converted image needs no repair. */
	private static final int IMAGE_MAX_WIDTH = 500;

	/** Guards against pathological inline nesting; real documents sit in single digits. */
	private static final int MAX_INLINE_DEPTH = 32;

	/** Guards against pathological block nesting (deeply indented lists, quotes). */
	private static final int MAX_BLOCK_DEPTH = 32;

	private static final Pattern CALLOUT_OPEN = Pattern.compile("^:::(info|warn|note|tip)\\s*$");
	private static final Pattern CALLOUT_CLOSE = Pattern.compile("^:::\\s*$");
	private static final Pattern SMART_LINK = Pattern.compile("\\{\\{(issue|doc|user):([^}]+)}}");

	private static final Parser PARSER = buildParser();

	private final ObjectMapper mapper;

	public MarkdownToLexical(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	private static Parser buildParser() {
		MutableDataSet options = new MutableDataSet();
		options.set(Parser.EXTENSIONS, List.of(
				TablesExtension.create(),
				StrikethroughExtension.create(),
				TaskListExtension.create()));
		return Parser.builder(options).build();
	}

	/**
	 * Converts markdown to a Lexical document. Blank input yields the canonical
	 * empty document rather than {@code null}, so callers never have to special-case
	 * "the agent sent an empty description".
	 */
	public ObjectNode convert(String markdown) {
		ArrayNode blocks = mapper.createArrayNode();
		if (markdown != null && !markdown.isBlank()) {
			for (Segment segment : split(markdown)) {
				if (segment.calloutKind() == null) {
					blocks(PARSER.parse(segment.markdown()), blocks, 0);
				} else {
					ArrayNode inner = mapper.createArrayNode();
					blocks(PARSER.parse(segment.markdown()), inner, 1);
					ObjectNode callout = element("callout", inner, 0);
					callout.put("kind", segment.calloutKind());
					blocks.add(callout);
				}
			}
		}
		if (blocks.isEmpty()) blocks.add(paragraph(mapper.createArrayNode(), 0));
		return LexicalJson.document(mapper, blocks);
	}

	// --- callout pre-pass -----------------------------------------------------

	/** A run of the source: either ordinary markdown, or the body of a callout. */
	private record Segment(String calloutKind, String markdown) {
	}

	/**
	 * Splits the source on {@code :::kind} … {@code :::} fences. An unterminated
	 * opener is not a callout — it stays ordinary text, which is what the app's
	 * renderer did and avoids swallowing the rest of a document over a typo.
	 */
	private static List<Segment> split(String markdown) {
		List<Segment> segments = new ArrayList<>();
		String[] lines = markdown.split("\n", -1);
		StringBuilder plain = new StringBuilder();
		int i = 0;
		while (i < lines.length) {
			Matcher open = CALLOUT_OPEN.matcher(lines[i]);
			if (open.matches()) {
				int end = i + 1;
				while (end < lines.length && !CALLOUT_CLOSE.matcher(lines[end]).matches()) end++;
				if (end < lines.length) {
					flush(segments, plain);
					StringBuilder body = new StringBuilder();
					for (int j = i + 1; j < end; j++) body.append(lines[j]).append('\n');
					segments.add(new Segment(open.group(1), body.toString()));
					i = end + 1;
					continue;
				}
			}
			plain.append(lines[i]).append('\n');
			i++;
		}
		flush(segments, plain);
		return segments;
	}

	private static void flush(List<Segment> segments, StringBuilder plain) {
		if (!plain.isEmpty()) {
			segments.add(new Segment(null, plain.toString()));
			plain.setLength(0);
		}
	}

	// --- blocks ---------------------------------------------------------------

	private void blocks(Node parent, ArrayNode out, int depth) {
		for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
			block(child, out, depth);
		}
	}

	private void block(Node node, ArrayNode out, int depth) {
		if (depth > MAX_BLOCK_DEPTH) {
			out.add(paragraph(literal(node), 0));
			return;
		}
		switch (node) {
			case Heading heading -> {
				ObjectNode element = element("heading", inlineChildren(heading), 0);
				element.put("tag", "h" + Math.clamp(heading.getLevel(), 1, 6));
				out.add(element);
			}
			case Paragraph paragraph -> out.add(paragraph(inlineChildren(paragraph), 0));
			case BlockQuote quote -> out.add(element("quote", quoteChildren(quote), 0));
			case FencedCodeBlock fenced ->
				out.add(code(fenced.getContentChars().toString(), fenced.getInfo().toString()));
			case IndentedCodeBlock indented -> out.add(code(indented.getContentChars().toString(), ""));
			case ThematicBreak ignored -> out.add(simple("horizontalrule"));
			case ListBlock list -> out.add(list(list, 0, depth));
			case TableBlock table -> out.add(table(table));
			case HtmlBlock html -> {
				// Raw HTML has no Lexical counterpart. Keeping the source as text is
				// the honest fallback: visible, editable, and not silently lost.
				String text = html.getChars().toString().strip();
				if (!text.isEmpty()) out.add(paragraph(textOnly(text, 0), 0));
			}
			default -> {
				if (node.getFirstChild() != null) blocks(node, out, depth + 1);
				else {
					ArrayNode literal = literal(node);
					if (!literal.isEmpty()) out.add(paragraph(literal, 0));
				}
			}
		}
	}

	/**
	 * A quote's children, flattened. Lexical's quote holds inline content directly,
	 * while markdown nests paragraphs inside the block — so paragraphs are merged
	 * and separated by line breaks instead of producing an illegal tree.
	 */
	private ArrayNode quoteChildren(BlockQuote quote) {
		ArrayNode out = mapper.createArrayNode();
		boolean first = true;
		for (Node child = quote.getFirstChild(); child != null; child = child.getNext()) {
			if (!first) out.add(simple("linebreak"));
			first = false;
			Inline inline = new Inline(out);
			inlines(child, inline, 0, 0);
			inline.flush();
		}
		return out;
	}

	/**
	 * Converts a list. {@code itemIndent} is the nesting level stamped on the
	 * items; the list element itself always sits at indent 0, which is how the
	 * conformance corpus encodes nesting.
	 */
	private ObjectNode list(ListBlock list, int itemIndent, int depth) {
		boolean ordered = list instanceof OrderedList;
		boolean check = hasTaskItem(list);

		ArrayNode items = mapper.createArrayNode();
		int value = ordered ? ((OrderedList) list).getStartNumber() : 1;
		for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
			if (!(child instanceof ListItem item)) continue;
			items.add(listItem(item, value++, itemIndent, check));
			// A nested list is a sibling item of its own holding only the list —
			// not a child of the item that introduced it. Putting it inside would
			// produce a listitem with both text and a list, which is not the shape
			// @lexical/list writes or normalizes to.
			for (Node sub = item.getFirstChild(); sub != null; sub = sub.getNext()) {
				if (sub instanceof ListBlock nested) {
					items.add(nestedItem(nested, value++, itemIndent, check, depth));
				}
			}
		}

		String listType = "bullet";
		if (check) listType = "check";
		else if (ordered) listType = "number";

		ObjectNode element = element("list", items, 0);
		element.put("listType", listType);
		element.put("start", ordered ? ((OrderedList) list).getStartNumber() : 1);
		element.put("tag", ordered ? "ol" : "ul");
		return element;
	}

	/**
	 * A nested list, wrapped in the sibling item that holds it. Split out of
	 * {@link #list} so that method stays readable.
	 */
	private ObjectNode nestedItem(ListBlock nested, int value, int itemIndent, boolean check, int depth) {
		ArrayNode wrapper = mapper.createArrayNode();
		wrapper.add(list(nested, itemIndent + 1, depth + 1));
		ObjectNode holder = element("listitem", wrapper, itemIndent);
		holder.put("value", value);
		if (check) holder.put("checked", false);
		return holder;
	}

	/**
	 * Whether any item carries a checkbox. Lexical types the <em>list</em> rather
	 * than the item, so one checkbox makes the whole list a check list and the
	 * plain items in it read as unchecked. Mixing the two in one markdown list is
	 * vanishingly rare, and preserving the checkboxes the author wrote beats
	 * dropping them.
	 */
	private static boolean hasTaskItem(ListBlock list) {
		for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
			if (child instanceof TaskListItem) return true;
		}
		return false;
	}

	/** One item's own content. Nested lists are hoisted by {@link #list}. */
	private ObjectNode listItem(ListItem item, int value, int indent, boolean check) {
		ArrayNode children = mapper.createArrayNode();
		Inline inline = new Inline(children);
		for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
			if (child instanceof ListBlock) continue;
			inlines(child, inline, 0, 0);
		}
		inline.flush();

		ObjectNode element = element("listitem", children, indent);
		element.put("value", value);
		if (check) element.put("checked", item instanceof TaskListItem task && task.isItemDoneMarker());
		return element;
	}

	private ObjectNode table(TableBlock table) {
		ArrayNode rows = mapper.createArrayNode();
		collectRows(table, rows, false);
		return element("table", rows, 0);
	}

	/**
	 * Walks head and body sections, emitting one {@code tablerow} per markdown row.
	 * Cells of the header row carry {@code headerState} 1 (row header), matching
	 * what Lexical writes for a table whose first row is a heading.
	 */
	private void collectRows(Node parent, ArrayNode rows, boolean header) {
		for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
			if (child instanceof TableSeparator) continue;
			if (child instanceof TableRow row) {
				rows.add(row(row, header));
			} else {
				// A TableHead / TableBody wrapper; everything under a head is header.
				collectRows(child, rows, header || child instanceof TableHead);
			}
		}
	}

	private ObjectNode row(TableRow row, boolean header) {
		ArrayNode cells = mapper.createArrayNode();
		for (Node node = row.getFirstChild(); node != null; node = node.getNext()) {
			if (node instanceof TableCell cell) cells.add(cell(cell, header));
		}
		return element("tablerow", cells, 0);
	}

	private ObjectNode cell(TableCell cell, boolean header) {
		ArrayNode content = mapper.createArrayNode();
		content.add(paragraph(inlineChildren(cell), 0));
		ObjectNode element = element("tablecell", content, 0);
		element.putNull("backgroundColor");
		element.put("colSpan", Math.max(1, cell.getSpan()));
		element.put("headerState", header || cell.isHeader() ? 1 : 0);
		element.put("rowSpan", 1);
		return element;
	}

	// --- inlines --------------------------------------------------------------

	/** Inline children of a block, with adjacent same-format text merged. */
	private ArrayNode inlineChildren(Node parent) {
		ArrayNode out = mapper.createArrayNode();
		Inline inline = new Inline(out);
		inlines(parent, inline, 0, 0);
		inline.flush();
		return out;
	}

	private void inlines(Node parent, Inline out, int format, int depth) {
		for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
			inline(child, out, format, depth);
		}
	}

	private void inline(Node node, Inline out, int format, int depth) {
		if (depth > MAX_INLINE_DEPTH) {
			out.text(node.getChars().toString(), format);
			return;
		}
		switch (node) {
			case com.vladsch.flexmark.ast.Text text -> smartText(text.getChars().toString(), out, format);
			case StrongEmphasis emphasis -> inlines(emphasis, out, format | BOLD, depth + 1);
			case Emphasis emphasis -> inlines(emphasis, out, format | ITALIC, depth + 1);
			case Strikethrough strike -> inlines(strike, out, format | STRIKETHROUGH, depth + 1);
			case Code inlineCode -> out.text(inlineCode.getText().toString(), format | CODE);
			case SoftLineBreak ignored -> out.text(" ", format);
			case HardLineBreak ignored -> out.node(simple("linebreak"));
			case Image image -> out.node(image(image));
			case Link link -> out.node(link(link, format, depth));
			case AutoLink autoLink -> out.node(autoLink(autoLink, format));
			case HtmlInline ignored -> {
				// An inline tag has no Lexical counterpart and is not content itself;
				// its text arrives separately as a sibling Text node.
			}
			default -> {
				if (node.getFirstChild() != null) inlines(node, out, format, depth + 1);
				else out.text(node.getChars().toString(), format);
			}
		}
	}

	/**
	 * Emits text, turning {@code {{kind:id}}} tokens into {@code smartlink} nodes.
	 * The token characters are inert in markdown for the id shapes hinata uses
	 * (readable issue keys and hex ObjectIds), so a token always arrives inside a
	 * single text node; anything unrecognized stays literal text.
	 */
	private void smartText(String text, Inline out, int format) {
		Matcher matcher = SMART_LINK.matcher(text);
		int last = 0;
		while (matcher.find()) {
			if (matcher.start() > last) out.text(text.substring(last, matcher.start()), format);
			out.node(smartLink(matcher.group(1), matcher.group(2).trim()));
			last = matcher.end();
		}
		if (last < text.length()) out.text(text.substring(last), format);
	}

	private ObjectNode link(Link link, int format, int depth) {
		ArrayNode children = mapper.createArrayNode();
		Inline inline = new Inline(children);
		inlines(link, inline, format, depth + 1);
		inline.flush();
		if (children.isEmpty()) children.add(text(link.getUrl().toString(), format));

		ObjectNode element = element("link", children, 0);
		element.putNull("rel");
		element.putNull("target");
		String title = link.getTitle().toString();
		if (title.isEmpty()) element.putNull("title");
		else element.put("title", title);
		element.put("url", link.getUrl().toString());
		return element;
	}

	private ObjectNode autoLink(AutoLink link, int format) {
		String url = link.getUrl().toString();
		ArrayNode children = mapper.createArrayNode();
		children.add(text(url, format));
		ObjectNode element = element("link", children, 0);
		element.putNull("rel");
		element.putNull("target");
		element.putNull("title");
		element.put("url", url);
		return element;
	}

	private ObjectNode image(Image image) {
		ObjectNode node = mapper.createObjectNode();
		node.put("altText", image.getText().toString());
		ObjectNode caption = mapper.createObjectNode();
		caption.set("editorState", emptyCaptionState());
		node.set("caption", caption);
		node.put("height", 0);
		node.put("maxWidth", IMAGE_MAX_WIDTH);
		node.put("showCaption", false);
		node.put("src", image.getUrl().toString());
		node.put("type", "image");
		node.put("version", 1);
		node.put("width", 0);
		return node;
	}

	/** A nested editor state holding an empty paragraph, as the image node expects. */
	private ObjectNode emptyCaptionState() {
		ArrayNode blocks = mapper.createArrayNode();
		blocks.add(paragraph(mapper.createArrayNode(), 0));
		return LexicalJson.document(mapper, blocks);
	}

	/**
	 * A smart link. The label is what the search index and notification teasers
	 * read, so it is only set when it is genuinely readable: an issue key is, an
	 * ObjectId is not — and leaving it off keeps ObjectIds out of the index, which
	 * the markdown tokens used to put in. The app fills the label in when it saves.
	 */
	private ObjectNode smartLink(String kind, String targetId) {
		ObjectNode node = mapper.createObjectNode();
		node.put("kind", kind);
		if ("issue".equals(kind)) node.put("label", targetId);
		else node.putNull("label");
		node.put("targetId", targetId);
		node.put("type", "smartlink");
		node.put("version", 1);
		return node;
	}

	// --- node builders --------------------------------------------------------

	/** An element node in canonical field order. */
	private ObjectNode element(String type, ArrayNode children, int indent) {
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
	 * <p>{@code textFormat} and {@code textStyle} are not free-standing values:
	 * Lexical recomputes them from the first text child on every export, so a
	 * paragraph that starts in bold carries {@code textFormat: 1}. Writing a
	 * constant 0 here produced documents the editor rewrote the moment it opened
	 * them — which is exactly the silent, invisible edit this whole format change
	 * exists to eliminate.
	 */
	private ObjectNode paragraph(ArrayNode children, int indent) {
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

	private ObjectNode code(String content, String language) {
		ArrayNode children = mapper.createArrayNode();
		// A code block's content is literal: no inline parsing, no trailing newline
		// (the fence's own line terminator is not part of the code).
		String stripped = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
		if (!stripped.isEmpty()) children.add(text(stripped, 0));
		ObjectNode node = element("code", children, 0);
		node.put("language", language.trim().toLowerCase(Locale.ROOT));
		return node;
	}

	private ObjectNode text(String value, int format) {
		ObjectNode node = mapper.createObjectNode();
		node.put("detail", 0);
		node.put("format", format);
		node.put("mode", "normal");
		node.put("style", "");
		node.put("text", value);
		node.put("type", "text");
		node.put("version", 1);
		return node;
	}

	private ArrayNode textOnly(String value, int format) {
		ArrayNode children = mapper.createArrayNode();
		children.add(text(value, format));
		return children;
	}

	private ObjectNode simple(String type) {
		ObjectNode node = mapper.createObjectNode();
		node.put("type", type);
		node.put("version", 1);
		return node;
	}

	/** The node's own source text, for constructs this converter does not model. */
	private ArrayNode literal(Node node) {
		String source = node.getChars().toString().strip();
		return source.isEmpty() ? mapper.createArrayNode() : textOnly(source, 0);
	}

	/**
	 * Collects inline output, merging runs of text that share a format into one
	 * text node. Lexical normalizes adjacent same-format text on import anyway, so
	 * merging here means a converted document is already in the form the editor
	 * would save it in.
	 */
	private final class Inline {
		private final ArrayNode out;
		private final StringBuilder buffer = new StringBuilder();
		private int bufferFormat;

		private Inline(ArrayNode out) {
			this.out = out;
		}

		void text(String value, int format) {
			if (value.isEmpty()) return;
			if (!buffer.isEmpty() && bufferFormat != format) flush();
			bufferFormat = format;
			buffer.append(value);
		}

		void node(ObjectNode node) {
			flush();
			out.add(node);
		}

		void flush() {
			if (buffer.isEmpty()) return;
			out.add(MarkdownToLexical.this.text(buffer.toString(), bufferFormat));
			buffer.setLength(0);
		}
	}
}
