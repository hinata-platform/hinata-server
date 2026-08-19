package com.ahmadre.hinata.issue.export;

import java.util.List;

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

	/** A table whose first row is its header. Cells are plain text — a cell is a
	 *  line by construction, so there is no emphasis left to carry. */
	record Table(List<String> headers, List<List<String>> rows) implements ExportBlock {
	}

	record Rule() implements ExportBlock {
	}
}
