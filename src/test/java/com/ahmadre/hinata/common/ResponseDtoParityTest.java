package com.ahmadre.hinata.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleController;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The response DTOs decouple the HTTP contract from the {@code @Document}
 * entities. Every field a DTO exposes must carry exactly its entity's value, so
 * a client {@code fromJson} never has to change — but an entity is allowed to
 * hold fields the contract does not expose, and one that does must keep them
 * off the wire rather than leaking storage internals to clients.
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
				.content("See HIN-1").contentDoc("{\"root\":{}}")
				.referencedIssueKeys(new ArrayList<>(List.of("HIN-1")))
				.tags(List.of("ops", "oncall"))
				.authorId("u1").sortOrder(3)
				.createdAt(Instant.parse("2026-07-01T08:00:00Z"))
				.updatedAt(Instant.parse("2026-07-19T09:00:00Z"))
				.build();

		JsonNode entityJson = mapper.valueToTree(entity);
		JsonNode dtoJson = mapper.valueToTree(ArticleController.ArticleResponse.from(entity));

		// Every exposed field carries its entity value, unchanged.
		dtoJson.properties().forEach(field -> assertThat(field.getValue())
				.as("field %s", field.getKey())
				.isEqualTo(entityJson.get(field.getKey())));
		assertThat(dtoJson.get("tags")).hasSize(2);
		// The client renders the document and previews the derived plain text.
		assertThat(dtoJson.get("contentDoc").asText()).isEqualTo("{\"root\":{}}");
		assertThat(dtoJson.get("content").asText()).isEqualTo("See HIN-1");
		// The derived backlink index is how the server answers ?referencesIssue.
		// It is storage, not contract — clients ask the endpoint, not the field.
		assertThat(dtoJson.has("referencedIssueKeys")).isFalse();
	}

	/**
	 * The migration keeps a copy of the pre-migration markdown so a converter
	 * defect stays recoverable. That copy is a backup, not content: it is not part
	 * of any HTTP contract and must not reach a client — including through the two
	 * entities that are serialized to clients directly, where the response is the
	 * entity rather than a DTO that could simply omit the field.
	 */
	@Test
	void theMigrationShadowFieldsStayOffTheWire() {
		Article article = Article.builder().id("a1").title("Runbook")
				.content("See HIN-1").contentDoc("{\"root\":{}}")
				.contentMd("See {{issue:HIN-1}} and **this**")
				.build();
		Issue issue = Issue.builder().id("i1").title("Login bug")
				.description("Betrifft Rebar").descriptionDoc("{\"root\":{}}")
				.descriptionMd("Betrifft {{user:u1}} und **das**")
				.build();
		IssueComment comment = IssueComment.builder().id("c1").issueId("i1").authorId("u1")
				.text("sieht gut aus").textDoc("{\"root\":{}}")
				.textMd("sieht `gut` aus")
				.build();

		assertThat(mapper.valueToTree(ArticleController.ArticleResponse.from(article))
				.has("contentMd")).isFalse();
		// These two have no DTO — the entity itself is the response body.
		assertThat(mapper.<JsonNode>valueToTree(issue).has("descriptionMd")).isFalse();
		assertThat(mapper.<JsonNode>valueToTree(comment).has("textMd")).isFalse();

		// …and they really are populated, so the assertions above are not vacuous.
		assertThat(article.getContentMd()).isNotNull();
		assertThat(issue.getDescriptionMd()).isNotNull();
		assertThat(comment.getTextMd()).isNotNull();
	}
}
