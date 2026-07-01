package com.ahmadre.hinata.media;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sweeps orphaned inline-Markdown images from object storage.
 *
 * <p>Inline media is referenced only by URL inside free-form Markdown (issue
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

	/** Every {@code /api/v1/media/{uuid}} reference in a chunk of Markdown. */
	private static final Pattern REFERENCE = Pattern.compile("/api/v1/media/([0-9a-fA-F-]{36})");

	/** (collection, Markdown field) pairs that can embed inline media. */
	private static final List<String[]> SOURCES = List.of(
			new String[] { "issues", "description" },
			new String[] { "issue_comments", "text" },
			new String[] { "articles", "content" });

	private final StorageService storage;
	private final MongoTemplate mongo;
	private final HinataProperties properties;

	/** Runs daily at 03:45 server time (just after the nightly audit sweep). */
	@Scheduled(cron = "0 45 3 * * *")
	public void sweep() {
		int graceHours = properties.getStorage().getMediaOrphanGraceHours();
		if (graceHours <= 0 || !storage.isConfigured()) {
			return; // sweep disabled or storage not configured
		}

		Set<String> referenced = collectReferencedIds();
		Instant cutoff = Instant.now().minus(Duration.ofHours(graceHours));

		int deleted = 0;
		for (StorageService.ObjectInfo object : storage.list(MediaService.PREFIX)) {
			String id = object.key().substring(MediaService.PREFIX.length());
			// Reap only objects that are unreferenced AND older than the grace
			// window (a just-uploaded image whose content isn't saved yet still
			// has a recent last-modified time, so it survives).
			boolean orphan = !referenced.contains(id)
					&& object.lastModified() != null
					&& !object.lastModified().isAfter(cutoff);
			if (orphan) {
				storage.delete(object.key());
				deleted++;
			}
		}
		if (deleted > 0) {
			log.info("[media] orphan sweep removed {} unreferenced image(s)", deleted);
		}
	}

	/** Scans every media-bearing document and collects the ids they reference. */
	private Set<String> collectReferencedIds() {
		Set<String> ids = new HashSet<>();
		for (String[] source : SOURCES) {
			String collection = source[0];
			String field = source[1];
			// Only pull documents that actually mention the media path, and project
			// just the Markdown field — keeps the scan light on large collections.
			// Literal path — no regex metacharacters, so a plain substring match.
			Query query = new Query(Criteria.where(field).regex("/api/v1/media/"));
			query.fields().include(field);
			try (Stream<Document> stream = mongo.stream(query, Document.class, collection)) {
				stream.forEach(document -> extractIds(document.getString(field), ids));
			}
		}
		return ids;
	}

	private static void extractIds(String markdown, Set<String> into) {
		if (markdown == null || markdown.isEmpty()) {
			return;
		}
		Matcher matcher = REFERENCE.matcher(markdown);
		while (matcher.find()) {
			into.add(matcher.group(1));
		}
	}
}
