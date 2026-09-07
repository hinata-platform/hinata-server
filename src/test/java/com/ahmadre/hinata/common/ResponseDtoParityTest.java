package com.ahmadre.hinata.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleController;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationController;
import com.ahmadre.hinata.timetracking.TimeTrackingController;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.time.LocalDate;
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

	/**
	 * Configured to write what the application writes. Dates and instants go on
	 * the wire as ISO-8601 strings — Jackson 3 defaults to that, a bare Jackson 2
	 * {@code ObjectMapper} does not — and a test that asserts "the app reads
	 * {@code 2026-09-07} here" has to serialize the way the app does, or it is
	 * pinning an array of three numbers nobody ever sees.
	 */
	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

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
	 * The work-item DTO exposes the entity whole: the published app parses the
	 * entity's shape, and the 2.0 fields ride along as nullable extras. Strict
	 * equality, so a field added to one side and not the other fails here first.
	 */
	@Test
	void workItemResponse_matchesEntityJson() {
		WorkItem entity = WorkItem.builder()
				.id("w1").issueId("i1").projectId("p1").userId("u1")
				.date(LocalDate.of(2026, 9, 7)).durationMinutes(90)
				.activityType("Testing").description("Smart commit abc1234: fix the thing")
				.createdAt(Instant.parse("2026-09-07T10:15:30Z"))
				.startedAt(Instant.parse("2026-09-07T08:00:00Z"))
				.endedAt(Instant.parse("2026-09-07T09:30:00Z"))
				.billable(true).tags(new ArrayList<>(List.of("deep-work")))
				.source(WorkItem.Source.SMART_COMMIT)
				.updatedAt(Instant.parse("2026-09-07T11:00:00Z")).updatedBy("u2")
				.sharedFromId(null)
				.build();

		JsonNode entityJson = mapper.valueToTree(entity);
		JsonNode dtoJson = mapper.valueToTree(TimeTrackingController.WorkItemResponse.from(entity));

		assertThat(dtoJson).isEqualTo(entityJson);
		// The fields the app reads today, unchanged in name and type …
		assertThat(dtoJson.get("date").asText()).isEqualTo("2026-09-07");
		assertThat(dtoJson.get("durationMinutes").asInt()).isEqualTo(90);
		// … and the 2.0 additions.
		assertThat(dtoJson.get("source").asText()).isEqualTo("SMART_COMMIT");
		assertThat(dtoJson.get("billable").asBoolean()).isTrue();
		assertThat(dtoJson.get("tags")).hasSize(1);
		assertThat(dtoJson.get("sharedFromId").isNull()).isTrue();
	}

	/** An entry written before 2.0 has none of the new fields; it still reads as an app entry. */
	@Test
	void workItemResponse_readsMissingSourceAndTagsAsDefaults() {
		WorkItem legacy = WorkItem.builder().id("w0").source(null).tags(null).build();

		TimeTrackingController.WorkItemResponse dto = TimeTrackingController.WorkItemResponse.from(legacy);

		assertThat(dto.source()).isEqualTo(WorkItem.Source.APP);
		assertThat(dto.tags()).isEmpty();
		assertThat(dto.billable()).isFalse();
	}

}
