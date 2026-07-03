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

/**
 * One-time, idempotent repair of issue readable ids after a project key change.
 * Projects renamed before the {@link ProjectService#reKeyIssues} cascade existed
 * left their issues on the old prefix (e.g. key changed HN2 -&gt; HN but issues
 * stayed HN2-1). For every project this rewrites each issue's {@code readableId}
 * (and the mirrored {@code git_dev_info.issueKey}) to the project's current key.
 * No-op once consistent / on fresh DBs.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ProjectKeyIssueMigration implements ApplicationRunner {

	private final MongoTemplate mongo;
	private final ProjectService projects;

	@Override
	public void run(ApplicationArguments args) {
		MongoCollection<Document> col = mongo.getCollection("projects");
		long repaired = 0;
		for (Document project : col.find()) {
			String key = project.getString("key");
			if (key == null || key.isBlank()) continue;
			String id = String.valueOf(project.get("_id"));
			repaired += projects.reKeyIssues(id, key);
		}
		if (repaired > 0) {
			log.info("ProjectKeyIssueMigration: re-keyed {} issue readableId(s) to their project key", repaired);
		}
	}
}
