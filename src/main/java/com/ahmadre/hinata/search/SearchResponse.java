package com.ahmadre.hinata.search;

import java.util.List;
import java.util.Map;

/**
 * Global-search payload: query-matched results grouped by category (in
 * {@link SearchCategory} order) plus the total entity count per category for
 * the scope-chip badges (query-independent).
 */
public record SearchResponse(List<SearchGroup> groups, Map<String, Long> counts) {

	public record SearchGroup(String category, List<SearchHit> items) {
	}
}
