package com.ahmadre.hinata.issue;

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
 * In-memory pub/sub of issue-link changes per issue, streamed to connected
 * clients over Server-Sent Events. Every viewer of an issue sees links added or
 * removed in real time — including the issue on the <em>other</em> end of a
 * link, which is published to as well, so a "blocks" added from issue A appears
 * live as "is blocked by" on issue B.
 *
 * <p>A change carries no payload: the perspective-correct verb differs per side,
 * so subscribers simply re-fetch their own grouped links on a {@code changed}
 * ping. Mirrors {@link com.ahmadre.hinata.storage.AttachmentEvents}; scope is the
 * single application instance (swap for a shared broker when clustered).
 */
@Slf4j
@Component
public class IssueLinkEvents {

	/** Idle timeout; the client transparently reconnects when the stream ends. */
	private static final long TIMEOUT_MS = 30 * 60 * 1000L;

	private final Map<String, List<SseEmitter>> byIssue = new ConcurrentHashMap<>();

	/** Registers a new SSE subscriber for the given (canonical) issue id. */
	public SseEmitter subscribe(String issueId) {
		SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
		List<SseEmitter> list = byIssue.computeIfAbsent(issueId, k -> new CopyOnWriteArrayList<>());
		list.add(emitter);
		emitter.onCompletion(() -> remove(issueId, emitter));
		emitter.onTimeout(emitter::complete);
		emitter.onError(e -> remove(issueId, emitter));
		try {
			// An initial comment opens the stream immediately and makes buffering
			// proxies (e.g. ngrok) flush, so the client knows it is connected.
			emitter.send(SseEmitter.event().comment("connected"));
		}
		catch (IOException ex) {
			remove(issueId, emitter);
		}
		return emitter;
	}

	/**
	 * Keep-alive heartbeat every ~15s. Without periodic bytes an idle SSE
	 * connection is silently dropped by proxies / mobile networks (a half-open
	 * socket the client's TCP stack never reports as closed); the comment line
	 * keeps it flowing, flushes buffering proxies, and — with the client's idle
	 * watchdog — lets a dead stream be detected and reconnected. The client
	 * ignores {@code :} comment lines, so it never triggers a spurious refresh.
	 */
	@Scheduled(fixedDelay = 15_000L)
	public void heartbeat() {
		for (Map.Entry<String, List<SseEmitter>> entry : byIssue.entrySet()) {
			for (SseEmitter emitter : entry.getValue()) {
				try {
					emitter.send(SseEmitter.event().comment("hb"));
				}
				catch (Exception ex) {
					remove(entry.getKey(), emitter);
				}
			}
		}
	}

	/** Notifies every viewer of {@code issueId} that its links changed. */
	public void publishChanged(String issueId) {
		List<SseEmitter> list = byIssue.get(issueId);
		if (list == null) {
			return;
		}
		for (SseEmitter emitter : list) {
			try {
				emitter.send(SseEmitter.event().name("changed").data(Map.of("issueId", issueId)));
			}
			catch (Exception ex) {
				// Broken pipe / closed tab: drop the subscriber quietly.
				remove(issueId, emitter);
			}
		}
	}

	private void remove(String issueId, SseEmitter emitter) {
		List<SseEmitter> list = byIssue.get(issueId);
		if (list != null) {
			list.remove(emitter);
			if (list.isEmpty()) {
				byIssue.remove(issueId);
			}
		}
	}
}
