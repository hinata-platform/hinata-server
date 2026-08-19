package com.ahmadre.hinata.issue.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three places an export hands content to something that will interpret it:
 * a spreadsheet cell, an HTTP header, and an XML parser. Each one turns a title
 * somebody typed into syntax somewhere else, which is what makes them the
 * export's attack surface rather than its formatting.
 */
class ExportTextTest {

	// --- spreadsheet cells ---------------------------------------------------

	/**
	 * The acceptance criterion, and the reason this file exists: a title that
	 * looks like a formula has to arrive as text. Excel, LibreOffice and Sheets
	 * all evaluate a cell whose first character is one of these.
	 */
	@Test
	void aCellThatWouldBeAFormulaIsNeutralised() {
		assertThat(ExportText.forSpreadsheet("=HYPERLINK(\"http://evil\",\"x\")"))
				.isEqualTo("'=HYPERLINK(\"http://evil\",\"x\")");
		assertThat(ExportText.forSpreadsheet("+1+1")).startsWith("'");
		assertThat(ExportText.forSpreadsheet("-1+1")).startsWith("'");
		assertThat(ExportText.forSpreadsheet("@SUM(A1)")).startsWith("'");
		assertThat(ExportText.forSpreadsheet("=cmd|'/c calc'!A1")).startsWith("'");
	}

	/** Whitespace first is still a formula to the applications that parse it. */
	@Test
	void leadingWhitespaceDoesNotSmuggleAFormulaThrough() {
		assertThat(ExportText.forSpreadsheet("\t=1+1")).isEqualTo("'=1+1");
		assertThat(ExportText.forSpreadsheet("\r\n=1+1")).isEqualTo("'=1+1");
	}

	@Test
	void ordinaryTextIsLeftExactlyAsItWas() {
		assertThat(ExportText.forSpreadsheet("Kalender & Schichtplanung"))
				.isEqualTo("Kalender & Schichtplanung");
		assertThat(ExportText.forSpreadsheet("2 - 1 is 1")).isEqualTo("2 - 1 is 1");
		assertThat(ExportText.forSpreadsheet("")).isEmpty();
		assertThat(ExportText.forSpreadsheet(null)).isEmpty();
	}

	// --- file names ----------------------------------------------------------

	/**
	 * The title reaches a response header. A newline in it would end that header
	 * and start whatever came after as a second one; a quote would end the
	 * {@code filename="…"} early.
	 */
	@Test
	void aTitleCannotInjectASecondHeader() {
		String stem = ExportText.fileNameStem("HIN-50",
				"evil\r\nX-Injected: yes\"; filename=\"owned");

		assertThat(stem).doesNotContain("\r").doesNotContain("\n").doesNotContain("\"");
	}

	/** And it cannot decide where the saved file lands. */
	@Test
	void aTitleCannotEscapeIntoAPath() {
		String stem = ExportText.fileNameStem("HIN-50", "../../etc/passwd");

		assertThat(stem).doesNotContain("/").doesNotContain("\\").doesNotContain("..");
	}

	/** Letters in any script survive; RFC 5987 encoding is the transport's job. */
	@Test
	void umlautsAndOtherScriptsAreKept() {
		assertThat(ExportText.fileNameStem("HIN-50", "Kalender & Schichtplanung"))
				.isEqualTo("HIN-50-Kalender-Schichtplanung");
		assertThat(ExportText.fileNameStem("HIN-50", "Grüße")).isEqualTo("HIN-50-Grüße");
	}

	@Test
	void aTitleThatIsAllPunctuationStillProducesAName() {
		assertThat(ExportText.fileNameStem("", "///")).isEqualTo("issue");
	}

	@Test
	void aVeryLongTitleIsCutToSomethingAFileSystemAccepts() {
		String stem = ExportText.fileNameStem("HIN-50", "x".repeat(400));

		assertThat(stem.length()).isLessThanOrEqualTo(80);
		assertThat(stem).doesNotEndWith("-");
	}

	// --- XML -----------------------------------------------------------------

	@Test
	void markupInAValueIsEscapedRatherThanEmitted() {
		assertThat(ExportText.forXml("<b>&\"'</b>"))
				.isEqualTo("&lt;b&gt;&amp;&quot;&apos;&lt;/b&gt;");
	}

	/** A section that could be ended early is a document that stops being XML. */
	@Test
	void aCdataTerminatorCannotEscapeItsElement() {
		assertThat(ExportText.forXml("]]><script>")).doesNotContain("<");
	}

	/**
	 * XML 1.0 has no spelling for a control character or a lone surrogate, so
	 * they are dropped — an escape a parser rejects is not an escape.
	 */
	@Test
	void charactersXmlCannotRepresentAreDropped() {
		assertThat(ExportText.forXml("a\u0007b\u0000c")).isEqualTo("abc");
		assertThat(ExportText.forXml("a\uD800b")).isEqualTo("ab");
		// A real pair is a real character and stays.
		assertThat(ExportText.forXml("a🐝b")).isEqualTo("a🐝b");
	}

	@Test
	void tabsAndNewlinesAreLegalAndSurvive() {
		assertThat(ExportText.forXml("a\tb\nc")).isEqualTo("a\tb\nc");
	}
}
