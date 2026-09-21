package com.ahmadre.hinata.issue.export;

import java.util.List;
import java.util.Set;

/**
 * The description, in the shape every export format can render: a flat list of
 * blocks, each carrying styled spans rather than markup.
 *
 * <p>It exists so the four renderers stay dumb. A PDF, a Word document, a
 * spreadsheet cell and an XML element have nothing in common except that each
 * can be told "this is a level-2 heading" or "this is a bullet list", and none
 * of them should be parsing anything. The same split the app's list export
 * already makes, where one row type carries display text and the builders only
 * place it.
 *
 * <p>Deliberately flat and deliberately small: no nesting beyond a list's items,
 * no links as objects, no images. An exported issue is a document somebody
 * reads or files, not a second editor.
 */
public sealed interface ExportBlock {

	/** A run of text with the emphasis it carried. */
	record Span(String text, boolean bold, boolean italic, boolean code, boolean strike) {

		/** Plain text, for the formats that have nowhere to put emphasis. */
		static String plain(List<Span> spans) {
			StringBuilder out = new StringBuilder();
			for (Span span : spans) {
				out.append(span.text());
			}
			return out.toString();
		}
	}

	/** [level] is 1..6, as in the document it came from. */
	record Heading(int level, List<Span> spans) implements ExportBlock {
	}

	record Paragraph(List<Span> spans) implements ExportBlock {
	}

	/** One list; [ordered] picks numbers over bullets, [items] are its lines. */
	record BulletList(boolean ordered, List<List<Span>> items) implements ExportBlock {
	}

	/** A fenced block. [language] may be blank; the text is never re-wrapped. */
	record Code(String language, String text) implements ExportBlock {
	}

	record Quote(List<Span> spans) implements ExportBlock {
	}

	/**
	 * A table whose first row is its header. Cells are plain text — a cell is a
	 * line by construction, so there is no emphasis left to carry.
	 *
	 * @param widths     relative column widths, or empty for equal columns
	 * @param endAligned the columns that hold figures and line up on their end
	 */
	record Table(List<String> headers, List<List<String>> rows, List<Float> widths,
			Set<Integer> endAligned) implements ExportBlock {

		public Table(List<String> headers, List<List<String>> rows) {
			this(headers, rows, List.of(), Set.of());
		}
	}

	/**
	 * A table read while it is written: the rows come from a database cursor and are
	 * never held together in memory. For the exports that run to tens of thousands of
	 * lines; the renderers walk [rows] exactly once, so it may be a cursor.
	 */
	record LongTable(List<String> headers, Iterable<List<String>> rows, List<Float> widths,
			Set<Integer> endAligned) implements ExportBlock {
	}

	record Rule() implements ExportBlock {
	}

	/**
	 * A heading the document itself owns — "Comments", "Profile", "Summary" —
	 * rather than one in the content it carries. Drawn in the letterhead's colour,
	 * and in a spreadsheet the start of a new sheet.
	 */
	record Section(String title) implements ExportBlock {
	}

	/** Labelled values, one per line: an issue's head, an account's profile. */
	record KeyValues(List<KeyValue> rows) implements ExportBlock {
	}

	record KeyValue(String label, String value) {
	}

	/** A quiet line: a byline, an empty section, the note that a file was cut short. */
	record Note(String text) implements ExportBlock {
	}
}
