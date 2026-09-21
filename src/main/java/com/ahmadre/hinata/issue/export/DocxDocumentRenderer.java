package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.common.usermodel.PictureType;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Dimension;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The Word layout of an {@link ExportDocument}: a real {@code .docx}, written with POI's
 * XWPF.
 *
 * <p>Real, rather than an HTML body served as {@code application/msword}: that opens in
 * Word and nowhere else, and a document somebody exports is a document somebody edits in
 * LibreOffice or Google Docs just as often.
 *
 * <p>Styling is inline rather than by named style. A .docx written from scratch has no
 * style definitions unless they are authored too, and a heading that refers to a
 * "Heading 2" the document does not define renders as body text in one reader and not in
 * another. Bold and a point size render identically everywhere.
 */
@Slf4j
@Component
public class DocxDocumentRenderer implements ExportDocumentRenderer {

	private static final String NAVY = "2D2B55";
	private static final String MUTED = "6B6A85";
	private static final String CODE_BG = "F4F3EF";

	/**
	 * The box the organization's mark is contained in, in points — the same one the PDF
	 * uses. Word draws exactly the box it is given, so the aspect ratio is kept here or the
	 * mark arrives stretched.
	 */
	private static final double LOGO_MAX_H = PdfDocumentRenderer.LOGO_MAX_H;
	private static final double LOGO_MAX_W = PdfDocumentRenderer.LOGO_MAX_W;

	@Override
	public ExportFormat format() {
		return ExportFormat.DOCX;
	}

	@Override
	public void render(ExportDocument export, OutputStream out) {
		try (XWPFDocument document = new XWPFDocument()) {
			head(document, export);
			blocks(document, export.blocks());
			footer(document, export);
			document.write(out);
		}
		catch (Exception ex) {
			log.error("Rendering a DOCX export failed: {}", ex.toString());
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error.issue.exportFailed");
		}
	}

	// --- letterhead ----------------------------------------------------------

	private void head(XWPFDocument document, ExportDocument export) {
		logo(document, export);
		if (!isBlank(export.eyebrow())) {
			run(document.createParagraph(), export.eyebrow(), 11, false, NAVY);
		}
		run(document.createParagraph(), nz(export.title()), 20, true, NAVY);
		if (!isBlank(export.subtitle())) {
			run(document.createParagraph(), export.subtitle(), 10, false, MUTED);
		}
	}

	/**
	 * The organization's mark above the title, when there is one. Wrapped whole, because
	 * {@code addPicture} throws on its own account and {@link #render} turns anything thrown
	 * into a 500: a badly configured logo must cost the letterhead, never the document.
	 */
	private void logo(XWPFDocument document, ExportDocument export) {
		byte[] png = export.logo();
		if (png == null || png.length == 0) {
			return;
		}
		try {
			Dimension pixels = pixels(png);
			if (pixels == null) {
				return;
			}
			// Word wants the drawn extent in EMU and applies no rules of its own, so the
			// containment happens here: one scale factor from whichever edge runs out first.
			double scale = Math.min(LOGO_MAX_W / pixels.getWidth(), LOGO_MAX_H / pixels.getHeight());
			int width = Units.toEMU(Math.max(1.0, pixels.getWidth() * scale));
			int height = Units.toEMU(Math.max(1.0, pixels.getHeight() * scale));
			XWPFRun run = document.createParagraph().createRun();
			try (ByteArrayInputStream in = new ByteArrayInputStream(png)) {
				run.addPicture(in, PictureType.PNG, "logo.png", width, height);
			}
		}
		catch (Exception ex) {
			log.warn("The organization logo was left out of a DOCX export: {}", ex.toString());
		}
	}

	/**
	 * The logo's pixel size from the image header, or null when nothing here can read it.
	 * MemoryCache stream, because ImageIO's default spools through a temp file on disk.
	 */
	private static Dimension pixels(byte[] png) throws Exception {
		try (ImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
			if (!readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(stream);
				return new Dimension(reader.getWidth(0), reader.getHeight(0));
			}
			finally {
				reader.dispose();
			}
		}
	}

	private void footer(XWPFDocument document, ExportDocument export) {
		XWPFParagraph paragraph = document.createParagraph();
		paragraph.setSpacingBefore(320);
		paragraph.setAlignment(ParagraphAlignment.CENTER);
		run(paragraph, export.issuer() + " · " + export.words().instant(export.generatedAt()), 8, false, MUTED);
	}

	// --- blocks --------------------------------------------------------------

	private void blocks(XWPFDocument document, List<ExportBlock> blocks) {
		for (ExportBlock block : blocks) {
			switch (block) {
				case ExportBlock.Section section -> {
					XWPFParagraph paragraph = document.createParagraph();
					paragraph.setSpacingBefore(240);
					run(paragraph, section.title(), 13, true, NAVY);
				}
				case ExportBlock.KeyValues values -> {
					if (!values.rows().isEmpty()) {
						XWPFTable table = document.createTable(values.rows().size(), 2);
						table.setWidth("100%");
						for (int i = 0; i < values.rows().size(); i++) {
							ExportBlock.KeyValue row = values.rows().get(i);
							cell(table.getRow(i), 0, row.label(), true, false);
							cell(table.getRow(i), 1, row.value(), false, false);
						}
					}
				}
				case ExportBlock.Note note -> {
					XWPFParagraph paragraph = document.createParagraph();
					paragraph.setSpacingBefore(160);
					run(paragraph, note.text(), 9, false, MUTED);
				}
				case ExportBlock.Heading heading -> {
					XWPFParagraph paragraph = document.createParagraph();
					paragraph.setSpacingBefore(200);
					// Six levels compressed into four sizes: past the third, a heading in a
					// two-page document is a bold line whatever it is called.
					int size = switch (heading.level()) {
						case 1 -> 16;
						case 2 -> 14;
						case 3 -> 12;
						default -> 11;
					};
					spans(paragraph, heading.spans(), size, true);
				}
				case ExportBlock.Paragraph paragraph -> spans(document.createParagraph(), paragraph.spans(), 10, false);
				case ExportBlock.BulletList list -> {
					int number = 1;
					for (List<ExportBlock.Span> item : list.items()) {
						XWPFParagraph paragraph = document.createParagraph();
						paragraph.setIndentationLeft(360);
						// A literal marker rather than Word's numbering definitions: those live
						// in a numbering part this document would have to author, and a list
						// that renders unindented in LibreOffice is worse than one whose
						// bullets are characters.
						run(paragraph, list.ordered() ? (number++) + ". " : "• ", 10, false, null);
						spans(paragraph, item, 10, false);
					}
				}
				case ExportBlock.Code code -> {
					for (String line : code.text().split("\n", -1)) {
						XWPFParagraph paragraph = document.createParagraph();
						paragraph.setIndentationLeft(360);
						XWPFRun run = paragraph.createRun();
						run.setFontFamily("Courier New");
						run.setFontSize(9);
						run.setText(line);
						run.setTextHighlightColor("lightGray");
					}
				}
				case ExportBlock.Quote quote -> {
					XWPFParagraph paragraph = document.createParagraph();
					paragraph.setIndentationLeft(360);
					spans(paragraph, quote.spans(), 10, false);
					paragraph.getRuns().forEach(run -> run.setItalic(true));
				}
				case ExportBlock.Table table -> {
					XWPFTable rendered = headerRow(document, table.headers(), table.endAligned());
					for (List<String> row : table.rows()) {
						fill(rendered.createRow(), row, table.endAligned());
					}
				}
				case ExportBlock.LongTable table -> {
					XWPFTable rendered = headerRow(document, table.headers(), table.endAligned());
					for (List<String> row : table.rows()) {
						fill(rendered.createRow(), row, table.endAligned());
					}
				}
				case ExportBlock.Rule ignored -> run(document.createParagraph(), "———", 10, false, MUTED);
			}
		}
	}

	private static XWPFTable headerRow(XWPFDocument document, List<String> headers, Set<Integer> endAligned) {
		XWPFTable table = document.createTable(1, Math.max(1, headers.size()));
		table.setWidth("100%");
		for (int i = 0; i < headers.size(); i++) {
			cell(table.getRow(0), i, headers.get(i), true, endAligned.contains(i));
		}
		return table;
	}

	private static void fill(XWPFTableRow row, List<String> values, Set<Integer> endAligned) {
		for (int i = 0; i < values.size(); i++) {
			cell(row, i, values.get(i), false, endAligned.contains(i));
		}
	}

	private static void spans(XWPFParagraph paragraph, List<ExportBlock.Span> spans, int size, boolean bold) {
		for (ExportBlock.Span span : spans) {
			XWPFRun run = paragraph.createRun();
			run.setFontSize(size);
			run.setBold(bold || span.bold());
			run.setItalic(span.italic());
			run.setStrikeThrough(span.strike());
			if (span.code()) {
				run.setFontFamily("Courier New");
			}
			run.setText(span.text());
		}
	}

	// --- helpers -------------------------------------------------------------

	private static void run(XWPFParagraph paragraph, String text, int size, boolean bold, String color) {
		XWPFRun run = paragraph.createRun();
		run.setFontSize(size);
		run.setBold(bold);
		if (color != null) {
			run.setColor(color);
		}
		run.setText(text);
	}

	/** Writes one cell, creating it when the row has fewer than [column] + 1. */
	private static void cell(XWPFTableRow row, int column, String value, boolean head, boolean end) {
		while (row.getCell(column) == null) {
			row.createCell();
		}
		XWPFParagraph paragraph = row.getCell(column).getParagraphs().get(0);
		if (end) {
			paragraph.setAlignment(ParagraphAlignment.RIGHT);
		}
		XWPFRun run = paragraph.createRun();
		run.setFontSize(head ? 9 : 10);
		run.setBold(head);
		if (head) {
			run.setColor(NAVY);
			row.getCell(column).setColor(CODE_BG);
		}
		run.setText(nz(value));
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static String nz(String value) {
		return value == null ? "" : value;
	}
}
