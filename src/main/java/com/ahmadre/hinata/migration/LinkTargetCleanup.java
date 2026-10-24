package com.ahmadre.hinata.migration;

import com.ahmadre.hinata.richtext.RichTextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One-time step of the update that brings the text check (HIN-176): links stored before it was
 * there that lead anywhere but {@code http}, {@code https}, {@code mailto} or a path of the app
 * lose their link and keep their words. Until now the app only refused such a link while typing,
 * and markdown from a tool or a mail stored it as it came.
 *
 * <p>Only documents with a link at all are read, each one rewritten in place when one of its
 * links changed. Runs once (a marker in {@code migrations}) and never fails the start; run again,
 * it finds nothing left to do.
 */
@Slf4j
@Component
@Order(47)
@RequiredArgsConstructor
public class LinkTargetCleanup implements ApplicationRunner {

	static final String MARKER_ID = "link-target-cleanup";

	/** A document holding a link node: the only ones worth parsing. */
	private static final Pattern HAS_LINK = Pattern.compile("\"type\"\\s*:\\s*\"(?:auto)?link\"");

	/** Where rich text is stored: collection and field of the document. */
	private record Store(String collection, String field) {
	}

	private static final List<Store> STORES = List.of(new Store("issues", "descriptionDoc"),
			new Store("issue_comments", "textDoc"), new Store("articles", "contentDoc"),
			new Store("article_versions", "contentDoc"));

	private final MigrationMarkers markers;
	private final MongoTemplate mongo;
	private final RichTextService richText;

	@Override
	public void run(ApplicationArguments args) {
		if (markers.done(MARKER_ID)) {
			return;
		}
		try {
			Document counts = new Document();
			for (Store store : STORES) {
				counts.append(store.collection(), clean(store));
			}
			markers.markDone(MARKER_ID, counts);
			log.info("Link targets cleaned: {}", counts.toJson());
		}
		catch (RuntimeException ex) {
			log.error("Cleaning link targets failed, retried at the next start: {}", ex.getMessage());
		}
	}

	/** Rewrites every document of [store] with a link that may not lead where it leads; how many. */
	private int clean(Store store) {
		Query query = Query.query(Criteria.where(store.field()).regex(HAS_LINK));
		query.fields().include(store.field());
		int rewritten = 0;
		// Streamed: an instance with years of comments does not hold them all at once.
		try (Stream<Document> documents = mongo.stream(query, Document.class, store.collection())) {
			for (Document stored : (Iterable<Document>) documents::iterator) {
				Object doc = stored.get(store.field());
				String cleaned = doc instanceof String json ? richText.withSafeLinks(json) : null;
				if (cleaned != null) {
					mongo.updateFirst(Query.query(Criteria.where("_id").is(stored.get("_id"))),
							new Update().set(store.field(), cleaned), store.collection());
					rewritten++;
				}
			}
		}
		return rewritten;
	}
}
