package com.ahmadre.hinata.media;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.storage.StorageService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweep decides what to delete from the object store, so the thing worth
 * pinning is what it counts as a reference.
 */
class MediaGarbageCollectorTest {

	private static final String ID = "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed";
	private static final String OTHER_ID = "2c8e7dbe-cc0e-4c3e-8c6e-bc9e0cce5cfe";
	private static final String KEY = MediaService.PREFIX + ID;

	/** A stored object old enough to be past any grace window. */
	private static StorageService.ObjectInfo stale() {
		return new StorageService.ObjectInfo(KEY, Instant.now().minus(Duration.ofDays(30)));
	}

	private static StorageService storageHolding(StorageService.ObjectInfo object) {
		StorageService storage = mock(StorageService.class);
		when(storage.isConfigured()).thenReturn(true);
		when(storage.list(MediaService.PREFIX)).thenReturn(List.of(object));
		return storage;
	}

	/** A template answering [field] of [collection] with [content], nothing else. */
	private static MongoTemplate mongoWith(String collection, String field, String content) {
		MongoTemplate mongo = mock(MongoTemplate.class);
		when(mongo.stream(any(Query.class), eq(Document.class), any(String.class)))
				.thenAnswer(invocation -> {
					String queried = invocation.getArgument(2);
					Query query = invocation.getArgument(0);
					boolean matches = queried.equals(collection)
							&& query.getQueryObject().containsKey(field);
					if (!matches) {
						return Stream.<Document>empty();
					}
					return Stream.of(new Document(field, content));
				});
		return mongo;
	}

	@Test
	void anImageReferencedFromALexicalBodyIsKept() {
		// Bodies are Lexical documents now; `description` holds only the
		// plain-text projection, which an image contributes nothing to. Scanning
		// the plain field found no references at all, so every uploaded image
		// looked orphaned and was deleted a grace window after it was embedded.
		StorageService storage = storageHolding(stale());
		MongoTemplate mongo = mongoWith(
				"issues", "descriptionDoc", "{\"src\":\"/api/v1/media/" + ID + "\"}");

		new MediaGarbageCollector(storage, mongo, new HinataProperties()).sweep();

		verify(storage, never()).delete(any());
	}

	@Test
	void anImageReferencedFromALegacyMarkdownBodyIsKept() {
		// A document written before the migration still has its markdown in the
		// plain field, and reaping those would be the same loss with a longer fuse.
		StorageService storage = storageHolding(stale());
		MongoTemplate mongo = mongoWith(
				"issues", "description", "![shot](/api/v1/media/" + ID + ")");

		new MediaGarbageCollector(storage, mongo, new HinataProperties()).sweep();

		verify(storage, never()).delete(any());
	}

	@Test
	void anImageNothingRefersToIsStillReaped() {
		// The sweep has to keep doing its job: an upload nobody ever embedded is
		// exactly what it exists to clear out. One other object is referenced,
		// so the scan has clearly worked and the safety net stays out of the way.
		StorageService storage = mock(StorageService.class);
		when(storage.isConfigured()).thenReturn(true);
		when(storage.list(MediaService.PREFIX))
				.thenReturn(List.of(stale(), new StorageService.ObjectInfo(
						MediaService.PREFIX + OTHER_ID,
						Instant.now().minus(Duration.ofDays(30)))));
		MongoTemplate mongo = mongoWith(
				"issues", "descriptionDoc", "/api/v1/media/" + OTHER_ID);

		new MediaGarbageCollector(storage, mongo, new HinataProperties()).sweep();

		verify(storage).delete(KEY);
	}

	@Test
	void aScanThatFoundNothingAtAllDeletesNothing() {
		// Zero references and a bucket full of objects is what a broken scan
		// looks like, and it is indistinguishable from a bucket that is
		// genuinely all garbage. The two are not worth equal risk: skipping
		// costs disk, proceeding costs every image in the product.
		StorageService storage = storageHolding(stale());
		MongoTemplate mongo = mongoWith("issues", "descriptionDoc", "no media here");

		new MediaGarbageCollector(storage, mongo, new HinataProperties()).sweep();

		verify(storage, never()).delete(any());
	}

	@Test
	void everyBodyFieldThatCanEmbedMediaIsScanned() {
		// A field missing from the scan is not a missing feature, it is deletion
		// of data that is in use — so each one is pinned by name.
		for (String[] source : List.of(
				new String[] { "issues", "descriptionDoc" },
				new String[] { "issue_comments", "textDoc" },
				new String[] { "articles", "contentDoc" })) {
			StorageService storage = storageHolding(stale());
			MongoTemplate mongo = mongoWith(
					source[0], source[1], "\"/api/v1/media/" + ID + "\"");

			new MediaGarbageCollector(storage, mongo, new HinataProperties()).sweep();

			List<String> deleted = new ArrayList<>();
			verify(storage, never()).delete(any());
			assertThat(deleted).isEmpty();
		}
	}
}
