package com.ahmadre.hinata.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleController;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The new response DTOs decouple the HTTP contract from the
 * {@code @Document} entities, but must serialize to <em>byte-identical</em> JSON
 * so no client {@code fromJson} change is required. These tests pin that
 * equivalence — if a DTO ever drifts from its entity's wire shape, they fail.
 */
class ResponseDtoParityTest {

	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

	@Test
	void notificationResponse_matchesEntityJson() {
		Notification entity = Notification.builder()
				.id("n1").userId("u1").type(Notification.Type.MENTION)
				.title("You were mentioned").body("in HIN-1")
				.link("/issues/HIN-1").read(true).createdAt(Instant.parse("2026-07-19T10:15:30Z"))
				.build();

		JsonNode entityJson = mapper.valueToTree(entity);
		JsonNode dtoJson = mapper.valueToTree(NotificationController.NotificationResponse.from(entity));

		assertThat(dtoJson).isEqualTo(entityJson);
		// Spot-check the fields the client actually reads.
		assertThat(dtoJson.get("type").asText()).isEqualTo("MENTION");
		assertThat(dtoJson.get("read").asBoolean()).isTrue();
	}

	@Test
	void articleResponse_matchesEntityJson() {
		Article entity = Article.builder()
				.id("a1").projectId("p1").teamId(null).parentId("root")
				.space("Engineering").icon("file-text").title("Runbook")
				.content("See {{issue:HIN-1}}").tags(List.of("ops", "oncall"))
				.authorId("u1").sortOrder(3)
				.createdAt(Instant.parse("2026-07-01T08:00:00Z"))
				.updatedAt(Instant.parse("2026-07-19T09:00:00Z"))
				.build();

		JsonNode entityJson = mapper.valueToTree(entity);
		JsonNode dtoJson = mapper.valueToTree(ArticleController.ArticleResponse.from(entity));

		assertThat(dtoJson).isEqualTo(entityJson);
		assertThat(dtoJson.get("tags")).hasSize(2);
		assertThat(dtoJson.get("content").asText()).contains("{{issue:HIN-1}}");
	}
}
