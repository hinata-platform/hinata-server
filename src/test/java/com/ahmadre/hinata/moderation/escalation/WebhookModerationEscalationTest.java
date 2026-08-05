package com.ahmadre.hinata.moderation.escalation;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.moderation.ModerationCategory;
import com.ahmadre.hinata.moderation.ModerationSurface;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
	 * attribute, and acting on an unattributable one is worse than having no webhook.
	 * So a URL without a secret fails at startup rather than degrading to unsigned
	 * delivery.
	 */
	@Test
	void aUrlWithoutASecretRefusesToStart() {
		HinataProperties properties = new HinataProperties();
		properties.getModeration().getEscalation().setUrl("http://example.invalid/hook");
		properties.getModeration().getEscalation().setSecret("");

		assertThatThrownBy(() -> new WebhookModerationEscalation(properties, audit))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("secret is required");
	}

	// --- helpers --------------------------------------------------------------------

	/** The adapter, delivering on the calling thread so assertions need no sleep. */
	private WebhookModerationEscalation escalation() {
		HinataProperties properties = new HinataProperties();
		HinataProperties.Moderation.Escalation config = properties.getModeration().getEscalation();
		config.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
		config.setSecret(SECRET);
		config.setMaxAttempts(3);
		config.setRetryDelay(Duration.ZERO);
		config.setTimeout(Duration.ofSeconds(2));
		return new WebhookModerationEscalation(properties, audit, Runnable::run);
	}

	private static ModerationEscalation.Event event() {
		return new ModerationEscalation.Event("rec-1", ModerationCategory.SEXUAL_MINORS,
				ModerationSurface.ATTACHMENT, "comment:c-1",
				Instant.parse("2026-08-05T12:00:00Z"));
	}
}
