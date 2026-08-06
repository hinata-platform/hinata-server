package com.ahmadre.hinata.moderation.escalation;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.moderation.ModerationCategory;
import com.ahmadre.hinata.moderation.ModerationPolicy;
import com.ahmadre.hinata.moderation.ModerationSurface;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The escalation webhook: what leaves the machine, and what happens when it does
 * not arrive.
 *
 * <p>Against a real loopback HTTP server rather than a mocked client, because both
 * halves of what is under test are properties of the bytes on the wire. The
 * signature has to verify against the <em>exact</em> body the recipient receives —
 * a test that re-serialises the payload and compares HMACs proves only that the
 * two calls agree with each other — and the assertion that the payload carries no
 * content is worth nothing unless it is made on the body somebody actually got.
 */
class WebhookModerationEscalationTest {

	private static final String SECRET = "shared-escalation-secret";

	private HttpServer server;
	private final List<String> bodies = new ArrayList<>();
	private final List<String> signatures = new ArrayList<>();
	private final AtomicInteger attempts = new AtomicInteger();
	private final AtomicInteger failuresToServe = new AtomicInteger();

	private AuditService audit;

	/** The environment half of the configuration, mutated per test. */
	private HinataProperties properties;

	/** The database half — an admin's overrides, as stored on the settings document. */
	private ServerSettings settings;

	private ModerationPolicy policy;

	@BeforeEach
	void setUp() throws Exception {
		audit = mock(AuditService.class, RETURNS_DEEP_STUBS);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			attempts.incrementAndGet();
			try (InputStream in = exchange.getRequestBody()) {
				bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			signatures.add(exchange.getRequestHeaders()
					.getFirst(WebhookModerationEscalation.SIGNATURE_HEADER));
			int status = failuresToServe.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 500 : 204;
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
		});
		server.start();
		// The baseline fixture is an operator who HAS a webhook, configured the way
		// every deployment could before this was settable: from the environment. The
		// tests about where the address comes from move it, and the two about a
		// missing one clear it.
		properties = new HinataProperties();
		HinataProperties.Moderation.Escalation escalation = properties.getModeration().getEscalation();
		escalation.setUrl(hookUrl());
		escalation.setSecret(SECRET);
		// Three attempts with no wait between them: the retry behaviour is what is
		// under test, the two seconds a real deployment waits are not.
		escalation.setMaxAttempts(3);
		escalation.setRetryDelay(Duration.ZERO);
		escalation.setTimeout(Duration.ofSeconds(2));
		settings = new ServerSettings();
		SettingsService settingsService = mock(SettingsService.class);
		when(settingsService.get()).thenReturn(settings);
		policy = new ModerationPolicy(settingsService, properties);
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	// --- what leaves the machine -------------------------------------------------

	/**
	 * A pointer and a classification. The negative half is the point: no bytes, no
	 * file name, no reporter's note, and above all not the hash programme's
	 * reference — that value identifies adjudicated material inside somebody else's
	 * corpus and must not reach a third-party endpoint.
	 */
	@Test
	void thePayloadCarriesNoContentAndNoHashReference() {
		escalation().escalate(event());

		assertThat(bodies).hasSize(1);
		String body = bodies.getFirst();
		assertThat(body)
				.contains("\"recordId\":\"rec-1\"")
				.contains("\"category\":\"SEXUAL_MINORS\"")
				.contains("\"surface\":\"ATTACHMENT\"")
				.contains("\"reference\":\"comment:c-1\"")
				.contains("\"at\":");
		assertThat(body)
				.doesNotContain("photodna")
				.doesNotContain("REF-9911")
				.doesNotContain("holiday-photo.png")
				.doesNotContain("the reporter's words");
	}

	/**
	 * Verified the way a recipient would: HMAC-SHA256 over the raw body, not over a
	 * re-encoding of the parsed object. A recipient hashing their own serialisation
	 * gets a different string the moment key order differs by one character, and the
	 * failure looks like an attack rather than a bug.
	 */
	@Test
	void theSignatureVerifiesAgainstTheRawBodyTheRecipientReceived() {
		escalation().escalate(event());

		assertThat(signatures).hasSize(1);
		String expected = WebhookModerationEscalation.hmac(
				SECRET.getBytes(StandardCharsets.UTF_8), bodies.getFirst());

		assertThat(signatures.getFirst()).isEqualTo(expected).hasSize(64);
	}

	@Test
	void aDifferentSecretDoesNotVerify() {
		escalation().escalate(event());

		String wrong = WebhookModerationEscalation.hmac(
				"not-the-secret".getBytes(StandardCharsets.UTF_8), bodies.getFirst());

		assertThat(signatures.getFirst()).isNotEqualTo(wrong);
	}

	@Test
	void aSuccessfulDeliveryIsAudited() {
		escalation().escalate(event());

		verify(audit).event(AuditAction.MODERATION_ESCALATED);
	}

	// --- when it does not arrive --------------------------------------------------

	/** At-least-once: a transient failure is retried and the notice still lands. */
	@Test
	void aTransientFailureIsRetriedUntilItSucceeds() {
		failuresToServe.set(2);

		escalation().escalate(event());

		assertThat(attempts).hasValue(3);
		verify(audit).event(AuditAction.MODERATION_ESCALATED);
	}

	/**
	 * Bounded, and the exhausted case is audited rather than logged and forgotten.
	 * This is the failure where the product did everything it could — froze the
	 * content, preserved the bytes — and the person who has to act on it was never
	 * told; a log line is not something an operator can be asked about afterwards.
	 */
	@Test
	void anUndeliverableNoticeIsRetriedABoundedNumberOfTimesAndThenAudited() {
		failuresToServe.set(Integer.MAX_VALUE);

		escalation().escalate(event());

		assertThat(attempts).hasValue(3);
		verify(audit).event(AuditAction.MODERATION_ESCALATION_FAILED);
	}

	/** A delivery failure never propagates: the refusal that raised it already happened. */
	@Test
	void aDeliveryFailureNeverThrows() {
		failuresToServe.set(Integer.MAX_VALUE);

		escalation().escalate(event());

		assertThat(attempts).hasValue(3);
	}

	// --- configuration ------------------------------------------------------------

	/**
	 * An unsigned notice claiming content was frozen is a claim its recipient cannot
	 * attribute, and acting on an unattributable one is worse than having no webhook
	 * at all. That used to be a startup failure — the bean was conditional on the URL
	 * and its constructor threw when no secret was set — and neither half survives an
	 * address an admin can type into a form at 3pm on a Tuesday.
	 *
	 * <p>So the refusal moved rather than disappeared: nothing is sent, and the
	 * undelivered notice is audited like any other, because "the product froze the
	 * material and told nobody" is exactly the case a log line cannot answer for.
	 *
	 * <p>The reason on the audit row is asserted as well, and not for completeness.
	 * An empty HMAC key is refused by the JCE too, so deleting the guard entirely
	 * still sends nothing — it just records the refusal as
	 * {@code IllegalArgumentException: Empty key}, which tells an operator to open a
	 * stack trace rather than to set a secret. The guard is what makes the row
	 * actionable, so the row is what pins it.
	 */
	@Test
	void aUrlWithNoSecretSendsNothingAndAuditsTheNoticeAsUndelivered() {
		properties.getModeration().getEscalation().setSecret("");

		escalation().escalate(event());

		assertThat(attempts).hasValue(0);
		assertThat(bodies).isEmpty();
		verify(audit.event(AuditAction.MODERATION_ESCALATION_FAILED)
				.outcome(AuditLog.Outcome.FAILURE)
				.target("rec-1", "comment:c-1")
				.meta("destination", WebhookModerationEscalation.ID)
				.meta("category", ModerationCategory.SEXUAL_MINORS.name())
				.meta("attempts", "0"))
				.meta("reason", "no signing secret configured");
	}

	/**
	 * No URL anywhere is a supported configuration — a small install may genuinely
	 * have nobody to notify — and it must stay distinguishable from a misconfigured
	 * one. Auditing a failure for the operator's own deliberate choice would bury the
	 * rows that mean something.
	 */
	@Test
	void withNoUrlAnywhereNothingIsSentAndNothingIsAudited() {
		properties.getModeration().getEscalation().setUrl("");
		WebhookModerationEscalation escalation = escalation();

		escalation.escalate(event());

		assertThat(escalation.configured()).isFalse();
		assertThat(attempts).hasValue(0);
		verifyNoInteractions(audit);
	}

	/** The panel's whole purpose: a webhook switched on without touching the container. */
	@Test
	void aUrlAndSecretSetOnlyInTheDatabaseAreUsed() {
		properties.getModeration().getEscalation().setUrl("");
		properties.getModeration().getEscalation().setSecret("");
		settings.getModeration().setEscalationUrl(hookUrl());
		settings.getModeration().setEscalationSecret(SECRET);

		escalation().escalate(event());

		assertThat(properties.getModeration().getEscalation().getUrl())
				.as("no environment key is set — both halves came from the database")
				.isEmpty();
		assertThat(bodies).hasSize(1);
		assertThat(signatures.getFirst()).isEqualTo(
				WebhookModerationEscalation.hmac(SECRET.getBytes(StandardCharsets.UTF_8),
						bodies.getFirst()));
	}

	/**
	 * The retry budget is a setting too. Asserted against a permanently failing
	 * endpoint, because the only observable difference an override makes is how many
	 * times the recipient is tried before the notice is written off.
	 */
	@Test
	void anAttemptBudgetSetInTheDatabaseIsTheOneUsed() {
		settings.getModeration().setEscalationMaxAttempts(5);
		failuresToServe.set(Integer.MAX_VALUE);

		escalation().escalate(event());

		assertThat(attempts)
				.as("the environment's three attempts must not win over the override")
				.hasValue(5);
		verify(audit).event(AuditAction.MODERATION_ESCALATION_FAILED);
	}

	/**
	 * Re-pointing has to reach the socket, not just the getter. A client captured in
	 * the constructor goes on POSTing notices about frozen material to whoever used
	 * to be at the old address.
	 */
	@Test
	void repointingTheWebhookMovesTheNextNoticeWithoutARestart() throws Exception {
		WebhookModerationEscalation escalation = escalation();
		escalation.escalate(event());
		assertThat(attempts).hasValue(1);

		HttpServer moved = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		AtomicInteger movedAttempts = new AtomicInteger();
		moved.createContext("/", exchange -> {
			movedAttempts.incrementAndGet();
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		moved.start();
		try {
			ServerSettings saved = new ServerSettings();
			saved.getModeration()
					.setEscalationUrl("http://127.0.0.1:" + moved.getAddress().getPort() + "/hook");
			saved.getModeration().setEscalationSecret(SECRET);
			policy.onSettingsChanged(new SettingsService.SettingsChangedEvent(saved));

			escalation.escalate(event());

			assertThat(movedAttempts).hasValue(1);
			assertThat(attempts)
					.as("the old endpoint must stop receiving notices about frozen material")
					.hasValue(1);
		}
		finally {
			moved.stop(0);
		}
	}

	// --- helpers --------------------------------------------------------------------

	/** The adapter, delivering on the calling thread so assertions need no sleep. */
	private WebhookModerationEscalation escalation() {
		return new WebhookModerationEscalation(policy, properties, audit, Runnable::run);
	}

	private String hookUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
	}

	private static ModerationEscalation.Event event() {
		return new ModerationEscalation.Event("rec-1", ModerationCategory.SEXUAL_MINORS,
				ModerationSurface.ATTACHMENT, "comment:c-1",
				Instant.parse("2026-08-05T12:00:00Z"));
	}
}
