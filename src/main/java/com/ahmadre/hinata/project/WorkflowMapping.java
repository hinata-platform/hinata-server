package com.ahmadre.hinata.project;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Maps a workflow state of one project onto the closest equivalent state of
 * another project. Two features depend on the same question — "where does this
 * status belong over there?" — so the heuristic lives in exactly one place:
 *
 * <ul>
 *   <li>the issue <em>move</em> wizard, which pre-fills the status mapping the
 *       user then confirms, and</li>
 *   <li>the multi-project board, which merges several projects' workflows into
 *       one set of columns and, on drop, picks the state that actually exists in
 *       the dragged card's own project.</li>
 * </ul>
 *
 * <p>The matching ladder, in order:
 * <ol>
 *   <li><b>Name</b> — case-insensitive, trimmed. "In Progress" → "In Progress".</li>
 *   <li><b>Hue</b> — {@link Project.WorkflowState#getHue()} is assigned
 *       semantically (see {@link Project#defaultWorkflow()}), so a project whose
 *       states were merely renamed to another language still matches:
 *       "In Progress"(70) → "In Arbeit"(70).</li>
 *   <li><b>Class</b> — resolved → the target's first resolved state, backlog →
 *       the target's backlog state.</li>
 *   <li><b>Position</b> — same index in the workflow, but only when both
 *       workflows are the same length, so alignment is actually plausible.</li>
 *   <li><b>Fallback</b> — the target's first non-backlog state.</li>
 * </ol>
 *
 * <p>Every method is null-safe and never throws: a caller feeding an unknown
 * state gets the fallback rather than an exception, because both call sites run
 * against user data that may predate a workflow edit.
 */
public final class WorkflowMapping {

	private WorkflowMapping() {
	}

	/**
	 * The state in {@code target} that best corresponds to {@code fromState} of
	 * {@code source}. Returns null only when the target has no states at all.
	 */
	public static String suggest(Project source, String fromState, Project target) {
		List<String> targetNames = target.workflowStateNames();
		if (targetNames.isEmpty()) return null;

		// 1. Same name.
		String byName = findByName(targetNames, fromState);
		if (byName != null) return byName;

		// 2. Same hue — survives a pure rename (incl. translation) of the workflow.
		Integer hue = hueOf(source, fromState);
		if (hue != null) {
			for (Project.WorkflowState state : states(target)) {
				if (state.getHue() == hue) return state.getName();
			}
		}

		// 3. Same class: a resolved state stays resolved, a backlog stays backlog.
		if (isResolved(source, fromState)) {
			String resolved = firstResolved(target);
			if (resolved != null) return resolved;
		}
		if (isBacklog(fromState)) {
			String backlog = firstBacklog(target);
			if (backlog != null) return backlog;
		}

		// 4. Same position, but only when the two workflows are the same shape.
		List<String> sourceNames = source.workflowStateNames();
		if (sourceNames.size() == targetNames.size()) {
			int index = indexOfName(sourceNames, fromState);
			if (index >= 0) return targetNames.get(index);
		}

		// 5. Anything else lands in the target's first working state.
		return firstWorking(target);
	}

	/**
	 * The state of {@code project} that belongs to a board column offering
	 * {@code columnStates} — used when a card is dropped into a merged column
	 * that lists one state per spanned project. Returns null when the column
	 * holds nothing this project knows about, so the caller can reject the drop
	 * instead of writing a state the project's workflow would refuse.
	 */
	public static String stateInColumn(Project project, List<String> columnStates) {
		if (columnStates == null) return null;
		List<String> own = project.workflowStateNames();
		for (String candidate : columnStates) {
			String match = findByName(own, candidate);
			if (match != null) return match;
		}
		return null;
	}

	/** Whether {@code project}'s workflow contains {@code state} (case-insensitive). */
	public static boolean contains(Project project, String state) {
		return findByName(project.workflowStateNames(), state) != null;
	}

	/** The canonically-cased state name as stored on the project, or null. */
	public static String canonical(Project project, String state) {
		return findByName(project.workflowStateNames(), state);
	}

	// --- internals -----------------------------------------------------------

	private static List<Project.WorkflowState> states(Project project) {
		List<Project.WorkflowState> states = project.getWorkflowStates();
		return states != null ? states : List.of();
	}

	private static String findByName(List<String> names, String wanted) {
		if (wanted == null) return null;
		String needle = wanted.trim();
		for (String name : names) {
			if (name != null && name.trim().equalsIgnoreCase(needle)) return name;
		}
		return null;
	}

	private static int indexOfName(List<String> names, String wanted) {
		if (wanted == null) return -1;
		String needle = wanted.trim();
		for (int i = 0; i < names.size(); i++) {
			String name = names.get(i);
			if (name != null && name.trim().equalsIgnoreCase(needle)) return i;
		}
		return -1;
	}

	private static Integer hueOf(Project project, String state) {
		if (state == null) return null;
		for (Project.WorkflowState each : states(project)) {
			if (each.getName() != null && each.getName().trim().equalsIgnoreCase(state.trim())) {
				return each.getHue();
			}
		}
		return null;
	}

	private static boolean isResolved(Project project, String state) {
		List<String> resolved = project.getResolvedStates();
		if (resolved == null || state == null) return false;
		return resolved.stream().anyMatch(each -> Objects.equals(
				lower(each), lower(state)));
	}

	private static String firstResolved(Project project) {
		List<String> resolved = project.getResolvedStates();
		if (resolved == null) return null;
		for (String each : resolved) {
			String match = findByName(project.workflowStateNames(), each);
			if (match != null) return match;
		}
		return null;
	}

	/** A workflow state counts as "backlog" by its conventional name. */
	public static boolean isBacklog(String state) {
		return state != null && "backlog".equals(lower(state));
	}

	private static String firstBacklog(Project project) {
		for (String name : project.workflowStateNames()) {
			if (isBacklog(name)) return name;
		}
		return null;
	}

	/** The first state that is neither a backlog nor, if avoidable, resolved. */
	private static String firstWorking(Project project) {
		List<String> names = project.workflowStateNames();
		for (String name : names) {
			if (!isBacklog(name) && !isResolved(project, name)) return name;
		}
		for (String name : names) {
			if (!isBacklog(name)) return name;
		}
		return names.isEmpty() ? null : names.get(0);
	}

	private static String lower(String value) {
		return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
	}
}
