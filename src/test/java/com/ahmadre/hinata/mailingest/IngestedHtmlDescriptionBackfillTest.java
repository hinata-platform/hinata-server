package com.ahmadre.hinata.mailingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IngestedHtmlDescriptionBackfillTest {

	private static final String HEADER = "Created from e-mail by **noreply@example.com**\n\n---\n\n";

	@Test
	void convertsHtmlBodyButKeepsHeaderVerbatim() {
		String description = HEADER
				+ "<html><head><style>#Eh-f{display:inline-block !important;}</style></head>"
				+ "<body><p>Ihre Anfrage wurde <b>beantwortet</b>.</p></body></html>";

		String rebuilt = IngestedHtmlDescriptionBackfill.rebuild(description);

		assertThat(rebuilt).isNotNull();
		assertThat(rebuilt).startsWith(HEADER);
		assertThat(rebuilt).contains("Ihre Anfrage wurde **beantwortet**.");
		assertThat(rebuilt).doesNotContain("display:inline-block");
		assertThat(rebuilt).doesNotContain("<p>");
	}

	@Test
	void leavesPlainMarkdownDescriptionUntouched() {
		String description = HEADER + "Hallo Team,\n\nbitte um Rückmeldung. Danke!";

		assertThat(IngestedHtmlDescriptionBackfill.rebuild(description)).isNull();
	}

	@Test
	void doesNotMatchOnAngleBracketsInProse() {
		String description = HEADER + "Der Wert ist 3 < 5 und passt.";

		assertThat(IngestedHtmlDescriptionBackfill.rebuild(description)).isNull();
	}

	@Test
	void isIdempotent() {
		String description = HEADER + "<p>Hallo <a href=\"https://example.com\">Welt</a></p>";

		String once = IngestedHtmlDescriptionBackfill.rebuild(description);
		assertThat(once).isNotNull();
		// A second pass finds no HTML markup and reports nothing to do.
		assertThat(IngestedHtmlDescriptionBackfill.rebuild(once)).isNull();
	}

	@Test
	void convertsRawHtmlWhenNoHeaderSeparatorPresent() {
		String description = "<div><p>Body ohne Header</p></div>";

		String rebuilt = IngestedHtmlDescriptionBackfill.rebuild(description);

		assertThat(rebuilt).isNotNull();
		assertThat(rebuilt).contains("Body ohne Header");
		assertThat(rebuilt).doesNotContain("<div>");
	}
}
