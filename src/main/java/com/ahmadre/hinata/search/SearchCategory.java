package com.ahmadre.hinata.search;

/**
 * Searchable entity categories, in the palette's display order. Commands are
 * intentionally absent — they are pure client-side actions and never hit the
 * backend.
 *
 * <p>The first five are what every client knows. The rest arrived with HIN-165;
 * a client that does not know a category skips its group, so adding one here is
 * additive on the wire. The four after {@link #DOCS} and {@link #ATTACHMENTS}
 * belong to modules and are answered only while their module is switched on.
 */
public enum SearchCategory {
	ISSUES,
	/** Comment text; the hit opens its issue scrolled to the comment. */
	COMMENTS,
	PROJECTS,
	/** Teams the caller belongs to. */
	TEAMS,
	PEOPLE,
	/** Boards and sprints. */
	BOARDS,
	/** Knowledge pages and spaces. */
	DOCS,
	/** File names of issue attachments. */
	ATTACHMENTS,
	/** Shift planning: events and shifts ({@code shift_planning}). */
	SHIFTS,
	/** The caller's own time entries and timesheets. */
	TIME,
	/** The caller's own absence requests and the absence types on offer (absence management). */
	ABSENCES,
	/** Project budgets and billing rates. */
	BILLING;

	/** Parses a scope param; {@code null}/blank/"all" means "every category". */
	static SearchCategory parse(String scope) {
		if (scope == null) return null;
		String value = scope.trim().toUpperCase();
		if (value.isEmpty() || value.equals("ALL")) return null;
		try {
			return valueOf(value);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}
}
