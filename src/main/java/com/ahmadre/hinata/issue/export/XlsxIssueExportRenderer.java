package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * The spreadsheet export: two sheets, "Fields" and "Comments".
 *
 * <p>One issue in a table is an odd shape and the ticket says so, but it is the
 * shape people ask for — a row per field is what gets pasted into a report or
 * pivoted next to twenty other exports.
 *
 * <p>Every cell of text goes through {@link ExportText#forSpreadsheet} and is
 * written as an explicit string type. Both, not either: the string type stops
 * the value being parsed as a number or a date, and the neutralising prefix
 * stops it being parsed as a <em>formula</em>, which is the one that matters
 * here — the content is a title and comments other people wrote.
 */
@Slf4j
@Component
class XlsxIssueExportRenderer implements IssueExportRenderer {

	/** Excel's own hard ceiling; a cell past it makes the file unopenable. */
	private static final int MAX_CELL_CHARS = 32_767;

	private static final int LABEL_WIDTH = 22 * 256;
	private static final int VALUE_WIDTH = 70 * 256;

	@Override
	public IssueExportFormat format() {
		return IssueExportFormat.XLSX;
	}

	@Override
	public byte[] render(IssueExport export) {
		try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			CellStyle header = headerStyle(workbook);
			CellStyle text = textStyle(workbook);
			fieldsSheet(workbook, export, header, text);
			commentsSheet(workbook, export, header, text);
			workbook.write(out);
			return out.toByteArray();
		}
		catch (Exception ex) {
			log.error("Rendering the XLSX export of {} failed: {}", export.readableId(), ex.toString());
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error.issue.exportFailed");
		}
	}

	private void fieldsSheet(Workbook workbook, IssueExport export, CellStyle header, CellStyle text) {
		Sheet sheet = workbook.createSheet("Fields");
		sheet.setColumnWidth(0, LABEL_WIDTH);
		sheet.setColumnWidth(1, VALUE_WIDTH);
		int r = 0;
		r = headerRow(sheet, r, header, "Field", "Value");
		r = row(sheet, r, text, "Key", export.readableId());
		r = row(sheet, r, text, "Title", export.title());
		r = row(sheet, r, text, "Project", export.project());
		for (IssueExport.Field field : export.fields()) {
			r = row(sheet, r, text, field.label(), field.value());
		}
		r = row(sheet, r, text, "Description", plainText(export.description()));
		if (!export.links().isEmpty()) {
			r = headerRow(sheet, r + 1, header, "Link", "Issue");
			for (IssueExport.Link link : export.links()) {
				r = row(sheet, r, text, link.verb(), link.readableId() + " " + link.title());
			}
		}
		if (!export.attachments().isEmpty()) {
			r = headerRow(sheet, r + 1, header, "Attachment", "Details");
			for (IssueExport.Attachment file : export.attachments()) {
				r = row(sheet, r, text, file.fileName(),
						file.contentType() + " · " + file.size() + " · " + file.uploader());
			}
		}
		if (!export.activity().isEmpty()) {
			r = headerRow(sheet, r + 1, header, "Activity", "Change");
			for (IssueExport.Activity entry : export.activity()) {
				row(sheet, r++, text, entry.at() + " " + entry.actor(), entry.what());
			}
		}
	}

	private void commentsSheet(Workbook workbook, IssueExport export, CellStyle header, CellStyle text) {
		Sheet sheet = workbook.createSheet("Comments");
		sheet.setColumnWidth(0, 24 * 256);
		sheet.setColumnWidth(1, 24 * 256);
		sheet.setColumnWidth(2, 90 * 256);
		Row head = sheet.createRow(0);
		cell(head, 0, header, "Date");
		cell(head, 1, header, "Author");
		cell(head, 2, header, "Comment");
		int r = 1;
		for (IssueExport.Comment comment : export.comments()) {
			Row row = sheet.createRow(r++);
			cell(row, 0, text, comment.at() == null ? "" : ExportText.DATE_TIME.format(comment.at()));
			cell(row, 1, text, comment.author());
			cell(row, 2, text, plainText(comment.body()));
		}
	}

	// --- cells ---------------------------------------------------------------

	private static int headerRow(Sheet sheet, int index, CellStyle style, String left, String right) {
		Row row = sheet.createRow(index);
		cell(row, 0, style, left);
		cell(row, 1, style, right);
		return index + 1;
	}

	private static int row(Sheet sheet, int index, CellStyle style, String label, String value) {
		Row row = sheet.createRow(index);
		cell(row, 0, style, label);
		cell(row, 1, style, value);
		return index + 1;
	}

	/**
	 * Writes one cell as text, neutralised and clipped.
	 *
	 * <p>{@code setCellType(STRING)} before the value is what keeps Excel from
	 * re-interpreting it; the neutralising prefix is what keeps it from running
	 * it. The clip is Excel's own limit — a longer cell does not truncate on
	 * open, it makes the workbook unreadable.
	 */
	private static void cell(Row row, int column, CellStyle style, String value) {
		Cell cell = row.createCell(column, CellType.STRING);
		String text = ExportText.forSpreadsheet(value);
		if (text.length() > MAX_CELL_CHARS) {
			text = text.substring(0, MAX_CELL_CHARS - 1) + "…";
		}
		cell.setCellValue(text);
		cell.setCellStyle(style);
	}

	private static CellStyle headerStyle(Workbook workbook) {
		CellStyle style = workbook.createCellStyle();
		Font font = workbook.createFont();
		font.setBold(true);
		style.setFont(font);
		style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
		style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
		style.setBorderBottom(BorderStyle.THIN);
		return style;
	}

	private static CellStyle textStyle(Workbook workbook) {
		CellStyle style = workbook.createCellStyle();
		style.setWrapText(true);
		style.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);
		return style;
	}

	/** The description as one string — a spreadsheet cell has no headings. */
	private static String plainText(List<ExportBlock> blocks) {
		StringBuilder out = new StringBuilder();
		for (ExportBlock block : blocks) {
			String line = switch (block) {
				case ExportBlock.Heading heading -> ExportBlock.Span.plain(heading.spans());
				case ExportBlock.Paragraph paragraph -> ExportBlock.Span.plain(paragraph.spans());
				case ExportBlock.Quote quote -> ExportBlock.Span.plain(quote.spans());
				case ExportBlock.Code code -> code.text();
				case ExportBlock.BulletList list -> {
					StringBuilder items = new StringBuilder();
					for (List<ExportBlock.Span> item : list.items()) {
						items.append("• ").append(ExportBlock.Span.plain(item)).append('\n');
					}
					yield items.toString().stripTrailing();
				}
				case ExportBlock.Table table -> {
					StringBuilder rows = new StringBuilder(String.join(" | ", table.headers()));
					for (List<String> row : table.rows()) {
						rows.append('\n').append(String.join(" | ", row));
					}
					yield rows.toString();
				}
				case ExportBlock.Rule ignored -> "—";
			};
			if (!line.isBlank()) {
				out.append(line).append("\n\n");
			}
		}
		return out.toString().stripTrailing();
	}
}
