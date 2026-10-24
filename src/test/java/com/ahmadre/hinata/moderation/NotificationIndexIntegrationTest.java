package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationIndexCleanup;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two reads of the bell this stage leans on run off their indexes (HIN-169): a person's
 * newest notices a page at a time, without sorting their whole history first, and every notice
 * about one issue, found by its link when the issue's words are scrubbed.
 */
class NotificationIndexIntegrationTest extends RemovalIntegrationSupport {

	@Autowired
	private NotificationIndexCleanup cleanup;

	@Test
	void theBellAndTheScrubReadOffTheirIndexes() {
		List<Notification> rows = new ArrayList<>();
		Instant now = Instant.now();
		for (int i = 0; i < 3000; i++) {
			rows.add(Notification.builder().userId("u" + (i % 3)).type(Notification.Type.ISSUE_UPDATED)
					.title("ACME-" + (i % 50)).body("Text " + i).link("/issues/ACME-" + (i % 50))
					.createdAt(now.minusSeconds(i)).build());
		}
		mongo.insertAll(rows);

		Document bell = mongo.getCollection("notifications")
				.find(new Document("userId", "u1"))
				.sort(new Document("createdAt", -1).append("_id", -1))
				.limit(25)
				.explain();
		String bellPlan = bell.get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson();
		assertThat(bellPlan).contains("IXSCAN").contains("user_created").doesNotContain("\"SORT\"");

		Document scrub = mongo.getCollection("notifications")
				.find(new Document("$or", List.of(new Document("link", "/issues/ACME-4"),
						new Document("link", Pattern.compile("^" + Pattern.quote("/issues/ACME-4?"))))))
				.explain();
		String scrubPlan = scrub.get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson();
		assertThat(scrubPlan).contains("IXSCAN").contains("link_id").doesNotContain("COLLSCAN");
	}

	/** An older server's index on {@code userId} alone goes; the bell's own serves what it served. */
	@Test
	void theIndexOnThePersonAloneIsDropped() {
		mongo.indexOps(Notification.class).createIndex(new Index().on("userId", Sort.Direction.ASC).named("userId"));

		cleanup.run(null);

		assertThat(mongo.indexOps(Notification.class).getIndexInfo()).extracting(IndexInfo::getName)
				.doesNotContain("userId").contains("user_created");
		String unread = mongo.getCollection("notifications").find(new Document("userId", "u1").append("read", false))
				.explain().get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson();
		assertThat(unread).contains("user_created").doesNotContain("COLLSCAN");
	}
}
