package com.ahmadre.hinata.me;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory pub/sub of account-level events per user, streamed to that user's
 * connected clients over Server-Sent Events. Its purpose is immediate,
 * real-time sign-out: when a session is revoked (by the user, by an admin
 * "terminate sessions", a password reset, or account deactivation) the affected
 * device receives a {@code logout} frame and signs out at once, instead of
 * waiting up to a full access-token lifetime for its next request to 401.
 *
 * <p>Each subscriber is tagged with the {@code sid} of the access token that
 * opened it, so revocations can target the right devices — "revoke others"
 * spares the caller's own stream while ending every other one.
 *
 * <p>Scope is the single application instance, mirroring {@code AttachmentEvents}.
 * For a clustered deployment swap the in-memory registry for a shared broker
 * (e.g. Redis pub/sub); the subscribe/publish contract is unchanged.
 */
@Slf4j
@Component
public class UserEvents {

	/** Idle timeout; the client transparently reconnects when the stream ends. */
	private static final long TIMEOUT_MS = 30 * 60 * 1000L;

	private record Subscriber(String sessionId, SseEmitter emitter) {
	}

	private final Map<String, List<Subscriber>> byUser = new ConcurrentHashMap<>();

	/**
	 * Registers a new SSE subscriber for the given user. {@code sessionId} is the
	 * {@code sid} of the opening access token (may be null for legacy tokens).
	 */
	public SseEmitter subscribe(String userId, String sessionId) {
		SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
		Subscriber subscriber = new Subscriber(sessionId, emitter);
		List<Subscriber> list = byUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
		list.add(subscriber);
		emitter.onCompletion(() -> remove(userId, subscriber));
		emitter.onTimeout(emitter::complete);
		emitter.onError(e -> remove(userId, subscriber));
		try {
			// An initial comment opens the stream immediately and makes buffering
			// proxies (e.g. ngrok) flush, so the client knows it is connected.
			emitter.send(SseEmitter.event().comment("connected"));
		}
		catch (IOException ex) {
			remove(userId, subscriber);
		}
		return emitter;
	}

	/**
	 * Keep-alive heartbeat every ~15s. This sign-out stream is long-lived and
	 * almost always idle, so without periodic bytes a half-open socket (proxy /
	 * mobile network) would silently swallow a future {@code logout} — the device
	 * would never sign out in real time. The comment line keeps it flowing and,
	 * with the client's idle watchdog, lets a dead stream be reconnected. The
	 * client ignores {@code :} comment lines.
	 */
	@Scheduled(fixedDelay = 15_000L)
	public void heartbeat() {
		for (Map.Entry<String, List<Subscriber>> entry : byUser.entrySet()) {
			for (Subscriber subscriber : entry.getValue()) {
				try {
					subscriber.emitter().send(SseEmitter.event().comment("hb"));
				}
				catch (Exception ex) {
					remove(entry.getKey(), subscriber);
				}
			}
		}
	}

	/** Signs out the device on a single revoked session. */
	public void revoked(String userId, String sessionId) {
		if (sessionId == null) return;
		logout(userId, sub -> sessionId.equals(sub.sessionId()));
	}

	/** Signs out every device except the one identified by {@code keepSessionId}. */
	public void revokedOthers(String userId, String keepSessionId) {
		logout(userId, sub -> keepSessionId == null || !keepSessionId.equals(sub.sessionId()));
	}

	/** Signs out every device of the user (password reset, deactivation, etc.). */
	public void revokedAll(String userId) {
		logout(userId, sub -> true);
	}

	private void logout(String userId, java.util.function.Predicate<Subscriber> match) {
		List<Subscriber> list = byUser.get(userId);
		if (list == null) {
			return;
		}
		for (Subscriber subscriber : list) {
			if (!match.test(subscriber)) {
				continue;
			}
			try {
				subscriber.emitter().send(SseEmitter.event().name("logout").data("revoked"));
				subscriber.emitter().complete();
			}
			catch (Exception ex) {
				// Broken pipe / already-closed: drop the subscriber quietly.
				remove(userId, subscriber);
			}
		}
	}

	private void remove(String userId, Subscriber subscriber) {
		List<Subscriber> list = byUser.get(userId);
		if (list != null) {
			list.remove(subscriber);
			if (list.isEmpty()) {
				byUser.remove(userId);
			}
		}
	}
}
