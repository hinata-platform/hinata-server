package com.ahmadre.hinata.common;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The open streams of a registry by who opened them (HIN-172), so all of a person's streams can
 * be closed the moment their sessions are revoked instead of running out their timeout.
 */
public final class ViewerStreams {

	private final Map<String, Set<SseEmitter>> byViewer = new ConcurrentHashMap<>();

	public void add(String viewerId, SseEmitter emitter) {
		if (viewerId != null) {
			byViewer.computeIfAbsent(viewerId, id -> ConcurrentHashMap.newKeySet()).add(emitter);
		}
	}

	public void remove(String viewerId, SseEmitter emitter) {
		if (viewerId == null) {
			return;
		}
		byViewer.computeIfPresent(viewerId, (id, streams) -> {
			streams.remove(emitter);
			return streams.isEmpty() ? null : streams;
		});
	}

	/** Closes every stream [viewerId] holds; each one's completion takes it out of its registry. */
	public void closeAll(String viewerId) {
		Set<SseEmitter> streams = viewerId == null ? null : byViewer.remove(viewerId);
		if (streams != null) {
			streams.forEach(SseEmitter::complete);
		}
	}
}
