package com.ahmadre.hinata.issue.export;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;

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
	private static final String TITLE = "Einzelnes Issue exportieren";

	private final PdfIssueExportRenderer pdf = new PdfIssueExportRenderer();
	private final DocxIssueExportRenderer docx = new DocxIssueExportRenderer();
	private final XlsxIssueExportRenderer xlsx = new XlsxIssueExportRenderer();
	private final XmlIssueExportRenderer xml = new XmlIssueExportRenderer();

	// --- fixtures ------------------------------------------------------------

	private static IssueExport export(String title, List<ExportBlock> description) {
		return export(title, description, null);
	}

	private static IssueExport export(String title, List<ExportBlock> description, byte[] logo) {
		return new IssueExport(
				"HIN-50", title, "hinata platform",
				List.of(new IssueExport.Field("status", "Status", "In Progress"),
						new IssueExport.Field("priority", "Priority", "Major"),
						new IssueExport.Field("assignees", "Assignees", "Rebar Ahmad")),
				description,
				List.of(new IssueExport.Comment("Lena", Instant.parse("2026-08-19T10:00:00Z"),
						MarkdownBlocks.of("A comment with **weight**."))),
				List.of(new IssueExport.Link("blocks", "HIN-51", "Issues klonen")),
				List.of(new IssueExport.Attachment("shot.png", "image/png", "2.0 KB",
						"Rebar Ahmad", Instant.parse("2026-08-18T09:00:00Z"))),
				List.of(new IssueExport.Activity(Instant.parse("2026-08-19T10:00:00Z"), "Lena",
						"Status: Open → In Progress")),
				"AStA", logo, Instant.parse("2026-08-20T08:00:00Z"),
				ExportWordsFixture.english());
	}

	private static IssueExport standard() {
		return export(TITLE, MarkdownBlocks.of("""
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

	// --- the organization logo ----------------------------------------------

	/**
	 * A [width]×[height] PNG, in the shape {@code BrandLogoService.raster()} hands
	 * over: normalized, opaque enough to see, and of an aspect ratio nobody chose.
	 */
	private static byte[] logoPng(int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0x2D, 0x2B, 0x55));
		g.fillRect(0, 0, width, height);
		g.dispose();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		ImageIO.write(image, "png", bytes);
		return bytes.toByteArray();
	}

	/**
	 * No logo is the ordinary case — most instances configure none, and a vector
	 * one cannot be decoded here — so it is the case that has to stay whole.
	 */
	@Test
	void withoutALogoBothDocumentsAreExactlyWhatTheyAlwaysWere() throws Exception {
		try (PDDocument document = Loader.loadPDF(pdf.render(standard()))) {
			assertThat(new PDFTextStripper().getText(document)).contains("HIN-50");
			assertThat(images(document)).isEmpty();
		}
		try (XWPFDocument document = new XWPFDocument(
				new ByteArrayInputStream(docx.render(standard())))) {
			assertThat(document.getAllPictures()).isEmpty();
		}
	}

	@Test
	void aConfiguredLogoIsDrawnIntoThePdfAboveTheIssueKey() throws Exception {
		// The same document twice, differing only in the logo — anything else in it
		// would decide the size comparison instead.
		byte[] plain = pdf.render(export(TITLE, List.of()));
		byte[] branded = pdf.render(export(TITLE, List.of(), logoPng(600, 60)));

		assertThat(branded.length).isGreaterThan(plain.length);
		try (PDDocument document = Loader.loadPDF(branded)) {
			assertThat(images(document)).as("the mark reached the page").hasSize(1);
			assertThat(new PDFTextStripper().getText(document)).contains("HIN-50");
		}
	}

	/**
	 * Word draws exactly the box it is handed, so containment is this renderer's
	 * own job: a 10:1 wordmark has to arrive 10:1 and inside the box, not filling
	 * it. The numbers are the box — 220pt wide, 32pt tall — and the one the wide
	 * mark runs out of first is its width.
	 */
	@Test
	void aWideLogoIsContainedInTheWordDocumentRatherThanStretched() throws Exception {
		byte[] plain = docx.render(export(TITLE, List.of()));
		byte[] branded = docx.render(export(TITLE, List.of(), logoPng(600, 60)));

		assertThat(branded.length).isGreaterThan(plain.length);
		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(branded))) {
			assertThat(document.getAllPictures()).hasSize(1);
			XWPFPicture picture = document.getParagraphs().stream()
					.flatMap(paragraph -> paragraph.getRuns().stream())
					.flatMap(run -> run.getEmbeddedPictures().stream())
					.findFirst().orElseThrow();
			assertThat(picture.getWidth()).isCloseTo(220, within(1.0));
			assertThat(picture.getDepth()).isCloseTo(22, within(1.0));
		}
	}

	/** A tall mark runs out of height first, and is bounded by that edge instead. */
	@Test
	void aTallLogoIsBoundedByTheHeightInstead() throws Exception {
		byte[] branded = docx.render(export("t", List.of(), logoPng(120, 600)));

		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(branded))) {
			XWPFPicture picture = document.getParagraphs().stream()
					.flatMap(paragraph -> paragraph.getRuns().stream())
					.flatMap(run -> run.getEmbeddedPictures().stream())
					.findFirst().orElseThrow();
			assertThat(picture.getDepth()).isCloseTo(32, within(1.0));
			assertThat(picture.getWidth()).isCloseTo(6.4, within(1.0));
		}
	}

	/**
	 * The rule the whole feature hangs on: the logo decorates the export, so bytes
	 * neither library will embed cost the letterhead and nothing else. Reached in
	 * practice by a source that changed under a cached external URL.
	 */
	@Test
	void bytesThatAreNotAnImageCostTheLetterheadAndNotTheExport() throws Exception {
		IssueExport broken = export(TITLE, List.of(),
				"not a picture".getBytes(StandardCharsets.UTF_8));

		try (PDDocument document = Loader.loadPDF(pdf.render(broken))) {
			assertThat(new PDFTextStripper().getText(document)).contains("HIN-50");
			assertThat(images(document)).isEmpty();
		}
		try (XWPFDocument document = new XWPFDocument(
				new ByteArrayInputStream(docx.render(broken)))) {
			assertThat(document.getAllPictures()).isEmpty();
			assertThat(document.getParagraphs()).isNotEmpty();
		}
	}

	/** Every image the first page actually references. */
	private static List<PDImageXObject> images(PDDocument document) throws Exception {
		PDResources resources = document.getPage(0).getResources();
		List<PDImageXObject> found = new ArrayList<>();
		for (COSName name : resources.getXObjectNames()) {
			if (resources.getXObject(name) instanceof PDImageXObject image) {
				found.add(image);
			}
		}
		return found;
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
				List.of(new IssueExport.Field("<key>", "<name>", "]]>")),
				MarkdownBlocks.of("text with <tags> & \"quotes\""),
				List.of(new IssueExport.Comment("<b>", Instant.parse("2026-08-19T10:00:00Z"),
						MarkdownBlocks.of("]]><!--"))),
				List.of(new IssueExport.Link("\"", "&", "<")),
				List.of(new IssueExport.Attachment("../x\".png", "text/plain", "1 B", "'", null)),
				List.of(), "<org>", null, Instant.parse("2026-08-20T08:00:00Z"),
				ExportWordsFixture.english());

		byte[] bytes = xml.render(export);

		assertThatCode(() -> validate(bytes)).doesNotThrowAnyException();
		String text = new String(bytes, StandardCharsets.UTF_8);
		assertThat(text).doesNotContain("<script>").doesNotContain("]]>");
	}

	@Test
	void theDocumentDeclaresItsSchemaVersion() {
		String text = new String(xml.render(standard()), StandardCharsets.UTF_8);

		assertThat(text).contains("<issue version=\"2\"");
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
				.getResourceAsStream("/schema/issue-export-v2.xsd")) {
			Schema schema = factory.newSchema(new StreamSource(xsd));
			Validator validator = schema.newValidator();
			validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
			try {
				validator.validate(new StreamSource(new ByteArrayInputStream(xmlBytes)));
			}
			catch (SAXException invalid) {
				throw new AssertionError("The export does not match issue-export-v2.xsd: "
						+ invalid.getMessage() + "\n" + new String(xmlBytes, StandardCharsets.UTF_8),
						invalid);
			}
		}
	}
}
