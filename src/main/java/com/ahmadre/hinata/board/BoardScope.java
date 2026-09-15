package com.ahmadre.hinata.board;

import com.ahmadre.hinata.project.Project;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One board as one viewer may read it, resolved once per request: the board, the projects of it
 * this viewer may see (active ones, in the board's order), and the columns built from exactly
 * those projects.
 */
record BoardScope(AgileBoard board, List<Project> projects, List<AgileBoard.Column> columns,
		Map<String, Integer> hues) {

	/** The colour a column gets when none of its states names one. */
	private static final int NEUTRAL_HUE = 250;

	List<String> projectIds() {
		return projects.stream().map(Project::getId).toList();
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
		return hues.getOrDefault(name, NEUTRAL_HUE);
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
	 * The spellings [states] are matched in. The board has always put an issue into the column of
	 * its state ignoring case. The exact, the upper and the lower case spelling keep that for the
	 * spellings in use and still leave the state an equality the index can bound.
	 */
	static Set<String> spellings(Collection<String> states) {
		Set<String> spellings = new LinkedHashSet<>();
		for (String state : states) {
			if (state != null) {
				spellings.add(state);
				spellings.add(state.toUpperCase(Locale.ROOT));
				spellings.add(state.toLowerCase(Locale.ROOT));
			}
		}
		return spellings;
	}
}
