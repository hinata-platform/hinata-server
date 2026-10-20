package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Who may see and who may plan shifts.
 *
 * <p>Seeing is {@link ProjectReach#canSee}: a direct member or a team grant, nothing else, and an
 * administrator is not a member by office. Planning is {@link ProjectService#canManage} (the
 * project's leads and the admins of a team that owns it) or an entry in {@code shift_planners}
 * ({@link ShiftPlanner}, HIN-45), always after visibility: a planner entry never opens a project the
 * person cannot see. Granting and revoking the planner role stays with {@code canManage}.
 *
 * <p>Lists ask {@link #visibleProjectIds} once per request and put the answer into the query as
 * {@code projectId $in}, so nothing invisible is ever read and then filtered out. Single objects
 * addressed by id answer 404 for anything the caller cannot see, the same as for something that is
 * not there; a stranger probing ids learns nothing from the difference.
 */
@Component
@RequiredArgsConstructor
public class ShiftAccess {

	private final ProjectReach reach;
	private final ProjectService projects;
	private final MongoTemplate mongo;

	/** An object of the module together with the project it belongs to. */
	public record Scoped<T>(T item, Project project) {
	}

	/**
	 * Every project the user may see, archived ones included, because {@link ProjectReach#canSee}
	 * does not ask either: two queries, ids only.
	 */
	public Set<String> visibleProjectIds(User user) {
		Query direct = Query.query(Criteria.where("memberIds").is(user.getId()));
		direct.fields().include("_id");
		Set<String> ids = new LinkedHashSet<>();
		for (Document project : mongo.find(direct, Document.class, mongo.getCollectionName(Project.class))) {
			ids.add(String.valueOf(project.get("_id")));
		}
		ids.addAll(reach.teamGrantedProjectIds(user));
		return ids;
	}

	public boolean canSee(Project project, User user) {
		return reach.canSee(project, user);
	}

	/**
	 * May the user plan shifts in this project. Visibility first, then the management rule, then a
	 * planner entry (one indexed lookup, only for somebody who does not manage the project).
	 */
	public boolean canPlan(Project project, User user) {
		if (project == null || user == null || !reach.canSee(project, user)) {
			return false;
		}
		return plans(project, user);
	}

	/**
	 * Which of [projectIds] the user may plan, for a list that shows a planner's controls per row:
	 * the two queries of {@link ProjectService#issueDeletableProjectIds} plus one planner query with
	 * {@code projectId $in}, however many projects there are. [projectIds] are projects the caller
	 * can see; the answer never adds one.
	 */
	public Set<String> plannableProjectIds(User user, Collection<String> projectIds) {
		if (projectIds == null || projectIds.isEmpty()) {
			return Set.of();
		}
		Set<String> asked = new HashSet<>(projectIds);
		Set<String> plannable = new HashSet<>(projects.issueDeletableProjectIds(user));
		plannable.retainAll(asked);
		Query planner = Query.query(Criteria.where("userId").is(user.getId()).and("projectId").in(asked));
		planner.fields().include("projectId");
		for (ShiftPlanner entry : mongo.find(planner, ShiftPlanner.class)) {
			plannable.add(entry.getProjectId());
		}
		return plannable;
	}

	/** Whether the user holds a planner entry in the project; says nothing about visibility. */
	public boolean isPlanner(String projectId, String userId) {
		return projectId != null && userId != null && mongo.exists(
				Query.query(Criteria.where("projectId").is(projectId).and("userId").is(userId)), ShiftPlanner.class);
	}

	/** The project behind a {@code /projects/{id}/...} route: 404 if missing, 403 if not a member. */
	public Project memberProject(String projectId, User user) {
		Project project = projects.get(projectId);
		projects.assertMember(project, user);
		return project;
	}

	/** As {@link #memberProject}, and the user plans there: 403 otherwise. */
	public Project plannedProject(String projectId, User user) {
		Project project = memberProject(projectId, user);
		requirePlanner(project, user);
		return project;
	}

	/**
	 * As {@link #memberProject}, and the user runs the project (lead or team admin): 403 otherwise.
	 * Who plans is decided here, never by a planner.
	 */
	public Project managedProject(String projectId, User user) {
		Project project = memberProject(projectId, user);
		projects.assertCanManage(project, user);
		return project;
	}

	/** An event addressed by id: 404 when it is missing or its project is out of sight. */
	public Scoped<ShiftEvent> visibleEvent(String eventId, User user) {
		ShiftEvent event = eventId == null ? null : mongo.findById(eventId, ShiftEvent.class);
		Project project = event == null ? null : projects.findOptional(event.getProjectId()).orElse(null);
		if (project == null || !reach.canSee(project, user)) {
			throw ApiException.notFound("shiftEvent");
		}
		return new Scoped<>(event, project);
	}

	/** As {@link #visibleEvent}, and the caller plans there: 403 for a member who does not. */
	public Scoped<ShiftEvent> plannedEvent(String eventId, User user) {
		Scoped<ShiftEvent> scoped = visibleEvent(eventId, user);
		requirePlanner(scoped.project(), user);
		return scoped;
	}

	/** A shift addressed by id: 404 when it is missing, deleted or out of sight. */
	public Scoped<Shift> visibleShift(String shiftId, User user) {
		Shift shift = shiftId == null ? null : mongo.findById(shiftId, Shift.class);
		Project project = shift == null || shift.isDeleted() ? null
				: projects.findOptional(shift.getProjectId()).orElse(null);
		if (project == null || !reach.canSee(project, user)) {
			throw ApiException.notFound("shift");
		}
		return new Scoped<>(shift, project);
	}

	public Scoped<Shift> plannedShift(String shiftId, User user) {
		Scoped<Shift> scoped = visibleShift(shiftId, user);
		requirePlanner(scoped.project(), user);
		return scoped;
	}

	/** The caller plans the project: 403 otherwise. Callers have checked visibility already. */
	public void requirePlanner(Project project, User user) {
		if (!plans(project, user)) {
			throw ApiException.forbidden("error.shifts.notPlanner");
		}
	}

	private boolean plans(Project project, User user) {
		return projects.canManage(project, user) || isPlanner(project.getId(), user.getId());
	}
}
