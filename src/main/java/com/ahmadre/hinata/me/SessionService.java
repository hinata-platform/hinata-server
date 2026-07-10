package com.ahmadre.hinata.me;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Creates, refreshes and revokes {@link RefreshSession} records — the per-device
 * sign-in list shown on the account screen. A session's id is embedded as the
 * refresh token's {@code sid} claim (see {@code TokenService}); deleting the
 * record revokes that device.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

	private final RefreshSessionRepository sessions;
	private final HinataProperties properties;
	private final UserEvents userEvents;
	private final NotificationService notifications;

	/** Opens a new session for a fresh sign-in. Best-effort device metadata. */
	public RefreshSession start(User user, String ip, String userAgent) {
		Instant now = Instant.now();
		Agent agent = parse(userAgent);
		// A device we've never seen before triggers a security alert (new sign-in).
		boolean knownDevice = sessions.findByUserIdOrderByLastActiveAtDesc(user.getId()).stream()
				.anyMatch(existing -> sameDevice(existing, agent));
		RefreshSession saved = sessions.save(RefreshSession.builder()
				.userId(user.getId())
				.kind(agent.kind())
				.os(agent.os())
				.client(agent.client())
				.app(agent.app())
				.ipMasked(mask(ip))
				.location(null)
				.createdAt(now)
				.lastActiveAt(now)
				.expiresAt(now.plusSeconds(properties.getJwt().getRefreshTokenSeconds()))
				.build());
		if (!knownDevice) {
			notifyNewSignIn(user, saved);
		}
		return saved;
	}

	/** Two sessions are the "same device" when platform, client and form factor match. */
	private boolean sameDevice(RefreshSession existing, Agent agent) {
		return existing.getKind() == agent.kind()
				&& Objects.equals(existing.getOs(), agent.os())
				&& Objects.equals(existing.getClient(), agent.client());
	}

	/** Security alert for a sign-in from a device not previously seen for this user. */
	private void notifyNewSignIn(User user, RefreshSession session) {
		String device = describe(session);
		boolean de = "de".equalsIgnoreCase(user.getLocale());
		notifications.notifySecurityAlert(user,
				de ? "Neue Anmeldung" : "New sign-in",
				de ? "Neue Anmeldung bei deinem Konto: " + device
						+ ". Warst du das nicht, ändere sofort dein Passwort."
						: "New sign-in to your account: " + device
								+ ". If this wasn't you, change your password immediately.");
	}

	/** Human-readable "Client · OS" for an alert, tolerant of missing metadata. */
	private String describe(RefreshSession session) {
		String client = session.getClient() != null ? session.getClient() : "Unknown app";
		return session.getOs() != null ? client + " · " + session.getOs() : client;
	}

	/** Bumps the last-active timestamp on token refresh. Silent if revoked. */
	public void touch(String sessionId) {
		if (sessionId == null) return;
		sessions.findById(sessionId).ifPresent(session -> {
			session.setLastActiveAt(Instant.now());
			sessions.save(session);
		});
	}

	/** True when the session still exists (i.e. has not been revoked). */
	public boolean isActive(String sessionId) {
		return sessionId != null && sessions.existsById(sessionId);
	}

	public List<RefreshSession> list(String userId) {
		return sessions.findByUserIdOrderByLastActiveAtDesc(userId);
	}

	/** Revokes one session; only the owner may. No-op if it isn't theirs. */
	public void revoke(String userId, String sessionId) {
		sessions.findById(sessionId)
				.filter(session -> session.getUserId().equals(userId))
				.ifPresent(session -> {
					sessions.delete(session);
					userEvents.revoked(userId, sessionId); // sign that device out now
				});
	}

	public void revokeOthers(String userId, String keepSessionId) {
		if (keepSessionId != null) {
			sessions.deleteByUserIdAndIdNot(userId, keepSessionId);
		}
		else {
			sessions.deleteByUserId(userId);
		}
		userEvents.revokedOthers(userId, keepSessionId); // sign the other devices out now
	}

	public void revokeAll(String userId) {
		sessions.deleteByUserId(userId);
		userEvents.revokedAll(userId); // sign every device out now
	}

	/** Masks the last two octets of an IPv4 address (best-effort for IPv6). */
	static String mask(String ip) {
		if (ip == null || ip.isBlank()) return null;
		String[] parts = ip.split("\\.");
		if (parts.length == 4) {
			return parts[0] + "." + parts[1] + ".xx.xx";
		}
		int cut = ip.lastIndexOf(':');
		return cut > 0 ? ip.substring(0, cut) + ":xx" : ip;
	}

	private record Agent(RefreshSession.Kind kind, String os, String client, String app) {
	}

	/**
	 * Minimal User-Agent classification — enough to label a session row. Native
	 * hinata clients send their own UA (e.g. "hinata/2.4 (iOS 18.2)").
	 */
	private static Agent parse(String ua) {
		String s = ua == null ? "" : ua;
		String lower = s.toLowerCase();

		boolean native_ = lower.contains("hinata") || lower.contains("dart");
		String app = native_ ? "Mobile" : "Web";

		RefreshSession.Kind kind = RefreshSession.Kind.desktop;
		if (lower.contains("ipad") || lower.contains("tablet")) {
			kind = RefreshSession.Kind.tablet;
		}
		else if (lower.contains("iphone") || lower.contains("android") || lower.contains("mobile")) {
			kind = RefreshSession.Kind.phone;
		}

		String os = "Unknown";
		if (lower.contains("ipad")) os = "iPadOS";
		else if (lower.contains("iphone") || lower.contains("ios")) os = "iOS";
		else if (lower.contains("android")) os = "Android";
		else if (lower.contains("mac os") || lower.contains("macintosh") || lower.contains("macos")) os = "macOS";
		else if (lower.contains("windows")) os = "Windows";
		else if (lower.contains("linux")) os = "Linux";

		String client = "Browser";
		if (native_) client = "hinata app";
		else if (lower.contains("edg")) client = "Edge";
		else if (lower.contains("chrome")) client = "Chrome";
		else if (lower.contains("firefox")) client = "Firefox";
		else if (lower.contains("safari")) client = "Safari";

		return new Agent(kind, os, client, app);
	}
}
