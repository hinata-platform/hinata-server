package com.ahmadre.hinata.moderation.image;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.moderation.ModerationCategory;
import com.ahmadre.hinata.moderation.ModerationDecision;
import com.ahmadre.hinata.moderation.ModerationException;
import com.ahmadre.hinata.moderation.ModerationPolicy;
import com.ahmadre.hinata.moderation.ModerationRecorder;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.moderation.ModerationSurface;
import com.ahmadre.hinata.moderation.ModerationVerdict;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The image tier against a real socket.
 *
 * <p>A stub HTTP server rather than a mocked {@code RestClient}, because almost
 * everything worth asserting here is on the wire or in the failure modes of one: a
 * sidecar that hangs, one whose model is not loaded, one that answers with a body
 * this server did not expect. A mock returning a prepared object proves the parser
 * works and nothing about the three cases that decide whether an outage silently
 * stops moderating uploads.
 */
class HttpImageModeratorTest {

	/** Bytes stand in for an image; nothing on this side of the wire decodes them. */
	private static final byte[] IMAGE = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x42 };

	/** Long enough that a healthy sidecar answers well inside it. */
	private static final Duration GENEROUS = Duration.ofSeconds(5);

	/** Short enough that a hung sidecar is a fast test rather than a slow one. */
	private static final Duration IMPATIENT = Duration.ofMillis(250);

	/** The first sidecar; tests that need a second address ask for {@link #anotherSidecar()}. */
	private Sidecar sidecar;

	/** Every sidecar this test started, so none of them outlives it. */
	private final List<Sidecar> sidecars = new ArrayList<>();

	/** The environment half of the configuration, mutated per test. */
	private HinataProperties properties;

	/** The database half — an admin's overrides, as stored on the settings document. */
	private ServerSettings settings;

	private ModerationPolicy policy;

	/**
	 * Registers the tier the way production does — an unconditional bean over the
	 * real policy — so the "no endpoint" test exercises the wiring rather than a
	 * hand-rolled restatement of it.
	 */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(HinataProperties.class)
	@Import(HttpImageModerator.class)
	static class TierConfiguration {

		/** The real resolver over an empty settings document, i.e. environment only. */
		@Bean
		ModerationPolicy moderationPolicy(HinataProperties properties) {
			SettingsService settings = mock(SettingsService.class);
			when(settings.get()).thenReturn(new ServerSettings());
			return new ModerationPolicy(settings, properties);
		}
	}

	private final ApplicationContextRunner contexts = new ApplicationContextRunner()
			.withUserConfiguration(TierConfiguration.class);

	@BeforeEach
	void setUp() throws IOException {
		sidecar = anotherSidecar();
		properties = new HinataProperties();
		settings = new ServerSettings();
		SettingsService settingsService = mock(SettingsService.class);
		when(settingsService.get()).thenReturn(settings);
		policy = new ModerationPolicy(settingsService, properties);
	}

	@AfterEach
	void tearDown() {
		sidecars.forEach(Sidecar::close);
	}

	// --- installation ------------------------------------------------------------

	/**
	 * The default for every self-hosted install: no endpoint anywhere, and a service
	 * that says so out loud instead of reporting a classifier nobody configured.
	 *
	 * <p>The bean is present, which is the change: it used to be
	 * {@code @ConditionalOnProperty} on the endpoint, and an address that can arrive
	 * from the admin panel cannot be a condition evaluated before Mongo is read. So
	 * "not configured" is now the tier's own answer rather than an empty bean list,
	 * and this asserts both halves — an unconditional bean that still reported
	 * {@code ACTIVE} would be the original bug with extra steps.
	 */
	@Test
	void withNoEndpointTheTierIsStillRegisteredAndReportsItselfUnconfigured() {
		contexts.run(context -> {
			assertThat(context).hasSingleBean(ImageModerator.class);
			assertThat(context.getBean(HttpImageModerator.class).configured()).isFalse();
			assertThat(serviceWith(context).imageTierState()).isEqualTo(ImageTierState.NOT_CONFIGURED);
		});
	}

	@Test
	void configuringAnEndpointMakesTheTierReportItselfActive() {
		contexts.withPropertyValues("hinata.moderation.image.endpoint=" + sidecar.baseUrl())
				.run(context -> {
					assertThat(context).hasSingleBean(HttpImageModerator.class);
					assertThat(context.getBean(HttpImageModerator.class).id()).isEqualTo("http-nsfw");
					assertThat(serviceWith(context).imageTierState()).isEqualTo(ImageTierState.ACTIVE);
				});
	}

	// --- where the address comes from ---------------------------------------------

	/**
	 * The case the whole change exists for: an operator who has never touched the
	 * container environment switches the tier on from the admin panel, and uploads
	 * start being classified.
	 */
	@Test
	void anEndpointSetOnlyInTheDatabaseIsUsed() {
		settings.getModeration().setImageEndpoint(sidecar.baseUrl());
		sidecar.classifyReturns(200, """
				{"scores": {"SEXUAL": 91}, "model": "stub", "version": "1.0.0"}""");

		ModerationVerdict verdict = serviceWith(tier())
				.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);

		assertThat(properties.getModeration().getImage().getEndpoint())
				.as("no environment key is set — the address came from the database alone")
				.isEmpty();
		assertThat(sidecar.classifyCalls()).isEqualTo(1);
		assertThat(verdict.decision()).isEqualTo(ModerationDecision.BLOCK);
	}

	/** DB over env, the same precedence every other setting in this product has. */
	@Test
	void aDatabaseEndpointOverridesTheEnvironmentOne() {
		Sidecar fromEnvironment = sidecar;
		Sidecar fromDatabase = anotherSidecar();
		properties.getModeration().getImage().setEndpoint(fromEnvironment.baseUrl());
		settings.getModeration().setImageEndpoint(fromDatabase.baseUrl());

		serviceWith(tier()).assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);

		assertThat(fromDatabase.classifyCalls()).isEqualTo(1);
		assertThat(fromEnvironment.classifyCalls()).isZero();
		assertThat(fromEnvironment.healthCalls())
				.as("the environment sidecar is not even probed while an override is in force")
				.isZero();
	}

	/**
	 * Clearing the field in the panel means "stop overriding", not "switch the tier
	 * off" — the environment value is still the deployment's own default.
	 */
	@Test
	void clearingTheDatabaseEndpointFallsBackToTheEnvironmentOne() {
		Sidecar fromEnvironment = sidecar;
		Sidecar fromDatabase = anotherSidecar();
		properties.getModeration().getImage().setEndpoint(fromEnvironment.baseUrl());
		settings.getModeration().setImageEndpoint(fromDatabase.baseUrl());
		ModerationService service = serviceWith(tier());
		service.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);

		repoint("");

		service.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);

		assertThat(fromEnvironment.classifyCalls()).isEqualTo(1);
		assertThat(fromDatabase.classifyCalls()).isEqualTo(1);
	}

	/** Neither source set is the documented default, and it must not pretend otherwise. */
	@Test
	void withNeitherSourceSetTheTierIsUnconfiguredAndSendsNothing() {
		HttpImageModerator moderator = tier();
		ModerationService service = serviceWith(moderator);

		ModerationVerdict verdict = service.assessImage(IMAGE, "image/jpeg",
				ModerationSurface.ATTACHMENT);

		assertThat(moderator.configured()).isFalse();
		assertThat(moderator.available()).isFalse();
		assertThat(service.imageTierState()).isEqualTo(ImageTierState.NOT_CONFIGURED);
		assertThat(verdict.tier()).isEqualTo(ModerationVerdict.ModerationTier.DISABLED);
		assertThat(sidecar.classifyCalls()).isZero();
	}

	/** The budget is a setting too, and the override has to reach the client that waits. */
	@Test
	void aTimeoutSetInTheDatabaseIsTheOneTheUploadWaitsFor() {
		properties.getModeration().getImage().setEndpoint(sidecar.baseUrl());
		properties.getModeration().getImage().setTimeout(Duration.ofSeconds(30));
		settings.getModeration().setImageTimeout(IMPATIENT);
		sidecar.classifyDelay(Duration.ofSeconds(3));

		ModerationVerdict verdict = serviceWith(tier())
				.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);

		assertThat(verdict.degraded())
				.as("the environment's 30s budget would have waited out the delay and passed clean")
				.isTrue();
	}

	// --- re-pointing without a restart ----------------------------------------------

	/**
	 * The point of the whole change, asserted where it can actually fail: on the
	 * socket.
	 *
	 * <p>A client captured in the constructor keeps its base URL forever, and the
	 * symptom is not an exception — it is bytes arriving at the host the operator
	 * just moved away from, or at nothing at all, while the panel reports the new
	 * address back to them. So this counts requests at two sidecars rather than
	 * reading a getter, which would pass against exactly that bug.
	 */
	@Test
	void repointingTheEndpointMovesTheNextUploadToTheNewSidecarWithoutARestart() {
		Sidecar before = sidecar;
		Sidecar after = anotherSidecar();
		properties.getModeration().getImage().setEndpoint(before.baseUrl());
		ModerationService service = serviceWith(tier());
		service.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);

		repoint(after.baseUrl());

		service.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);

		assertThat(after.classifyCalls())
				.as("the same instance, no restart — the bytes have to reach the new host")
				.isEqualTo(1);
		assertThat(before.classifyCalls()).isEqualTo(1);
	}

	/**
	 * And the cached probe moves with it.
	 *
	 * <p>Health is cached for {@link HttpImageModerator#HEALTH_TTL}, so an operator
	 * who re-points away from a sidecar whose model never loaded would otherwise be
	 * shown that sidecar's answer — and have every upload skipped — for up to half a
	 * minute after fixing the thing they opened the panel to fix.
	 */
	@Test
	void repointingAwayFromABrokenSidecarDoesNotInheritItsCachedHealth() {
		Sidecar broken = sidecar;
		broken.healthReturns(503);
		Sidecar working = anotherSidecar();
		properties.getModeration().getImage().setEndpoint(broken.baseUrl());
		ModerationService service = serviceWith(tier());
		assertThat(service.imageTierState()).isEqualTo(ImageTierState.CONFIGURED_UNAVAILABLE);

		repoint(working.baseUrl());

		assertThat(service.imageTierState()).isEqualTo(ImageTierState.ACTIVE);
		service.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);
		assertThat(working.classifyCalls()).isEqualTo(1);
	}

	// --- the happy path ------------------------------------------------------------

	@Test
	void aScoredResponseFoldsIntoTheVerdict() {
		sidecar.classifyReturns(200, """
				{"scores": {"SEXUAL": 91}, "model": "falconsai-nsfw-vit-int8", "version": "1.0.0"}""");

		ModerationVerdict verdict = serviceWith(moderator(GENEROUS))
				.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);

		assertThat(verdict.primaryCategory()).isEqualTo(ModerationCategory.SEXUAL);
		assertThat(verdict.primary().score()).isEqualTo(91);
		assertThat(verdict.decision()).isEqualTo(ModerationDecision.BLOCK);
		assertThat(verdict.tier()).isEqualTo(ModerationVerdict.ModerationTier.LOCAL_MODEL);
		assertThat(verdict.degraded()).isFalse();
	}

	/** Pins the request half of the contract the sidecar is being built against. */
	@Test
	void theSidecarReceivesTheRawBytesAndTheVerifiedDeclaredType() {
		serviceWith(moderator(GENEROUS)).assessImage(IMAGE, "image/JPEG", ModerationSurface.ATTACHMENT);

		assertThat(sidecar.lastContentType()).isEqualTo("application/octet-stream");
		assertThat(sidecar.lastDeclaredType()).isEqualTo("image/jpeg");
		assertThat(sidecar.lastBody()).isEqualTo(IMAGE);
	}

	/**
	 * A category the sidecar reports but this server does not know must not fail the
	 * upload — a sidecar can ship a new label before the server learns it.
	 */
	@Test
	void anUnknownCategoryIsIgnoredRatherThanFailingTheUpload() {
		sidecar.classifyReturns(200, """
				{"scores": {"SEXUAL": 60, "GAMBLING": 99}, "model": "stub", "version": "1.0.0"}""");

		ModerationVerdict verdict = serviceWith(moderator(GENEROUS))
				.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);

		assertThat(verdict.matches()).hasSize(1);
		assertThat(verdict.primaryCategory()).isEqualTo(ModerationCategory.SEXUAL);
		assertThat(verdict.degraded()).isFalse();
	}

	/**
	 * The one score no image model is permitted to make. A general NSFW classifier
	 * fires on family photos; wired to a non-overridable category that blocks even a
	 * long-form body, it would refuse them as child sexual abuse material and route
	 * them to a human to look at — the exact harm {@link ImageModerator}'s javadoc
	 * says must never be built behind that interface.
	 */
	@Test
	void aChildSexualContentScoreFromAnImageModelIsDropped() {
		sidecar.classifyReturns(200, """
				{"scores": {"SEXUAL": 60, "SEXUAL_MINORS": 99}, "model": "stub", "version": "1.0.0"}""");

		ModerationVerdict verdict = serviceWith(moderator(GENEROUS))
				.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);

		assertThat(verdict.matches())
				.extracting(ModerationVerdict.Match::category)
				.containsExactly(ModerationCategory.SEXUAL);
		assertThat(verdict.decision()).isEqualTo(ModerationDecision.FLAG);
	}

	// --- what the tier refuses to send ------------------------------------------------

	@Test
	void supportedTypesAreMatchedCaseInsensitivelyAndWithoutParameters() {
		HttpImageModerator moderator = moderator(GENEROUS);

		assertThat(moderator.supports("image/jpeg")).isTrue();
		assertThat(moderator.supports("IMAGE/PNG")).isTrue();
		assertThat(moderator.supports("image/webp; charset=binary")).isTrue();
		assertThat(moderator.supports("image/gif")).isFalse();
		assertThat(moderator.supports("application/pdf")).isFalse();
		assertThat(moderator.supports(null)).isFalse();
	}

	/**
	 * An unsupported type is not sent at all: the round trip would end in a 415, and
	 * a degraded verdict queues a file no moderator needs to see. The honest answer
	 * is that the bytes were not judged.
	 */
	@Test
	void anUnsupportedTypeNeverReachesTheSidecar() {
		ModerationVerdict verdict = serviceWith(moderator(GENEROUS))
				.assessImage(IMAGE, "image/gif", ModerationSurface.ATTACHMENT);

		assertThat(verdict.tier()).isEqualTo(ModerationVerdict.ModerationTier.DISABLED);
		assertThat(sidecar.classifyCalls()).isZero();
		assertThat(sidecar.healthCalls()).isZero();
	}

	// --- degradation --------------------------------------------------------------------

	/**
	 * The case the whole degrade path exists for: the classifier is unreachable, the
	 * colleague's upload still succeeds, and the bypass is a queue row rather than a
	 * silent pass.
	 */
	@Test
	void aTimeoutQueuesTheUploadInsteadOfFailingTheWrite() {
		sidecar.classifyDelay(Duration.ofSeconds(3));

		ModerationVerdict verdict = serviceWith(moderator(IMPATIENT))
				.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);

		assertThat(verdict.decision()).isEqualTo(ModerationDecision.ALLOW);
		assertThat(verdict.degraded()).isTrue();
		assertThat(verdict.needsReview()).isTrue();
	}

	@Test
	void aTimeoutDoesNotThrowOnAnInternalUpload() {
		sidecar.classifyDelay(Duration.ofSeconds(3));
		ModerationService service = serviceWith(moderator(IMPATIENT));

		assertThatCode(() -> service.checkImage(IMAGE, "image/jpeg", "screenshot.png",
				ModerationSurface.ATTACHMENT)).doesNotThrowAnyException();
	}

	/**
	 * External ingress is the one place a silent pass is not acceptable: nobody is
	 * accountable for the content and nobody is waiting on the response.
	 */
	@Test
	void aTimeoutOnExternalIngressRefusesRatherThanPasses() {
		sidecar.classifyDelay(Duration.ofSeconds(3));
		ModerationService service = serviceWith(moderator(IMPATIENT));

		assertThatThrownBy(() -> service.assessImage(IMAGE, "image/jpeg",
				ModerationSurface.EMAIL_ATTACHMENT))
				.isInstanceOf(ModerationException.class)
				.hasMessageContaining("unavailable");
	}

	/**
	 * Every documented error status degrades. None of them may return an empty score
	 * map: {@code ModerationService} reads that as "ran, found nothing", which is a
	 * clean pass the classifier never gave.
	 */
	@ParameterizedTest
	@ValueSource(ints = { 413, 415, 422, 500, 503 })
	void anErrorStatusDegradesRatherThanPassingClean(int status) {
		sidecar.classifyReturns(status, "{\"error\":\"nope\"}");

		ModerationVerdict verdict = serviceWith(moderator(GENEROUS))
				.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);

		assertThat(verdict.degraded()).isTrue();
		assertThat(verdict.needsReview()).isTrue();
	}

	@Test
	void aBodyThisServerCannotReadDegradesRatherThanPassingClean() {
		sidecar.classifyReturns(200, "<html>bad gateway</html>");

		assertThat(serviceWith(moderator(GENEROUS))
				.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT).degraded()).isTrue();
	}

	/** A 200 without the scores object is a broken sidecar, not an innocent image. */
	@Test
	void aResponseWithoutScoresDegradesRatherThanPassingClean() {
		sidecar.classifyReturns(200, "{\"model\":\"stub\",\"version\":\"1.0.0\"}");

		assertThat(serviceWith(moderator(GENEROUS))
				.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT).degraded()).isTrue();
	}

	// --- the health probe -------------------------------------------------------------

	/**
	 * {@code assessImage} asks {@code available()} before every upload. If that were
	 * a request, the sidecar would see two calls per attachment and the user would
	 * wait for both — so the probe is cached, and this counts the sockets to prove it.
	 */
	@Test
	void theHealthProbeIsCachedRatherThanRunPerUpload() {
		ModerationService service = serviceWith(moderator(GENEROUS));

		for (int i = 0; i < 5; i++) {
			service.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);
		}
		// Twice more through the admin panel's path, which asks the same question.
		service.imageTierState();
		service.imageTierState();

		assertThat(sidecar.classifyCalls()).isEqualTo(5);
		assertThat(sidecar.healthCalls()).isEqualTo(1);
	}

	/**
	 * A sidecar whose model never loaded is installed and useless, which is a
	 * different thing from absent — and it must not be sent bytes it cannot judge.
	 */
	@Test
	void aSidecarWithNoModelIsUnavailableAndIsNeverAskedToClassify() {
		sidecar.healthReturns(503);
		ModerationService service = serviceWith(moderator(GENEROUS));

		ModerationVerdict verdict = service.assessImage(IMAGE, "image/jpeg",
				ModerationSurface.ATTACHMENT);

		assertThat(verdict.tier()).isEqualTo(ModerationVerdict.ModerationTier.DISABLED);
		assertThat(service.imageTierState()).isEqualTo(ImageTierState.CONFIGURED_UNAVAILABLE);
		assertThat(sidecar.classifyCalls()).isZero();
		assertThat(sidecar.healthCalls()).isEqualTo(1);
	}

	/**
	 * The one thing that may cut the cache short. A 503 from the classifier is the
	 * sidecar contradicting the cached "yes"; keeping it would spend a full timeout
	 * on every upload for the rest of the window to be told the same thing again.
	 */
	@Test
	void aClassifierThatReportsNoModelInvalidatesTheCachedProbe() {
		ModerationService service = serviceWith(moderator(GENEROUS));
		service.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);
		sidecar.classifyReturns(503, "{\"error\":\"model not loaded\"}");

		service.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);
		service.assessImage(IMAGE, "image/jpeg", ModerationSurface.ATTACHMENT);

		assertThat(sidecar.healthCalls()).isEqualTo(2);
	}

	// --- helpers ---------------------------------------------------------------------

	/** The tier pointed at {@link #sidecar} by the environment, with [timeout] as the budget. */
	private HttpImageModerator moderator(Duration timeout) {
		HinataProperties.Moderation.Image image = properties.getModeration().getImage();
		image.setEndpoint(sidecar.baseUrl());
		image.setTimeout(timeout);
		return tier();
	}

	/** The tier over whatever the two configuration halves currently say. */
	private HttpImageModerator tier() {
		return new HttpImageModerator(properties, policy);
	}

	/**
	 * An admin saving a new sidecar address, delivered the way the container would.
	 *
	 * <p>A <em>fresh</em> settings document rather than a mutation of the one the
	 * repository stub returns, deliberately: the policy caches the block it last
	 * resolved, so a test that edited that block in place would pass even if the
	 * event listener were deleted.
	 */
	private void repoint(String endpoint) {
		ServerSettings saved = new ServerSettings();
		saved.getModeration().setImageEndpoint(endpoint);
		policy.onSettingsChanged(new SettingsService.SettingsChangedEvent(saved));
	}

	/** A second (third…) sidecar, so "which host got the bytes" is answerable. */
	private Sidecar anotherSidecar() {
		try {
			Sidecar started = new Sidecar();
			sidecars.add(started);
			return started;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private ModerationService serviceWith(ImageModerator tier) {
		return new ModerationService(policy, mock(ModerationRecorder.class), List.of(), List.of(tier));
	}

	/** The gate as it is actually assembled: whatever tiers the context installed. */
	private ModerationService serviceWith(ApplicationContext context) {
		return new ModerationService(policy, mock(ModerationRecorder.class), List.of(),
				context.getBeanProvider(ImageModerator.class).stream().toList());
	}

	/**
	 * The sidecar, as far as this side of the wire is concerned: two paths, a
	 * settable answer for each, and a count of who asked.
	 */
	private static final class Sidecar implements AutoCloseable {

		private final HttpServer server;
		private final ExecutorService workers = Executors.newCachedThreadPool();
		private final AtomicInteger healthCalls = new AtomicInteger();
		private final AtomicInteger classifyCalls = new AtomicInteger();
		private final AtomicReference<byte[]> lastBody = new AtomicReference<>();
		private final AtomicReference<String> lastDeclaredType = new AtomicReference<>();
		private final AtomicReference<String> lastContentType = new AtomicReference<>();

		private volatile int healthStatus = 200;
		private volatile int classifyStatus = 200;
		private volatile String classifyBody =
				"{\"scores\": {}, \"model\": \"stub\", \"version\": \"1.0.0\"}";
		private volatile Duration classifyDelay = Duration.ZERO;

		Sidecar() throws IOException {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.createContext(HttpImageModerator.HEALTH_PATH, exchange -> {
				healthCalls.incrementAndGet();
				respond(exchange, healthStatus, "{\"status\":\"ok\",\"model\":\"stub\","
						+ "\"version\":\"1.0.0\",\"supported\":[\"image/jpeg\"]}");
			});
			server.createContext(HttpImageModerator.CLASSIFY_PATH, exchange -> {
				classifyCalls.incrementAndGet();
				lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
				lastDeclaredType.set(exchange.getRequestHeaders()
						.getFirst(HttpImageModerator.DECLARED_TYPE_HEADER));
				lastBody.set(exchange.getRequestBody().readAllBytes());
				pause(classifyDelay);
				respond(exchange, classifyStatus, classifyBody);
			});
			server.setExecutor(workers);
			server.start();
		}

		String baseUrl() {
			return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
		}

		void classifyReturns(int status, String body) {
			classifyStatus = status;
			classifyBody = body;
		}

		void healthReturns(int status) {
			healthStatus = status;
		}

		void classifyDelay(Duration delay) {
			classifyDelay = delay;
		}

		int healthCalls() {
			return healthCalls.get();
		}

		int classifyCalls() {
			return classifyCalls.get();
		}

		byte[] lastBody() {
			return lastBody.get();
		}

		String lastDeclaredType() {
			return lastDeclaredType.get();
		}

		String lastContentType() {
			return lastContentType.get();
		}

		private static void respond(HttpExchange exchange, int status, String body) throws IOException {
			byte[] payload = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			try {
				exchange.sendResponseHeaders(status, payload.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(payload);
				}
			}
			catch (IOException ex) {
				// The client hung up first — which is the whole point of the timeout
				// tests, and not something the stub has to have an opinion about.
			}
			finally {
				exchange.close();
			}
		}

		private static void pause(Duration delay) {
			if (delay.isZero() || delay.isNegative()) {
				return;
			}
			try {
				Thread.sleep(delay.toMillis());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}

		@Override
		public void close() {
			server.stop(0);
			workers.shutdownNow();
		}
	}
}
