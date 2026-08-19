package com.ahmadre.hinata.issue.export;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What actually comes out of the four renderers.
 *
 * <p>Each format is opened again with the library its readers use — PDFBox for
 * the PDF, POI for the Word and Excel files, a validating parser for the XML —
 * because "it produced bytes" is not the promise. The promise is that Word,
 * Excel and whatever someone scripted against the XML can read it, and the
 * closest a test can get to that is to read it back with the same parsers those
 * applications are built on.
 */
class IssueExportRenderersTest {

	private static final String HOSTILE_TITLE = "=HYPERLINK(\"http://evil\",\"x\")";

	private final PdfIssueExportRenderer pdf = new PdfIssueExportRenderer();
	private final DocxIssueExportRenderer docx = new DocxIssueExportRenderer();
	private final XlsxIssueExportRenderer xlsx = new XlsxIssueExportRenderer();
	private final XmlIssueExportRenderer xml = new XmlIssueExportRenderer();

	// --- fixtures ------------------------------------------------------------

	private static IssueExport export(String title, List<ExportBlock> description) {
		return new IssueExport(
				"HIN-50", title, "hinata platform",
				List.of(new IssueExport.Field("Status", "In Progress"),
						new IssueExport.Field("Priority", "MAJOR"),
						new IssueExport.Field("Assignees", "Rebar Ahmad")),
				description,
				List.of(new IssueExport.Comment("Lena", Instant.parse("2026-08-19T10:00:00Z"),
						MarkdownBlocks.of("A comment with **weight**."))),
				List.of(new IssueExport.Link("blocks", "HIN-51", "Issues klonen")),
				List.of(new IssueExport.Attachment("shot.png", "image/png", "2.0 KB",
						"Rebar Ahmad", Instant.parse("2026-08-18T09:00:00Z"))),
				List.of(new IssueExport.Activity("2026-08-19 10:00 UTC", "Lena", "STATE: Open → In Progress")),
				"AStA", Instant.parse("2026-08-20T08:00:00Z"));
	}

	private static IssueExport standard() {
		return export("Einzelnes Issue exportieren", MarkdownBlocks.of("""
				# Ziel

				A paragraph with **bold**, *italic* and `code`.

				- first
				- second

				1. one
				2. two

				```dart
				void main() {}
				```

				> quoted

				| Format | Library |
				| --- | --- |
				| docx | POI |
				| pdf | openpdf |

				---
				"""));
	}

	// --- PDF -----------------------------------------------------------------

	@Test
	void thePdfIsReadableAndCarriesEverySection() throws Exception {
		byte[] bytes = pdf.render(standard());

		try (PDDocument document = Loader.loadPDF(bytes)) {
			String text = new PDFTextStripper().getText(document);
			assertThat(text)
					.contains("HIN-50")
					.contains("Einzelnes Issue exportieren")
					.contains("In Progress")
					.contains("Ziel")
					.contains("first")
					.contains("void main()")
					.contains("quoted")
					.contains("openpdf")
					.contains("A comment with weight")
					.contains("HIN-51")
					.contains("shot.png")
					.contains("AStA");
		}
	}

	// --- DOCX ----------------------------------------------------------------

	/**
	 * Opened again with POI, which is the same OPC reader LibreOffice and Word
	 * validate against. A .docx that POI refuses is a .docx neither of them will
	 * open either — which is the whole reason this is a real package and not
	 * HTML wearing a Word content type.
	 */
	@Test
	void theWordDocumentReopensAndCarriesEverySection() throws Exception {
		byte[] bytes = docx.render(standard());

		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
			String text = document.getParagraphs().stream()
					.map(XWPFParagraph::getText)
					.reduce("", (a, b) -> a + "\n" + b);
			assertThat(text)
					.contains("HIN-50")
					.contains("Einzelnes Issue exportieren")
					.contains("Ziel")
					.contains("• first")
					.contains("1. one")
					.contains("void main() {}")
					.contains("quoted")
					.contains("A comment with weight")
					.contains("blocks");
			// The details, attachments and description tables all became tables.
			assertThat(document.getTables()).isNotEmpty();
		}
	}

	@Test
	void emphasisSurvivesIntoWordRatherThanBecomingPlainText() throws Exception {
		byte[] bytes = docx.render(export("t", MarkdownBlocks.of("plain **bold** *italic*")));

		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
			assertThat(document.getParagraphs())
					.flatMap(XWPFParagraph::getRuns)
					.anySatisfy(run -> {
						assertThat(run.getText(0)).isEqualTo("bold");
						assertThat(run.isBold()).isTrue();
					})
					.anySatisfy(run -> {
						assertThat(run.getText(0)).isEqualTo("italic");
						assertThat(run.isItalic()).isTrue();
					});
		}
	}

	// --- XLSX ----------------------------------------------------------------

	/**
	 * The acceptance criterion, end to end: a title that reads as a formula must
	 * arrive in the workbook as a string cell whose text is not evaluated.
	 */
	@Test
	void aTitleThatLooksLikeAFormulaBecomesTextInExcel() throws Exception {
		byte[] bytes = xlsx.render(export(HOSTILE_TITLE, List.of()));

		try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			Cell cell = findValueOf(workbook.getSheet("Fields"), "Title");
			assertThat(cell).isNotNull();
			assertThat(cell.getCellType()).isEqualTo(CellType.STRING);
			assertThat(cell.getStringCellValue()).startsWith("'=");
			assertThatCode(cell::getCellFormula)
					.as("a string cell has no formula to read")
					.isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	void theWorkbookHasAFieldsSheetAndACommentsSheet() throws Exception {
		byte[] bytes = xlsx.render(standard());

		try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			assertThat(workbook.getSheet("Fields")).isNotNull();
			assertThat(workbook.getSheet("Comments")).isNotNull();
			assertThat(findValueOf(workbook.getSheet("Fields"), "Status").getStringCellValue())
					.isEqualTo("In Progress");
			Row comment = workbook.getSheet("Comments").getRow(1);
			assertThat(comment.getCell(1).getStringCellValue()).isEqualTo("Lena");
			assertThat(comment.getCell(2).getStringCellValue()).contains("A comment with weight");
		}
	}

	private static Cell findValueOf(Sheet sheet, String label) {
		for (Row row : sheet) {
			Cell key = row.getCell(0);
			if (key != null && label.equals(key.getStringCellValue())) {
				return row.getCell(1);
			}
		}
		return null;
	}

	// --- XML -----------------------------------------------------------------

	/** The published schema is the contract, so the output is held to it. */
	@Test
	void theXmlValidatesAgainstThePublishedSchema() throws Exception {
		byte[] bytes = xml.render(standard());

		assertThatCode(() -> validate(bytes)).doesNotThrowAnyException();
	}

	/**
	 * And it still validates when a title, a comment and a file name are doing
	 * their best to end an element early — which is the case a hand-written
	 * writer gets wrong.
	 */
	@Test
	void hostileContentCannotBreakTheDocument() throws Exception {
		IssueExport export = new IssueExport(
				"HIN-50", "</issue><script>alert(1)</script>", "a & b",
				List.of(new IssueExport.Field("<name>", "]]>")),
				MarkdownBlocks.of("text with <tags> & \"quotes\""),
				List.of(new IssueExport.Comment("<b>", Instant.parse("2026-08-19T10:00:00Z"),
						MarkdownBlocks.of("]]><!--"))),
				List.of(new IssueExport.Link("\"", "&", "<")),
				List.of(new IssueExport.Attachment("../x\".png", "text/plain", "1 B", "'", null)),
				List.of(), "<org>", Instant.parse("2026-08-20T08:00:00Z"));

		byte[] bytes = xml.render(export);

		assertThatCode(() -> validate(bytes)).doesNotThrowAnyException();
		String text = new String(bytes, StandardCharsets.UTF_8);
		assertThat(text).doesNotContain("<script>").doesNotContain("]]>");
	}

	@Test
	void theDocumentDeclaresItsSchemaVersion() {
		String text = new String(xml.render(standard()), StandardCharsets.UTF_8);

		assertThat(text).contains("<issue version=\"1\"");
		// A shape a consumer can rely on: the blocks keep their kind.
		assertThat(text).contains("<heading level=\"1\">Ziel</heading>")
				.contains("<list ordered=\"false\">")
				.contains("<list ordered=\"true\">")
				.contains("<code language=\"dart\">")
				.contains("<row header=\"true\">");
	}

	private static void validate(byte[] xmlBytes) throws Exception {
		SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
		// The validator reads our own schema and the document under test and
		// nothing else — no DTD, no external entity, no network.
		factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
		factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
		try (InputStream xsd = IssueExportRenderersTest.class
				.getResourceAsStream("/schema/issue-export-v1.xsd")) {
			Schema schema = factory.newSchema(new StreamSource(xsd));
			Validator validator = schema.newValidator();
			validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			try {
				validator.validate(new StreamSource(new ByteArrayInputStream(xmlBytes)));
			}
			catch (SAXException invalid) {
				throw new AssertionError("The export does not match issue-export-v1.xsd: "
						+ invalid.getMessage() + "\n" + new String(xmlBytes, StandardCharsets.UTF_8),
						invalid);
			}
		}
	}
}
