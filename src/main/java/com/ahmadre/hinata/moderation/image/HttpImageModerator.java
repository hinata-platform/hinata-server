package com.ahmadre.hinata.moderation.image;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.moderation.ModerationCategory;
import com.ahmadre.hinata.moderation.ModerationPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
 * decision live in {@link ModerationPolicy}, so swapping the model behind this
 * endpoint cannot silently change what a user is told when their upload is refused.
 *
 * <h2>Why the bean exists even with nowhere to send bytes</h2>
 *
 * <p>This used to be {@code @ConditionalOnProperty(prefix = "hinata.moderation.image",
 * name = "endpoint")}: no environment variable, no bean, and
 * {@code ModerationService} read the resulting empty list as "not configured".
 * That cannot work once the address can also arrive from the database, because
 * the condition is evaluated while the context is being built and the answer it
 * needs lives in a collection Mongo has not been asked about yet. So the bean is
 * unconditional and the question moved to runtime: {@link #configured()} answers
 * from the address currently resolved, {@link #available()} is false the moment
 * there is none, and the four {@link ImageTierState} constants keep meaning what
 * their javadoc says.
 *
 * <p><b>The client is therefore never captured.</b> Every call resolves the
 * endpoint through the policy and compares it with the one the current client was
 * built for; a difference rebuilds. A client held from the constructor would keep
 * POSTing to the host an admin edited away from, and it would do it without an
 * error — the old address either refuses the connection, which reads as a sidecar
 * outage, or worse, still answers.
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
 * kept for {@link #HEALTH_TTL} — and dropped early on two events that both make the
 * cached answer known to be wrong: a classify call returning 503, and the endpoint
 * changing underneath it. Without the second, an admin who re-points the panel at a
 * working sidecar would be shown the previous one's health for up to the TTL, which
 * is precisely the "did my edit take?" question they opened the panel to answer.
 */
@Slf4j
@Component
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

	private final ModerationPolicy policy;

	/**
	 * Env-only, unlike the address: this list has to match what the deployed sidecar
	 * build actually decodes, so it is read straight from the properties and read
	 * once. A tenant setting that claimed the sidecar could read GIFs would not make
	 * it able to.
	 */
	private final Set<String> supportedTypes;

	/** Guards both rebuilds — the client's and the probe's; see {@link #available()}. */
	private final Object lock = new Object();

	private volatile Target target;
	private volatile Health health;

	public HttpImageModerator(HinataProperties properties, ModerationPolicy policy) {
		this.policy = policy;
		Set<String> configured = properties.getModeration().getImage().getSupportedTypes();
		this.supportedTypes = configured == null
				? Set.of()
				: configured.stream()
						.map(HttpImageModerator::normalise)
						.filter(type -> !type.isEmpty())
						.collect(Collectors.toUnmodifiableSet());
		// Deliberately nothing about the endpoint here. The database has not been
		// read at construction time and a log line stating an address the operator
		// then changes from the panel is worse than none; ModerationService warns
		// once at startup about the state that actually matters, and the rebuild
		// below announces every address this ever points at.
	}

	@Override
	public String id() {
		return ID;
	}

	/**
	 * Whether this tier has anywhere to send bytes at all.
	 *
	 * <p>The question {@code @ConditionalOnProperty} used to answer by not creating
	 * the bean. It is the difference between the two states an operator acts on
	 * differently — "nobody installed one" and "the one you installed is broken" —
	 * and with an always-present bean nothing else can tell them apart.
	 */
	@Override
	public boolean configured() {
		return !target().endpoint().isEmpty();
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
		return configured() && supportedTypes.contains(normalise(contentType));
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
		// First, because resolving may rebuild the client and drop a probe that
		// answered for an address this instance no longer uses.
		Target current = target();
		if (current.endpoint().isEmpty()) {
			return false;
		}
		Health cached = health;
		if (cached != null && cached.fresh()) {
			return cached.healthy();
		}
		synchronized (lock) {
			cached = health;
			if (cached != null && cached.fresh()) {
				return cached.healthy();
			}
			Health probed = probe(current);
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
		Target current = target();
		if (current.endpoint().isEmpty()) {
			throw new IllegalStateException("no image moderation endpoint configured");
		}
		String body;
		try {
			body = current.http().post()
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
			throw new IllegalStateException("image classifier at " + current.endpoint() + " failed: "
					+ ex.getClass().getSimpleName());
		}
		return parse(body);
	}

	/**
	 * The client for the address currently configured, rebuilt when that changed.
	 *
	 * <p>Read outside the lock on the fast path: a hit is one volatile read and two
	 * comparisons, which is what every upload pays. The rebuild is inside it, and it
	 * drops the cached probe in the same critical section — the two have to move
	 * together, because a probe that outlived its endpoint is an answer about a host
	 * this instance no longer talks to.
	 */
	private Target target() {
		String endpoint = trimTrailingSlash(policy.imageEndpoint());
		Duration timeout = policy.imageTimeout();
		Target current = target;
		if (current != null && current.matches(endpoint, timeout)) {
			return current;
		}
		synchronized (lock) {
			current = target;
			if (current != null && current.matches(endpoint, timeout)) {
				return current;
			}
			Target rebuilt = new Target(endpoint, timeout, client(endpoint, timeout));
			target = rebuilt;
			health = null;
			announce(current, rebuilt);
			return rebuilt;
		}
	}

	/**
	 * Says where the tier now points, once per change.
	 *
	 * <p>Silent on the very first resolution of an empty endpoint, which is the
	 * default every self-hosted install boots with and which
	 * {@code ModerationService} already warns about once at startup. Everything else
	 * is an operator having edited something, and they need to see that the server
	 * agrees with the panel.
	 */
	private void announce(Target previous, Target current) {
		if (!current.endpoint().isEmpty()) {
			log.info("Image moderation sidecar is now {} (timeout {}, types {})",
					current.endpoint(), current.timeout(), supportedTypes);
		}
		else if (previous != null && !previous.endpoint().isEmpty()) {
			log.warn("The image moderation sidecar address was cleared — uploaded images are NOT "
					+ "being classified until one is set again.");
		}
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
	private Health probe(Target target) {
		try {
			ResponseEntity<Void> response = target.http().get()
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
			log.warn("Image moderation sidecar at {} is unreachable: {}", target.endpoint(),
					ex.getClass().getSimpleName());
			return new Health(false);
		}
	}

	/**
	 * A client with both timeouts set explicitly. Neither has a useful default: an
	 * unset read timeout on a sidecar that accepted the connection and then stopped
	 * talking holds the uploading request open forever, which is a worse outage than
	 * the classifier being down. The budget itself is already sanitised by
	 * {@link ModerationPolicy#imageTimeout()}.
	 */
	private static RestClient client(String endpoint, Duration timeout) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
				// The sidecar is a plain HTTP service on an internal network; pinning
				// 1.1 skips the h2c upgrade dance for a connection that gains nothing
				// from multiplexing a single request.
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(timeout)
				.build());
		factory.setReadTimeout(timeout);
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

	/**
	 * One address and the client built for it.
	 *
	 * <p>A record rather than two fields so no caller can read a base URL from one
	 * configuration and a client from the next — the interleaving is easy to write
	 * and impossible to see in a stack trace, and the symptom is a request that went
	 * to the wrong sidecar.
	 */
	private record Target(String endpoint, Duration timeout, RestClient http) {

		boolean matches(String otherEndpoint, Duration otherTimeout) {
			return endpoint.equals(otherEndpoint) && timeout.equals(otherTimeout);
		}
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
