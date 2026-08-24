package com.ahmadre.hinata.me;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.auth.SecurityPolicy;
import com.ahmadre.hinata.issue.IssueWatchService;
import com.ahmadre.hinata.setup.BrandLogoService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two pages the server renders into a real browser. A user only ever sees
 * them after clicking a link in an e-mail, so nothing about the request says
 * which app they came from: the language has to come off {@code Accept-Language}
 * and the branding off the instance's settings, and neither may be able to
 * break the reset flow they decorate.
 *
 * <p>Copy is resolved against the real {@code messages*.properties}, so a key
 * that is missing from either language fails here rather than in a browser.
 */
class MeControllerPagesTest {

	private SettingsService settings;
	private BrandLogoService brandLogo;
	private MeController controller;

	private final ServerSettings serverSettings = new ServerSettings();

	@BeforeEach
	void setUp() {
		settings = mock(SettingsService.class);
		brandLogo = mock(BrandLogoService.class);
		SecurityPolicy securityPolicy = mock(SecurityPolicy.class);

		ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
		messages.setBasename("messages");
		messages.setDefaultEncoding("UTF-8");
		// Mirrors spring.messages.fallback-to-system-locale=false: an untranslated
		// language must land on English, not on the machine's OS locale.
		messages.setFallbackToSystemLocale(false);

		when(settings.get()).thenReturn(serverSettings);
		when(brandLogo.display()).thenReturn(Optional.empty());
		when(securityPolicy.passwordMinLength()).thenReturn(12);

		controller = new MeController(mock(MeService.class), mock(CurrentUser.class),
				mock(UserEvents.class), mock(DataExportPdfService.class), mock(UserService.class),
				mock(IssueWatchService.class), securityPolicy, mock(JwtDecoder.class), settings,
				brandLogo, messages);
	}

	private String resetForm(String acceptLanguage) {
		return controller.passwordResetForm("tok-1", acceptLanguage).getBody();
	}

	@Test
	void rendersGermanForAGermanBrowser() {
		String page = resetForm("de-DE,de;q=0.9");

		assertThat(page).contains("<html lang=\"de\"")
				.contains("Neues Passwort wählen")
				.contains("Neues Passwort (mind. 12 Zeichen)")
				.contains("Passwort aktualisieren")
				.doesNotContain("Choose a new password");
	}

	@Test
	void rendersEnglishForEveryOtherBrowser() {
		assertThat(resetForm("en-GB,en;q=0.8")).contains("<html lang=\"en\"")
				.contains("Choose a new password")
				.contains("New password (min. 12 chars)")
				.contains("Update password");
		// A language we do not translate, and a browser that sends no header at
		// all, both fall back to English rather than to the key.
		assertThat(resetForm("fr-FR")).contains("Choose a new password");
		assertThat(resetForm(null)).contains("Choose a new password");
	}

	@Test
	void localizesTheResultPagesToo() {
		assertThat(controller.confirmEmailChange("tok-1", "de").getBody())
				.contains("E-Mail bestätigt")
				.contains("Anmelde-E-Mail-Adresse");
		assertThat(controller.confirmEmailChange("tok-1", "en").getBody())
				.contains("Email confirmed");
		assertThat(controller.submitPasswordReset("tok-1", "secret", "de").getBody())
				.contains("Passwort aktualisiert");
		assertThat(controller.submitPasswordReset("tok-1", "secret", null).getBody())
				.contains("Password updated");
	}

	@Test
	void showsTheOrganizationNameWhenThereIsNoLogo() {
		serverSettings.setOrganizationName("Acme Ltd");

		assertThat(resetForm("en")).contains(">Acme Ltd</div>").doesNotContain(">hinata</div>");
	}

	@Test
	void escapesTheOrganizationName() {
		serverSettings.setOrganizationName("<script>alert(1)</script> & Co");

		String page = resetForm("en");

		assertThat(page).contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; Co")
				.doesNotContain("<script>");
	}

	@Test
	void showsTheLogoWhenTheInstanceHasUsableBytes() {
		serverSettings.setOrganizationName("Acme \"the\" Ltd");
		when(brandLogo.display()).thenReturn(Optional.of(
				new BrandLogoService.BrandAsset(new byte[] { 1 }, "image/png", true)));

		String page = resetForm("en");

		// Same-origin proxy path, and the name escaped inside the alt attribute.
		assertThat(page).contains("<img src=\"/api/v1/meta/logo\" alt=\"Acme &quot;the&quot; Ltd\"")
				.doesNotContain(">hinata</div>");
	}

	@Test
	void fallsBackToTheWordmarkWhenBrandingIsUnavailable() {
		// A logo that is configured but cannot be fetched leaves BrandLogoService
		// empty, and an unreachable settings store throws: neither may cost the
		// user their password reset.
		when(settings.get()).thenThrow(new IllegalStateException("mongo down"));

		String page = resetForm("en");

		assertThat(page).contains(">hinata</div>").doesNotContain("<img");
	}

	@Test
	void keepsTheFormContractAndTheCspThatLetsTheLogoLoad() {
		var response = controller.passwordResetForm("tok-1", "de");

		assertThat(response.getBody())
				.contains("<form method=\"post\" action=\"/api/v1/me/password-reset/confirm\">")
				.contains("<input type=\"hidden\" name=\"token\" value=\"tok-1\"/>")
				.contains("name=\"password\" minlength=\"12\" required");
		// The chain-wide policy is default-src 'none'; without this header the
		// page loses both its styling and the logo it was just given.
		assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
				.contains("img-src 'self'")
				.contains("style-src 'unsafe-inline'")
				.contains("form-action 'self'");
	}
}
