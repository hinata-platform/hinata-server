package com.ahmadre.hinata.project;

import com.mongodb.client.MongoCollection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * One-time, idempotent upgrade of legacy project documents to the colored
 * settings schema. Older projects stored {@code workflowStates}/{@code labels}
 * as plain string arrays, which no longer deserialize into the new
 * {@link Project.WorkflowState}/{@link Project.Label} POJOs — so this runs
 * against raw BSON <em>before</em> any typed read (highest precedence runner,
 * which fires before {@code ApplicationReadyEvent} seeders) and rewrites each
 * string element to {@code {id, name, hue}}. Also backfills {@code leadIds}
 * from the legacy single {@code leadId}. No-op once converted / on fresh DBs.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ProjectSchemaMigration implements ApplicationRunner {

	private final MongoTemplate mongo;

	@Override
	public void run(ApplicationArguments args) {
		MongoCollection<Document> col = mongo.getCollection("projects");
		int migrated = 0;
		for (Document doc : col.find()) {
			Document set = new Document();
			List<Document> states = upgradeStates(doc.get("workflowStates"));
			if (states != null) set.put("workflowStates", states);
			List<Document> labels = upgradeLabels(doc.get("labels"));
			if (labels != null) set.put("labels", labels);
			List<String> leadIds = backfillLeadIds(doc);
			if (leadIds != null) set.put("leadIds", leadIds);
			if (!set.isEmpty()) {
				col.updateOne(new Document("_id", doc.get("_id")), new Document("$set", set));
				migrated++;
			}
		}
		if (migrated > 0) {
			log.info("ProjectSchemaMigration: upgraded {} project document(s) to colored schema", migrated);
		}
	}

	/** Returns the converted array, or null when nothing needs migrating. */
	private List<Document> upgradeStates(Object raw) {
		if (!(raw instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof String)) {
			return null;
		}
		List<Document> out = new ArrayList<>();
		for (Object o : list) {
			String name = String.valueOf(o);
			out.add(new Document("id", Project.newId())
					.append("name", name)
					.append("hue", Project.defaultHueForState(name)));
		}
		return out;
	}

	private List<Document> upgradeLabels(Object raw) {
		if (!(raw instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof String)) {
			return null;
		}
		List<Document> out = new ArrayList<>();
		int i = 0;
		for (Object o : list) {
			out.add(new Document("id", Project.newId())
					.append("name", String.valueOf(o))
					.append("hue", Project.labelHueAt(i++)));
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private List<String> backfillLeadIds(Document doc) {
		Object existing = doc.get("leadIds");
		if (existing instanceof List<?> list && !list.isEmpty()) return null;
		Object leadId = doc.get("leadId");
		if (leadId instanceof String s && !s.isBlank()) {
			return List.of(s);
		}
		return null;
	}
}
