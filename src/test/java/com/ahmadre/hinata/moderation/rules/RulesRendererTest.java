package com.ahmadre.hinata.moderation.rules;

import com.ahmadre.hinata.setup.ServerSettings.RulesMode;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The house rules as every language reads them (HIN-175): the built-in rules with all seven rules
 * in all nine languages, and the section on how moderation works after whatever rules apply,
 * saying what the settings say.
 */
class RulesRendererTest {

	private static RulesRenderer renderer() {
		ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
		messages.setBasenames("messages");
		messages.setDefaultEncoding("UTF-8");
		messages.setFallbackToSystemLocale(false);
		return new RulesRenderer(messages);
	}

	private static RulesRenderer.Inputs inputs(RulesMode mode, String text, String url, String email) {
		return new RulesRenderer.Inputs(mode, text, url, "Werkhaus e. V.", email, "https://werkhaus.example/impressum",
				12, "https://api.werkhaus.example/api/v1/public/report", RulesRenderer.TextCheck.DEFAULT);
	}

	@Test
	void theBuiltInRulesHaveAllSevenRulesInEveryLanguage() {
		Map<String, String> rendered = renderer().render(inputs(RulesMode.BUILT_IN, null, null, "moderation@werkhaus.example"));

		assertThat(rendered).containsOnlyKeys(com.ahmadre.hinata.common.Languages.SUPPORTED);
		rendered.forEach((language, markdown) -> {
			for (int rule = 1; rule <= 7; rule++) {
				assertThat(markdown).as(language + " rule " + rule).contains("## " + rule + ". ");
			}
			assertThat(markdown).as(language).doesNotContain("{operator}", "{contact}")
					.contains("Werkhaus e. V.", "mailto:moderation@werkhaus.example");
		});
	}

	@Test
	void howModerationWorksFollowsOwnRulesAndRulesElsewhereToo() {
		RulesRenderer renderer = renderer();
		String own = renderer.render(inputs(RulesMode.TEXT, "# Unsere Regeln\n\nSeid nett.", null, null)).get("de");
		String elsewhere = renderer.render(inputs(RulesMode.URL, null, "https://werkhaus.example/regeln", null)).get("de");

		assertThat(own).startsWith("# Unsere Regeln").contains("## So moderieren wir");
		assertThat(elsewhere).contains("[https://werkhaus.example/regeln](https://werkhaus.example/regeln)")
				.contains("## So moderieren wir");
	}

	private static RulesRenderer.Inputs checking(RulesRenderer.TextCheck check) {
		return new RulesRenderer.Inputs(RulesMode.BUILT_IN, null, null, "Werkhaus e. V.", null, null, 6,
				"https://api.werkhaus.example/api/v1/public/report", check);
	}

	/** "How we moderate" says what the text check does (HIN-176), own terms included and inert. */
	@Test
	void theTextCheckLineFollowsTheSwitchesAndNamesOwnTerms() {
		RulesRenderer renderer = renderer();
		String refuses = renderer.render(checking(RulesRenderer.TextCheck.DEFAULT)).get("de");
		String both = renderer.render(checking(new RulesRenderer.TextCheck(true, true, List.of()))).get("de");
		String off = renderer.render(checking(new RulesRenderer.TextCheck(false, false, List.of()))).get("de");
		String own = renderer.render(checking(new RulesRenderer.TextCheck(false, false,
				List.of("Spast", "[x](javascript:alert(1))")))).get("de");

		assertThat(refuses).contains("**Texte** prüft der Server beim Speichern. Eindeutige Begriffe")
				.doesNotContain("legt er der Moderation vor");
		assertThat(both).contains("Was unklar ist, legt er der Moderation vor, und ein Mensch entscheidet.");
		assertThat(off).contains("**Texte** werden nicht automatisch geprüft.");
		assertThat(own).contains("nur auf diese Begriffe und lehnt sie ab: Spast, \\[x\\]\\(javascript:alert\\(1\\)\\)");
	}

	@Test
	void howModerationWorksSaysWhatTheSettingsSay() {
		String german = renderer().render(inputs(RulesMode.BUILT_IN, null, null, "moderation@werkhaus.example")).get("de");

		assertThat(german)
				.contains("Bilder** werden nicht automatisch geprüft. Gemeldete Bilder prüft ein Mensch.")
				.contains("nach 12 Monaten gelöscht")
				.contains("binnen 24 Stunden")
				.contains("api.werkhaus.example/api/v1/public/report")
				.contains("[https://werkhaus.example/impressum](https://werkhaus.example/impressum)");
		// Without a mailbox the built-in rules point at the form instead of an address.
		String noMailbox = renderer().render(inputs(RulesMode.BUILT_IN, null, null, null)).get("en");
		assertThat(noMailbox).doesNotContain("mailto:", "**Contact.**").contains("Questions are answered").contains("public/report");
	}

	@Test
	void aLinkCannotBreakOutOfItsMarkdown() {
		assertThat(RulesRenderer.link("https://a.example/x (y)")).isEqualTo(
				"[https://a.example/x%20%28y%29](https://a.example/x%20%28y%29)");
	}
}
