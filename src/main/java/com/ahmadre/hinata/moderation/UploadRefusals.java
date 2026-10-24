package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.storage.UploadRefusedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Counts every upload the gate refused into the yearly figures (HIN-177), by kind of upload. */
@Slf4j
@Component
@RequiredArgsConstructor
class UploadRefusals {

	private final ModerationStats stats;

	/** In the request that was refused; a count that fails must not turn the refusal into an error. */
	@EventListener
	void on(UploadRefusedEvent refused) {
		try {
			stats.count(StatMetric.UPLOAD_REFUSED, refused.kind().name());
		}
		catch (RuntimeException ex) {
			log.warn("Counting a refused upload ({}, {}) failed: {}", refused.kind(), refused.reason(),
					ex.getMessage());
		}
	}
}
