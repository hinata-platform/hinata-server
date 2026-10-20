package com.ahmadre.hinata.search;

import java.util.List;
import java.util.Map;

/**
 * Global-search payload: query-matched results grouped by category (in
 * {@link SearchCategory} order) plus the total entity count per category for
 * the scope-chip badges (query-independent; empty while something is typed).
 */
public record SearchResponse(List<SearchGroup> groups, Map<String, Long> counts,
		/**
		 * The categories whose count reached {@link SearchSource#COUNT_CAP} and stopped there
		 * (HIN-165): the client shows "99+" for them. The five original categories are counted
		 * exactly and never appear here.
		 */
		List<String> cappedCounts) {

	/**
	 * One category's hits, best first, at most the cap of the request.
	 *
	 * @param more whether the category holds more than {@code items} (HIN-165): the search reads one
	 *             hit past the cap to know, so "show all" is offered only where there is more
	 */
	public record SearchGroup(String category, List<SearchHit> items, boolean more) {
	}
}
