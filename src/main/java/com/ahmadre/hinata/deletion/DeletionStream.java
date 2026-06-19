package com.ahmadre.hinata.deletion;

import com.ahmadre.hinata.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Thin progress channel over a single {@link SseEmitter} for one cascading
 * delete. The destructive work runs on a background thread (see
 * {@link DeletionService}); this object lets it report each {@code progress}
 * step, a terminal {@code done} summary, or a localized {@code error} — then
 * closes the stream. The frontend renders a live progress bar from these frames.
 *
 * <p>Phases are sent as stable string keys (e.g. {@code "deletingSprints"}); the
 * client localizes them, so no human text crosses the wire except resolved error
 * messages (which carry an i18n {@link ApiException} key resolved here against
 * the request locale captured when the stream opened).
 */
@Slf4j
public class DeletionStream {

	private final SseEmitter emitter;
	private final MessageSource messages;
	private final Locale locale;
	private boolean closed;

	DeletionStream(SseEmitter emitter, MessageSource messages, Locale locale) {
		this.emitter = emitter;
		this.messages = messages;
		this.locale = locale;
	}

	/**
	 * A stream that discards every frame — lets the same cascade run from a plain
	 * (non-streaming) {@code DELETE} request without an open SSE connection.
	 */
	static DeletionStream noop() {
		DeletionStream stream = new DeletionStream(null, null, null);
		stream.closed = true;
		return stream;
	}

	/** Reports one cascade step. {@code total <= 0} means "indeterminate". */
	public void progress(String phase, int current, int total) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("phase", phase);
		data.put("current", current);
		data.put("total", total);
		send("progress", data);
	}

	/** A single-shot step with no sub-progress (current == total == 1). */
	public void step(String phase) {
		progress(phase, 1, 1);
	}

	/** Terminal success: emits the summary and closes the stream. */
	public void done(Object summary) {
		send("done", Map.of("summary", summary));
		complete();
	}

	/** Terminal failure: emits the localized message and closes the stream. */
	public void failed(ApiException ex) {
		send("error", Map.of("message", localize(ex)));
		complete();
	}

	/** Terminal failure for an unexpected error (no internals leaked). */
	public void failedUnexpected() {
		send("error", Map.of("message",
				messages.getMessage("error.internal", null, "error.internal", locale)));
		complete();
	}

	private void send(String event, Object data) {
		if (closed) {
			return;
		}
		try {
			emitter.send(SseEmitter.event().name(event).data(data));
		}
		catch (IOException | IllegalStateException ex) {
			// Client disconnected mid-delete: the cascade still completes server-side
			// (it must finish to keep the DB consistent); we just stop streaming.
			closed = true;
			log.debug("Deletion stream closed early: {}", ex.getMessage());
		}
	}

	private void complete() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			emitter.complete();
		}
		catch (Exception ex) {
			log.debug("Completing deletion stream failed: {}", ex.getMessage());
		}
	}

	private String localize(ApiException ex) {
		return messages.getMessage(ex.getMessageKey(), ex.getArgs(), ex.getMessageKey(), locale);
	}
}
