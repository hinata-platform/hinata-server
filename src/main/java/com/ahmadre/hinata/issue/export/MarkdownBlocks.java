package com.ahmadre.hinata.issue.export;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TableSeparator;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a description into {@link ExportBlock}s, by way of the markdown the
 * document already knows how to produce.
 *
 * <p>Going through markdown rather than walking the Lexical document is the
 * point. {@code LexicalToMarkdown} is the renderer this codebase already tests
 * to a fixed point, and reusing it means an exported description says exactly
 * what every other markdown consumer sees. Writing a second walker over the
 * document would be a second definition of what a description <em>is</em>, and
 * the two would drift the first time a node type was added.
 *
 * <p>The parser is configured exactly like {@code MarkdownToLexical}'s — the
 * same three extensions, because the markdown it reads is the markdown that one
 * writes. Anything those extensions do not produce cannot appear here.
 */
final class MarkdownBlocks {

	/**
	 * Blocks a single description may contribute. A document is already bounded
	 * by {@code LexicalJson}'s node budget on the way in, but the export walks it
	 * into objects that then go into a PDF or a Word document, and a ceiling here
	 * is cheaper than discovering the limit in a renderer.
	 */
	private static final int MAX_BLOCKS = 2_000;

	private static final Parser PARSER = buildParser();

	private MarkdownBlocks() {
	}

	private static Parser buildParser() {
		MutableDataSet options = new MutableDataSet();
		options.set(Parser.EXTENSIONS, List.of(
				TablesExtension.create(),
				StrikethroughExtension.create(),
				TaskListExtension.create()));
		return Parser.builder(options).build();
	}

	/** The blocks of [markdown]; empty for blank input. */
	static List<ExportBlock> of(String markdown) {
		List<ExportBlock> blocks = new ArrayList<>();
		if (markdown == null || markdown.isBlank()) {
			return blocks;
		}
		appendChildren(PARSER.parse(markdown), blocks);
		return blocks;
	}

	private static void appendChildren(Node parent, List<ExportBlock> blocks) {
		for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
			if (blocks.size() >= MAX_BLOCKS) {
				return;
			}
			append(node, blocks);
		}
	}

	private static void append(Node node, List<ExportBlock> blocks) {
		switch (node) {
			case Heading heading ->
					blocks.add(new ExportBlock.Heading(heading.getLevel(), spans(heading)));
			case Paragraph paragraph -> {
				List<ExportBlock.Span> spans = spans(paragraph);
				if (!ExportBlock.Span.plain(spans).isBlank()) {
					blocks.add(new ExportBlock.Paragraph(spans));
				}
			}
			case FencedCodeBlock fenced -> blocks.add(new ExportBlock.Code(
					fenced.getInfo().toString().trim(), fenced.getContentChars().toString()));
			case IndentedCodeBlock indented ->
					blocks.add(new ExportBlock.Code("", indented.getContentChars().toString()));
			case BulletList list -> blocks.add(new ExportBlock.BulletList(false, items(list)));
			case OrderedList list -> blocks.add(new ExportBlock.BulletList(true, items(list)));
			case BlockQuote quote -> blocks.add(new ExportBlock.Quote(spans(quote)));
			case TableBlock table -> appendTable(table, blocks);
			case ThematicBreak ignored -> blocks.add(new ExportBlock.Rule());
			// Anything else — an HTML block, a link reference definition — carries
			// no shape worth reproducing, but its text still belongs in the export.
			default -> {
				List<ExportBlock.Span> spans = spans(node);
				if (!ExportBlock.Span.plain(spans).isBlank()) {
					blocks.add(new ExportBlock.Paragraph(spans));
				}
			}
		}
	}

	/** One entry per list item, flattened: a nested list contributes its lines. */
	private static List<List<ExportBlock.Span>> items(Node list) {
		List<List<ExportBlock.Span>> items = new ArrayList<>();
		for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
			if (child instanceof ListItem) {
				List<ExportBlock.Span> spans = spans(child);
				if (!ExportBlock.Span.plain(spans).isBlank()) {
					items.add(spans);
				}
			}
		}
		return items;
	}

	private static void appendTable(TableBlock table, List<ExportBlock> blocks) {
		List<String> headers = new ArrayList<>();
		List<List<String>> rows = new ArrayList<>();
		collectRows(table, headers, rows);
		if (!headers.isEmpty() || !rows.isEmpty()) {
			blocks.add(new ExportBlock.Table(headers, rows));
		}
	}

	/** Depth-first over the table's sections; the first row read is the header. */
	private static void collectRows(Node node, List<String> headers, List<List<String>> rows) {
		for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
			if (child instanceof TableSeparator) {
				continue;
			}
			if (child instanceof TableRow row) {
				List<String> cells = new ArrayList<>();
				for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
					if (cell instanceof TableCell) {
						cells.add(ExportBlock.Span.plain(spans(cell)).trim());
					}
				}
				if (headers.isEmpty()) {
					headers.addAll(cells);
				}
				else {
					rows.add(cells);
				}
				continue;
			}
			collectRows(child, headers, rows);
		}
	}

	/** The styled text under [node], with emphasis flattened onto each run. */
	private static List<ExportBlock.Span> spans(Node node) {
		List<ExportBlock.Span> spans = new ArrayList<>();
		collectSpans(node, spans, false, false, false, false);
		return spans;
	}

	private static void collectSpans(Node parent, List<ExportBlock.Span> spans,
			boolean bold, boolean italic, boolean code, boolean strike) {
		for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
			switch (node) {
				case StrongEmphasis ignored ->
						collectSpans(node, spans, true, italic, code, strike);
				case Emphasis ignored -> collectSpans(node, spans, bold, true, code, strike);
				case Strikethrough ignored -> collectSpans(node, spans, bold, italic, code, true);
				case Code inline ->
						add(spans, inline.getText().toString(), bold, italic, true, strike);
				default -> {
					if (node.getFirstChild() != null) {
						collectSpans(node, spans, bold, italic, code, strike);
					}
					else {
						// A leaf: its own characters, whatever kind of leaf it is. A soft
						// line break inside a paragraph reads as a space, which is what
						// its characters already are.
						add(spans, node.getChars().toString(), bold, italic, code, strike);
					}
				}
			}
		}
	}

	private static void add(List<ExportBlock.Span> spans, String text,
			boolean bold, boolean italic, boolean code, boolean strike) {
		if (!text.isEmpty()) {
			spans.add(new ExportBlock.Span(text, bold, italic, code, strike));
		}
	}
}
