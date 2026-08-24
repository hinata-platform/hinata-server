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
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.List;

/**
 * The Word export: a real {@code .docx}, written with POI's XWPF.
 *
 * <p>Real, rather than Jira's trick of serving an HTML body as
 * {@code application/msword}. That opens in Word and nowhere else — LibreOffice
 * and Google Docs either refuse it or mangle it, and editing the result
 * produces a file that is neither. A document somebody exports is a document
 * somebody will edit.
 *
 * <p>Styling is inline rather than by named style. A .docx written from scratch
 * has no style definitions unless they are authored too, and a heading that
 * refers to a "Heading 2" the document does not define renders as body text in
 * one reader and not in another. Bold and a point size render identically
 * everywhere.
 */
@Slf4j
@Component
class DocxIssueExportRenderer implements IssueExportRenderer {

	private static final String NAVY = "2D2B55";
	private static final String MUTED = "6B6A85";
	private static final String CODE_BG = "F4F3EF";

	/**
	 * The box the organization's mark is contained in, in points — the same one
	 * the PDF uses, so the two documents an export produces show the same mark at
	 * the same size. Contained rather than fitted: a logo is a 6:1 wordmark as
	 * readily as a square signet, and Word draws exactly the box it is given, so
	 * the aspect ratio has to be preserved here or the mark arrives stretched.
	 */
	private static final double LOGO_MAX_H = 32;
	private static final double LOGO_MAX_W = 220;

	@Override
	public IssueExportFormat format() {
		return IssueExportFormat.DOCX;
	}

	@Override
	public byte[] render(IssueExport export) {
		try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			title(document, export);
			fields(document, export);
			if (!export.description().isEmpty()) {
				section(document, "Description");
				blocks(document, export.description());
			}
			comments(document, export);
			links(document, export);
			attachments(document, export);
			activity(document, export);
			footer(document, export);
			document.write(out);
			return out.toByteArray();
		}
		catch (Exception ex) {
			log.error("Rendering the DOCX export of {} failed: {}", export.readableId(), ex.toString());
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error.issue.exportFailed");
		}
	}

	// --- sections ------------------------------------------------------------

	private void title(XWPFDocument document, IssueExport export) {
		logo(document, export);
		XWPFParagraph key = document.createParagraph();
		run(key, export.readableId(), 11, false, NAVY);
		XWPFParagraph title = document.createParagraph();
		run(title, export.title(), 20, true, NAVY);
		XWPFParagraph project = document.createParagraph();
		run(project, export.project(), 10, false, MUTED);
	}

	/**
	 * The organization's mark above the issue key, when there is one.
	 *
	 * <p>Wrapped whole, because {@code addPicture} throws on its own account —
	 * {@link org.apache.poi.openxml4j.exceptions.InvalidFormatException} for bytes
	 * POI will not embed, {@link java.io.IOException} while it copies them into
	 * the package — and {@link #render} turns anything thrown into a 500. A badly
	 * configured logo must cost the letterhead, never the export.
	 */
	private void logo(XWPFDocument document, IssueExport export) {
		byte[] png = export.logo();
		if (png == null || png.length == 0) {
			return;
		}
		try {
			Dimension pixels = pixels(png);
			if (pixels == null) {
				return;
			}
			// Word wants the drawn extent in EMU and applies no rules of its own,
			// so the containment happens here: one scale factor from whichever edge
			// runs out first, applied to both.
			double scale = Math.min(LOGO_MAX_W / pixels.getWidth(), LOGO_MAX_H / pixels.getHeight());
			int width = Units.toEMU(Math.max(1.0, pixels.getWidth() * scale));
			int height = Units.toEMU(Math.max(1.0, pixels.getHeight() * scale));
			XWPFRun run = document.createParagraph().createRun();
			try (ByteArrayInputStream in = new ByteArrayInputStream(png)) {
				run.addPicture(in, PictureType.PNG, "logo.png", width, height);
			}
		}
		catch (Exception ex) {
			log.warn("The organization logo was left out of the DOCX export of {}: {}",
					export.readableId(), ex.toString());
		}
	}

	/**
	 * The logo's real pixel size, or null when nothing here can read it.
	 *
	 * <p>Read from the image's header rather than by decoding it: the picture is
	 * embedded as the bytes that arrived, so the only question asked of it is how
	 * wide and how tall it is, and rasterizing a megapixel image to answer that
	 * would be work thrown away. The stream is a MemoryCache one for the reason it
	 * is everywhere else in this codebase — ImageIO's default spools through a
	 * temp file on disk.
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

	private void section(XWPFDocument document, String label) {
		XWPFParagraph paragraph = document.createParagraph();
		paragraph.setSpacingBefore(240);
		run(paragraph, label, 13, true, NAVY);
	}

	private void fields(XWPFDocument document, IssueExport export) {
		section(document, "Details");
		XWPFTable table = document.createTable(1, 2);
		table.setWidth("100%");
		fill(table.getRow(0), List.of("Field", "Value"), true);
		for (IssueExport.Field field : export.fields()) {
			if (!field.value().isBlank()) {
				fill(table.createRow(), List.of(field.label(), field.value()), false);
			}
		}
	}

	private void comments(XWPFDocument document, IssueExport export) {
		if (export.comments().isEmpty()) {
			return;
		}
		section(document, "Comments (" + export.comments().size() + ")");
		for (IssueExport.Comment comment : export.comments()) {
			XWPFParagraph meta = document.createParagraph();
			meta.setSpacingBefore(160);
			run(meta, comment.author() + " · "
					+ (comment.at() == null ? "" : ExportText.DATE_TIME.format(comment.at())),
					9, true, MUTED);
			blocks(document, comment.body());
		}
	}

	private void links(XWPFDocument document, IssueExport export) {
		if (export.links().isEmpty()) {
			return;
		}
		section(document, "Linked issues");
		for (IssueExport.Link link : export.links()) {
			XWPFParagraph paragraph = document.createParagraph();
			run(paragraph, link.verb() + "  ", 10, true, NAVY);
			run(paragraph, link.readableId() + " " + link.title(), 10, false, null);
		}
	}

	private void attachments(XWPFDocument document, IssueExport export) {
		if (export.attachments().isEmpty()) {
			return;
		}
		section(document, "Attachments");
		XWPFTable table = document.createTable(1, 4);
		table.setWidth("100%");
		fill(table.getRow(0), List.of("File", "Type", "Size", "Uploaded by"), true);
		for (IssueExport.Attachment file : export.attachments()) {
			fill(table.createRow(),
					List.of(file.fileName(), file.contentType(), file.size(), file.uploader()),
					false);
		}
	}

	private void activity(XWPFDocument document, IssueExport export) {
		if (export.activity().isEmpty()) {
			return;
		}
		section(document, "History");
		for (IssueExport.Activity entry : export.activity()) {
			XWPFParagraph paragraph = document.createParagraph();
			run(paragraph, entry.at() + " · " + entry.actor() + " · ", 9, false, MUTED);
			run(paragraph, entry.what(), 9, false, null);
		}
	}

	private void footer(XWPFDocument document, IssueExport export) {
		XWPFParagraph paragraph = document.createParagraph();
		paragraph.setSpacingBefore(320);
		paragraph.setAlignment(ParagraphAlignment.CENTER);
		String org = export.organization().isBlank() ? "hinata" : export.organization();
		run(paragraph, org + " · " + ExportText.DATE_TIME.format(export.generatedAt()),
				8, false, MUTED);
	}

	// --- description blocks --------------------------------------------------

	private void blocks(XWPFDocument document, List<ExportBlock> blocks) {
		for (ExportBlock block : blocks) {
			switch (block) {
				case ExportBlock.Heading heading -> {
					XWPFParagraph paragraph = document.createParagraph();
					paragraph.setSpacingBefore(200);
					// Six levels compressed into four sizes: past the third, a heading
					// in a two-page document is a bold line whatever it is called.
					int size = switch (heading.level()) {
						case 1 -> 16;
						case 2 -> 14;
						case 3 -> 12;
						default -> 11;
					};
					spans(paragraph, heading.spans(), size, true);
				}
				case ExportBlock.Paragraph paragraph ->
						spans(document.createParagraph(), paragraph.spans(), 10, false);
				case ExportBlock.BulletList list -> {
					int number = 1;
					for (List<ExportBlock.Span> item : list.items()) {
						XWPFParagraph paragraph = document.createParagraph();
						paragraph.setIndentationLeft(360);
						// A literal marker rather than Word's numbering definitions: those
						// live in a numbering part this document would have to author, and
						// a list that renders as unindented body text in LibreOffice is
						// worse than one whose bullets are characters.
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
					XWPFTable rendered = document.createTable(1,
							Math.max(1, table.headers().size()));
					rendered.setWidth("100%");
					fill(rendered.getRow(0), table.headers(), true);
					for (List<String> row : table.rows()) {
						fill(rendered.createRow(), row, false);
					}
				}
				case ExportBlock.Rule ignored -> {
					XWPFParagraph paragraph = document.createParagraph();
					run(paragraph, "———", 10, false, MUTED);
				}
			}
		}
	}

	private void spans(XWPFParagraph paragraph, List<ExportBlock.Span> spans, int size, boolean bold) {
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

	/** Writes [values] across [row], creating cells the template row lacks. */
	private static void fill(XWPFTableRow row, List<String> values, boolean head) {
		for (int i = 0; i < values.size(); i++) {
			if (row.getCell(i) == null) {
				row.createCell();
			}
			XWPFParagraph paragraph = row.getCell(i).getParagraphs().get(0);
			XWPFRun run = paragraph.createRun();
			run.setFontSize(head ? 9 : 10);
			run.setBold(head);
			if (head) {
				run.setColor(NAVY);
				row.getCell(i).setColor(CODE_BG);
			}
			run.setText(values.get(i));
		}
	}
}
