package com.ahmadre.hinata.search;

import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.user.User;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * What one search knows about its caller, worked out once per request and handed to
 * every category: the caller, the projects they see, the typed text.
 *
 * <p>The projects are the caller's reach ({@code ProjectService.visibleTo}: direct
 * membership or a team grant, active projects only). A category puts
 * {@link #projectIds()} into its query as {@code projectId $in}; it never reads more
 * and filters afterwards. The project names come with the set, so a category that
 * matches on a project's name ({@link #projectsMatching()}) asks the database nothing
 * for it.
 *
 * <p>The search has a time, set when the context is made ({@link #timeLeft()}). Every read a
 * category sends takes what is left of it as its limit ({@link #bounded(Query)}, or
 * {@link #timeLeftMillis()} for a raw aggregation), so the database itself stops a read the
 * search no longer waits for, the way {@code board.BoardTime} bounds the board reads.
 */
public final class SearchContext {

	private final User user;
	private final Map<String, Project> projects;
	private final Set<String> archivedProjectIds;
	private final Criteria pages;
	private final String query;
	/** When the search stops waiting, on {@link System#nanoTime()}'s clock. */
	private final long deadline;

	SearchContext(User user, Map<String, Project> projects, Set<String> archivedProjectIds, Criteria pages,
			String query, Duration time) {
		this.user = user;
		this.projects = projects;
		this.archivedProjectIds = archivedProjectIds;
		this.pages = pages;
		this.query = query == null ? "" : query.trim();
		this.deadline = System.nanoTime() + time.toNanos();
	}

	public User user() {
		return user;
	}

	public String userId() {
		return user.getId();
	}

	/** The active projects the caller sees, for {@code projectId $in}. */
	public Set<String> projectIds() {
		return projects.keySet();
	}

	/** A project of the reach by id, or null when the caller does not see it. */
	public Project project(String projectId) {
		return projectId == null ? null : projects.get(projectId);
	}

	/** The projects of the reach whose name contains, or whose key starts with, the query. */
	public List<Project> projectsMatching() {
		String needle = query.toLowerCase(Locale.ROOT);
		if (needle.isEmpty()) return List.of();
		return projects.values().stream()
				.filter(p -> contains(p.getName(), needle)
						|| (p.getKey() != null && p.getKey().toLowerCase(Locale.ROOT).startsWith(needle)))
				.toList();
	}

	/** The trimmed text the caller typed; empty for a suggestion. */
	public String query() {
		return query;
	}

	/** Case-insensitive "contains" on [field], regex-escaped (NoSQL-injection safe). */
	public Criteria contains(String field) {
		return Criteria.where(field).regex(Pattern.quote(query), "i");
	}

	/** Case-insensitive anchored prefix on [field]. */
	public Criteria prefix(String field) {
		return Criteria.where(field).regex("^" + Pattern.quote(query), "i");
	}

	/** Whether [text] contains the query, ignoring case: for picking which part of a found row matched. */
	public boolean matches(String text) {
		return contains(text, query.toLowerCase(Locale.ROOT));
	}

	/**
	 * A short piece of [text] around the first match of the query, at most [max] characters, with
	 * an ellipsis where it was cut: what a hit shows of a long body.
	 */
	public String excerpt(String text, int max) {
		if (text == null) return null;
		String flat = text.replaceAll("\\s+", " ").trim();
		if (flat.length() <= max) return flat;
		int at = query.isEmpty() ? -1 : flat.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
		int start = at < 0 ? 0 : Math.max(0, Math.min(at - max / 3, flat.length() - max));
		String piece = flat.substring(start, Math.min(flat.length(), start + max)).trim();
		return (start > 0 ? "…" : "") + piece + (start + max < flat.length() ? "…" : "");
	}

	/**
	 * What is left of the search's time: the limit of the next read.
	 *
	 * @throws OutOfTime once less than a millisecond is left, which the database would read as no
	 *                   limit at all; the category that asked is left out
	 */
	public Duration timeLeft() {
		long left = deadline - System.nanoTime();
		if (left < TimeUnit.MILLISECONDS.toNanos(1)) throw new OutOfTime();
		return Duration.ofNanos(left);
	}

	/** {@link #timeLeft()} in milliseconds, for a raw aggregation's {@code maxTime}. */
	public long timeLeftMillis() {
		return timeLeft().toMillis();
	}

	/** [query] with {@link #timeLeft()} as its limit: how every read of a search is sent. */
	public Query bounded(Query query) {
		return query.maxTime(timeLeft());
	}

	/** Nanoseconds until the search stops waiting; zero or less once it has. */
	long nanosLeft() {
		return deadline - System.nanoTime();
	}

	Set<String> archivedProjectIds() {
		return archivedProjectIds;
	}

	Criteria pages() {
		return pages;
	}

	/**
	 * The issues the caller reaches: those of the projects they see and those shared with them
	 * (HIN-136). A top-level $or with the archive filter in each branch, so each branch is planned
	 * on its own index (see IssueService#inScope).
	 */
	public Criteria issues(boolean archived) {
		return new Criteria().orOperator(
				new Criteria().andOperator(archivedIs(archived), Criteria.where("projectId").in(projectIds())),
				new Criteria().andOperator(archivedIs(archived), Criteria.where("participantIds").is(userId())));
	}

	/**
	 * The active issues of the caller's projects with [also] (HIN-165): the project branch of
	 * {@link #issues} alone, so a query can name the index it runs on.
	 */
	Criteria projectIssues(Criteria... also) {
		List<Criteria> all = new ArrayList<>(List.of(archivedIs(false), Criteria.where("projectId").in(projectIds())));
		all.addAll(List.of(also));
		return new Criteria().andOperator(all);
	}

	/**
	 * The active issues shared with the caller (HIN-136) outside their projects, with [also]: the
	 * other branch of {@link #issues}, without what {@link #projectIssues} already holds.
	 */
	Criteria sharedIssues(Criteria... also) {
		List<Criteria> all = new ArrayList<>(List.of(Criteria.where("participantIds").is(userId()), archivedIs(false),
				Criteria.where("projectId").nin(projectIds())));
		all.addAll(List.of(also));
		return new Criteria().andOperator(all);
	}

	Criteria projects(boolean archived) {
		return new Criteria().andOperator(Criteria.where("archived").is(archived),
				Criteria.where("_id").in(archived ? archivedProjectIds : projectIds()));
	}

	Criteria boards() {
		return Criteria.where("projectIds").in(projectIds());
	}

	/** Issue archived filter — {@code ne(true)} keeps matching documents that lack the flag. */
	private static Criteria archivedIs(boolean archived) {
		return archived
				? Criteria.where("archived").is(true)
				: Criteria.where("archived").ne(true);
	}

	private static boolean contains(String text, String lowerNeedle) {
		return text != null && text.toLowerCase(Locale.ROOT).contains(lowerNeedle);
	}

	/** A search's time ran out before one of its reads was sent. */
	static final class OutOfTime extends RuntimeException {

		OutOfTime() {
			super("The search ran out of time", null, false, false);
		}
	}
}
