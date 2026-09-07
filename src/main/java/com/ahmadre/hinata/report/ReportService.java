package com.ahmadre.hinata.report;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The numbers behind {@link ReportController}, each scoped by the caller: a
 * project report is for the project's members, and the cross-project time
 * report only ever sums projects the caller can see. The shapes are unchanged
 * from 1.x — the docs promise exactly these maps.
 *
 * <p>Tested in two places: {@code report.ReportNumbersIntegrationTest} for the
 * numbers and the windows they are counted over, and
 * {@code timetracking.TimeTrackingAccessIntegrationTest} for who may ask —
 * which lives there because it shares that class's fixture of members,
 * outsiders and two projects.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

	/** Longest created-vs-resolved window, in days. */
	static final int MAX_TREND_DAYS = 180;
	/** Longest window a time report may cover, in days — a year and a day for leap years. */
	static final int MAX_TIME_RANGE_DAYS = 366;

	private static final String DEFAULT_ACTIVITY = "Development";

	private final MongoTemplate mongo;
	private final ProjectService projects;
	private final Clock clock;

	public record TrendPoint(LocalDate date, long created, long resolved) {
	}

	public Map<String, Long> issuesByState(String projectId, User user) {
		requireMember(projectId, user);
		return countBy(projectId, "state", null);
	}

	public Map<String, Long> issuesByAssignee(String projectId, User user) {
		requireMember(projectId, user);
		return countBy(projectId, "assigneeId", "unassigned");
	}

	public Map<String, Long> issuesByPriority(String projectId, User user) {
		requireMember(projectId, user);
		return countBy(projectId, "priority", null);
	}

	/**
	 * How many issues were opened and closed on each of the last {@code days}
	 * days.
	 *
	 * <p>Counted in one pass over two timestamps. The obvious reading — walk the
	 * days, and for each one count the issues that match — costs days × issues,
	 * which on a ten-thousand-issue project at the full window is three and a
	 * half million date conversions to produce a hundred and eighty numbers.
	 * Bucketing instead visits each issue once.
	 */
	public List<TrendPoint> createdVsResolved(String projectId, int days, User user) {
		requireMember(projectId, user);
		int range = Math.clamp(days, 1, MAX_TREND_DAYS);
		LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
		LocalDate first = today.minusDays(range - 1);
		Map<LocalDate, long[]> perDay = new LinkedHashMap<>();
		first.datesUntil(today.plusDays(1)).forEach(day -> perDay.put(day, new long[2]));
		// Only the two timestamps are read, and read as raw documents: an issue
		// carries its whole rich-text description, none of which a count needs,
		// and a partial issue cannot be mapped back onto the entity anyway (its
		// constructor takes primitives, which a missing field has no value for).
		Query query = Query.query(Criteria.where("projectId").is(projectId));
		query.fields().include("createdAt").include("resolvedAt");
		for (Document issue : mongo.find(query, Document.class, "issues")) {
			bump(perDay, issue.get("createdAt", Date.class), 0);
			bump(perDay, issue.get("resolvedAt", Date.class), 1);
		}
		List<TrendPoint> points = new ArrayList<>(perDay.size());
		perDay.forEach((day, counts) -> points.add(new TrendPoint(day, counts[0], counts[1])));
		return points;
	}

	/** Counts {@code stamp}'s day, when that day is one of the ones asked about. */
	private static void bump(Map<LocalDate, long[]> perDay, Date stamp, int slot) {
		if (stamp == null) {
			return;
		}
		long[] counts = perDay.get(LocalDate.ofInstant(stamp.toInstant(), ZoneOffset.UTC));
		if (counts != null) {
			counts[slot]++;
		}
	}

	/**
	 * Minutes per project in the range — over the projects {@code user} can see
	 * (admins: all). Entries without a project (detached from a deleted issue in
	 * a project that has since gone) are skipped: a null key would have no name
	 * to show and no JSON to serialize to.
	 *
	 * <p>Mongo does the summing. Reading the entries to add them up here meant an
	 * admin could ask for every year at once and pull the whole collection into
	 * memory to produce one number per project; the window is now bounded like
	 * every other one, and what comes back is one row per project either way.
	 */
	public Map<String, Integer> timePerProject(LocalDate from, LocalDate to, User user) {
		requireRange(from, to);
		Criteria criteria = Criteria.where("date").gte(from).lte(to);
		if (!user.isAdmin()) {
			List<String> visible = projects.visibleTo(user).stream().map(Project::getId).toList();
			if (visible.isEmpty()) {
				return new LinkedHashMap<>();
			}
			criteria = criteria.and("projectId").in(visible);
		}
		Map<String, Integer> result = new LinkedHashMap<>();
		Aggregation aggregation = Aggregation.newAggregation(
				Aggregation.match(criteria),
				Aggregation.group("projectId").sum("durationMinutes").as("minutes"));
		for (Document row : mongo.aggregate(aggregation, WorkItem.class, Document.class)) {
			Object projectId = row.get("_id");
			if (projectId != null && row.get("minutes") instanceof Number minutes) {
				result.put(projectId.toString(), minutes.intValue());
			}
		}
		return result;
	}

	/**
	 * Minutes per activity in one project. Summed in the query for the same
	 * reason as {@link #timePerProject}: a busy project over the permitted year
	 * is tens of thousands of entries, and the answer is about six numbers.
	 */
	public Map<String, Integer> timePerActivity(String projectId, LocalDate from, LocalDate to,
			User user) {
		requireMember(projectId, user);
		requireRange(from, to);
		Aggregation aggregation = Aggregation.newAggregation(
				Aggregation.match(Criteria.where("projectId").is(projectId)
						.and("date").gte(from).lte(to)),
				Aggregation.group("activityType").sum("durationMinutes").as("minutes"));
		Map<String, Integer> result = new LinkedHashMap<>();
		for (Document row : mongo.aggregate(aggregation, WorkItem.class, Document.class)) {
			if (row.get("minutes") instanceof Number minutes) {
				// Nothing is dropped: an entry without an activity counts under
				// the default, which is what one written before 2.0 reads as.
				Object activity = row.get("_id");
				result.merge(activity != null ? activity.toString() : DEFAULT_ACTIVITY,
						minutes.intValue(), Integer::sum);
			}
		}
		return result;
	}

	/** 404 for a project that does not exist, 403 for one the caller is not a member of. */
	private void requireMember(String projectId, User user) {
		projects.assertMember(projects.get(projectId), user);
	}

	/** The same guard the timesheet applies, at the width a report is allowed. */
	private static void requireRange(LocalDate from, LocalDate to) {
		if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > MAX_TIME_RANGE_DAYS) {
			throw ApiException.badRequest("error.time.invalidRange");
		}
	}

	/**
	 * A distribution over one field of a project's issues, counted from that
	 * field alone: an issue document is mostly its rich-text description, and no
	 * count needs a word of it.
	 *
	 * <p>An issue that carries no value there counts under {@code absent}, and
	 * where there is no sensible name for that — a state, a priority; every
	 * issue has both — it is left out rather than counted under a null key,
	 * which has nothing to show and no JSON to serialize to.
	 */
	private Map<String, Long> countBy(String projectId, String field, String absent) {
		Query query = Query.query(Criteria.where("projectId").is(projectId));
		query.fields().include(field);
		Map<String, Long> result = new LinkedHashMap<>();
		for (Document issue : mongo.find(query, Document.class, "issues")) {
			Object value = issue.get(field);
			String key = value != null ? value.toString() : absent;
			if (key != null) {
				result.merge(key, 1L, Long::sum);
			}
		}
		return result;
	}
}
