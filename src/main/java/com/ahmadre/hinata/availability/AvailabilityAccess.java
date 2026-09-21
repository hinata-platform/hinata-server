package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRepository;
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
import java.util.List;
import java.util.Optional;
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
	private final TeamRepository teams;
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

	/** Why a group is read: to show who is away, or to add up what capacity is left. */
	public enum Purpose {
		CALENDAR, BAND
	}

	/**
	 * The people of a group a viewer may read together (HIN-118), capped at
	 * {@link CapacityService#GROUP_MAX}, sorted so a cut is the same cut every time.
	 */
	public record Roster(List<String> userIds, boolean truncated) {
	}

	/**
	 * Whose absences [viewer] may read as a group: the team named by [teamId], the project named by
	 * [projectId], or with neither the people of the viewer's own projects.
	 *
	 * <p><b>Nobody appears in a group through a relationship somebody else can make.</b> Whoever
	 * creates a project leads it, whoever creates a team manages it, and both can add anybody
	 * without asking. So a person counts in a group only by time they recorded themselves within
	 * {@link #WORKED_ON} on a project of that group ({@link AvailabilityPolicy#whoWorkedOn}) — the
	 * same anchor a lead's view of one person has ({@link #of}). A keeper already sees everybody's
	 * absences one by one, and gets the group without that filter.
	 *
	 * <p>Reading a group is narrower than being in one. The calendar is for anybody who can see the
	 * project or belongs to the team; the capacity band, which adds everybody's days up, is for
	 * who plans: a lead of the project, an admin of the team, a keeper. A project or team the viewer
	 * cannot see answers 404, as if it were not there.
	 */
	public Roster roster(User viewer, String teamId, String projectId, Purpose purpose) {
		boolean hasTeam = teamId != null && !teamId.isBlank();
		boolean hasProject = projectId != null && !projectId.isBlank();
		if (hasTeam && hasProject) {
			throw ApiException.badRequest("error.availability.scopeInvalid");
		}
		boolean keeper = keeps(viewer);
		Set<String> candidates = new HashSet<>();
		Set<String> projectIds = new HashSet<>();
		if (hasProject) {
			Project project = mongo.findById(projectId, Project.class);
			if (project == null || !keeper && !reach.canSee(project, viewer)) {
				throw ApiException.notFound("project");
			}
			if (purpose == Purpose.BAND && !keeper && !leads(project, viewer)) {
				throw ApiException.forbidden("error.availability.forbidden");
			}
			addPeople(candidates, project);
			projectIds.add(project.getId());
		}
		else if (hasTeam) {
			Team team = teams.findById(teamId).orElse(null);
			TeamMembership membership = team == null ? null : team.membership(viewer.getId());
			if (team == null || !keeper && membership == null) {
				throw ApiException.notFound("team");
			}
			if (purpose == Purpose.BAND && !keeper && !membership.isAdmin()) {
				throw ApiException.forbidden("error.availability.forbidden");
			}
			team.getMembers().forEach(member -> candidates.add(member.getUserId()));
			projectIds.addAll(team.getProjectIds());
		}
		else {
			Criteria mine = purpose == Purpose.BAND
					? new Criteria().orOperator(Criteria.where("leadIds").is(viewer.getId()),
							Criteria.where("leadId").is(viewer.getId()))
					: new Criteria().orOperator(Criteria.where("memberIds").is(viewer.getId()),
							Criteria.where("leadIds").is(viewer.getId()),
							Criteria.where("leadId").is(viewer.getId()));
			Query query = Query.query(mine).limit(LED_PROJECTS_MAX);
			query.fields().include("_id").include("memberIds").include("leadIds").include("leadId");
			for (Document project : mongo.query(Project.class).as(Document.class).matching(query).all()) {
				projectIds.add(String.valueOf(project.get("_id")));
				Optional.ofNullable(project.getList("memberIds", String.class)).ifPresent(candidates::addAll);
				Optional.ofNullable(project.getList("leadIds", String.class)).ifPresent(candidates::addAll);
				Optional.ofNullable(project.getString("leadId")).ifPresent(candidates::add);
			}
		}
		candidates.remove(null);
		Set<String> people = keeper ? candidates
				: new HashSet<>(policyOrNone().whoWorkedOn(candidates, projectIds, LocalDate.now(clock).minus(WORKED_ON)));
		if (candidates.contains(viewer.getId())) {
			people.add(viewer.getId());
		}
		List<String> sorted = people.stream().sorted().toList();
		boolean truncated = sorted.size() > CapacityService.GROUP_MAX;
		return new Roster(truncated ? sorted.subList(0, CapacityService.GROUP_MAX) : sorted, truncated);
	}

	/** The policy, or one that knows nobody worked anywhere: without it no group has anybody in it. */
	private AvailabilityPolicy policyOrNone() {
		AvailabilityPolicy current = policy.getIfAvailable();
		return current != null ? current : NOBODY_WORKED;
	}

	private static final AvailabilityPolicy NOBODY_WORKED = new AvailabilityPolicy() {
		@Override
		public boolean leadsSeeMemberAbsences() {
			return false;
		}

		@Override
		public Set<String> projectsWorkedOn(String userId, Set<String> among, LocalDate since) {
			return Set.of();
		}

		@Override
		public Set<String> whoWorkedOn(java.util.Collection<String> userIds, Set<String> among, LocalDate since) {
			return Set.of();
		}
	};

	private static void addPeople(Set<String> into, Project project) {
		if (project.getMemberIds() != null) {
			into.addAll(project.getMemberIds());
		}
		if (project.getLeadIds() != null) {
			into.addAll(project.getLeadIds());
		}
		if (project.getLeadId() != null) {
			into.add(project.getLeadId());
		}
	}

	private static boolean leads(Project project, User viewer) {
		return viewer.getId().equals(project.getLeadId())
				|| project.getLeadIds() != null && project.getLeadIds().contains(viewer.getId());
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
