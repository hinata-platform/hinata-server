package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who can lift what: the leads of a project, and the administrators.
 *
 * <p>Asked by the approvals (who decides a submission, whose inbox it lands in),
 * by the correction requests (who is asked, who may answer) and by the entry
 * rules (who may manage somebody else's entry, who sees a member's rows). One
 * answer for all of them, because a request that went to one set of people and
 * could only be answered by another would be a request nobody can close.
 */
@Component
@RequiredArgsConstructor
class TimeApprovers {

	/** Most projects one inbox query narrows to. Beyond this the $in stops being cheap. */
	static final int MAX_LED_PROJECTS = 500;

	private final ProjectService projects;
	private final UserRepository users;
	private final MongoTemplate mongo;

	/**
	 * The ids of the projects this person leads, archived ones included.
	 *
	 * <p>Archived deliberately: a closed project's books are exactly the ones still
	 * needing a reopen. Asked of the project collection directly and only for ids,
	 * because this runs on every page of an inbox — also for members who lead
	 * nothing — and loading every visible project in full to find none would cost a
	 * member of two hundred projects two hundred documents per page. Capped, because
	 * an $in is not free.
	 */
	Collection<String> ledProjectIds(User user) {
		Query query = Query.query(new Criteria().orOperator(
						Criteria.where("leadIds").is(user.getId()),
						Criteria.where("leadId").is(user.getId())))
				.limit(MAX_LED_PROJECTS);
		query.fields().include("_id");
		return mongo.query(Project.class).as(Document.class).matching(query).all().stream()
				.map(WorkItemDocuments::id)
				.toList();
	}

	/**
	 * Whether {@code user} leads the project — the authority an approval needs, and
	 * the one {@code ProjectService.isLeadOrAdmin} states. False for no project.
	 */
	boolean leads(String projectId, User user) {
		return projectId != null && projects.findOptional(projectId)
				.map(project -> projects.isLeadOrAdmin(project, user))
				.orElse(false);
	}

	/** The leads of a project, or the administrators when it has none. */
	Set<String> approverIds(String projectId) {
		Set<String> ids = new LinkedHashSet<>();
		projects.findOptional(projectId).ifPresent(project -> {
			if (project.getLeadIds() != null) {
				ids.addAll(project.getLeadIds());
			}
			if (project.getLeadId() != null) {
				ids.add(project.getLeadId());
			}
		});
		if (ids.isEmpty()) {
			// A project with no lead still has to have somebody to ask, or the
			// Art.-16 route would be a button that notifies nobody.
			ids.addAll(adminIds());
		}
		return ids;
	}

	/**
	 * Active administrators.
	 *
	 * <p>Through the repository's own query rather than by filtering every user:
	 * this is reached from the Art.-16 routes, which anybody may call, and draining
	 * the user collection per request is how a courtesy feature becomes a way to
	 * make the server work.
	 */
	Set<String> adminIds() {
		return users.findByRolesContainingAndActiveIsTrue(Role.ADMIN).stream()
				.map(User::getId)
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}
}
