package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * How much of somebody's availability a viewer may see.
 *
 * <ul>
 * <li>One's own: everything.</li>
 * <li>An administrator: everything, because an administrator also keeps absences for others.</li>
 * <li>A lead of a project the person can see, while {@link AvailabilityPolicy} allows it: the type
 * and the span of absences and the capacity they leave, never a note.</li>
 * <li>Everybody else: nothing, not even that there is anything.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AvailabilityAccess {

	/** Most led projects one check looks at; beyond this the $in stops being cheap. */
	static final int LED_PROJECTS_MAX = 500;

	private final ObjectProvider<AvailabilityPolicy> policy;
	private final MongoTemplate mongo;
	private final ProjectReach reach;
	private final UserRepository users;

	public enum Sight {
		FULL, TYPE_AND_SPAN, NONE
	}

	public Sight of(User viewer, String userId) {
		if (viewer.getId().equals(userId) || viewer.isAdmin()) {
			return Sight.FULL;
		}
		AvailabilityPolicy current = policy.getIfAvailable();
		if (current == null || !current.leadsSeeMemberAbsences()) {
			return Sight.NONE;
		}
		return leadsSomebodyWhoSees(viewer, userId) ? Sight.TYPE_AND_SPAN : Sight.NONE;
	}

	/**
	 * Whether [viewer] leads a project [userId] can see: as a direct member, asked in the query, or
	 * through a team, asked once for all led projects.
	 */
	private boolean leadsSomebodyWhoSees(User viewer, String userId) {
		Criteria leads = new Criteria().orOperator(
				Criteria.where("leadIds").is(viewer.getId()),
				Criteria.where("leadId").is(viewer.getId()));
		if (mongo.exists(Query.query(new Criteria().andOperator(leads, Criteria.where("memberIds").is(userId))),
				Project.class)) {
			return true;
		}
		User person = users.findById(userId).orElse(null);
		if (person == null) {
			return false;
		}
		Set<String> granted = reach.teamGrantedProjectIds(person);
		if (granted.isEmpty()) {
			return false;
		}
		Query led = Query.query(new Criteria().andOperator(leads, Criteria.where("_id").in(granted)))
				.limit(LED_PROJECTS_MAX);
		led.fields().include("_id");
		return !mongo.query(Project.class).as(Document.class).matching(led).all().isEmpty();
	}
}
