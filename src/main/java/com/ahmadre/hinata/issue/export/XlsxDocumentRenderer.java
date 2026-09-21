package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The workbook layout of an {@link ExportDocument}. Each {@link ExportBlock.Section} opens a
 * sheet of its own, named after it; what comes before the first one shares a sheet with the
 * title.
 *
 * <p>Written with POI's streaming workbook: rows leave memory once a window of them has been
 * written, so a fifty-thousand-line report costs a window, not fifty thousand rows of cells.
 *
 * <p>Every text cell goes through {@link ExportText#forSpreadsheet} and is written as an
 * explicit string: the string type stops a value being read as a number or a date, and the
 * neutralising prefix stops it being read as a formula — the content is what other people
 * typed. Only a column the document marks as figures ({@code endAligned}) is written as a
 * number, and only where the text is a plain number, so a total can be summed where it lands.
 */
@Slf4j
@Component
public class XlsxDocumentRenderer implements ExportDocumentRenderer {

	/** Excel's own hard ceiling; a cell past it makes the file unopenable. */
	private static final int MAX_CELL_CHARS = 32_767;

	/** Rows kept in memory while a sheet is written. */
	private static final int ROW_WINDOW = 200;

	/** Column width a relative width of 1 stands for, in characters. */
	private static final int BASE_WIDTH_CHARS = 16;

	private static final Pattern PLAIN_NUMBER = Pattern.compile("-?\\d{1,12}(\\.\\d{1,6})?");

	@Override
	public ExportFormat format() {
		return ExportFormat.XLSX;
	}

	@Override
	public void render(ExportDocument export, OutputStream out) {
		SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_WINDOW);
		workbook.setCompressTempFiles(true);
		try {
			new Writer(workbook, export).write();
			workbook.write(out);
		}
		catch (ApiException ex) {
			throw ex;
		}
		catch (Exception ex) {
			log.error("Rendering an XLSX export failed: {}", ex.toString());
			throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "error.issue.exportFailed");
		}
		finally {
			workbook.dispose();
			try {
				workbook.close();
			}
			catch (Exception ignored) {
				// The bytes are written or the error above says why; closing adds nothing.
			}
		}
	}

	/** One workbook being written: the current sheet, its next row, and whether it holds content yet. */
	private static final class Writer {

		private final Workbook workbook;
		private final ExportDocument export;
		private final CellStyle header;
		private final CellStyle text;
		private final CellStyle bold;
		private final CellStyle number;
		private Sheet sheet;
		private int next;
		private boolean hasBody;

		Writer(Workbook workbook, ExportDocument export) {
			this.workbook = workbook;
			this.export = export;
			this.header = headerStyle(workbook);
			this.text = textStyle(workbook, false);
			this.bold = textStyle(workbook, true);
			this.number = numberStyle(workbook);
		}

		void write() {
			sheet = workbook.createSheet(sheetName(workbook, export.title()));
			textRow(nz(export.title()), bold);
			if (!isBlank(export.subtitle())) {
				textRow(export.subtitle(), text);
			}
			textRow(export.issuer() + " · " + export.words().instant(export.generatedAt()), text);
			next++;
			for (ExportBlock block : export.blocks()) {
				block(block);
			}
		}

		private void block(ExportBlock block) {
			switch (block) {
				case ExportBlock.Section section -> section(section.title());
				case ExportBlock.KeyValues values -> {
					for (ExportBlock.KeyValue row : values.rows()) {
						Row line = sheet.createRow(next++);
						cell(line, 0, row.label(), bold);
						cell(line, 1, row.value(), text);
					}
					body();
				}
				case ExportBlock.Note note -> bodyText(note.text(), text);
				case ExportBlock.Heading heading -> bodyText(ExportBlock.Span.plain(heading.spans()), bold);
				case ExportBlock.Paragraph paragraph -> bodyText(ExportBlock.Span.plain(paragraph.spans()), text);
				case ExportBlock.Quote quote -> bodyText(ExportBlock.Span.plain(quote.spans()), text);
				case ExportBlock.Code code -> bodyText(code.text(), text);
				case ExportBlock.BulletList list -> {
					for (List<ExportBlock.Span> item : list.items()) {
						bodyText("• " + ExportBlock.Span.plain(item), text);
					}
				}
				case ExportBlock.Table table -> table(table.headers(), table.rows(), table.widths(), table.endAligned());
				case ExportBlock.LongTable table ->
						table(table.headers(), table.rows(), table.widths(), table.endAligned());
				case ExportBlock.Rule ignored -> next++;
			}
		}

		/** A new sheet, or the current one renamed while it holds nothing but the title. */
		private void section(String title) {
			if (hasBody) {
				sheet = workbook.createSheet(sheetName(workbook, title));
				next = 0;
				hasBody = false;
			}
			else {
				workbook.setSheetName(workbook.getSheetIndex(sheet), sheetName(workbook, title));
			}
			textRow(title, bold);
		}

		private void table(List<String> headers, Iterable<List<String>> rows, List<Float> widths,
				Set<Integer> endAligned) {
			if (next > 0 && hasBody) {
				next++;
			}
			Row head = sheet.createRow(next++);
			for (int i = 0; i < headers.size(); i++) {
				cell(head, i, headers.get(i), header);
				float weight = widths != null && widths.size() == headers.size() ? widths.get(i) : 1f;
				sheet.setColumnWidth(i, Math.clamp(Math.round(BASE_WIDTH_CHARS * weight), 8, 80) * 256);
			}
			for (List<String> values : rows) {
				Row row = sheet.createRow(next++);
				for (int i = 0; i < values.size(); i++) {
					String value = values.get(i);
					if (endAligned.contains(i) && value != null && PLAIN_NUMBER.matcher(value).matches()) {
						Cell cell = row.createCell(i, CellType.NUMERIC);
						cell.setCellValue(Double.parseDouble(value));
						cell.setCellStyle(number);
					}
					else {
						cell(row, i, value, text);
					}
				}
			}
			body();
		}

		private void bodyText(String value, CellStyle style) {
			textRow(value, style);
			body();
		}

		private void textRow(String value, CellStyle style) {
			cell(sheet.createRow(next++), 0, value, style);
		}

		private void body() {
			hasBody = true;
		}
	}

	/**
	 * A sheet name Excel will accept: no {@code : \\ / ? * [ ]}, at most 31 characters,
	 * never blank, and not one this workbook already uses. Sanitised rather than validated:
	 * a shortened tab name is a far better outcome than a failed download.
	 */
	static String sheetName(Workbook workbook, String wanted) {
		String cleaned = wanted == null ? "" : wanted.replaceAll("[\\\\/:*?\\[\\]]", " ").trim();
		if (cleaned.isEmpty()) {
			cleaned = "Sheet";
		}
		if (cleaned.length() > 31) {
			cleaned = cleaned.substring(0, 31).trim();
		}
		if (workbook.getSheet(cleaned) == null) {
			return cleaned;
		}
		String stem = cleaned.length() > 29 ? cleaned.substring(0, 29) : cleaned;
		for (int i = 2; i < 100; i++) {
			String candidate = stem + " " + i;
			if (workbook.getSheet(candidate) == null) {
				return candidate;
			}
		}
		return stem + " x";
	}

	/** Writes one cell as text, neutralised and clipped to what Excel can open. */
	private static void cell(Row row, int column, String value, CellStyle style) {
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

	private static CellStyle textStyle(Workbook workbook, boolean bold) {
		CellStyle style = workbook.createCellStyle();
		style.setVerticalAlignment(VerticalAlignment.TOP);
		if (bold) {
			Font font = workbook.createFont();
			font.setBold(true);
			style.setFont(font);
		}
		return style;
	}

	private static CellStyle numberStyle(Workbook workbook) {
		CellStyle style = workbook.createCellStyle();
		style.setAlignment(HorizontalAlignment.RIGHT);
		style.setVerticalAlignment(VerticalAlignment.TOP);
		return style;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static String nz(String value) {
		return value == null ? "" : value;
	}
}
