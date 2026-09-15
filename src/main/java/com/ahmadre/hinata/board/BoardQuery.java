package com.ahmadre.hinata.board;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.Characters;
import com.ahmadre.hinata.issue.Issue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a board narrows its cards by: the search text, the facets of its filter and the shape of
 * the cards it wants. Built from request parameters and checked here, so the queries behind it
 * can rely on its sizes.
 *
 * @param text        matched against the key, the title and the labels, ignoring case
 * @param states      workflow states as the client names them, any case
 * @param assigneeIds people assigned to a card, first or further
 * @param sprintIds   sprints a card may be in
 * @param noSprint    whether a card in no sprint passes the sprint facet as well
 * @param epicIds     epics a card rolls up to, directly or through its parent
 */
public record BoardQuery(String text, Set<String> states, Set<Issue.Type> types,
		Set<Issue.Priority> priorities, Set<String> assigneeIds, Set<String> reporterIds, Set<String> labels,
		Set<String> sprintIds, boolean noSprint, Set<String> epicIds, Shape shape) {

	/** Which cards a query is about. */
	public enum Shape {

		/** Work items. Epics head lanes and sub-tasks live inside their parent. */
		WALL,

		/**
		 * Work items and sub-tasks, for a wall grouped by sub-task. In a sprint this takes in the
		 * sub-tasks of the sprint's work items, which carry no sprint of their own.
		 */
		SUBTASKS,

		/** Work items and epics, for the timeline. */
		TIMELINE,

		/** Every issue type, as the sprint planning has always listed them. */
		PLANNING;

		/** Whether an issue of [type] is a card of this shape. */
		boolean lists(Issue.Type type) {
			return switch (this) {
				case WALL -> type.isStandard();
				case SUBTASKS -> !type.isEpic();
				case TIMELINE -> !type.isSubtask();
				case PLANNING -> true;
			};
		}

		/** The types of the cards of this shape, in the order the issue model names them. */
		List<Issue.Type> types() {
			return Arrays.stream(Issue.Type.values()).filter(this::lists).toList();
		}
	}

	/** The most characters a search holds, counted as someone reads them, the way the app's field counts. */
	static final int MAX_TEXT = 100;

	/**
	 * The most UTF-16 units a search takes however few characters they make. One character can take
	 * many, as an emoji of a family takes eleven, and a pattern is built from every one of them.
	 */
	static final int MAX_TEXT_UNITS = 2_000;
	static final int MAX_VALUES = 50;
	static final int MAX_VALUE_LENGTH = 200;

	/** Stands in the sprint facet for the cards in no sprint. */
	static final String NO_SPRINT = "__none__";

	/** Every card of the wall. */
	static final BoardQuery ALL = all(Shape.WALL);

	public BoardQuery {
		states = Set.copyOf(states);
		types = Set.copyOf(types);
		priorities = Set.copyOf(priorities);
		assigneeIds = Set.copyOf(assigneeIds);
		reporterIds = Set.copyOf(reporterIds);
		labels = Set.copyOf(labels);
		sprintIds = Set.copyOf(sprintIds);
		epicIds = Set.copyOf(epicIds);
	}

	/**
	 * The query the request parameters describe.
	 *
	 * @throws ApiException 400 for a text of more than {@value #MAX_TEXT} characters or
	 *                      {@value #MAX_TEXT_UNITS} UTF-16 units, a facet with
	 *                      more than {@value #MAX_VALUES} values or a value longer than
	 *                      {@value #MAX_VALUE_LENGTH}, a text or value with a control character,
	 *                      and a type, priority or shape that does not exist
	 */
	public static BoardQuery of(String text, List<String> states, List<String> types, List<String> priorities,
			List<String> assigneeIds, List<String> reporterIds, List<String> labels, List<String> sprints,
			List<String> epicIds, String shape) {
		String stripped = text == null ? "" : text.strip();
		if (stripped.length() > MAX_TEXT_UNITS || Characters.count(stripped) > MAX_TEXT || hasControl(stripped)) {
			throw invalid();
		}
		Set<String> sprintIds = values(sprints);
		boolean noSprint = sprintIds.remove(NO_SPRINT);
		return new BoardQuery(stripped, values(states), enums(types, Issue.Type.class),
				enums(priorities, Issue.Priority.class), values(assigneeIds), values(reporterIds), values(labels),
				sprintIds, noSprint, values(epicIds), shapeOf(shape));
	}

	/** Every card of [shape], narrowed by nothing. */
	static BoardQuery all(Shape shape) {
		return new BoardQuery("", Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), false,
				Set.of(), shape);
	}

	boolean hasText() {
		return !text.isEmpty();
	}

	private static Set<String> values(List<String> raw) {
		Set<String> values = new LinkedHashSet<>();
		if (raw == null) {
			return values;
		}
		if (raw.size() > MAX_VALUES) {
			throw invalid();
		}
		for (String value : raw) {
			String stripped = value == null ? "" : value.strip();
			if (stripped.length() > MAX_VALUE_LENGTH || hasControl(stripped)) {
				throw invalid();
			}
			if (!stripped.isEmpty()) {
				values.add(stripped);
			}
		}
		return values;
	}

	private static <E extends Enum<E>> Set<E> enums(List<String> raw, Class<E> type) {
		Set<E> parsed = EnumSet.noneOf(type);
		for (String value : values(raw)) {
			try {
				parsed.add(Enum.valueOf(type, value.toUpperCase(Locale.ROOT)));
			}
			catch (IllegalArgumentException ex) {
				throw invalid();
			}
		}
		return parsed;
	}

	/** The shape [raw] names, the wall when it names none. */
	static Shape shapeOf(String raw) {
		if (raw == null || raw.isBlank()) {
			return Shape.WALL;
		}
		try {
			return Shape.valueOf(raw.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw invalid();
		}
	}

	/** Whether [value] holds a control character: no board sends one, and a pattern must not carry a NUL. */
	private static boolean hasControl(String value) {
		return value.chars().anyMatch(Character::isISOControl);
	}

	private static ApiException invalid() {
		return ApiException.badRequest("error.validationFailed");
	}
}
