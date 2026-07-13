package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.issue.Issue;
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
 * In-memory pub/sub of attachment changes per issue, streamed to connected
 * clients over Server-Sent Events. Every viewer of an issue sees uploads and
 * removals in real time – e.g. when several files are uploaded at once or by a
 * teammate in another session.
 *
 * <p>Scope is the single application instance. For a clustered deployment swap
 * the in-memory registry for a shared broker (e.g. Redis pub/sub); the
 * controller contract ({@link #subscribe}/{@link #publishAdded}) is unchanged.
 */
@Slf4j
@Component
public class AttachmentEvents {

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

	public void publishAdded(String issueId, Issue.Attachment attachment) {
		publish(issueId, "added", attachment);
	}

	public void publishRemoved(String issueId, String attachmentId) {
		publish(issueId, "removed", Map.of("id", attachmentId));
	}

	private void publish(String issueId, String name, Object data) {
		List<SseEmitter> list = byIssue.get(issueId);
		if (list == null) {
			return;
		}
		for (SseEmitter emitter : list) {
			try {
				emitter.send(SseEmitter.event().name(name).data(data));
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
