package com.ahmadre.hinata.moderation.image;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.moderation.ModerationCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The image tier as a sidecar: the bytes go over HTTP to a process that holds the
 * model, and the scores come back mapped onto {@link ModerationCategory}.
 *
 * <p>Nothing here decides policy. It converts one protocol into another and is
 * deliberately incapable of anything else — the thresholds, the surfaces and the
 * decision live in {@code ModerationPolicy}, so swapping the model behind this
 * endpoint cannot silently change what a user is told when their upload is refused.
 *
 * <h2>Why an unreachable sidecar is an exception rather than an empty result</h2>
 *
 * <p>Every failure path here throws. That looks harsh for an optional tier, and it
 * is the only honest option: {@code ModerationService.assessImage} reads an empty
 * score map as "the classifier ran and found nothing", which is a clean pass the
 * product never actually made. A {@link RuntimeException} instead marks the verdict
 * degraded, so the upload is queued for a human — and on an
 * {@code external()} surface, where {@code failOpen} is false, refused. Returning
 * {@code Map.of()} on a timeout would convert every sidecar outage into a silent
 * bypass that no queue row and no metric would ever show.
 *
 * <p>For the same reason there is no retry. The timeout is a budget on a user's
 * upload, and retrying inside it would triple the wait to reach the same degraded
 * verdict the first failure already produced correctly.
 *
 * <h2>Why health is cached</h2>
 *
 * <p>{@code assessImage} asks {@link #available()} before every single upload, and
 * the admin panel asks {@code imageTierState()} whenever it renders. A probe per
 * call would double the request count against the sidecar and put a second network
 * round trip in front of a byte the user is waiting on, to answer a question whose
 * answer changes on the timescale of a container restart. The answer is therefore
 * kept for {@link #HEALTH_TTL} — and dropped early when a classify call itself
 * returns 503, because at that moment the cached "yes" is known to be wrong.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "hinata.moderation.image", name = "endpoint")
public class HttpImageModerator implements ImageModerator {

	/** Recorded on every verdict this tier produces, so a stored decision names its origin. */
	public static final String ID = "http-nsfw";

	/** Path the sidecar classifies on. */
	static final String CLASSIFY_PATH = "/v1/classify/image";

	/** Path the sidecar reports its model state on. */
	static final String HEALTH_PATH = "/v1/health";

	/**
	 * Carries the type the upload declared, which the server has already verified
	 * against the file's magic bytes. Sent as a header rather than as the request's
	 * own {@code Content-Type} so the body stays an opaque octet stream: the sidecar
	 * must sniff the bytes itself, and a header it can compare against what it found
	 * is more useful to it than one it would be tempted to trust.
	 */
	static final String DECLARED_TYPE_HEADER = "X-Content-Type";

	/**
	 * How long a health answer is trusted. Long enough that a burst of uploads costs
	 * one probe, short enough that a sidecar which came back is used again without an
	 * operator restarting anything.
	 */
	static final Duration HEALTH_TTL = Duration.ofSeconds(30);

	/**
	 * Response bodies are a handful of bytes and never touch the product's own
	 * serialization config, so this parses with its own mapper rather than borrowing
	 * the application one — the same reasoning {@code RichTextService} uses, and it
	 * keeps a Jackson customisation made for the API from changing how a classifier
	 * result is read.
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final String endpoint;
	private final Set<String> supportedTypes;
	private final RestClient http;

	/** Guards the refresh, not the read — see {@link #available()}. */
	private final Object probeLock = new Object();

	private volatile Health health;

	public HttpImageModerator(HinataProperties properties) {
		HinataProperties.Moderation.Image config = properties.getModeration().getImage();
		this.endpoint = trimTrailingSlash(config.getEndpoint());
		this.supportedTypes = config.getSupportedTypes() == null
				? Set.of()
				: config.getSupportedTypes().stream()
						.map(HttpImageModerator::normalise)
						.filter(type -> !type.isEmpty())
						.collect(Collectors.toUnmodifiableSet());
		this.http = client(this.endpoint, config.getTimeout());
		if (this.endpoint.isEmpty()) {
			// @ConditionalOnProperty cannot express "set to something", so an operator
			// who exports the variable empty gets the bean anyway. Say so once instead
			// of letting every upload discover it as a connection failure.
			log.warn("hinata.moderation.image.endpoint is set but empty — the image tier is installed "
					+ "and will classify nothing. Unset the property entirely, or point it at a sidecar.");
		}
		else {
			log.info("Image moderation sidecar configured at {} for {}", this.endpoint, this.supportedTypes);
		}
	}

	@Override
	public String id() {
		return ID;
	}

	/**
	 * Whether the sidecar can judge [contentType] at all.
	 *
	 * <p>Answered from configuration rather than by asking, because the alternative
	 * costs a round trip to learn something that does not change, and because a type
	 * the sidecar would reject must not reach it: an unsupported upload should be an
	 * honest "not judged", not a 415 that degrades the verdict and queues a file
	 * nobody needs to look at.
	 */
	@Override
	public boolean supports(String contentType) {
		return !endpoint.isEmpty() && supportedTypes.contains(normalise(contentType));
	}

	/**
	 * Whether the sidecar currently has its model loaded, from a cached probe.
	 *
	 * <p>The lock is only taken to refresh. Readers inside the TTL — which is
	 * every upload but the first of each window — see the volatile field and never
	 * touch it, and the threads that arrive together at an expiry double-check inside
	 * the lock so exactly one of them probes.
	 */
	@Override
	public boolean available() {
		if (endpoint.isEmpty()) {
			return false;
		}
		Health current = health;
		if (current != null && current.fresh()) {
			return current.healthy();
		}
		synchronized (probeLock) {
			current = health;
			if (current != null && current.fresh()) {
				return current.healthy();
			}
			Health probed = probe();
			health = probed;
			return probed.healthy();
		}
	}

	/**
	 * Classifies [data], which the caller has already bounded and magic-byte checked.
	 *
	 * @throws IllegalStateException on any answer that is not a scored classification
	 *                               — the verdict degrades rather than passing clean
	 */
	@Override
	public Map<ModerationCategory, Integer> score(byte[] data, String contentType) {
		if (endpoint.isEmpty()) {
			throw new IllegalStateException("no image moderation endpoint configured");
		}
		String body;
		try {
			body = http.post()
					.uri(CLASSIFY_PATH)
					.contentType(MediaType.APPLICATION_OCTET_STREAM)
					.header(DECLARED_TYPE_HEADER, normalise(contentType))
					.body(data)
					.retrieve()
					.body(String.class);
		}
		catch (RestClientResponseException ex) {
			int status = ex.getStatusCode().value();
			if (status == 503) {
				// The sidecar just told us its model is gone, which contradicts the
				// answer we are caching. Drop it so the next upload probes instead of
				// spending a full timeout finding out again.
				health = null;
			}
			throw new IllegalStateException("image classifier answered HTTP " + status);
		}
		catch (RestClientException ex) {
			// Timeouts and connection failures. The message names the endpoint and the
			// failure, never the payload — this string reaches a log line.
			throw new IllegalStateException("image classifier at " + endpoint + " failed: "
					+ ex.getClass().getSimpleName());
		}
		return parse(body);
	}

	/**
	 * Reads the {@code scores} object into policy categories.
	 *
	 * <p>Unrecognised names are dropped rather than rejected: a sidecar that learns a
	 * new label before this server does should not start failing every upload. A
	 * <em>missing</em> {@code scores} object is different and does fail, because
	 * "this body does not speak the contract" must not be read as "nothing fired".
	 */
	private Map<ModerationCategory, Integer> parse(String body) {
		if (body == null || body.isBlank()) {
			throw new IllegalStateException("image classifier returned an empty body");
		}
		JsonNode scores;
		try {
			scores = MAPPER.readTree(body).get("scores");
		}
		catch (Exception ex) {
			throw new IllegalStateException("image classifier returned an unreadable body");
		}
		if (scores == null || !scores.isObject()) {
			throw new IllegalStateException("image classifier returned no scores object");
		}
		Map<ModerationCategory, Integer> result = new EnumMap<>(ModerationCategory.class);
		scores.properties().forEach(entry -> {
			ModerationCategory category = categoryOf(entry.getKey());
			JsonNode value = entry.getValue();
			if (category == null || !value.isNumber()) {
				log.debug("Ignoring unusable score from the image classifier: {}", entry.getKey());
				return;
			}
			result.put(category, Math.clamp(value.asInt(), 0, 100));
		});
		return result;
	}

	/**
	 * The category [name] denotes, or {@code null} when nothing here may report it.
	 *
	 * <p>{@link ModerationCategory#SEXUAL_MINORS} is dropped even when the sidecar
	 * sends it, and that is a refusal to accept a score rather than an oversight. The
	 * category is non-overridable, exempt from the long-form flag-only trade and
	 * outside the degrade path — a general NSFW model that emits it would refuse
	 * family photos as child sexual abuse material, and then require a human to look
	 * at everything it flagged, which is the exact harm {@link ImageModerator}'s
	 * javadoc explains no classifier behind this interface may create. It reaches the
	 * pipeline from text and from human reports; never from pixels.
	 */
	private static ModerationCategory categoryOf(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		for (ModerationCategory category : ModerationCategory.values()) {
			if (category.name().equalsIgnoreCase(name.trim())) {
				if (category == ModerationCategory.SEXUAL_MINORS) {
					log.warn("The image classifier reported {} — dropped. No image model may make that "
							+ "call; see ImageModerator's javadoc.", category);
					return null;
				}
				return category;
			}
		}
		return null;
	}

	/** Asks the sidecar whether its model is loaded. Never throws — a failure is an answer. */
	private Health probe() {
		if (endpoint.isEmpty()) {
			return new Health(false);
		}
		try {
			ResponseEntity<Void> response = http.get()
					.uri(HEALTH_PATH)
					.retrieve()
					// A 503 here is the documented "model not loaded", i.e. information
					// rather than an error, so it is read from the status line instead
					// of being thrown and caught one line later.
					.onStatus(status -> true, (request, ignored) -> { })
					.toBodilessEntity();
			boolean healthy = response.getStatusCode().is2xxSuccessful();
			if (!healthy) {
				log.warn("Image moderation sidecar is not ready: HTTP {}", response.getStatusCode().value());
			}
			return new Health(healthy);
		}
		catch (RestClientException ex) {
			log.warn("Image moderation sidecar at {} is unreachable: {}", endpoint,
					ex.getClass().getSimpleName());
			return new Health(false);
		}
	}

	/**
	 * A client with both timeouts set explicitly. Neither has a useful default: an
	 * unset read timeout on a sidecar that accepted the connection and then stopped
	 * talking holds the uploading request open forever, which is a worse outage than
	 * the classifier being down.
	 */
	private static RestClient client(String endpoint, Duration timeout) {
		Duration budget = timeout == null || timeout.isNegative() || timeout.isZero()
				? Duration.ofSeconds(5)
				: timeout;
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
				// The sidecar is a plain HTTP service on an internal network; pinning
				// 1.1 skips the h2c upgrade dance for a connection that gains nothing
				// from multiplexing a single request.
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(budget)
				.build());
		factory.setReadTimeout(budget);
		return RestClient.builder()
				.baseUrl(endpoint.isEmpty() ? "http://unconfigured.invalid" : endpoint)
				.requestFactory(factory)
				.build();
	}

	/** Lower-cased, parameter-free media type — {@code image/JPEG; q=1} is {@code image/jpeg}. */
	private static String normalise(String contentType) {
		if (contentType == null) {
			return "";
		}
		int parameters = contentType.indexOf(';');
		String type = parameters < 0 ? contentType : contentType.substring(0, parameters);
		return type.trim().toLowerCase(Locale.ROOT);
	}

	private static String trimTrailingSlash(String url) {
		String value = url == null ? "" : url.trim();
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	/** One health answer and when it was given. */
	private record Health(boolean healthy, long checkedAt) {

		Health(boolean healthy) {
			this(healthy, System.nanoTime());
		}

		boolean fresh() {
			return System.nanoTime() - checkedAt < HEALTH_TTL.toNanos();
		}
	}
}
