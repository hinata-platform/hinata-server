package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.common.ApiException;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one PDF layout on this server. Issues, the GDPR data export, timesheets and time
 * reports are all drawn here, so they share a letterhead, a palette and — through
 * {@link ExportFonts} — the fonts that make a Cyrillic or Chinese label appear on the page
 * instead of coming out blank.
 *
 * <p>Also what "Print" sends to the platform's print dialog: a printed document and a saved
 * PDF of it are the same bytes.
 */
@Slf4j
@Component
public class PdfDocumentRenderer implements ExportDocumentRenderer {

	private static final Color NAVY = new Color(0x2D, 0x2B, 0x55);
	private static final Color INK = new Color(0x23, 0x22, 0x3F);
	private static final Color MUTED = new Color(0x6B, 0x6A, 0x85);
	private static final Color HEAD_BG = new Color(0xF4, 0xF3, 0xEF);
	private static final Color LINE = new Color(0xE7, 0xE5, 0xDE);

	/**
	 * The box the organization's mark is contained in, in points. Contained, never fitted:
	 * a logo is a 6:1 wordmark as readily as a square signet, and bounding both edges is the
	 * only rule that leaves an unknown aspect ratio recognizable. The height keeps the mark
	 * subordinate to the title — this is stationery, not a cover page.
	 */
	static final float LOGO_MAX_H = 32f;
	static final float LOGO_MAX_W = 220f;

	/**
	 * Rows a long table hands to the page at a time. openpdf keeps a table in memory until
	 * it is added; a table marked incomplete can be added in parts, which is what keeps a
	 * five-thousand-line report from being five thousand rows of cells at once.
	 */
	static final int LONG_TABLE_FLUSH = 200;

	/** The type styles, each drawn in whichever face can carry the text it is given. */
	private enum Style {
		EYEBROW(10, Font.BOLD, MUTED),
		TITLE(20, Font.BOLD, NAVY),
		SECTION(12, Font.BOLD, NAVY),
		BODY(10, Font.NORMAL, INK),
		SMALL(8.5f, Font.NORMAL, MUTED),
		TH(8, Font.BOLD, NAVY),
		TD(9, Font.NORMAL, INK);

		final float size;
		final int weight;
		final Color color;

		Style(float size, int weight, Color color) {
			this.size = size;
			this.weight = weight;
			this.color = color;
		}
	}

	@Override
	public ExportFormat format() {
		return ExportFormat.PDF;
	}

	@Override
	public void render(ExportDocument export, OutputStream out) {
		Document document = new Document(PageSize.A4, 42, 42, 46, 52);
		Fonts fonts = new Fonts();
		try {
			PdfWriter.getInstance(document, out);
			document.open();
			head(document, export, fonts);
			blocks(document, export.blocks(), fonts);
			footer(document, export, fonts);
			document.close();
		}
		catch (ApiException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.error("Rendering a PDF export failed: {}", ex.toString());
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error.issue.exportFailed");
		}
		finally {
			if (document.isOpen()) {
				document.close();
			}
		}
	}

	// --- letterhead ----------------------------------------------------------

	private void head(Document document, ExportDocument export, Fonts fonts) {
		logo(document, export);
		if (!isBlank(export.eyebrow())) {
			document.add(paragraph(export.eyebrow(), fonts.of(Style.EYEBROW, export.eyebrow()), 0, 2));
		}
		document.add(paragraph(nz(export.title()), fonts.of(Style.TITLE, export.title()), 0, 4));
		if (!isBlank(export.subtitle())) {
			document.add(paragraph(export.subtitle(), fonts.of(Style.SMALL, export.subtitle()), 0, 14));
		}
	}

	/**
	 * The organization's mark above the title, when there is one.
	 *
	 * <p>Every failure is silence rather than an exception: {@link #render} turns anything
	 * thrown into a 500, and a logo an admin configured badly must cost the letterhead, never
	 * the document — least of all the GDPR data export somebody is entitled to.
	 */
	private void logo(Document document, ExportDocument export) {
		byte[] png = export.logo();
		if (png == null || png.length == 0) {
			return;
		}
		try {
			Image image = Image.getInstance(png);
			image.scaleToFit(LOGO_MAX_W, LOGO_MAX_H);
			// Carried by a paragraph: openpdf applies spacing to paragraphs only, so a bare
			// image would sit flush against the line under it.
			Paragraph holder = new Paragraph();
			holder.setSpacingAfter(10);
			holder.add(new Chunk(image, 0, 0));
			document.add(holder);
		}
		catch (Exception ex) {
			log.warn("The organization logo was left out of a PDF export: {}", ex.toString());
		}
	}

	private void footer(Document document, ExportDocument export, Fonts fonts) {
		String text = export.issuer() + " · " + export.words().instant(export.generatedAt());
		Paragraph line = paragraph(text, fonts.of(Style.SMALL, text), 20, 0);
		line.setAlignment(Element.ALIGN_CENTER);
		document.add(line);
	}

	// --- blocks --------------------------------------------------------------

	private void blocks(Document document, List<ExportBlock> blocks, Fonts fonts) {
		for (ExportBlock block : blocks) {
			switch (block) {
				case ExportBlock.Section section ->
						document.add(paragraph(section.title(), fonts.of(Style.SECTION, section.title()), 14, 6));
				case ExportBlock.KeyValues values -> keyValues(document, values, fonts);
				case ExportBlock.Note note -> document.add(paragraph(note.text(), fonts.of(Style.SMALL, note.text()), 8, 2));
				case ExportBlock.Heading heading -> {
					// Four sizes for six levels: below the third, a heading inside a two-page
					// document is a bold line whatever depth it claims.
					float size = switch (heading.level()) {
						case 1 -> 14f;
						case 2 -> 12f;
						case 3 -> 11f;
						default -> 10f;
					};
					Paragraph paragraph = new Paragraph();
					paragraph.setSpacingBefore(10);
					paragraph.setSpacingAfter(3);
					spans(paragraph, heading.spans(), size, true);
					document.add(paragraph);
				}
				case ExportBlock.Paragraph text -> {
					Paragraph paragraph = new Paragraph();
					paragraph.setSpacingAfter(6);
					spans(paragraph, text.spans(), 10f, false);
					document.add(paragraph);
				}
				case ExportBlock.BulletList list -> {
					int number = 1;
					for (List<ExportBlock.Span> item : list.items()) {
						Paragraph paragraph = new Paragraph();
						paragraph.setIndentationLeft(14);
						paragraph.setSpacingAfter(2);
						String marker = list.ordered() ? (number++) + ". " : "• ";
						paragraph.add(new Phrase(marker, fonts.of(Style.BODY, marker)));
						spans(paragraph, item, 10f, false);
						document.add(paragraph);
					}
				}
				case ExportBlock.Code code -> {
					PdfPTable table = table(new float[] { 1f });
					PdfPCell cell = new PdfPCell(new Phrase(code.text(), code(code.text())));
					cell.setBackgroundColor(HEAD_BG);
					cell.setBorderColor(LINE);
					cell.setPadding(6);
					table.addCell(cell);
					document.add(table);
				}
				case ExportBlock.Quote quote -> {
					Paragraph paragraph = new Paragraph();
					paragraph.setIndentationLeft(14);
					paragraph.setSpacingAfter(6);
					spans(paragraph, quote.spans(), 10f, false);
					document.add(paragraph);
				}
				case ExportBlock.Table table ->
						document.add(dataTable(table.headers(), table.rows(), table.widths(), table.endAligned(), fonts));
				case ExportBlock.LongTable table -> longTable(document, table, fonts);
				case ExportBlock.Rule ignored -> {
					Paragraph rule = paragraph("———", fonts.of(Style.SMALL, ""), 6, 6);
					rule.setAlignment(Element.ALIGN_CENTER);
					document.add(rule);
				}
			}
		}
	}

	private void keyValues(Document document, ExportBlock.KeyValues values, Fonts fonts) {
		if (values.rows().isEmpty()) {
			return;
		}
		PdfPTable table = table(new float[] { 1.1f, 3f });
		for (ExportBlock.KeyValue row : values.rows()) {
			table.addCell(cell(row.label(), fonts.of(Style.TH, row.label()), HEAD_BG, false));
			table.addCell(cell(row.value(), fonts.of(Style.TD, row.value()), null, false));
		}
		document.add(table);
	}

	private PdfPTable dataTable(List<String> headers, List<List<String>> rows, List<Float> widths,
			Set<Integer> endAligned, Fonts fonts) {
		int columns = Math.max(1, Math.max(headers.size(),
				rows.stream().mapToInt(List::size).max().orElse(1)));
		PdfPTable table = header(headers, columns, widths, endAligned, fonts);
		for (List<String> row : rows) {
			row(table, row, columns, endAligned, fonts);
		}
		return table;
	}

	/**
	 * A table added to the page in parts of {@link #LONG_TABLE_FLUSH} rows, its header
	 * repeated on every page it runs over.
	 */
	private void longTable(Document document, ExportBlock.LongTable source, Fonts fonts) {
		int columns = Math.max(1, source.headers().size());
		PdfPTable table = header(source.headers(), columns, source.widths(), source.endAligned(), fonts);
		table.setComplete(false);
		int pending = 0;
		for (List<String> row : source.rows()) {
			row(table, row, columns, source.endAligned(), fonts);
			if (++pending == LONG_TABLE_FLUSH) {
				document.add(table);
				pending = 0;
			}
		}
		table.setComplete(true);
		document.add(table);
	}

	private PdfPTable header(List<String> headers, int columns, List<Float> widths, Set<Integer> endAligned,
			Fonts fonts) {
		PdfPTable table = table(widthsOf(widths, columns));
		for (int i = 0; i < headers.size(); i++) {
			table.addCell(cell(headers.get(i), fonts.of(Style.TH, headers.get(i)), HEAD_BG, endAligned.contains(i)));
		}
		// A row shorter than the header leaves the table incomplete, and openpdf renders an
		// incomplete table as nothing at all — so short rows are padded.
		padTo(table, columns, headers.size(), fonts);
		table.setHeaderRows(headers.isEmpty() ? 0 : 1);
		return table;
	}

	private void row(PdfPTable table, List<String> row, int columns, Set<Integer> endAligned, Fonts fonts) {
		int written = Math.min(row.size(), columns);
		for (int i = 0; i < written; i++) {
			table.addCell(cell(row.get(i), fonts.of(Style.TD, row.get(i)), null, endAligned.contains(i)));
		}
		padTo(table, columns, written, fonts);
	}

	private void padTo(PdfPTable table, int columns, int written, Fonts fonts) {
		for (int i = written; i < columns; i++) {
			table.addCell(cell("", fonts.of(Style.TD, ""), null, false));
		}
	}

	private static float[] widthsOf(List<Float> widths, int columns) {
		float[] result = new float[columns];
		Arrays.fill(result, 1f);
		if (widths != null && widths.size() == columns) {
			for (int i = 0; i < columns; i++) {
				result[i] = widths.get(i);
			}
		}
		return result;
	}

	private static void spans(Paragraph paragraph, List<ExportBlock.Span> spans, float size, boolean bold) {
		for (ExportBlock.Span span : spans) {
			int style = Font.NORMAL;
			if (bold || span.bold()) {
				style |= Font.BOLD;
			}
			if (span.italic()) {
				style |= Font.ITALIC;
			}
			if (span.strike()) {
				style |= Font.STRIKETHRU;
			}
			Font font = span.code() && ExportFonts.scriptOf(span.text()) == ExportFonts.Script.LATIN
					? new Font(Font.COURIER, size, style, INK)
					: ExportFonts.forText(span.text(), size, style, INK);
			paragraph.add(new Phrase(span.text(), font));
		}
	}

	/** Monospace where the built-in face can draw the text, the script's own face where not. */
	private static Font code(String text) {
		return ExportFonts.scriptOf(text) == ExportFonts.Script.LATIN
				? new Font(Font.COURIER, 9, Font.NORMAL, INK)
				: ExportFonts.forText(text, 9, Font.NORMAL, INK);
	}

	// --- primitives ----------------------------------------------------------

	private static Paragraph paragraph(String text, Font font, float before, float after) {
		Paragraph paragraph = new Paragraph(text, font);
		paragraph.setSpacingBefore(before);
		paragraph.setSpacingAfter(after);
		return paragraph;
	}

	private static PdfPTable table(float[] widths) {
		PdfPTable table = new PdfPTable(widths);
		table.setWidthPercentage(100);
		table.setSpacingBefore(4);
		table.setSpacingAfter(8);
		return table;
	}

	private static PdfPCell cell(String text, Font font, Color background, boolean end) {
		PdfPCell cell = new PdfPCell(new Phrase(nz(text), font));
		cell.setPadding(5);
		cell.setBorderColor(LINE);
		cell.setVerticalAlignment(Element.ALIGN_TOP);
		if (end) {
			cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
		}
		if (background != null) {
			cell.setBackgroundColor(background);
		}
		return cell;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static String nz(String value) {
		return value == null ? "" : value;
	}

	/**
	 * The fonts of one document, by style and script. A report of five thousand rows asks
	 * for forty thousand cell fonts, and each is one of a handful.
	 */
	private static final class Fonts {

		private final Map<Style, Map<ExportFonts.Script, Font>> cache = new EnumMap<>(Style.class);

		Font of(Style style, String text) {
			ExportFonts.Script script = ExportFonts.scriptOf(text);
			return cache.computeIfAbsent(style, key -> new EnumMap<>(ExportFonts.Script.class))
					.computeIfAbsent(script, key -> ExportFonts.forText(text, style.size, style.weight, style.color));
		}
	}
}
