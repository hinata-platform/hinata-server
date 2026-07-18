package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.config.HinataProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for Hinata Connect, the single central service that the published
 * client app depends on (see {@link HinataProperties.Gateway}).
 *
 * <p>Enrolment is <b>admin-driven</b> (Rocket.Chat model): an operator creates
 * the instance in the Connect portal, receives a one-time enrolment token, and
 * pastes it into the Adminbereich. {@link #enroll} exchanges that token for the
 * per-instance {@code serverId} + {@code secret}, which are <b>persisted</b> in
 * {@link ConnectEnrollment} — the server never re-registers on boot, and the
 * secret survives restarts. The credentials then:
 *
 *  - sign universal-link relay URLs for invite / password-reset emails, and
 *  - authenticate push fan-out (the gateway owns the app's FCM credentials).
 *
 * Self-contained JSON (no Jackson dependency) keeps this decoupled from the
 * server's serialization stack. Without an enrolment, relay links fall back to
 * the server's own web app and push simply no-ops.
 */
@Service
@RequiredArgsConstructor
public class GatewayService {

	private static final Logger log = LoggerFactory.getLogger(GatewayService.class);
	private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final long LINK_TTL_SECONDS = 7L * 24 * 3600;
	/** Grace after a handshake's declared expiry before the local poller gives up. */
	private static final long HANDSHAKE_GRACE_MS = 5_000L;

	private final HinataProperties props;
	private final ConnectEnrollmentRepository enrollments;
	private final ConnectHandshakeRepository handshakes;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	/** Single daemon thread that polls the gateway while a handshake is pending. */
	private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "connect-handshake-poll");
		t.setDaemon(true);
		return t;
	});

	/** Cached copy of the persisted enrolment (volatile — read on hot paths). */
	private volatile ConnectEnrollment enrollment;
	/** The in-flight "Jetzt verbinden" handshake, if any (volatile — touched by the poller). */
	private volatile ConnectHandshake pendingHandshake;
	private volatile ScheduledFuture<?> pollFuture;

	public enum PushResult { SENT, DEAD, FAILED, DISABLED }

	/**
	 * Status view consumed by the Adminbereich. Besides the enrolment state it
	 * carries any in-flight handshake so the "Jetzt verbinden" UI can show a
	 * "waiting for approval" state and re-open the portal URL.
	 */
	public record ConnectStatus(boolean enabled, boolean enrolled, String gatewayBaseUrl,
			String serverId, String challenge, boolean domainVerified, long enrolledAt,
			boolean handshakePending, String handshakePortalUrl, long handshakeExpiresAt) {
	}

	/** Result of starting a handshake — what the app needs to open the portal. */
	public record HandshakeStart(String portalUrl, long expiresAt) {
	}

	@PostConstruct
	void load() {
		enrollment = enrollments.findById(ConnectEnrollment.SINGLETON_ID).orElse(null);
		if (!props.getGateway().isEnabled()) {
			log.info("Hinata Connect disabled — push + universal-link relay are off.");
		} else if (enrollment == null) {
			log.info("Hinata Connect not enrolled yet — enrol via Adminbereich → Connect "
					+ "(push + relay fall back until then).");
		} else {
			log.info("Hinata Connect enrolment loaded (server {}).", enrollment.getServerId());
		}
		resumePendingHandshake();
	}

	/** Resume (or discard) a handshake that was in flight when the server restarted. */
	private void resumePendingHandshake() {
		ConnectHandshake h = handshakes.findById(ConnectHandshake.SINGLETON_ID).orElse(null);
		if (h == null) {
			return;
		}
		boolean dead = enrollment != null || !props.getGateway().isEnabled()
				|| Instant.now().toEpochMilli() > h.getExpiresAt() + HANDSHAKE_GRACE_MS;
		if (dead) {
			handshakes.deleteById(ConnectHandshake.SINGLETON_ID);
			return;
		}
		pendingHandshake = h;
		schedulePoll();
		log.info("Resuming pending Hinata Connect handshake ({}).", h.getHandshakeId());
	}

	@PreDestroy
	void shutdown() {
		poller.shutdownNow();
	}

	public boolean registered() {
		ConnectEnrollment e = enrollment;
		return e != null && e.getServerId() != null && e.getSecret() != null;
	}

	/** The domain-proof nonce served at /.well-known/hinata-connect-challenge. */
	public Optional<String> challenge() {
		ConnectEnrollment e = enrollment;
		return e == null || e.getChallenge() == null || e.getChallenge().isBlank()
				? Optional.empty()
				: Optional.of(e.getChallenge());
	}

	public ConnectStatus status() {
		ConnectEnrollment e = enrollment;
		boolean enabled = props.getGateway().isEnabled();
		if (e == null) {
			ConnectHandshake h = pendingHandshake;
			boolean pending = h != null;
			return new ConnectStatus(enabled, false, gw(), null, null, false, 0,
					pending, pending ? h.getPortalUrl() : null, pending ? h.getExpiresAt() : 0);
		}
		return new ConnectStatus(enabled, true, gw(), e.getServerId(), e.getChallenge(),
				e.isDomainVerified(), e.getEnrolledAt(), false, null, 0);
	}

	/**
	 * Exchanges a one-time enrolment token (minted in the Connect portal) for
	 * this server's credentials and persists them.
	 *
	 * @throws IllegalStateException    when Connect is disabled or the gateway is unreachable
	 * @throws IllegalArgumentException when the gateway refuses the token/URLs (message is generic)
	 */
	public ConnectStatus enroll(String enrollmentToken) {
		if (!props.getGateway().isEnabled()) {
			throw new IllegalStateException("connect disabled");
		}
		if (enrollmentToken == null || enrollmentToken.isBlank()) {
			throw new IllegalArgumentException("missing token");
		}
		String body = "{\"apiBaseUrl\":" + jstr(props.getBaseUrl())
				+ ",\"webBaseUrl\":" + jstr(props.webBase()) + "}";
		HttpResponse<String> r;
		try {
			r = http.send(HttpRequest.newBuilder(URI.create(gw() + "/register"))
					.timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/json")
					.header("X-Enrollment-Token", enrollmentToken.trim())
					.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
					HttpResponse.BodyHandlers.ofString());
		} catch (Exception e) {
			throw new IllegalStateException("gateway unreachable", e);
		}
		if (r.statusCode() / 100 != 2) {
			log.warn("Hinata Connect enrolment refused: HTTP {}", r.statusCode());
			throw new IllegalArgumentException("enrollment rejected");
		}
		ConnectEnrollment e = new ConnectEnrollment();
		e.setGatewayBaseUrl(gw());
		e.setServerId(extract(r.body(), "serverId"));
		e.setSecret(extract(r.body(), "secret"));
		e.setChallenge(extract(r.body(), "challenge"));
		e.setDomainVerified(extractBool(r.body(), "domainVerified"));
		e.setApiBaseUrl(props.getBaseUrl());
		e.setWebBaseUrl(props.webBase());
		e.setEnrolledAt(Instant.now().toEpochMilli());
		if (e.getServerId() == null || e.getSecret() == null) {
			throw new IllegalStateException("gateway returned no credentials");
		}
		enrollments.save(e);
		enrollment = e;
		log.info("Enrolled with Hinata Connect ({}) as server {}.", gw(), e.getServerId());
		return status();
	}

	/**
	 * Polls the gateway for this server's verification state (also nudges the
	 * gateway to re-run the domain check). Best-effort; returns current status.
	 */
	public ConnectStatus refreshStatus() {
		ConnectEnrollment e = enrollment;
		if (e == null || !props.getGateway().isEnabled()) {
			return status();
		}
		try {
			HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(gw() + "/register/status"))
					.timeout(Duration.ofSeconds(10))
					.header("X-Server-Id", e.getServerId())
					.header("X-Server-Secret", e.getSecret())
					.POST(HttpRequest.BodyPublishers.noBody()).build(),
					HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() / 100 == 2) {
				boolean verified = extractBool(r.body(), "domainVerified");
				String challenge = extract(r.body(), "challenge");
				if (verified != e.isDomainVerified()
						|| (challenge != null && !challenge.equals(e.getChallenge()))) {
					e.setDomainVerified(verified);
					if (challenge != null) {
						e.setChallenge(challenge);
					}
					enrollments.save(e);
				}
			} else if (r.statusCode() == 401) {
				log.warn("Hinata Connect status: credentials no longer accepted (revoked in the portal?)");
			}
		} catch (Exception ex) {
			log.debug("Hinata Connect status poll failed: {}", ex.getMessage());
		}
		return status();
	}

	/** Removes the local enrolment (the portal side is revoked by the operator there). */
	public void disconnect() {
		enrollments.deleteById(ConnectEnrollment.SINGLETON_ID);
		enrollment = null;
		log.info("Hinata Connect enrolment removed locally.");
	}

	// --- Browser-mediated "Jetzt verbinden" handshake ---

	/**
	 * Starts the automated enrolment flow: registers a handshake with the gateway
	 * (declaring this server's origins + the hash of a locally-kept verifier) and
	 * returns the portal URL the admin opens to approve it. A background poller
	 * then collects the minted credentials over the back channel and promotes them
	 * to an enrolment — the app only has to watch {@link #status()} flip.
	 *
	 * @throws IllegalStateException    when Connect is disabled or the gateway is unreachable
	 * @throws IllegalArgumentException when the gateway refuses the request
	 */
	public HandshakeStart startHandshake() {
		if (!props.getGateway().isEnabled()) {
			throw new IllegalStateException("connect disabled");
		}
		String verifier = randomToken(32);
		String verifierHash = sha256Hex(verifier);
		String body = "{\"apiBaseUrl\":" + jstr(props.getBaseUrl())
				+ ",\"webBaseUrl\":" + jstr(props.webBase())
				+ ",\"verifierHash\":" + jstr(verifierHash) + "}";
		HttpResponse<String> r;
		try {
			r = http.send(HttpRequest.newBuilder(URI.create(gw() + "/register/handshake/start"))
					.timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
					HttpResponse.BodyHandlers.ofString());
		} catch (Exception e) {
			throw new IllegalStateException("gateway unreachable", e);
		}
		if (r.statusCode() / 100 != 2) {
			log.warn("Hinata Connect handshake start refused: HTTP {}", r.statusCode());
			throw new IllegalArgumentException("handshake rejected");
		}
		String handshakeId = extract(r.body(), "handshakeId");
		String portalUrl = extract(r.body(), "portalUrl");
		long expiresAt = extractLong(r.body(), "expiresAt");
		if (handshakeId == null) {
			throw new IllegalStateException("gateway returned no handshake");
		}
		ConnectHandshake h = new ConnectHandshake();
		h.setHandshakeId(handshakeId);
		h.setVerifier(verifier);
		h.setPortalUrl(portalUrl);
		h.setExpiresAt(expiresAt);
		h.setStartedAt(Instant.now().toEpochMilli());
		handshakes.save(h);
		pendingHandshake = h;
		schedulePoll();
		log.info("Hinata Connect handshake started ({}).", handshakeId);
		return new HandshakeStart(portalUrl, expiresAt);
	}

	/** Cancels an in-flight handshake (admin abandoned the flow). */
	public ConnectStatus cancelHandshake() {
		clearHandshake();
		return status();
	}

	private synchronized void schedulePoll() {
		cancelPoll();
		// Small initial delay (the operator needs a moment) then a steady cadence.
		pollFuture = poller.scheduleWithFixedDelay(this::pollHandshakeSafe, 2, 3, TimeUnit.SECONDS);
	}

	private synchronized void cancelPoll() {
		if (pollFuture != null) {
			pollFuture.cancel(false);
			pollFuture = null;
		}
	}

	private void clearHandshake() {
		pendingHandshake = null;
		cancelPoll();
		try {
			handshakes.deleteById(ConnectHandshake.SINGLETON_ID);
		} catch (Exception ex) {
			log.debug("Handshake cleanup failed: {}", ex.getMessage());
		}
	}

	/** Never lets a background exception kill the scheduled task. */
	private void pollHandshakeSafe() {
		try {
			pollHandshakeOnce();
		} catch (Exception e) {
			log.debug("Connect handshake poll error: {}", e.getMessage());
		}
	}

	private void pollHandshakeOnce() {
		ConnectHandshake h = pendingHandshake;
		if (h == null) {
			cancelPoll();
			return;
		}
		if (Instant.now().toEpochMilli() > h.getExpiresAt() + HANDSHAKE_GRACE_MS) {
			log.info("Hinata Connect handshake expired — cancelling.");
			clearHandshake();
			return;
		}
		String body = "{\"handshakeId\":" + jstr(h.getHandshakeId())
				+ ",\"verifier\":" + jstr(h.getVerifier()) + "}";
		HttpResponse<String> r;
		try {
			r = http.send(HttpRequest.newBuilder(URI.create(gw() + "/register/handshake/poll"))
					.timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
					HttpResponse.BodyHandlers.ofString());
		} catch (Exception ex) {
			return; // transient network blip — try again next tick
		}
		if (r.statusCode() / 100 != 2) {
			// 401 (bad verifier) or 404 (gone) mean this handshake can never succeed.
			if (r.statusCode() == 401 || r.statusCode() == 404) {
				log.warn("Hinata Connect handshake no longer valid (HTTP {}) — cancelling.", r.statusCode());
				clearHandshake();
			}
			return;
		}
		String state = extract(r.body(), "status");
		if ("APPROVED".equals(state)) {
			applyHandshakeEnrollment(r.body());
		} else if ("DENIED".equals(state) || "EXPIRED".equals(state)) {
			log.info("Hinata Connect handshake {} — cancelling.", state);
			clearHandshake();
		}
		// "PENDING" → keep waiting.
	}

	/** Promotes an approved handshake's collected credentials to a persisted enrolment. */
	private void applyHandshakeEnrollment(String bodyJson) {
		String serverId = extract(bodyJson, "serverId");
		String secret = extract(bodyJson, "secret");
		if (serverId == null || secret == null) {
			log.warn("Handshake approved but the gateway returned no credentials — will retry.");
			return;
		}
		ConnectEnrollment e = new ConnectEnrollment();
		e.setGatewayBaseUrl(gw());
		e.setServerId(serverId);
		e.setSecret(secret);
		e.setChallenge(extract(bodyJson, "challenge"));
		e.setDomainVerified(extractBool(bodyJson, "domainVerified"));
		e.setApiBaseUrl(props.getBaseUrl());
		e.setWebBaseUrl(props.webBase());
		e.setEnrolledAt(Instant.now().toEpochMilli());
		enrollments.save(e);
		enrollment = e;
		clearHandshake();
		log.info("Enrolled with Hinata Connect via handshake as server {}.", serverId);
	}

	/**
	 * A signed universal-link relay URL for an email deep link (invite / reset),
	 * which the app resolves locally and the gateway redirects for the web. Falls
	 * back to the server's own web app when not enrolled.
	 */
	public String relayLink(String path, String token) {
		if (!props.getGateway().isEnabled() || !registered()) {
			return directFallback(path, token);
		}
		ConnectEnrollment e = enrollment;
		long exp = Instant.now().getEpochSecond() + LINK_TTL_SECONDS;
		String payload = "{\"sid\":" + jstr(e.getServerId())
				+ ",\"a\":" + jstr(props.getBaseUrl())
				+ ",\"u\":" + jstr(props.webBase())
				+ ",\"p\":" + jstr(path)
				+ ",\"t\":" + jstr(token)
				+ ",\"e\":" + exp + "}";
		String code = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
		String sig = B64.encodeToString(hmac(e.getSecret(), code));
		return gw() + "/l/" + code + "." + sig;
	}

	private String directFallback(String path, String token) {
		return props.webBase() + path + "?token=" + enc(token) + "&server=" + enc(props.getBaseUrl());
	}

	/** Send one push to a device token via the gateway. */
	public PushResult push(String token, String title, String body, String link) {
		if (!props.getGateway().isEnabled()) return PushResult.DISABLED;
		if (!registered()) return PushResult.DISABLED;
		ConnectEnrollment e = enrollment;
		try {
			String data = (link != null && !link.isBlank()) ? ",\"data\":{\"link\":" + jstr(link) + "}" : "";
			String json = "{\"token\":" + jstr(token) + ",\"title\":" + jstr(title)
					+ ",\"body\":" + jstr(body) + data + "}";
			HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(gw() + "/push/send"))
					.timeout(Duration.ofSeconds(15))
					.header("Content-Type", "application/json")
					.header("X-Server-Id", e.getServerId())
					.header("X-Server-Secret", e.getSecret())
					.POST(HttpRequest.BodyPublishers.ofString(json)).build(),
					HttpResponse.BodyHandlers.ofString());
			int sc = r.statusCode();
			if (sc / 100 == 2) return PushResult.SENT;
			if (sc == 404) return PushResult.DEAD;
			if (sc == 401) {
				// Credentials revoked in the portal — keep them (an admin decision
				// is required); just log loudly.
				log.warn("Gateway push rejected our credentials — re-enrol via Adminbereich → Connect.");
			} else {
				log.warn("Gateway push failed: HTTP {}", sc);
			}
			return PushResult.FAILED;
		} catch (Exception ex) {
			log.warn("Gateway push error: {}", ex.getMessage());
			return PushResult.FAILED;
		}
	}

	private String gw() {
		String u = props.getGateway().getBaseUrl();
		return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
	}

	private static byte[] hmac(String secret, String data) {
		try {
			Mac m = Mac.getInstance("HmacSHA256");
			m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return m.doFinal(data.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalStateException("HMAC failed", e);
		}
	}

	private static String enc(String v) {
		return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
	}

	/** Minimal JSON string literal (handles the characters that occur in our values). */
	private static String jstr(String v) {
		if (v == null) return "null";
		StringBuilder s = new StringBuilder("\"");
		for (int i = 0; i < v.length(); i++) {
			char c = v.charAt(i);
			switch (c) {
				case '"' -> s.append("\\\"");
				case '\\' -> s.append("\\\\");
				case '\n' -> s.append("\\n");
				case '\r' -> s.append("\\r");
				case '\t' -> s.append("\\t");
				default -> {
					if (c < 0x20) s.append(String.format("\\u%04x", (int) c));
					else s.append(c);
				}
			}
		}
		return s.append('"').toString();
	}

	private static String extract(String json, String key) {
		Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json == null ? "" : json);
		return m.find() ? m.group(1) : null;
	}

	private static boolean extractBool(String json, String key) {
		Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(json == null ? "" : json);
		return m.find() && Boolean.parseBoolean(m.group(1));
	}

	private static long extractLong(String json, String key) {
		Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json == null ? "" : json);
		return m.find() ? Long.parseLong(m.group(1)) : 0L;
	}

	/** URL-safe, high-entropy opaque token from {@code bytes} of randomness (the PKCE verifier). */
	private static String randomToken(int bytes) {
		byte[] buf = new byte[bytes];
		RANDOM.nextBytes(buf);
		return B64.encodeToString(buf);
	}

	/** Lowercase hex SHA-256 — the PKCE {@code verifierHash} sent to the gateway. */
	private static String sha256Hex(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
