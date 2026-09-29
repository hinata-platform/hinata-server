package com.ahmadre.hinata.migration;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One-time tidy-up for HIN-129: every knowledge-base page whose place (project,
 * team, private) differs from its parent's becomes a top-level page in its own
 * place.
 *
 * <p>Before HIN-129 a page and its parent could live in different places, because
 * the place and the parent were saved separately. From now on a page lives where
 * its parent lives, and a Team-Admin opens a page "with everything below it". A
 * mixed tree would make that grant reach into another project, and moving the top
 * page would drag somebody else's page along. Nothing moves to a new place here:
 * each page keeps exactly the readers it has, it only stops hanging under a page
 * of another place.
 *
 * <p>Runs once (a marker in {@code migrations}), never fails the start. Reads ids
 * and places only.
 */
@Slf4j
@Component
@Order(41)
@RequiredArgsConstructor
public class ArticlePlaceBackfill implements ApplicationRunner {

	static final String MARKER_ID = "article-place-follows-parent";

	private final MongoTemplate mongo;
	private final MigrationMarkers markers;

	@Override
	public void run(ApplicationArguments args) {
		if (markers.done(MARKER_ID)) return;
		try {
			Map<Object, Document> byId = new HashMap<>();
			mongo.getCollection("articles").find()
					.projection(new Document("_id", 1).append("parentId", 1).append("projectId", 1)
							.append("teamId", 1).append("authorId", 1))
					.forEach(row -> byId.put(row.get("_id"), row));
			List<Object> detach = new ArrayList<>();
			for (Document row : byId.values()) {
				String parentId = row.getString("parentId");
				if (parentId == null) continue;
				Document parent = byId.get(parentId);
				if (parent == null) {
					parent = byId.get(asObjectId(parentId));
				}
				if (parent != null && !samePlace(row, parent)) {
					detach.add(row.get("_id"));
				}
			}
			if (!detach.isEmpty()) {
				mongo.getCollection("articles").updateMany(Filters.in("_id", detach), Updates.unset("parentId"));
				log.info("ArticlePlaceBackfill: {} page(s) now stand at the top of their own place", detach.size());
			}
			markers.markDone(MARKER_ID, new Document("detached", detach.size()));
		}
		catch (RuntimeException ex) {
			log.warn("ArticlePlaceBackfill failed; will retry on next start", ex);
		}
	}

	/**
	 * The rule of {@code ArticleService.Place}: a project, a team, or one author's
	 * private pages. Two formerly organisation-wide pages by two people are private
	 * to two people now, so they are two places.
	 */
	private static boolean samePlace(Document a, Document b) {
		boolean samePlace = Objects.equals(a.getString("projectId"), b.getString("projectId"))
				&& Objects.equals(a.getString("teamId"), b.getString("teamId"));
		boolean isPrivate = a.getString("projectId") == null && a.getString("teamId") == null;
		return samePlace && (!isPrivate || Objects.equals(a.getString("authorId"), b.getString("authorId")));
	}

	private static Object asObjectId(String id) {
		return org.bson.types.ObjectId.isValid(id) ? new org.bson.types.ObjectId(id) : id;
	}
}
