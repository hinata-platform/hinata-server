package com.ahmadre.hinata.search;

import java.util.List;

/**
 * One category of the global search, or one kind of hit within it, provided by the
 * package that owns the data (HIN-165).
 *
 * <p>Inverted on purpose, the way {@code me.PersonalDataExport} is: {@code search}
 * must not know shift planning, time tracking, absence management or billing, or
 * switching one off (or deleting its package) would mean editing the search. A module
 * implements this and the search collects every implementation Spring knows.
 *
 * <p>The rules an implementation keeps:
 * <ul>
 * <li>{@link #enabled()} asks the module's own switch. A source whose module is off is
 * not asked at all, and its category stays out of the answer.</li>
 * <li>The caller's reach goes into the query: {@link SearchContext#projectIds()} as
 * {@code projectId $in}, or the caller's own id for personal data. Nothing is read and
 * then filtered out.</li>
 * <li>At most [cap] hits, and a fixed number of queries whatever the data holds.</li>
 * <li>Each hit carries {@link SearchHit#getRoute()}, the app address that opens exactly
 * this entry.</li>
 * </ul>
 */
public interface SearchSource {

	/**
	 * Where a category's count stops (HIN-165). The palette shows a number on every scope chip; past
	 * this it says "99+" rather than paying for an exact total of somebody's whole history.
	 */
	int COUNT_CAP = 100;

	SearchCategory category();

	/** Whether the module behind the source is switched on right now. */
	boolean enabled();

	/** Up to [cap] hits for {@link SearchContext#query()}, best first. Never called with a blank query. */
	List<SearchHit> search(SearchContext context, int cap);

	/**
	 * Up to [limit] entries to suggest when the category is chosen and nothing is typed yet,
	 * newest or nearest first. None by default.
	 */
	default List<SearchHit> suggest(SearchContext context, int limit) {
		return List.of();
	}

	/**
	 * How many entries of the category the caller could find, counted under the same reach as the
	 * hits and at most [limit] ({@link #COUNT_CAP}): a count query with a limit, never a read of the
	 * rows. Asked when the palette opens, independent of what is typed.
	 */
	long count(SearchContext context, int limit);
}
