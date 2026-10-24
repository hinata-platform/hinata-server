package com.ahmadre.hinata.media;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.storage.ImagePreviewService;
import com.ahmadre.hinata.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Sweeps orphaned inline images from object storage.
 *
 * <p>Inline media is referenced only by URL inside free-form content (issue
 * descriptions, comments, KB articles), so there is no per-entity link to drive
 * an immediate delete — and the same image may be pasted in several places.
 * Deleting the moment a URL disappears from one document would therefore risk
 * breaking it elsewhere. Instead this periodically reconciles the bucket against
 * <em>all</em> referencing content and drops only objects that are both
 * unreferenced and older than a grace window (so a freshly uploaded image whose
 * content has not been saved yet is never reaped). This is the same
 * reference-scan approach mature tools use for embedded attachments.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaGarbageCollector {

	private final StorageService storage;
	private final MediaReferences references;
	private final HinataProperties properties;

	/** Runs daily at 03:45 server time (just after the nightly audit sweep). */
	@Scheduled(cron = "0 45 3 * * *")
	public void sweep() {
		int graceHours = properties.getStorage().getMediaOrphanGraceHours();
		if (graceHours <= 0 || !storage.isConfigured()) {
			return; // sweep disabled or storage not configured
		}

		Set<String> referenced = references.referencedIds();
		// media/ alone: what moderation put away lives under quarantine/ and is never reaped here.
		List<StorageService.ObjectInfo> objects = storage.list(MediaService.PREFIX);

		// Nothing references anything, yet there are objects to reap: that is
		// what a broken scan looks like, and it is indistinguishable from a
		// bucket that is genuinely all garbage. The two are not worth equal
		// risk — the cost of skipping is disk, the cost of proceeding is every
		// image in the product, deleted, with nothing to restore from. This is
		// not hypothetical: renaming the body fields to hold Lexical documents
		// turned this sweep into exactly that job.
		if (referenced.isEmpty() && !objects.isEmpty()) {
			log.warn("[media] orphan sweep skipped: {} object(s) in storage and not one "
					+ "reference found — the reference scan is almost certainly wrong",
					objects.size());
			return;
		}

		Instant cutoff = Instant.now().minus(Duration.ofHours(graceHours));

		int deleted = 0;
		for (StorageService.ObjectInfo object : objects) {
			String id = object.key().substring(MediaService.PREFIX.length());
			// Reap only objects that are unreferenced AND older than the grace
			// window (a just-uploaded image whose content isn't saved yet still
			// has a recent last-modified time, so it survives).
			boolean orphan = !referenced.contains(id)
					&& object.lastModified() != null
					&& !object.lastModified().isAfter(cutoff);
			if (orphan) {
				storage.delete(object.key());
				// The generated thumbnail lives under its own prefix (deliberately
				// outside media/, or this very sweep would reap it — nothing
				// references a thumbnail). It has to go with its image.
				storage.delete(ImagePreviewService.mediaThumbnailKey(id));
				deleted++;
			}
		}
		if (deleted > 0) {
			log.info("[media] orphan sweep removed {} unreferenced image(s)", deleted);
		}
	}
}
