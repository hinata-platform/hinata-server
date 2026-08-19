package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
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
		XWPFParagraph key = document.createParagraph();
		run(key, export.readableId(), 11, false, NAVY);
		XWPFParagraph title = document.createParagraph();
		run(title, export.title(), 20, true, NAVY);
		XWPFParagraph project = document.createParagraph();
		run(project, export.project(), 10, false, MUTED);
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
		fill(table.getRow(0), "Field", "Value", true);
		for (IssueExport.Field field : export.fields()) {
			if (!field.value().isBlank()) {
				fill(table.createRow(), field.label(), field.value(), false);
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
		XWPFTable table = document.createTable(1, 3);
		table.setWidth("100%");
		fill(table.getRow(0), "File", "Type", "Size", true);
		for (IssueExport.Attachment file : export.attachments()) {
			fill(table.createRow(), file.fileName(), file.contentType(), file.size(), false);
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

	private static void fill(XWPFTableRow row, String left, String right, boolean head) {
		fill(row, List.of(left, right), head);
	}

	private static void fill(XWPFTableRow row, String a, String b, String c, boolean head) {
		fill(row, List.of(a, b, c), head);
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
