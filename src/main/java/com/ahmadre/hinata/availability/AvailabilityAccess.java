package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.HashSet;
import java.util.Set;

/**
 * Who may see and who may keep somebody's availability. The services and the routes ask here, so
 * the rule is written once.
 *
 * <ul>
 * <li>One's own: everything.</li>
 * <li>An administrator: everything, because an administrator also keeps absences for others.</li>
 * <li>A lead, while {@link AvailabilityPolicy} allows it: the type and the span of absences, never a
 * note, and a sick day only as an absence ({@link #typeFor}). Only for somebody who recorded time
 * themselves within {@link #WORKED_ON} on a project the viewer leads and the person still belongs
 * to ({@link AvailabilityPolicy#projectsWorkedOn}): membership alone is not enough, because whoever
 * creates a project leads it and can add anybody.</li>
 * <li>Everybody else: nothing, not even that there is anything.</li>
 * </ul>
 *
 * <p>Keeping is narrower than seeing: absences, working-time patterns and the capacity they spell
 * out are one's own or an administrator's ({@link #requireKeeper}). A lead reads absences, not hours.
 */
@Component
@RequiredArgsConstructor
public class AvailabilityAccess {

	/** How far back recorded time ties a person to the leads of a project. */
	static final Period WORKED_ON = Period.ofYears(1);

	private final ObjectProvider<AvailabilityPolicy> policy;
	private final MongoTemplate mongo;
	private final ProjectReach reach;
	private final UserRepository users;
	private final Clock clock;

	public enum Sight {
		FULL, TYPE_AND_SPAN, NONE
	}

	/** Whose availability a read is about, and how much of it the reader sees. */
	public record Visible(String userId, Sight sight) {
	}

	public Sight of(User viewer, String userId) {
		if (viewer.getId().equals(userId) || viewer.isAdmin()) {
			return Sight.FULL;
		}
		AvailabilityPolicy current = policy.getIfAvailable();
		if (current == null || !current.leadsSeeMemberAbsences()) {
			return Sight.NONE;
		}
		return leadsSomebodyWhoWorkedFor(viewer, userId, current) ? Sight.TYPE_AND_SPAN : Sight.NONE;
	}

	/** The person a read names, oneself when blank, and what the viewer sees of them; 403 for nothing. */
	public Visible requireVisible(User viewer, String userId) {
		String person = userId == null || userId.isBlank() ? viewer.getId() : userId;
		Sight sight = of(viewer, person);
		if (sight == Sight.NONE) {
			throw ApiException.forbidden("error.availability.forbidden");
		}
		return new Visible(person, sight);
	}

	/** The person a write, a pattern or a capacity is about: oneself, or anybody for an administrator. */
	public User requireKeeper(User viewer, String userId) {
		if (userId == null || userId.isBlank() || userId.equals(viewer.getId())) {
			return viewer;
		}
		if (!viewer.isAdmin()) {
			throw ApiException.forbidden("error.availability.forbidden");
		}
		return users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
	}

	/**
	 * The type a reader is shown. Anybody who does not see everything sees a sick day as
	 * {@code OTHER}: being ill is health data (Art. 9 DSGVO), and planning only needs to know that
	 * somebody is away.
	 */
	public static TimeOff.Type typeFor(TimeOff.Type type, Sight sight) {
		return sight != Sight.FULL && type == TimeOff.Type.SICK ? TimeOff.Type.OTHER : type;
	}

	/**
	 * Whether [viewer] leads a project [userId] recorded time on within {@link #WORKED_ON} and can
	 * still see: as a direct member, or through a team.
	 */
	private boolean leadsSomebodyWhoWorkedFor(User viewer, String userId, AvailabilityPolicy current) {
		Set<String> worked = current.projectsWorkedOn(userId, LocalDate.now(clock).minus(WORKED_ON));
		if (worked.isEmpty()) {
			return false;
		}
		Criteria leads = new Criteria().orOperator(
				Criteria.where("leadIds").is(viewer.getId()),
				Criteria.where("leadId").is(viewer.getId()));
		if (mongo.exists(Query.query(new Criteria().andOperator(leads, Criteria.where("_id").in(worked),
				Criteria.where("memberIds").is(userId))), Project.class)) {
			return true;
		}
		User person = users.findById(userId).orElse(null);
		if (person == null) {
			return false;
		}
		Set<String> granted = new HashSet<>(reach.teamGrantedProjectIds(person));
		granted.retainAll(worked);
		return !granted.isEmpty()
				&& mongo.exists(Query.query(new Criteria().andOperator(leads, Criteria.where("_id").in(granted))),
						Project.class);
	}
}
