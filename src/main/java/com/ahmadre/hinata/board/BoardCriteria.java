package com.ahmadre.hinata.board;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueSearchText;
import org.springframework.data.mongodb.core.query.Criteria;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Which issues of a board a read takes, and the index that serves the read.
 *
 * <p>Every read starts from the board's projects its viewer may see and their active issues, takes
 * the place it asks for (the whole board, a sprint or the backlog) and the cards of its shape, and
 * narrows them by the search and the filter. The search matches {@link Issue#getSearchText()}, which
 * the board indexes hold as their last key, so a search reads index keys and fetches only the cards
 * it keeps. The other filters are checked on the documents the index narrowed to.
 */
final class BoardCriteria {

	/** A column's cards and counts: equality on the state, then board order. */
	static final String BOARD_COLUMN = "board_column";

	/** A sprint's and the backlog's: equality on the sprint, then board order. */
	static final String BOARD_SPRINT = "board_sprint";

	/** The timeline's: the dates in timeline order. */
	static final String BOARD_TIMELINE = "board_timeline";

	/** Issues an epic filter, or the sub-tasks of a sprint, may reach through. */
	static final int MAX_LINKED = 10_000;

	private static final String EPIC = Issue.Type.EPIC.name();
	private static final String SUBTASK = Issue.Type.SUBTASK.name();

	private BoardCriteria() {
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
	 * the board's projects do not have and so keeps nothing. [idsOf] looks up the ids of the issues a
	 * criteria matches: the work items of a sprint, the children of epics.
	 */
	static Optional<Criteria> of(BoardScope scope, Place place, BoardQuery query,
			Function<Criteria, List<String>> idsOf) {
		List<String> projectIds = scope.projectIds();
		List<Criteria> parts = new ArrayList<>();
		parts.add(Criteria.where("projectId").in(projectIds));
		parts.add(Criteria.where("archived").is(false));
		if (subTasksOfASprint(place, query)) {
			// A sub-task carries no sprint of its own: it is in the sprint its parent is in.
			List<String> sprintWork = idsOf.apply(Criteria.where("projectId").in(projectIds).and("archived").is(false)
					.and("sprintId").is(place.sprintId()).and("type").nin(EPIC, SUBTASK));
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
			switch (query.shape()) {
				case WALL -> parts.add(Criteria.where("type").nin(EPIC, SUBTASK));
				case SUBTASKS -> parts.add(Criteria.where("type").ne(EPIC));
				case TIMELINE -> parts.add(Criteria.where("type").ne(SUBTASK));
				case PLANNING -> { }
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
			parts.add(stateIn(BoardScope.spellings(named)));
		}
		if (!query.types().isEmpty()) {
			parts.add(Criteria.where("type").in(names(query.types())));
		}
		if (!query.priorities().isEmpty()) {
			parts.add(Criteria.where("priority").in(names(query.priorities())));
		}
		if (!query.assigneeIds().isEmpty()) {
			parts.add(new Criteria().orOperator(
					Criteria.where("assigneeIds").in(query.assigneeIds()),
					Criteria.where("assigneeId").in(query.assigneeIds())));
		}
		if (!query.reporterIds().isEmpty()) {
			parts.add(Criteria.where("reporterId").in(query.reporterIds()));
		}
		if (!query.labels().isEmpty()) {
			parts.add(Criteria.where("tags").in(query.labels()));
		}
		if (!query.sprintIds().isEmpty() || query.noSprint()) {
			List<Criteria> anySprint = new ArrayList<>();
			if (!query.sprintIds().isEmpty()) {
				anySprint.add(Criteria.where("sprintId").in(query.sprintIds()));
			}
			if (query.noSprint()) {
				anySprint.add(Criteria.where("sprintId").is(null));
			}
			parts.add(anySprint.size() == 1 ? anySprint.getFirst() : new Criteria().orOperator(anySprint));
		}
		if (!query.epicIds().isEmpty()) {
			Criteria underEpic = Criteria.where("parentId").in(query.epicIds());
			// A work item names its epic as its parent. Only a sub-task rolls up to its epic through
			// its parent, so only a shape with sub-task cards looks those parents up.
			List<String> children = withSubTaskCards(query.shape())
					? idsOf.apply(Criteria.where("projectId").in(projectIds).and("archived").is(false)
							.and("parentId").in(query.epicIds()))
					: List.of();
			parts.add(children.isEmpty() ? underEpic
					: new Criteria().orOperator(underEpic, Criteria.where("parentId").in(children)));
		}
		return Optional.of(new Criteria().andOperator(parts));
	}

	/**
	 * The index that serves a read of [place] by [query], with [dated] set for the timeline's two
	 * lists. Null for the sub-tasks of a sprint, which come in by two ways the planner combines.
	 */
	static String index(Place place, BoardQuery query, Boolean dated) {
		if (dated != null) {
			return BOARD_TIMELINE;
		}
		if (subTasksOfASprint(place, query)) {
			return null;
		}
		return place.inSprint() || place.backlog() ? BOARD_SPRINT : BOARD_COLUMN;
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
