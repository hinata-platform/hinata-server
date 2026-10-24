package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.notification.Notification;
import org.bson.Document;
import org.junit.jupiter.api.Test;

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
}
