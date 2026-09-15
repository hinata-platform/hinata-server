package com.ahmadre.hinata.board;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueSearchText;
import org.springframework.data.mongodb.core.query.Criteria;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which issues of a board a read takes, and the index that serves the read.
 *
 * <p>Every read starts from the board's projects its viewer may see and their active issues, takes
 * the place it asks for (the whole board, a sprint or the backlog) and the cards of its shape, and
 * narrows them by the search and the filter. The search matches {@link Issue#getSearchText()}, which
 * the state, sprint and date indexes hold as their last key, so a search reads index keys and
 * fetches only the cards it keeps. Which index a read comes off is {@link #index}'s choice.
 */
final class BoardCriteria {

	/** A column's cards and counts: equality on the state, then board order. */
	static final String BY_STATE = "board_by_state";

	/** A sprint's, a sprint filter's and the backlog's: equality on the sprint, then board order. */
	static final String BY_SPRINT = "board_by_sprint";

	/** The timeline's: the dates in timeline order. */
	static final String BY_DATES = "board_by_dates";

	/** A filter by assignee: equality on one of the people, then the state and board order. */
	static final String BY_ASSIGNEE = "board_by_assignee";

	/** A filter by reporter: equality on one of the people, then the state and board order. */
	static final String BY_REPORTER = "board_by_reporter";

	/** A filter by label: equality on one of the labels, then the state and board order. */
	static final String BY_LABEL = "board_by_label";

	/** An epic filter's: equality on the parent, see {@link Issue}'s {@code parent_number}. */
	static final String BY_PARENT = "parent_number";

	/** Issues an epic filter, or the sub-tasks of a sprint, may reach through. */
	static final int MAX_LINKED = 10_000;

	private static final String EPIC = Issue.Type.EPIC.name();
	private static final String SUBTASK = Issue.Type.SUBTASK.name();

	private BoardCriteria() {
	}

	/** Looks up the ids of the issues a criteria matches, off the index it names. */
	@FunctionalInterface
	interface IdLookup {

		List<String> idsOf(Criteria criteria, String index);
	}

	/** Which issues of the board a read starts from: all of them, one sprint's, or the backlog. */
	record Place(String sprintId, boolean backlog) {

		static final Place BOARD = new Place(null, false);
		static final Place BACKLOG = new Place(null, true);

		static Place sprint(String sprintId) {
			return new Place(sprintId, false);
		}

		boolean inSprint() {
			return sprintId != null;
		}
	}

	/**
	 * The issues of [place] in [scope] that [query] keeps, or empty when the query names only states
	 * the board's projects do not have and so keeps nothing. [lookup] finds the ids of the issues a
	 * criteria matches: the work items of a sprint, the children of epics.
	 */
	static Optional<Criteria> of(BoardScope scope, Place place, BoardQuery query, IdLookup lookup) {
		List<String> projectIds = scope.projectIds();
		List<Criteria> parts = new ArrayList<>();
		parts.add(Criteria.where("projectId").in(projectIds));
		parts.add(Criteria.where("archived").is(false));
		if (subTasksOfASprint(place, query)) {
			// A sub-task carries no sprint of its own: it is in the sprint its parent is in.
			List<String> sprintWork = lookup.idsOf(Criteria.where("projectId").in(projectIds).and("archived").is(false)
					.and("sprintId").is(place.sprintId()).and("type").nin(EPIC, SUBTASK), BY_SPRINT);
			parts.add(new Criteria().orOperator(
					Criteria.where("sprintId").is(place.sprintId()).and("type").ne(EPIC),
					Criteria.where("type").is(SUBTASK).and("parentId").in(sprintWork)));
		}
		else {
			if (place.inSprint()) {
				parts.add(Criteria.where("sprintId").is(place.sprintId()));
			}
			if (place.backlog()) {
				parts.add(Criteria.where("sprintId").is(null));
			}
			List<String> otherTypes = Arrays.stream(Issue.Type.values())
					.filter(type -> !query.shape().lists(type))
					.map(Enum::name)
					.toList();
			if (otherTypes.size() == 1) {
				parts.add(Criteria.where("type").ne(otherTypes.getFirst()));
			}
			else if (!otherTypes.isEmpty()) {
				parts.add(Criteria.where("type").nin(otherTypes));
			}
		}
		if (query.hasText()) {
			parts.add(Criteria.where(IssueSearchText.FIELD).regex(Pattern.quote(IssueSearchText.needle(query.text()))));
		}
		if (!query.states().isEmpty()) {
			Set<String> named = scope.statesNamed(query.states());
			if (named.isEmpty()) {
				return Optional.empty();
			}
			parts.add(stateIn(scope.spellings(named)));
		}
		if (!query.types().isEmpty()) {
			parts.add(Criteria.where("type").in(names(query.types())));
		}
		if (!query.priorities().isEmpty()) {
			parts.add(Criteria.where("priority").in(names(query.priorities())));
		}
		if (!query.assigneeIds().isEmpty()) {
			// The list holds the first assignee too: the issue keeps the two in step, and
			// IssueSchemaMigration seeded the list of every issue saved before there was one.
			parts.add(Criteria.where("assigneeIds").in(query.assigneeIds()));
		}
		if (!query.reporterIds().isEmpty()) {
			parts.add(Criteria.where("reporterId").in(query.reporterIds()));
		}
		if (!query.labels().isEmpty()) {
			parts.add(Criteria.where("tags").in(query.labels()));
		}
		if (!query.sprintIds().isEmpty() || query.noSprint()) {
			// One list, the cards in no sprint as the sprint null, so the sprint index bounds it.
			List<String> sprints = new ArrayList<>(query.sprintIds());
			if (query.noSprint()) {
				sprints.add(null);
			}
			parts.add(Criteria.where("sprintId").in(sprints));
		}
		if (!query.epicIds().isEmpty()) {
			Set<String> parents = new LinkedHashSet<>(query.epicIds());
			// A work item names its epic as its parent. Only a sub-task rolls up to its epic through
			// its parent, so only a shape with sub-task cards looks those parents up.
			if (withSubTaskCards(query.shape())) {
				parents.addAll(lookup.idsOf(Criteria.where("projectId").in(projectIds).and("archived").is(false)
						.and("parentId").in(query.epicIds()), BY_PARENT));
			}
			parts.add(Criteria.where("parentId").in(parents));
		}
		return Optional.of(new Criteria().andOperator(parts));
	}

	/**
	 * The index that serves a read of [place] by [query], with [dated] set for the timeline's two
	 * lists: the one that bounds the read to the fewest cards. The children of a few epics and the
	 * cards of a sprint are few; one person's, one reporter's or one label's cards are a share of the
	 * board; the dates, the backlog, a sprint filter and the columns hold the board in the order they
	 * read it. Null for the sub-tasks of a sprint, which come in by two ways the planner combines.
	 */
	static String index(Place place, BoardQuery query, Boolean dated) {
		if (subTasksOfASprint(place, query)) {
			return null;
		}
		if (!query.epicIds().isEmpty()) {
			return BY_PARENT;
		}
		if (place.inSprint()) {
			return BY_SPRINT;
		}
		if (!query.assigneeIds().isEmpty()) {
			return BY_ASSIGNEE;
		}
		if (!query.reporterIds().isEmpty()) {
			return BY_REPORTER;
		}
		if (!query.labels().isEmpty()) {
			return BY_LABEL;
		}
		if (dated != null) {
			return BY_DATES;
		}
		if (place.backlog() || !query.sprintIds().isEmpty() || query.noSprint()) {
			return BY_SPRINT;
		}
		return BY_STATE;
	}

	/** Cards with a date, in two branches without overlap that the timeline index bounds. */
	static Criteria dated() {
		return new Criteria().orOperator(
				Criteria.where("startDate").ne(null),
				Criteria.where("startDate").is(null).and("dueDate").ne(null));
	}

	static Criteria undated() {
		return Criteria.where("startDate").is(null).and("dueDate").is(null);
	}

	static Criteria stateIn(Collection<String> spellings) {
		return Criteria.where("state").in(spellings);
	}

	static Criteria and(Criteria first, Criteria second) {
		return new Criteria().andOperator(first, second);
	}

	private static boolean subTasksOfASprint(Place place, BoardQuery query) {
		return place.inSprint() && query.shape() == BoardQuery.Shape.SUBTASKS;
	}

	private static boolean withSubTaskCards(BoardQuery.Shape shape) {
		return shape == BoardQuery.Shape.SUBTASKS || shape == BoardQuery.Shape.PLANNING;
	}

	private static List<String> names(Set<? extends Enum<?>> values) {
		return values.stream().map(Enum::name).sorted().toList();
	}
}
