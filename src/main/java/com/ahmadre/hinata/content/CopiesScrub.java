package com.ahmadre.hinata.content;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scrubs the copies of content from every package that keeps one: once per request, with all the
 * content that request removed or deleted, so a thread with replies or a hundred issues deleted at
 * once is one pass per kind of copy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CopiesScrub {

	private final ObjectProvider<ContentCopies> copies;

	/**
	 * Hands [sources] to each kind of copy. One that fails is logged and the others still run:
	 * a bell that keeps a stale line must not keep a reply's quote as well.
	 */
	public void scrub(List<CopySource> sources, ScrubReason reason, Placeholders placeholders) {
		if (sources.isEmpty()) {
			return;
		}
		for (ContentCopies kind : copies.orderedStream().toList()) {
			try {
				kind.scrub(sources, reason, placeholders);
			}
			catch (RuntimeException ex) {
				log.warn("Scrubbing {} copies of {} item(s) failed", kind.getClass().getSimpleName(),
						sources.size(), ex);
			}
		}
	}
}
