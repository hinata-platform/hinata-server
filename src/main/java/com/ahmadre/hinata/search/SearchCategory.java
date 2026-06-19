package com.ahmadre.hinata.search;

/**
 * Searchable entity categories, in the palette's display order. Commands are
 * intentionally absent — they are pure client-side actions and never hit the
 * backend.
 */
public enum SearchCategory {
	ISSUES,
	PROJECTS,
	PEOPLE,
	BOARDS,
	DOCS;

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
