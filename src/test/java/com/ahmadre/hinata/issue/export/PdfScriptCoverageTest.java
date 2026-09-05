package com.ahmadre.hinata.issue.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.Document;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.lowagie.text.pdf.PdfReader;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A PDF must be able to carry the languages this platform speaks.
 *
 * <p>The exports are the one thing here that leaves the screen: an issue printed
 * for a meeting, and the GDPR Art. 15 report a person is legally entitled to.
 * A PDF font that cannot draw the reader's script does not fail — it silently
 * writes nothing, and the reader receives a form with empty fields where their
 * own data should be. So the fonts the renderers use are checked against every
 * script we ship, here, rather than discovered by whoever exports first.
 */
class PdfScriptCoverageTest {

	/** One word per shipped language, in that language's own script. */
	private static final Map<String, String> SAMPLES = new LinkedHashMap<>(Map.of(
			"en", "Data export",
			"de", "Datenexport",
			"es", "Exportación de datos",
			"zh", "数据导出",
			"hi", "डेटा निर्यात"));

	@Test
	void everyScriptWithAnInstalledFontSurvivesTheRoundTrip() throws Exception {
		Map<String, String> unrenderable = new LinkedHashMap<>();
		for (Map.Entry<String, String> sample : SAMPLES.entrySet()) {
			String text = sample.getValue();
			// A build machine without the Noto packages cannot draw these, and
			// that is a property of the machine, not of the code. The container
			// installs them (see Dockerfile); this asserts the code uses them
			// wherever they are present.
			if (ExportFonts.missingFontFor(text) != null) {
				continue;
			}
			if (!renderAndExtract(text).contains(text)) {
				unrenderable.put(sample.getKey(), text);
			}
		}
		assertThat(unrenderable)
				.as("these languages would export a document with their own text "
						+ "missing: %s", unrenderable.keySet())
				.isEmpty();
	}

	/** Latin has no excuse: it works with the built-in face, always. */
	@Test
	void latinNeedsNoInstalledFont() throws Exception {
		assertThat(ExportFonts.missingFontFor("Exportación de datos")).isNull();
		assertThat(renderAndExtract("Exportacion de datos")).contains("Exportacion de datos");
	}

	private String renderAndExtract(String text) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		Document doc = new Document();
		PdfWriter.getInstance(doc, out);
		doc.open();
		doc.add(new Paragraph(text, ExportFonts.forText(text, 12, com.lowagie.text.Font.NORMAL,
				java.awt.Color.BLACK)));
		doc.close();
		PdfReader reader = new PdfReader(out.toByteArray());
		try {
			return new PdfTextExtractor(reader).getTextFromPage(1);
		}
		finally {
			reader.close();
		}
	}
}
