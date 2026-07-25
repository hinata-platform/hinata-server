package com.ahmadre.hinata.board;

import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.WorkflowMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * position). States that align to nothing become their own column appended at
 * the end, so nothing is ever silently dropped.
 */
public final class BoardColumns {

	private BoardColumns() {
	}

	/**
	 * Merged columns for {@code projects}, in the first project's workflow order.
	 * The first project acts as the template; every further project contributes
	 * at most one state per column.
	 */
	public static List<AgileBoard.Column> merge(List<Project> projects) {
		List<AgileBoard.Column> columns = new ArrayList<>();
		if (projects == null || projects.isEmpty()) return columns;

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

	// --- internals -----------------------------------------------------------

	private static AgileBoard.Column alignByName(List<AgileBoard.Column> columns, String state) {
		for (AgileBoard.Column column : columns) {
			if (containsIgnoreCase(column.getStates(), state)) return column;
		}
		return null;
	}

	private static AgileBoard.Column alignByHue(List<AgileBoard.Column> columns, Project base,
			Project other, String state) {
		Integer hue = hueOf(other, state);
		if (hue == null) return null;
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
