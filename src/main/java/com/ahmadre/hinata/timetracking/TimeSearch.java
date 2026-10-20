package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.search.SearchCategory;
import com.ahmadre.hinata.search.SearchContext;
import com.ahmadre.hinata.search.SearchHit;
import com.ahmadre.hinata.search.SearchSource;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The caller's own time in the global search (HIN-165): entries by their description, and, with
 * the extended module and approvals on, the caller's timesheets by project name.
 *
 * <p>Only ever the caller's own rows: {@code userId} is the equality of every query, nobody
 * else's time is read, whatever their role. Recording time itself has no switch (the base
 * timesheet is always there), so entries are searched always; the timesheets belong to the
 * extended module and are asked only while it and its approvals are on. Entries use the partial
 * {@code user_date_described} index (userId, then date for the order; the description is the
 * range), timesheets {@code user_project_period}. Two reads at most.
 */
@Component
@RequiredArgsConstructor
class TimeSearch implements SearchSource {

	/** How much of a description a hit shows. */
	static final int EXCERPT = 120;

	private final TimeTrackingSettings settings;
	private final MongoTemplate mongo;

	@Override
	public SearchCategory category() {
		return SearchCategory.TIME;
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public List<SearchHit> search(SearchContext context, int cap) {
		Query entries = Query.query(new Criteria().andOperator(Criteria.where("userId").is(context.userId()),
						// $gt '' as well, so the query implies the partial index's filter.
						Criteria.where("description").gt(""), context.contains("description")))
				.with(Sort.by(Sort.Direction.DESC, "date", "_id")).limit(cap);
		List<SearchHit> hits = new ArrayList<>(entries(mongo.find(context.bounded(entries), WorkItem.class), context));
		if (settings.advancedEnabled() && settings.approvalsEnabled()) {
			hits.addAll(timesheets(context, cap));
		}
		return hits.size() > cap ? hits.subList(0, cap) : hits;
	}

	/** The caller's latest described entries. */
	@Override
	public List<SearchHit> suggest(SearchContext context, int limit) {
		Query latest = Query.query(new Criteria().andOperator(Criteria.where("userId").is(context.userId()),
						Criteria.where("description").gt("")))
				.with(Sort.by(Sort.Direction.DESC, "date", "_id")).limit(limit);
		return entries(mongo.find(context.bounded(latest), WorkItem.class), context);
	}

	/** The caller's described entries, and their timesheets while those exist, each up to [limit]. */
	@Override
	public long count(SearchContext context, int limit) {
		long entries = mongo.count(context.bounded(Query.query(new Criteria().andOperator(
				Criteria.where("userId").is(context.userId()), Criteria.where("description").gt(""))).limit(limit)),
				WorkItem.class);
		if (!settings.advancedEnabled() || !settings.approvalsEnabled()) return entries;
		return entries + mongo.count(context.bounded(Query.query(Criteria.where("userId").is(context.userId()))
				.limit(limit)), TimesheetApproval.class);
	}

	private List<SearchHit> entries(List<WorkItem> items, SearchContext context) {
		// The calendar of the extended module where it is on, the base timesheet where it is not.
		String page = settings.advancedEnabled() ? "/time/calendar" : "/timesheet";
		return items.stream().map(item -> {
			Project project = context.project(item.getProjectId());
			return SearchHit.builder()
					.category(SearchCategory.TIME.name())
					.kind("ENTRY")
					.id(item.getId())
					.route(page + "?date=" + item.getDate() + "&entry=" + item.getId())
					.title(context.excerpt(item.getDescription(), EXCERPT))
					.from(item.getDate())
					.minutes(item.getDurationMinutes())
					.projectId(project == null ? null : project.getId())
					.projectName(project == null ? null : project.getName())
					.build();
		}).toList();
	}

	/** The caller's timesheets of the projects whose name matches; none when no project does. */
	private List<SearchHit> timesheets(SearchContext context, int cap) {
		List<String> projects = context.projectsMatching().stream().map(Project::getId).toList();
		if (projects.isEmpty()) return List.of();
		Query query = Query.query(Criteria.where("userId").is(context.userId()).and("projectId").in(projects))
				.with(Sort.by(Sort.Direction.DESC, "periodStart", "_id")).limit(cap);
		return mongo.find(context.bounded(query), TimesheetApproval.class).stream().map(sheet -> {
			Project project = context.project(sheet.getProjectId());
			return SearchHit.builder()
					.category(SearchCategory.TIME.name())
					.kind("TIMESHEET")
					.id(sheet.getId())
					.route("/time/timesheet?date=" + sheet.getPeriodStart() + "&project=" + sheet.getProjectId())
					.title(project == null ? null : project.getName())
					.from(sheet.getPeriodStart())
					.to(sheet.getPeriodEnd())
					.status(sheet.getStatus() == null ? null : sheet.getStatus().name())
					.projectId(sheet.getProjectId())
					.projectName(project == null ? null : project.getName())
					.build();
		}).toList();
	}
}
