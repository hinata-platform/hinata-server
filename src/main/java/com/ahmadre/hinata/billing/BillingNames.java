package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The names behind the ids a page of rates, report groups or invoice lines carries — looked up
 * once per kind and page, never once per row.
 */
@Component
@RequiredArgsConstructor
class BillingNames {

	/** A name and a second line: a project's key, an issue's readable id, a person's username. */
	record Name(String label, String detail) {
	}

	private final MongoTemplate mongo;

	Map<String, Name> projects(Collection<String> ids) {
		return named(Project.class, ids, document -> new Name(document.getString("name"), document.getString("key")),
				"name", "key");
	}

	Map<String, Name> issues(Collection<String> ids) {
		return named(Issue.class, ids,
				document -> new Name(document.getString("title"), document.getString("readableId")),
				"title", "readableId");
	}

	Map<String, Name> users(Collection<String> ids) {
		return named(User.class, ids, document -> new Name(firstNonBlank(document.getString("displayName"),
				document.getString("username")), document.getString("username")), "displayName", "username");
	}

	Map<String, Name> teams(Collection<String> ids) {
		return named(Team.class, ids, document -> new Name(document.getString("name"), document.getString("key")),
				"name", "key");
	}

	/** The project an issue belongs to, or null when the issue is gone. */
	String projectOfIssue(String issueId) {
		Query query = Query.query(Criteria.where("_id").is(issueId));
		query.fields().include("projectId");
		Document issue = mongo.query(Issue.class).as(Document.class).matching(query).firstValue();
		return issue == null ? null : issue.getString("projectId");
	}

	/**
	 * Which of [projectIds] the reader is in — member or lead — and so may read the content of:
	 * a project's name and its issues' titles. An organisation administrator prices and bills
	 * every project but reads the content only of those they are in (HIN-129); the key of a
	 * project and the readable id of an issue are what billing needs and stay visible.
	 */
	Set<String> readable(String viewerId, Collection<String> projectIds) {
		Set<String> wanted = new LinkedHashSet<>();
		projectIds.stream().filter(Objects::nonNull).forEach(wanted::add);
		if (wanted.isEmpty()) {
			return Set.of();
		}
		Query query = Query.query(new Criteria().andOperator(Criteria.where("_id").in(wanted),
				new Criteria().orOperator(Criteria.where("memberIds").is(viewerId),
						Criteria.where("leadIds").is(viewerId), Criteria.where("leadId").is(viewerId))));
		query.fields().include("_id");
		Set<String> readable = new LinkedHashSet<>();
		for (Document project : mongo.query(Project.class).as(Document.class).matching(query).all()) {
			readable.add(String.valueOf(project.get("_id")));
		}
		return readable;
	}

	/** The project of each of [issueIds]. */
	Map<String, String> projectsOfIssues(Collection<String> issueIds) {
		Set<String> wanted = new LinkedHashSet<>();
		issueIds.stream().filter(Objects::nonNull).forEach(wanted::add);
		if (wanted.isEmpty()) {
			return Map.of();
		}
		Query query = Query.query(Criteria.where("_id").in(wanted));
		query.fields().include("projectId");
		Map<String, String> found = new HashMap<>();
		for (Document issue : mongo.query(Issue.class).as(Document.class).matching(query).all()) {
			if (issue.getString("projectId") != null) {
				found.put(String.valueOf(issue.get("_id")), issue.getString("projectId"));
			}
		}
		return found;
	}

	boolean exists(Class<?> type, String id) {
		return id != null && mongo.exists(Query.query(Criteria.where("_id").is(id)), type);
	}

	/** The teams each person belongs to — every team, read once; teams are few. */
	Map<String, List<String>> teamsByPerson() {
		Query query = new Query();
		query.fields().include("members.userId");
		Map<String, List<String>> byPerson = new HashMap<>();
		for (Document team : mongo.query(Team.class).as(Document.class).matching(query).all()) {
			String teamId = String.valueOf(team.get("_id"));
			for (Document member : team.getList("members", Document.class, List.of())) {
				String userId = member.getString("userId");
				if (userId != null) {
					byPerson.computeIfAbsent(userId, id -> new java.util.ArrayList<>()).add(teamId);
				}
			}
		}
		return byPerson;
	}

	private Map<String, Name> named(Class<?> type, Collection<String> ids,
			java.util.function.Function<Document, Name> name, String... fields) {
		Set<String> wanted = new LinkedHashSet<>();
		ids.stream().filter(Objects::nonNull).forEach(wanted::add);
		if (wanted.isEmpty()) {
			return Map.of();
		}
		Query query = Query.query(Criteria.where("_id").in(wanted));
		for (String field : fields) {
			query.fields().include(field);
		}
		Map<String, Name> found = new HashMap<>();
		for (Document document : mongo.query(type).as(Document.class).matching(query).all()) {
			found.put(String.valueOf(document.get("_id")), name.apply(document));
		}
		return found;
	}

	private static String firstNonBlank(String first, String second) {
		return first != null && !first.isBlank() ? first : second;
	}
}
