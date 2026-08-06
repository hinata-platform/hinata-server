package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.auth.SecurityPolicy;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.git.GitIntegrationSettings;
import com.ahmadre.hinata.moderation.ModerationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The admin settings panel's half of making the image tier and the escalation
 * webhook configurable.
 *
 * <p>The interesting assertions are all about the secret, and they are the same
 * three the OIDC, SMTP and Git blocks already have to satisfy — stated here as
 * behaviour rather than assumed from the annotation, because
 * {@code @JsonProperty(WRITE_ONLY)} is one word away from being wrong and the
 * failure is a shared HMAC key in an HTTP response that any admin session can
 * fetch. So the "never echoed" test serialises what the controller actually
 * returns instead of reading a getter.
 */
class AdminModerationSettingsTest {

	private static final String STORED_SECRET = "the-secret-that-is-already-saved";

	/**
	 * Jackson 3, i.e. the databind the API actually serialises with — not the
	 * Jackson 2 that is also on the classpath for a handful of adapters. Reading a
	 * response shape off the wrong one would prove nothing about the wire.
	 */
	private static final ObjectMapper JSON = new ObjectMapper();

	private ServerSettings stored;
	private HinataProperties properties;
	private ModerationPolicy policy;
	private AdminSettingsController controller;

	@BeforeEach
	void setUp() {
		stored = new ServerSettings();
		properties = new HinataProperties();
		SettingsService settings = mock(SettingsService.class);
		when(settings.get()).thenAnswer(invocation -> stored);
		// Saving hands the document back, the way Mongo does.
		when(settings.save(any(ServerSettings.class))).thenAnswer(invocation -> {
			stored = invocation.getArgument(0);
			return stored;
		});
		policy = new ModerationPolicy(settings, properties);
		controller = new AdminSettingsController(settings, properties,
				new GitIntegrationSettings(settings, properties),
				mock(AuditService.class, RETURNS_DEEP_STUBS),
				mock(CurrentUser.class, RETURNS_DEEP_STUBS),
				new SecurityPolicy(settings, properties), policy,
				mock(OrganizationLogoService.class));
	}

	// --- the secret ----------------------------------------------------------------

	/**
	 * The HMAC key a recipient uses to tell a real freeze notice from anyone who
	 * learned the URL. A GET that echoes it hands that distinction to every admin
	 * session, every browser cache and every proxy log between here and the panel.
	 */
	@Test
	void theEscalationSecretIsNeverEchoedByTheSettingsRead() throws Exception {
		stored.getModeration().setEscalationUrl("https://alerts.example.test/hook");
		stored.getModeration().setEscalationSecret(STORED_SECRET);

		String body = JSON.writeValueAsString(controller.get());

		assertThat(body).doesNotContain(STORED_SECRET);
		assertThat(body).doesNotContain("escalationSecret\":");
		assertThat(body)
				.as("the address is not a secret and the panel has to render it")
				.contains("https://alerts.example.test/hook");
	}

	/**
	 * The consequence of never echoing it: the panel PUTs the field back blank on
	 * every save, and a blank field cannot be allowed to mean "delete it". An admin
	 * changing the refusal threshold would otherwise silence the escalation webhook.
	 */
	@Test
	void puttingABlankSecretPreservesTheStoredOne() {
		stored.getModeration().setEscalationUrl("https://alerts.example.test/hook");
		stored.getModeration().setEscalationSecret(STORED_SECRET);

		ServerSettings submitted = new ServerSettings();
		submitted.getModeration().setEscalationUrl("https://alerts.example.test/hook");
		submitted.getModeration().setEscalationSecret("");

		ServerSettings saved = controller.update(submitted);

		assertThat(saved.getModeration().getEscalationSecret()).isEqualTo(STORED_SECRET);
	}

	@Test
	void puttingANewSecretReplacesTheStoredOne() {
		stored.getModeration().setEscalationUrl("https://alerts.example.test/hook");
		stored.getModeration().setEscalationSecret(STORED_SECRET);

		ServerSettings submitted = new ServerSettings();
		submitted.getModeration().setEscalationUrl("https://alerts.example.test/hook");
		submitted.getModeration().setEscalationSecret("rotated");

		assertThat(controller.update(submitted).getModeration().getEscalationSecret())
				.isEqualTo("rotated");
	}

	/**
	 * The one thing the UI may learn about the secret, and it has to be true from
	 * either source — an operator whose secret lives in the environment is not
	 * missing one, and telling them they are would invite them to "fix" it by
	 * typing a different key into the panel and breaking every signature.
	 */
	@Test
	void escalationSecretConfiguredReflectsRealityFromEitherSource() {
		assertThat(controller.get().getModeration().isEscalationSecretConfigured())
				.as("nothing set anywhere")
				.isFalse();

		properties.getModeration().getEscalation().setSecret("from-the-environment");
		assertThat(controller.get().getModeration().isEscalationSecretConfigured())
				.as("the environment alone is a configured secret")
				.isTrue();

		properties.getModeration().getEscalation().setSecret("");
		stored.getModeration().setEscalationSecret(STORED_SECRET);
		assertThat(controller.get().getModeration().isEscalationSecretConfigured())
				.as("the database alone is a configured secret")
				.isTrue();
	}

	// --- the guarantee that used to be a startup failure ----------------------------

	/**
	 * {@code WebhookModerationEscalation} used to refuse to be created when a URL had
	 * no secret behind it. An address typed into this form has no startup to fail at,
	 * so the save is what refuses — an unsigned notice claiming content was frozen is
	 * a claim its recipient cannot attribute.
	 */
	@Test
	void savingAWebhookAddressWithNoSecretAnywhereIsRefused() {
		ServerSettings submitted = new ServerSettings();
		submitted.getModeration().setEscalationUrl("https://alerts.example.test/hook");

		assertThatThrownBy(() -> controller.update(submitted))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.moderation.escalationSecretRequired");
	}

	/** …and is accepted when the secret is only in the environment, which is not missing. */
	@Test
	void savingAWebhookAddressIsAllowedWhenTheSecretComesFromTheEnvironment() {
		properties.getModeration().getEscalation().setSecret("from-the-environment");
		ServerSettings submitted = new ServerSettings();
		submitted.getModeration().setEscalationUrl("https://alerts.example.test/hook");

		assertThatCode(() -> controller.update(submitted)).doesNotThrowAnyException();
	}

	/** No webhook at all stays a supported configuration and saves without complaint. */
	@Test
	void savingWithNoWebhookAddressIsFine() {
		assertThatCode(() -> controller.update(new ServerSettings())).doesNotThrowAnyException();
	}

	// --- what the panel is shown ------------------------------------------------------

	/**
	 * The addresses are pre-filled from the effective configuration, so the form
	 * shows what is actually in force rather than an empty box beside a status line
	 * saying a classifier is running.
	 */
	@Test
	void theTierAddressesArePrefilledFromTheEnvironmentWhenNothingOverridesThem() {
		properties.getModeration().getImage().setEndpoint("http://hinata-moderation:8081");
		properties.getModeration().getImage().setTimeout(Duration.ofSeconds(7));
		properties.getModeration().getEscalation().setUrl("https://alerts.example.test/hook");
		properties.getModeration().getEscalation().setSecret("from-the-environment");
		properties.getModeration().getEscalation().setMaxAttempts(5);

		ServerSettings.Moderation shown = controller.get().getModeration();

		assertThat(shown.getImageEndpoint()).isEqualTo("http://hinata-moderation:8081");
		assertThat(shown.getImageTimeout()).isEqualTo(Duration.ofSeconds(7));
		assertThat(shown.getEscalationUrl()).isEqualTo("https://alerts.example.test/hook");
		assertThat(shown.getEscalationMaxAttempts()).isEqualTo(5);
	}

	/**
	 * The two budgets go over the wire as ISO-8601 durations.
	 *
	 * <p>Pinned because the client parses them and there is nothing else in this
	 * document shaped like a {@link Duration}. Jackson's other option — a decimal
	 * number of seconds — is a serialization feature away, and the panel would start
	 * rendering an empty timeout field with no error anywhere.
	 */
	@Test
	void theBudgetsAreSerialisedAsIsoDurations() throws Exception {
		properties.getModeration().getImage().setEndpoint("http://hinata-moderation:8081");
		properties.getModeration().getImage().setTimeout(Duration.ofSeconds(7));

		String body = JSON.writeValueAsString(controller.get());

		assertThat(body).contains("\"imageTimeout\":\"PT7S\"");
	}

	/** An override wins in the form exactly as it wins in enforcement. */
	@Test
	void anOverrideIsShownRatherThanTheEnvironmentValue() {
		properties.getModeration().getImage().setEndpoint("http://from-the-environment:8081");
		stored.getModeration().setImageEndpoint("http://from-the-panel:8081");

		assertThat(controller.get().getModeration().getImageEndpoint())
				.isEqualTo("http://from-the-panel:8081");
		assertThat(policy.imageEndpoint())
				.as("the form and enforcement must not be able to disagree")
				.isEqualTo("http://from-the-panel:8081");
	}
}
