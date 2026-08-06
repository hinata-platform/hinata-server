package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.issue.Issue;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The one place an issue read learns about a freeze.
 *
 * <h2>Why a collaborator and not a filter per caller</h2>
 *
 * <p>{@code IssueService.getForUser} and {@code IssueService.search} are the two
 * seams every issue read is supposed to pass through, and the audit that produced
 * this class found nine callers that had gone around them — the board, the Gantt
 * chart, the dashboard, the weekly summary, the reports, the due-date reminder.
 * None of those was an independent mistake. Every one of them reached for
 * {@code mongo.find(query, Issue.class)} or a derived finder because that was the
 * shortest way to the rows it wanted, and none of them had any reason to know that
 * an unrelated moderation feature had an opinion about the result.
 *
 * <p>Nine one-line fixes would have closed those nine. This closes the tenth as
 * well, because it turns "remember to filter" into "wrap the query you were writing
 * anyway", and because {@code ModerationWiringTest} can then pin the small set of
 * files allowed to touch {@code Issue.class} at all. A convention fails silently
 * when the next caller appears; an allow-list fails the build.
 *
 * <h2>The two forms, and why both are needed</h2>
 *
 * <p>{@link #scoped} is the one to prefer. It puts the exclusion in the query, so
 * Mongo never returns the row — which matters for the same reason
 * {@code IssueService.search} argues at length: a post-filter yields short pages
 * and leaves counts describing rows nobody can open. Every caller here that also
 * reports a total or a count uses this form, so its number stays honest.
 *
 * <p>{@link #readable} exists for the callers that do not build a {@code Query} at
 * all — the board and the Gantt chart go through derived repository finders — and
 * it is safe there precisely because those results are bounded and unpaged: a board
 * column is the whole set or it is wrong, so dropping a row from it cannot produce
 * the short-page failure. It is not a general-purpose escape hatch, and a paged
 * caller reaching for it is a bug.
 *
 * <h2>What it costs when nothing is frozen</h2>
 *
 * <p>Nothing, which is the state of every healthy install.
 * {@link FrozenContentService#exclusion} on an empty set is a {@code $nin: []} that
 * matches every document, and {@link #readable} returns early on an empty set
 * without touching the list. The one thing that is <em>not</em> free is that both
 * forms consult the snapshot, so both answer 503 while the registry is unknown —
 * that is the fail-closed contract, deliberately inherited rather than worked
 * around.
 */
@Component
@RequiredArgsConstructor
public class FrozenIssues {

	private final FrozenContentService frozen;

	/**
	 * Adds the freeze exclusion to [query] and returns it.
	 *
	 * <p>Mutates and returns the same object rather than copying, so a call site
	 * reads {@code mongo.find(frozenIssues.scoped(query), Issue.class)} and a caller
	 * that builds the query in one statement and executes it in the next cannot
	 * accidentally run the unscoped original. The one hazard that shape carries is
	 * the {@code count}/{@code find} pair over a shared {@code Query}: scope it once,
	 * before either, exactly as {@code IssueService.search} does.
	 *
	 * <p>Filters on {@code _id}. That is only correct because every caller executes
	 * against {@code Issue.class}, which lets Spring Data's query mapper convert the
	 * ids to whatever the {@code _id} field actually holds. An untyped read
	 * ({@code Document.class} plus a collection name) would silently match nothing —
	 * the trap {@code NotificationRedactor} documents and avoids by matching on
	 * {@code readableId} instead.
	 */
	public Query scoped(Query query) {
		query.addCriteria(frozen.exclusion("_id", FrozenTargetType.ISSUE));
		return query;
	}

	/**
	 * The issues of [candidates] that may be served, as a new mutable list.
	 *
	 * <p>Mutable because every caller sorts or partitions the result immediately, and
	 * an immutable return would push each of them into a defensive copy — the kind of
	 * friction that makes the next caller skip the wrapper.
	 *
	 * <p>Null-tolerant on the way in ({@code null} is an empty result) because the
	 * derived finders it wraps are free to return one.
	 */
	public List<Issue> readable(List<Issue> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return new ArrayList<>();
		}
		Set<String> active = frozen.frozenIds(FrozenTargetType.ISSUE);
		if (active.isEmpty()) {
			return new ArrayList<>(candidates);
		}
		List<Issue> readable = new ArrayList<>(candidates.size());
		for (Issue issue : candidates) {
			if (issue != null && !active.contains(issue.getId())) {
				readable.add(issue);
			}
		}
		return readable;
	}
}
