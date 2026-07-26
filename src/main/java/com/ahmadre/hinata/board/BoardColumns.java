package com.ahmadre.hinata.board;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.WorkflowMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Builds the columns of a board that spans several projects, merging equivalent
 * workflow states into one column — the same idea as YouTrack's merged columns.
 *
 * <p>Without this, a board spanning a project with {@code Open / In Progress /
 * Done} and one with {@code Neu / In Arbeit / Fertig} would show six columns
 * instead of three, and dropping a card into a column whose state its own
 * project doesn't know would be rejected by
 * {@code IssueService.update}'s workflow validation.
 *
 * <p>Alignment uses the shared {@link WorkflowMapping} ladder (name → hue →
 * position); a state that aligns to nothing becomes its own column, so nothing
 * is ever silently dropped. Merging too eagerly is the worse failure of the two —
 * a needless column is a cosmetic annoyance, a wrong merge hides a step of the
 * process — so every rung has to be sure before it fires. Where those columns end
 * up is a separate question, answered from every project's workflow sequence —
 * see {@link #order}.
 */
public final class BoardColumns {

	/** Ceiling on a hand-made layout — a wall nobody could scroll is a mistake. */
	private static final int MAX_COLUMNS = 24;

	private BoardColumns() {
	}

	/**
	 * Merged columns for {@code projects}: equivalent states folded together, in an
	 * order that respects <em>every</em> project's workflow sequence.
	 *
	 * <p>Two steps, deliberately separate. {@link #align} decides which states
	 * share a column; {@link #order} decides where those columns go.
	 */
	public static List<AgileBoard.Column> merge(List<Project> projects) {
		if (projects == null || projects.isEmpty()) return new ArrayList<>();
		return order(align(projects), projects);
	}

	/**
	 * Folds equivalent states into shared columns. The first project acts as the
	 * template; every further project contributes at most one state per column,
	 * and a state that aligns to nothing becomes its own column — so nothing is
	 * ever silently dropped. The resulting order is provisional; {@link #order}
	 * has the final say.
	 */
	private static List<AgileBoard.Column> align(List<Project> projects) {
		List<AgileBoard.Column> columns = new ArrayList<>();
		Project base = projects.get(0);
		for (String state : base.workflowStateNames()) {
			columns.add(AgileBoard.Column.builder()
					.name(state)
					.states(new ArrayList<>(List.of(state)))
					.build());
		}

		int baseSize = columns.size();
		for (int p = 1; p < projects.size(); p++) {
			Project other = projects.get(p);
			List<String> otherNames = other.workflowStateNames();
			// Positional alignment is only plausible when the workflows are the
			// same shape; otherwise index N of one means nothing in the other.
			boolean sameShape = otherNames.size() == baseSize;
			for (int i = 0; i < otherNames.size(); i++) {
				String state = otherNames.get(i);
				AgileBoard.Column target = alignByName(columns, state);
				if (target == null) target = alignByHue(columns, base, other, state);
				if (target == null && sameShape && i < columns.size()) {
					// Only take the slot when this project hasn't already filled it,
					// so two of its states can never collapse into one column.
					AgileBoard.Column candidate = columns.get(i);
					if (!holdsStateOf(candidate, other)) target = candidate;
				}
				if (target == null) {
					columns.add(AgileBoard.Column.builder()
							.name(state)
							.states(new ArrayList<>(List.of(state)))
							.build());
					continue;
				}
				if (!containsIgnoreCase(target.getStates(), state)) {
					target.getStates().add(state);
				}
			}
		}
		return columns;
	}

	/**
	 * Orders merged columns so that every project's own sequence is respected.
	 *
	 * <p>A project does not state absolute positions, it states <em>relations</em>:
	 * this state comes before that one. Taking only the first project's order and
	 * appending the rest throws those relations away — which is why a step another
	 * project puts first used to end up last. So each project contributes an edge
	 * per consecutive pair of its states and the columns are sorted topologically
	 * over the union of those edges. Names carry no meaning here; only sequence
	 * does.
	 *
	 * <p>Where the relations leave a genuine choice — two projects each add their
	 * own extra state in the same gap — the tie is broken by the earliest position
	 * the column holds in any project, then by the order the columns were built in
	 * (so the board's first project still decides). Contradicting projects (one
	 * says "Review before Done", the other the opposite) form a cycle; the columns
	 * left over are appended in their original order rather than dropped.
	 */
	private static List<AgileBoard.Column> order(List<AgileBoard.Column> columns,
			List<Project> projects) {
		int size = columns.size();
		if (size < 2) return columns;

		List<Set<Integer>> successors = new ArrayList<>();
		for (int i = 0; i < size; i++) successors.add(new LinkedHashSet<>());
		int[] indegree = new int[size];
		int[] earliest = new int[size];
		Arrays.fill(earliest, Integer.MAX_VALUE);

		for (Project project : projects) {
			List<Integer> sequence = new ArrayList<>();
			List<String> names = project.workflowStateNames();
			for (int position = 0; position < names.size(); position++) {
				int column = columnOf(columns, names.get(position));
				if (column < 0) continue;
				earliest[column] = Math.min(earliest[column], position);
				// A project can only constrain distinct columns — two of its states
				// in one column would be a self-edge, and the merge forbids that.
				if (!sequence.isEmpty() && sequence.get(sequence.size() - 1) == column) continue;
				sequence.add(column);
			}
			for (int i = 1; i < sequence.size(); i++) {
				int from = sequence.get(i - 1);
				int to = sequence.get(i);
				if (successors.get(from).add(to)) indegree[to]++;
			}
		}

		// Ready columns come out earliest-position first, then in build order, so
		// the result is stable and the template project still breaks ties.
		Comparator<Integer> tieBreak = Comparator
				.<Integer>comparingInt(index -> earliest[index])
				.thenComparingInt(index -> index);
		PriorityQueue<Integer> ready = new PriorityQueue<>(tieBreak);
		for (int i = 0; i < size; i++) {
			if (indegree[i] == 0) ready.add(i);
		}

		List<AgileBoard.Column> ordered = new ArrayList<>(size);
		boolean[] emitted = new boolean[size];
		while (!ready.isEmpty()) {
			int index = ready.poll();
			emitted[index] = true;
			ordered.add(columns.get(index));
			for (int next : successors.get(index)) {
				if (--indegree[next] == 0) ready.add(next);
			}
		}
		// Cycle: the projects contradict each other. Keep every column, in the
		// order the merge built them, rather than losing the ones in the cycle.
		for (int i = 0; i < size; i++) {
			if (!emitted[i]) ordered.add(columns.get(i));
		}
		return ordered;
	}

	/** Index of the column holding {@code state}, or -1. */
	private static int columnOf(List<AgileBoard.Column> columns, String state) {
		for (int i = 0; i < columns.size(); i++) {
			if (containsIgnoreCase(columns.get(i).getStates(), state)) return i;
		}
		return -1;
	}

	/**
	 * Column hue by column name, taken from the first spanned project that knows
	 * any of the column's states — so a merged column is tinted like the state it
	 * represents rather than falling back to the neutral default.
	 */
	public static Map<String, Integer> hues(List<AgileBoard.Column> columns, List<Project> projects) {
		Map<String, Integer> byColumn = new LinkedHashMap<>();
		for (AgileBoard.Column column : columns) {
			Integer hue = null;
			for (Project project : projects) {
				for (Project.WorkflowState state : safeStates(project)) {
					if (containsIgnoreCase(column.getStates(), state.getName())) {
						hue = state.getHue();
						break;
					}
				}
				if (hue != null) break;
			}
			byColumn.put(column.getName(), hue != null ? hue : 250);
		}
		return byColumn;
	}

	/**
	 * Drops columns that only reference states of projects the caller cannot see
	 * (or that are archived), so a scoped board view never shows an empty column
	 * belonging to a foreign project. Columns keeping at least one visible state
	 * are narrowed to exactly those states.
	 */
	public static List<AgileBoard.Column> restrictTo(List<AgileBoard.Column> columns,
			List<Project> visibleProjects) {
		List<String> allowed = new ArrayList<>();
		for (Project project : visibleProjects) allowed.addAll(project.workflowStateNames());

		List<AgileBoard.Column> kept = new ArrayList<>();
		for (AgileBoard.Column column : columns) {
			List<String> states = new ArrayList<>();
			for (String state : column.getStates()) {
				if (containsIgnoreCase(allowed, state)) states.add(state);
			}
			if (states.isEmpty()) continue;
			kept.add(AgileBoard.Column.builder()
					.name(column.getName())
					.states(states)
					.wipLimit(column.getWipLimit())
					.build());
		}
		return kept;
	}

	/**
	 * A hand-made layout, made whole for {@code projects}: states the projects no
	 * longer define (renamed, deleted, or belonging to a project the viewer cannot
	 * see) drop out, and states that have no column yet are appended as their own.
	 *
	 * <p>Both halves matter. Without the first, a scoped view would show a column
	 * of a foreign project; without the second, a status added in project settings
	 * would have nowhere to live and its issues would quietly vanish from the
	 * board. A manual layout is respected, never at the cost of losing cards.
	 */
	public static List<AgileBoard.Column> reconcile(List<AgileBoard.Column> stored,
			List<Project> projects) {
		List<AgileBoard.Column> columns = restrictTo(stored == null ? List.of() : stored, projects);
		for (Project project : projects) {
			for (String state : project.workflowStateNames()) {
				if (columnOf(columns, state) >= 0) continue;
				columns.add(AgileBoard.Column.builder()
						.name(state)
						.states(new ArrayList<>(List.of(state)))
						.build());
			}
		}
		return columns;
	}

	/**
	 * Rejects a layout a board could not work with. Two rules carry real weight:
	 * every state needs exactly one column — an unassigned status would hide its
	 * issues, a doubly assigned one would make the board ambiguous — and no column
	 * may hold two states of the <em>same</em> project, because a drop resolves the
	 * state from the card's own project and would have no way to choose.
	 */
	public static void validate(List<AgileBoard.Column> columns, List<Project> projects) {
		if (columns == null || columns.isEmpty()) {
			throw ApiException.badRequest("error.board.noColumns");
		}
		if (columns.size() > MAX_COLUMNS) {
			throw ApiException.badRequest("error.board.tooManyColumns", MAX_COLUMNS);
		}

		List<String> knownStates = new ArrayList<>();
		for (Project project : projects) knownStates.addAll(project.workflowStateNames());

		List<String> seenNames = new ArrayList<>();
		List<String> seenStates = new ArrayList<>();
		for (AgileBoard.Column column : columns) {
			String name = column.getName();
			if (name == null || name.isBlank()) {
				throw ApiException.badRequest("error.board.columnNameRequired");
			}
			if (containsIgnoreCase(seenNames, name)) {
				throw ApiException.badRequest("error.board.duplicateColumnName", name);
			}
			seenNames.add(name);

			for (String state : safeList(column.getStates())) {
				if (!containsIgnoreCase(knownStates, state)) {
					throw ApiException.badRequest("error.board.unknownState", state);
				}
				if (containsIgnoreCase(seenStates, state)) {
					throw ApiException.badRequest("error.board.duplicateState", state);
				}
				seenStates.add(state);
			}
			for (Project project : projects) {
				if (statesOf(column, project) > 1) {
					throw ApiException.badRequest("error.board.ambiguousColumn", name, project.getKey());
				}
			}
		}

		for (String state : knownStates) {
			if (!containsIgnoreCase(seenStates, state)) {
				throw ApiException.badRequest("error.board.statesUnassigned", state);
			}
		}
	}

	// --- internals -----------------------------------------------------------

	/** How many of {@code project}'s states the column carries. */
	private static long statesOf(AgileBoard.Column column, Project project) {
		long count = 0;
		for (String name : project.workflowStateNames()) {
			if (containsIgnoreCase(column.getStates(), name)) count++;
		}
		return count;
	}

	private static List<String> safeList(List<String> values) {
		return values != null ? values : List.of();
	}

	private static AgileBoard.Column alignByName(List<AgileBoard.Column> columns, String state) {
		for (AgileBoard.Column column : columns) {
			if (containsIgnoreCase(column.getStates(), state)) return column;
		}
		return null;
	}

	private static AgileBoard.Column alignByHue(List<AgileBoard.Column> columns, Project base,
			Project other, String state) {
		Integer hue = hueOf(other, state);
		// A hue only says "same step" when it belongs to exactly one state on each
		// side. Every status added in project settings inherits the same neutral
		// default, so a hue two states of one workflow share means "nobody picked a
		// colour" — reading identity into it merged unrelated steps into one column.
		if (hue == null || countByHue(other, hue) != 1 || countByHue(base, hue) != 1) return null;
		for (AgileBoard.Column column : columns) {
			if (holdsStateOf(column, other)) continue; // slot already taken by this project
			for (Project.WorkflowState baseState : safeStates(base)) {
				if (baseState.getHue() == hue
						&& containsIgnoreCase(column.getStates(), baseState.getName())) {
					return column;
				}
			}
		}
		return null;
	}

	/** Whether the column already carries a state belonging to {@code project}. */
	private static boolean holdsStateOf(AgileBoard.Column column, Project project) {
		for (String name : project.workflowStateNames()) {
			if (containsIgnoreCase(column.getStates(), name)) return true;
		}
		return false;
	}

	/** How many of {@code project}'s states carry {@code hue}. */
	private static int countByHue(Project project, int hue) {
		int count = 0;
		for (Project.WorkflowState state : safeStates(project)) {
			if (state.getHue() == hue) count++;
		}
		return count;
	}

	private static Integer hueOf(Project project, String state) {
		for (Project.WorkflowState each : safeStates(project)) {
			if (equalsIgnoreCase(each.getName(), state)) return each.getHue();
		}
		return null;
	}

	private static List<Project.WorkflowState> safeStates(Project project) {
		List<Project.WorkflowState> states = project.getWorkflowStates();
		return states != null ? states : List.of();
	}

	private static boolean containsIgnoreCase(List<String> values, String wanted) {
		if (values == null) return false;
		for (String value : values) {
			if (equalsIgnoreCase(value, wanted)) return true;
		}
		return false;
	}

	private static boolean equalsIgnoreCase(String a, String b) {
		if (a == null || b == null) return false;
		return a.trim().toLowerCase(Locale.ROOT).equals(b.trim().toLowerCase(Locale.ROOT));
	}
}
