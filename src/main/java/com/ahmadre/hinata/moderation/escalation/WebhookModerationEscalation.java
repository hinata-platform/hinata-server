package com.ahmadre.hinata.moderation.escalation;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.moderation.ModerationPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Delivers a freeze notice to the operator's own endpoint, signed.
 *
 * <h2>What the body may say, and what it may never say</h2>
 *
 * <p>Five fields: the row id, the category, the surface, an in-product locator
 * and a timestamp. The body is assembled field by field here rather than by
 * serialising {@link Event} reflectively, and that is the point — a record that
 * grows a field later would silently start sending it, and the fields this
 * object is near are the ones that must never leave. No content, no bytes, no
 * file name, and above all not the hash programme's reference: that value is the
 * handle by which an accredited body identifies known material, it is stored on
 * the moderation record and nowhere else, and posting it to a third-party
 * endpoint would put it somewhere neither the operator nor the programme
 * controls.
 *
 * <h2>Why the signature covers the raw body</h2>
 *
 * <p>HMAC-SHA256 over the exact bytes that go on the wire, not over a
 * re-serialisation of the parsed object. A recipient verifying against their own
 * re-encoding is verifying a different string the moment key order or number
 * formatting differs by one character, and the failure looks like an attack
 * rather than a bug. The body is therefore serialised once, hashed, and sent.
 *
 * <h2>Where "a URL without a secret" is refused now</h2>
 *
 * <p>It used to be refused at startup: the bean was
 * {@code @ConditionalOnProperty} on the URL and its constructor threw when the
 * secret was missing, so a misconfigured deployment did not boot. Neither half
 * survives an address that can arrive from the admin panel — the condition is
 * evaluated before Mongo is read, and there is no startup left to fail at when
 * the value changes at 3pm on a Tuesday. The guarantee did not change, only its
 * location: {@code AdminSettingsController} refuses to <em>save</em> a URL with
 * no secret behind it, and {@link #escalate} refuses to <em>send</em> one, with
 * the undelivered notice audited exactly like any other failure. An unsigned
 * notice claiming content was frozen is a claim its recipient cannot attribute,
 * and acting on an unattributable one is worse than having no webhook at all.
 *
 * <p>No URL anywhere is a different case and stays silent. A small install with
 * nobody to notify is supported, the freeze happened regardless, and auditing a
 * failure for an operator's deliberate choice would bury the ones that matter.
 *
 * <h2>Why delivery is off the calling thread</h2>
 *
 * <p>The call site is a refused upload or a filed report — someone is waiting on
 * an HTTP response. Retrying in front of them would add the whole retry budget to
 * a request whose outcome is already decided: the content is refused, the target
 * is already frozen, and nothing the endpoint says changes either. So the notice
 * is handed to a single-thread executor and the request returns. The executor is
 * a constructor parameter so a test can pass a same-thread one and assert on the
 * outcome instead of sleeping.
 */
@Slf4j
@Component
public class WebhookModerationEscalation implements ModerationEscalation {

	/** Recorded on audit rows so a failure names its destination. */
	public static final String ID = "webhook";

	/** Header carrying the lowercase hex HMAC-SHA256 of the raw request body. */
	public static final String SIGNATURE_HEADER = "X-Hinata-Signature";

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	/**
	 * Its own mapper, not the application one. This body is a wire contract with
	 * somebody else's system; a Jackson customisation made for the REST API — a
	 * naming strategy, a global inclusion rule — must not be able to change what
	 * an escalation looks like to a recipient who has already written a parser.
	 */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final ModerationPolicy policy;

	/**
	 * For the retry delay only, which is env-only: how long a failed attempt waits
	 * is infrastructure tuning, it changes nothing about what is escalated, and
	 * three tries is not a backoff curve worth a form field.
	 */
	private final HinataProperties properties;

	private final AuditService audit;
	private final Executor executor;

	/** Guards the client rebuild; see {@link #target()}. */
	private final Object lock = new Object();

	private volatile Target target;

	/**
	 * Carries {@code @Autowired} because the package-private constructor below
	 * exists: two candidates make the container's "single constructor" rule stop
	 * applying, and it then looks for a no-arg one and fails. It went unnoticed
	 * while the bean was conditional on an environment key no test sets.
	 */
	@Autowired
	public WebhookModerationEscalation(ModerationPolicy policy, HinataProperties properties,
			AuditService audit) {
		this(policy, properties, audit, Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "moderation-escalation");
			// Daemon: an undelivered notice must not hold a shutdown open. The
			// bounded retry means the window is seconds, and a notice lost to a
			// restart is already audited as undelivered by the attempt that failed.
			thread.setDaemon(true);
			return thread;
		}));
	}

	WebhookModerationEscalation(ModerationPolicy policy, HinataProperties properties,
			AuditService audit, Executor executor) {
		this.policy = policy;
		this.properties = properties;
		this.audit = audit;
		this.executor = executor;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public boolean configured() {
		return !target().url().isEmpty();
	}

	@Override
	public void escalate(Event event) {
		if (event == null) {
			return;
		}
		Target destination = target();
		if (destination.url().isEmpty()) {
			// Nobody to tell, by the operator's choice. Not a failure and not audited.
			return;
		}
		byte[] secret = policy.escalationSecret().getBytes(StandardCharsets.UTF_8);
		int maxAttempts = policy.escalationMaxAttempts();
		if (secret.length == 0) {
			log.error("A moderation escalation endpoint is configured with no signing secret — the "
					+ "notice for {} was NOT sent. An unsigned notice cannot be attributed by its "
					+ "recipient; set the escalation secret.", event.recordId());
			failed(event, "no signing secret configured", 0);
			return;
		}
		try {
			String body = body(event);
			String signature = hmac(secret, body);
			executor.execute(() -> deliver(destination, maxAttempts, event, body, signature));
		}
		catch (RuntimeException ex) {
			// Assembling or signing failed, which is a bug rather than an outage —
			// but it is still an escalation that never left, so it is audited as one
			// instead of surfacing as an exception in an upload the caller has
			// already refused.
			failed(event, ex.toString(), 0);
		}
	}

	/**
	 * POSTs the notice, retrying a bounded number of times.
	 *
	 * <p>Every failure mode is treated the same, deliberately: a 500, a timeout and
	 * a 404 all mean "the person who has to act on this has not been told", and
	 * distinguishing them would only produce a rule under which some of them are
	 * quietly not retried.
	 *
	 * <p>The destination and the attempt budget are parameters rather than fields
	 * because this runs on another thread: resolving them here would let a notice
	 * queued against one endpoint be delivered to whatever an admin changed it to
	 * while it waited.
	 */
	private void deliver(Target destination, int maxAttempts, Event event, String body,
			String signature) {
		String lastFailure = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				destination.http().post()
						.contentType(MediaType.APPLICATION_JSON)
						.header(SIGNATURE_HEADER, signature)
						.body(body)
						.retrieve()
						.toBodilessEntity();
				audit.event(AuditAction.MODERATION_ESCALATED)
						.target(event.recordId(), event.reference())
						.meta("destination", ID)
						.meta("category", event.category() == null ? null : event.category().name())
						.meta("attempts", String.valueOf(attempt))
						.log();
				return;
			}
			catch (RuntimeException ex) {
				lastFailure = ex.getClass().getSimpleName();
				log.warn("Moderation escalation attempt {}/{} failed: {}", attempt, maxAttempts,
						lastFailure);
				if (attempt < maxAttempts) {
					sleep();
				}
			}
		}
		failed(event, lastFailure, maxAttempts);
	}

	/**
	 * The one reaction an undelivered notice gets. Audited rather than logged and
	 * forgotten: this is the case where the product preserved the material and
	 * nobody was told, and a log line is not something an operator can be asked
	 * about afterwards.
	 */
	private void failed(Event event, String reason, int attempts) {
		audit.event(AuditAction.MODERATION_ESCALATION_FAILED)
				.outcome(AuditLog.Outcome.FAILURE)
				.target(event.recordId(), event.reference())
				.meta("destination", ID)
				.meta("category", event.category() == null ? null : event.category().name())
				.meta("attempts", String.valueOf(attempts))
				.meta("reason", reason)
				.log();
		log.error("Moderation escalation for {} could not be delivered after {} attempt(s)",
				event.recordId(), attempts);
	}

	private void sleep() {
		Duration retryDelay = properties.getModeration().getEscalation().getRetryDelay();
		if (retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()) {
			return;
		}
		try {
			Thread.sleep(retryDelay.toMillis());
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * The client for the address currently configured, rebuilt when that changed.
	 *
	 * <p>Same shape and same reason as {@code HttpImageModerator.target()}: an
	 * address that an admin can edit must not be captured once, or the product goes
	 * on signing notices about frozen material and posting them to whoever used to
	 * be at the old URL.
	 */
	private Target target() {
		String url = policy.escalationUrl();
		Duration timeout = policy.escalationTimeout();
		Target current = target;
		if (current != null && current.matches(url, timeout)) {
			return current;
		}
		synchronized (lock) {
			current = target;
			if (current != null && current.matches(url, timeout)) {
				return current;
			}
			Target rebuilt = new Target(url, timeout, client(url, timeout));
			target = rebuilt;
			if (!url.isEmpty()) {
				log.info("Moderation escalation webhook is now {} (timeout {})", url, timeout);
			}
			else if (current != null && !current.url().isEmpty()) {
				log.warn("The moderation escalation webhook was cleared — freezes will no longer "
						+ "notify anybody outside the product.");
			}
			return rebuilt;
		}
	}

	/**
	 * The wire body, built field by field.
	 *
	 * <p>Package-private so the test can assert on the exact JSON a recipient
	 * receives — the negative half of that assertion (no content, no hash
	 * reference) is the part worth pinning, and it cannot be made against a
	 * mocked HTTP client.
	 */
	static String body(Event event) {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("recordId", event.recordId());
		node.put("category", event.category() == null ? null : event.category().name());
		node.put("surface", event.surface() == null ? null : event.surface().name());
		node.put("reference", event.reference());
		node.put("at", event.at() == null ? null : event.at().toString());
		try {
			return MAPPER.writeValueAsString(node);
		}
		catch (Exception ex) {
			throw new IllegalStateException("could not serialise the escalation payload");
		}
	}

	/**
	 * The verification a recipient performs, exposed so the test verifies the
	 * signature the way a recipient would rather than re-deriving it from this
	 * class's internals.
	 */
	public static String hmac(byte[] secret, String body) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
			byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xf, 16));
				hex.append(Character.forDigit(b & 0xf, 16));
			}
			return hex.toString();
		}
		catch (Exception ex) {
			throw new IllegalStateException("HMAC-SHA256 unavailable");
		}
	}

	/**
	 * A client with both timeouts set explicitly, for the reason
	 * {@code HttpImageModerator} gives: an unset read timeout against an endpoint
	 * that accepted the connection and then stopped talking holds the retry thread
	 * forever, and the notice after it never leaves. The budget is already
	 * sanitised by {@link ModerationPolicy#escalationTimeout()}.
	 */
	private static RestClient client(String url, Duration timeout) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(timeout).build());
		factory.setReadTimeout(timeout);
		return RestClient.builder()
				.baseUrl(url.isEmpty() ? "http://unconfigured.invalid" : url)
				.requestFactory(factory)
				.build();
	}

	/** One address and the client built for it; see {@link #target()}. */
	private record Target(String url, Duration timeout, RestClient http) {

		boolean matches(String otherUrl, Duration otherTimeout) {
			return url.equals(otherUrl) && timeout.equals(otherTimeout);
		}
	}
}
