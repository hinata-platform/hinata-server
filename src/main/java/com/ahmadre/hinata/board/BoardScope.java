package com.ahmadre.hinata.board;

import com.ahmadre.hinata.project.Project;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One board as one viewer may read it, resolved once per request: the board, the projects of it
 * this viewer may see (active ones, in the board's order), and the columns built from exactly those
 * projects.
 */
record BoardScope(AgileBoard board, List<Project> projects, List<AgileBoard.Column> columns,
		Map<String, Integer> hues) {

	List<String> projectIds() {
		return projects.stream().map(Project::getId).toList();
	}

	/** The resolved states of each of the projects, by project id. */
	Map<String, List<String>> resolvedStates() {
		Map<String, List<String>> resolved = new HashMap<>();
		for (Project project : projects) {
			resolved.put(project.getId(), project.getResolvedStates() == null ? List.of() : project.getResolvedStates());
		}
		return resolved;
	}

	/** The column called [name], ignoring case as the layout check does, or null. */
	AgileBoard.Column column(String name) {
		String wanted = name.strip();
		for (AgileBoard.Column column : columns) {
			if (column.getName() != null && column.getName().strip().equalsIgnoreCase(wanted)) {
				return column;
			}
		}
		return null;
	}

	/** The work-in-progress limit stored for the column called [name]. */
	Integer wipLimit(String name) {
		for (AgileBoard.Column stored : board.getColumns()) {
			if (stored.getName() != null && stored.getName().equals(name)) {
				return stored.getWipLimit();
			}
		}
		return null;
	}

	int hue(String name) {
		return hues.getOrDefault(name, BoardColumns.NEUTRAL_HUE);
	}

	/** The workflow states of the board's projects that [names] name, ignoring case. */
	Set<String> statesNamed(Collection<String> names) {
		Set<String> named = new LinkedHashSet<>();
		for (Project project : projects) {
			for (String state : project.workflowStateNames()) {
				for (String name : names) {
					if (state.equalsIgnoreCase(name)) {
						named.add(state);
					}
				}
			}
		}
		return named;
	}

	/**
	 * The workflow states of the board's projects, each project's in its workflow's order and each
	 * state once however the projects spell it: the states a filter narrows the cards by.
	 */
	List<String> workflowStates() {
		List<String> states = new ArrayList<>();
		for (Project project : projects) {
			for (String state : project.workflowStateNames()) {
				if (state != null && !state.isBlank() && states.stream().noneMatch(state::equalsIgnoreCase)) {
					states.add(state);
				}
			}
		}
		return states;
	}
}
