package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.common.ApiException;
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
 * out are one's own, an administrator's, or the business of somebody an operator named to keep
 * absences ({@link #requireKeeper}, {@link AbsenceKeepers}). A lead reads absences, not hours.
 */
@Component
@RequiredArgsConstructor
public class AvailabilityAccess {

	/** How far back recorded time ties a person to the leads of a project. */
	static final Period WORKED_ON = Period.ofYears(1);

	/** Most led projects one check looks at; beyond this the $in stops being cheap. */
	static final int LED_PROJECTS_MAX = 500;

	private final ObjectProvider<AvailabilityPolicy> policy;
	/**
	 * Whether somebody keeps absences without being an administrator. Injected the same way and
	 * for the same reason as the policy: with absence management absent or off, the answer is the
	 * one this module always gave.
	 */
	private final ObjectProvider<AbsenceKeepers> keepers;
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

	/**
	 * The person a write, a pattern or a capacity is about: oneself, or anybody for somebody who
	 * keeps absences.
	 *
	 * <p>"Keeps absences" was the administrators alone until an operator could name a narrower
	 * circle (HIN-116). Naming one has to mean something on the day they use it: a keeper who could
	 * grant a year of leave but not enter the absence it is taken as would be a role that reads and
	 * never acts.
	 */
	public User requireKeeper(User viewer, String userId) {
		if (userId == null || userId.isBlank() || userId.equals(viewer.getId())) {
			return viewer;
		}
		if (!keeps(viewer)) {
			throw ApiException.forbidden("error.availability.forbidden");
		}
		return users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
	}

	/** Whether [viewer] may write somebody else's absences at all. */
	public boolean keeps(User viewer) {
		return viewer != null
				&& (viewer.isAdmin() || keepers.getIfAvailable(AbsenceKeepers::adminsOnly).keeps(viewer));
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
	 * Whether [viewer] leads a project [userId] can still see, as a direct member or through a team,
	 * and recorded time on within {@link #WORKED_ON}. The led projects first: they are few and cheap
	 * to find, and most readers lead none, so the entries are only read for a lead.
	 */
	private boolean leadsSomebodyWhoWorkedFor(User viewer, String userId, AvailabilityPolicy current) {
		LocalDate since = LocalDate.now(clock).minus(WORKED_ON);
		Set<String> direct = led(viewer, Criteria.where("memberIds").is(userId));
		if (!direct.isEmpty() && !current.projectsWorkedOn(userId, direct, since).isEmpty()) {
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
		Set<String> throughTeams = led(viewer, Criteria.where("_id").in(granted));
		return !throughTeams.isEmpty() && !current.projectsWorkedOn(userId, throughTeams, since).isEmpty();
	}

	/** The ids of the projects [viewer] leads that match [which], at most {@link #LED_PROJECTS_MAX}. */
	private Set<String> led(User viewer, Criteria which) {
		Criteria leads = new Criteria().orOperator(
				Criteria.where("leadIds").is(viewer.getId()),
				Criteria.where("leadId").is(viewer.getId()));
		Query query = Query.query(new Criteria().andOperator(leads, which)).limit(LED_PROJECTS_MAX);
		query.fields().include("_id");
		Set<String> ids = new HashSet<>();
		mongo.query(Project.class).as(Document.class).matching(query).all()
				.forEach(project -> ids.add(String.valueOf(project.get("_id"))));
		return ids;
	}
}
