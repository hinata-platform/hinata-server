package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.config.HinataProperties;
import jakarta.annotation.PostConstruct;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
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
	private static final long LINK_TTL_SECONDS = 7L * 24 * 3600;

	private final HinataProperties props;
	private final ConnectEnrollmentRepository enrollments;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10)).build();

	/** Cached copy of the persisted enrolment (volatile — read on hot paths). */
	private volatile ConnectEnrollment enrollment;

	public enum PushResult { SENT, DEAD, FAILED, DISABLED }

	/** Status view consumed by the Adminbereich. */
	public record ConnectStatus(boolean enabled, boolean enrolled, String gatewayBaseUrl,
			String serverId, String challenge, boolean domainVerified, long enrolledAt) {
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
			return new ConnectStatus(enabled, false, gw(), null, null, false, 0);
		}
		return new ConnectStatus(enabled, true, gw(), e.getServerId(), e.getChallenge(),
				e.isDomainVerified(), e.getEnrolledAt());
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
}
