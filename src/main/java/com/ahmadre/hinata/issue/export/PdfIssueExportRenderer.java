package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.common.ApiException;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
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
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * The printable export. Also what "Print" sends to the platform's print dialog,
 * so there is exactly one layout: a printed issue and a saved PDF of it can
 * never disagree, because they are the same bytes.
 *
 * <p>Rendered with openpdf, matching {@code DataExportPdfService} — the same
 * palette and the same fonts, so the two documents this platform produces look
 * like they came from the same place.
 */
@Slf4j
@Component
class PdfIssueExportRenderer implements IssueExportRenderer {

	private static final Color NAVY = new Color(0x2D, 0x2B, 0x55);
	private static final Color INK = new Color(0x23, 0x22, 0x3F);
	private static final Color MUTED = new Color(0x6B, 0x6A, 0x85);
	private static final Color HEAD_BG = new Color(0xF4, 0xF3, 0xEF);
	private static final Color LINE = new Color(0xE7, 0xE5, 0xDE);

	private static final Font H_KEY = new Font(Font.HELVETICA, 10, Font.BOLD, MUTED);
	private static final Font H_TITLE = new Font(Font.HELVETICA, 20, Font.BOLD, NAVY);
	private static final Font H_SECTION = new Font(Font.HELVETICA, 12, Font.BOLD, NAVY);
	private static final Font BODY = new Font(Font.HELVETICA, 10, Font.NORMAL, INK);
	private static final Font SMALL = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, MUTED);
	private static final Font TH = new Font(Font.HELVETICA, 8, Font.BOLD, NAVY);
	private static final Font TD = new Font(Font.HELVETICA, 9, Font.NORMAL, INK);
	private static final Font MONO = new Font(Font.COURIER, 9, Font.NORMAL, INK);

	@Override
	public IssueExportFormat format() {
		return IssueExportFormat.PDF;
	}

	@Override
	public byte[] render(IssueExport export) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Document document = new Document(PageSize.A4, 42, 42, 46, 52);
		try {
			PdfWriter.getInstance(document, out);
			document.open();
			head(document, export);
			fields(document, export);
			description(document, export);
			comments(document, export);
			links(document, export);
			attachments(document, export);
			activity(document, export);
			footer(document, export);
			document.close();
			return out.toByteArray();
		}
		catch (Exception ex) {
			log.error("Rendering the PDF export of {} failed: {}", export.readableId(), ex.toString());
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error.issue.exportFailed");
		}
		finally {
			if (document.isOpen()) {
				document.close();
			}
		}
	}

	// --- sections ------------------------------------------------------------

	private void head(Document document, IssueExport export) {
		document.add(paragraph(export.readableId(), H_KEY, 0, 2));
		document.add(paragraph(export.title(), H_TITLE, 0, 4));
		String where = export.project().isBlank() ? export.organization()
				: export.project() + (export.organization().isBlank() ? ""
						: " · " + export.organization());
		document.add(paragraph(where, SMALL, 0, 14));
	}

	private void section(Document document, String label) {
		document.add(paragraph(label, H_SECTION, 14, 6));
	}

	private void fields(Document document, IssueExport export) {
		PdfPTable table = table(new float[] { 1.1f, 3f });
		for (IssueExport.Field field : export.fields()) {
			if (!field.value().isBlank()) {
				table.addCell(cell(field.label(), TH, HEAD_BG));
				table.addCell(cell(field.value(), TD, null));
			}
		}
		if (table.getRows().isEmpty()) {
			return;
		}
		document.add(table);
	}

	private void description(Document document, IssueExport export) {
		if (export.description().isEmpty()) {
			return;
		}
		section(document, "Description");
		blocks(document, export.description());
	}

	private void comments(Document document, IssueExport export) {
		if (export.comments().isEmpty()) {
			return;
		}
		section(document, "Comments (" + export.comments().size() + ")");
		for (IssueExport.Comment comment : export.comments()) {
			String meta = comment.author()
					+ (comment.at() == null ? "" : " · " + ExportText.DATE_TIME.format(comment.at()));
			document.add(paragraph(meta, SMALL, 8, 2));
			blocks(document, comment.body());
		}
	}

	private void links(Document document, IssueExport export) {
		if (export.links().isEmpty()) {
			return;
		}
		section(document, "Linked issues");
		PdfPTable table = table(new float[] { 1.1f, 0.8f, 2.4f });
		for (IssueExport.Link link : export.links()) {
			table.addCell(cell(link.verb(), TD, null));
			table.addCell(cell(link.readableId(), TD, null));
			table.addCell(cell(link.title(), TD, null));
		}
		document.add(table);
	}

	private void attachments(Document document, IssueExport export) {
		if (export.attachments().isEmpty()) {
			return;
		}
		section(document, "Attachments");
		PdfPTable table = table(new float[] { 2.2f, 1.4f, 0.7f, 1.2f });
		table.addCell(cell("File", TH, HEAD_BG));
		table.addCell(cell("Type", TH, HEAD_BG));
		table.addCell(cell("Size", TH, HEAD_BG));
		table.addCell(cell("Uploaded by", TH, HEAD_BG));
		for (IssueExport.Attachment file : export.attachments()) {
			table.addCell(cell(file.fileName(), TD, null));
			table.addCell(cell(file.contentType(), TD, null));
			table.addCell(cell(file.size(), TD, null));
			table.addCell(cell(file.uploader(), TD, null));
		}
		document.add(table);
	}

	private void activity(Document document, IssueExport export) {
		if (export.activity().isEmpty()) {
			return;
		}
		section(document, "History");
		PdfPTable table = table(new float[] { 1.2f, 1.1f, 2.7f });
		for (IssueExport.Activity entry : export.activity()) {
			table.addCell(cell(entry.at(), TD, null));
			table.addCell(cell(entry.actor(), TD, null));
			table.addCell(cell(entry.what(), TD, null));
		}
		document.add(table);
	}

	private void footer(Document document, IssueExport export) {
		String org = export.organization().isBlank() ? "hinata" : export.organization();
		Paragraph line = paragraph(org + " · " + ExportText.DATE_TIME.format(export.generatedAt()),
				SMALL, 20, 0);
		line.setAlignment(Element.ALIGN_CENTER);
		document.add(line);
	}

	// --- description blocks --------------------------------------------------

	private void blocks(Document document, List<ExportBlock> blocks) {
		for (ExportBlock block : blocks) {
			switch (block) {
				case ExportBlock.Heading heading -> {
					// Four sizes for six levels: below the third, a heading inside a
					// two-page document is a bold line whatever depth it claims.
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
						paragraph.add(new Phrase(list.ordered() ? (number++) + ". " : "• ", BODY));
						spans(paragraph, item, 10f, false);
						document.add(paragraph);
					}
				}
				case ExportBlock.Code code -> {
					PdfPTable table = table(new float[] { 1f });
					PdfPCell cell = new PdfPCell(new Phrase(code.text(), MONO));
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
				case ExportBlock.Table rendered -> document.add(dataTable(rendered));
				case ExportBlock.Rule ignored -> {
					Paragraph rule = paragraph("———", SMALL, 6, 6);
					rule.setAlignment(Element.ALIGN_CENTER);
					document.add(rule);
				}
			}
		}
	}

	private PdfPTable dataTable(ExportBlock.Table source) {
		int columns = Math.max(1, Math.max(source.headers().size(),
				source.rows().stream().mapToInt(List::size).max().orElse(1)));
		PdfPTable table = table(equalWidths(columns));
		for (String header : source.headers()) {
			table.addCell(cell(header, TH, HEAD_BG));
		}
		// A row shorter than the header leaves the table incomplete, and openpdf
		// renders an incomplete table as nothing at all — so short rows are padded.
		padTo(table, columns, source.headers().size());
		for (List<String> row : source.rows()) {
			for (String value : row) {
				table.addCell(cell(value, TD, null));
			}
			padTo(table, columns, row.size());
		}
		return table;
	}

	private void padTo(PdfPTable table, int columns, int written) {
		for (int i = written; i < columns; i++) {
			table.addCell(cell("", TD, null));
		}
	}

	private static float[] equalWidths(int columns) {
		float[] widths = new float[columns];
		Arrays.fill(widths, 1f);
		return widths;
	}

	private void spans(Paragraph paragraph, List<ExportBlock.Span> spans, float size, boolean bold) {
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
			Font font = new Font(span.code() ? Font.COURIER : Font.HELVETICA, size, style, INK);
			paragraph.add(new Phrase(span.text(), font));
		}
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

	private static PdfPCell cell(String text, Font font, Color background) {
		PdfPCell cell = new PdfPCell(new Phrase(text, font));
		cell.setPadding(5);
		cell.setBorderColor(LINE);
		if (background != null) {
			cell.setBackgroundColor(background);
		}
		return cell;
	}
}
