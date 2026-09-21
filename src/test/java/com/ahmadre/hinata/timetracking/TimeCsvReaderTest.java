package com.ahmadre.hinata.timetracking;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The CSV reader of the time import (HIN-93): quotes, line breaks inside them, and the delimiter a file uses. */
class TimeCsvReaderTest {

	@Test
	void quotedFieldsKeepTheirCommasQuotesAndLineBreaks() throws IOException {
		TimeCsvReader csv = new TimeCsvReader(new StringReader(
				"﻿date,description\r\n2026-03-02,\"Call, then \"\"notes\"\"\nsecond line\"\r\n\r\n2026-03-03,plain\n"));

		assertThat(csv.header()).containsExactly("date", "description");
		assertThat(csv.next()).containsExactly("2026-03-02", "Call, then \"notes\"\nsecond line");
		assertThat(csv.next()).containsExactly("2026-03-03", "plain");
		assertThat(csv.next()).isNull();
	}

	@Test
	void aGermanSpreadsheetsSemicolonsAreTheDelimiter() throws IOException {
		TimeCsvReader csv = new TimeCsvReader(new StringReader("Datum;Stunden;Tags\n02.03.2026;1,5;a, b\n"));

		assertThat(csv.header()).containsExactly("Datum", "Stunden", "Tags");
		assertThat(csv.next()).isEqualTo(List.of("02.03.2026", "1,5", "a, b"));
	}
}
