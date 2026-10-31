package com.ahmadre.hinata.richtext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Only the allow-listed CSS survives, each value in one normal form (HIN-181). */
class CssStylesTest {

	@Test
	void block_keepsTheListedDeclarationsInNormalForm() {
		String style = CssStyles.block("Background-Color: #E60000; padding: 12pt 1.5em; border-radius: 8px;"
				+ " max-width: 600px; margin: 0 auto; border-top: 1px solid #CCCCCC; color: white !important");

		assertThat(style).isEqualTo("background-color:#e60000;padding:16px 24px;border-radius:8px;"
				+ "max-width:600px;margin-top:0px;margin-bottom:0px;margin-left:auto;margin-right:auto;"
				+ "border-top:1px solid #cccccc;color:white");
	}

	@Test
	void block_dropsEverythingElse() {
		assertThat(CssStyles.block("position:fixed;top:0;display:none;font-size:40px;z-index:9")).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "background-color:url(https://tracker.example/p.gif)",
			"background-color:expression(alert(1))", "color:var(--x)", "width:calc(100% - 2px)",
			"padding:1px 2px 3px 4px 5px", "border:1px dashed red", "border:none", "width:-5px",
			"margin-left:12px", "background-color:rgb(300,0,0)" })
	void block_refusesValuesItCannotRead(String css) {
		assertThat(CssStyles.block(css)).isEmpty();
	}

	@Test
	void block_takesTheColorOutOfABackgroundShorthandAndDropsItsImage() {
		assertThat(CssStyles.block("background:#fff")).isEqualTo("background-color:#fff");
		assertThat(CssStyles.block("background:#333333 url(https://cdn.example/curve.png) no-repeat top center"))
				.isEqualTo("background-color:#333333");
		assertThat(CssStyles.block("background:url(https://cdn.example/curve.png)")).isEmpty();
	}

	@Test
	void block_takesPercentForWidthOnly() {
		assertThat(CssStyles.block("width:100%;padding:10%")).isEqualTo("width:100%");
	}

	@Test
	void block_takesFitContentAsAWidthOnly() {
		assertThat(CssStyles.block("width:fit-content;margin:0 auto")).isEqualTo(
				"width:fit-content;margin-top:0px;margin-bottom:0px;margin-left:auto;margin-right:auto");
		assertThat(CssStyles.block("max-width:fit-content;padding:fit-content")).isEmpty();
	}

	@Test
	void block_takesAStackBreakpointInPixelsUpToTwoThousand() {
		assertThat(CssStyles.block("--stack-below:480px")).isEqualTo("--stack-below:480px");
		assertThat(CssStyles.block("--stack-below:0px")).isEqualTo("--stack-below:0px");
		for (String refused : new String[] { "--stack-below:2001px", "--stack-below:50%", "--stack-below:30em",
				"--stack-below:-1px", "--stack-below:480", "--stack-below:var(--x)", "--other:1px" }) {
			assertThat(CssStyles.block(refused)).as(refused).isEmpty();
		}
	}

	@Test
	void block_staysWithinItsBound() {
		StringBuilder css = new StringBuilder();
		for (int i = 0; i < 200; i++) css.append("padding:").append(i).append("px;");
		css.append("x".repeat(10_000));

		assertThat(CssStyles.block(css.toString()).length()).isLessThanOrEqualTo(CssStyles.MAX_CHARS);
	}

	@Test
	void text_keepsTheTypography() {
		String style = CssStyles.text("font-family: Arial, \"Helvetica Neue\", sans-serif; font-size: 18pt;"
				+ " font-weight: 700; line-height: 1.4; letter-spacing: 0.5px; text-transform: uppercase;"
				+ " text-decoration: underline; color: rgba(0, 0, 0, 0.8); padding: 4px");

		assertThat(style).isEqualTo("font-family:arial, 'helvetica neue', sans-serif;font-size:24px;"
				+ "font-weight:700;line-height:1.4;letter-spacing:0.5px;text-transform:uppercase;"
				+ "text-decoration:underline;color:rgba(0,0,0,0.8)");
	}

	@Test
	void text_refusesAFontFamilyThatIsNoFontFamily() {
		assertThat(CssStyles.text("font-family:x}body{background:red")).isEmpty();
		assertThat(CssStyles.text("font-family:x;}body{background:red")).as("the rest is no declaration").isEqualTo("font-family:x");
	}
}
