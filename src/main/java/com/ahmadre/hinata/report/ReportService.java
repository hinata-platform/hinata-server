package com.ahmadre.hinata.report;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The numbers behind {@link ReportController}, each scoped by the caller: a
 * project report is for the project's members, and the cross-project time
 * report only ever sums projects the caller can see. The shapes are unchanged
 * from 1.x — the docs promise exactly these maps.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

	/** Longest created-vs-resolved window, in days. */
	static final int MAX_TREND_DAYS = 180;

	private static final String DEFAULT_ACTIVITY = "Development";

	private final MongoTemplate mongo;
	private final ProjectService projects;
	private final Clock clock;

	public record TrendPoint(LocalDate date, long created, long resolved) {
	}

	public Map<String, Long> issuesByState(String projectId, User user) {
		requireMember(projectId, user);
		return countBy(projectId, Issue::getState);
	}

	public Map<String, Long> issuesByAssignee(String projectId, User user) {
		requireMember(projectId, user);
		return countBy(projectId, issue ->
				issue.getAssigneeId() != null ? issue.getAssigneeId() : "unassigned");
	}

	public Map<String, Long> issuesByPriority(String projectId, User user) {
		requireMember(projectId, user);
		return countBy(projectId, issue -> issue.getPriority().name());
	}

	public List<TrendPoint> createdVsResolved(String projectId, int days, User user) {
		requireMember(projectId, user);
		int range = Math.clamp(days, 1, MAX_TREND_DAYS);
		LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
		List<Issue> issues = mongo.find(
				Query.query(Criteria.where("projectId").is(projectId)), Issue.class);
		return today.minusDays(range - 1).datesUntil(today.plusDays(1))
				.map(day -> new TrendPoint(day,
						issues.stream().filter(i -> isOn(i.getCreatedAt(), day)).count(),
						issues.stream().filter(i -> isOn(i.getResolvedAt(), day)).count()))
				.toList();
	}

	/**
	 * Minutes per project in the range — over the projects {@code user} can see
	 * (admins: all). Entries without a project (detached from a deleted issue in
	 * a project that has since gone) are skipped: a null key would have no name
	 * to show and no JSON to serialize to.
	 */
	public Map<String, Integer> timePerProject(LocalDate from, LocalDate to, User user) {
		Criteria criteria = Criteria.where("date").gte(from).lte(to);
		if (!user.isAdmin()) {
			List<String> visible = projects.visibleTo(user).stream().map(Project::getId).toList();
			if (visible.isEmpty()) {
				return new LinkedHashMap<>();
			}
			criteria = criteria.and("projectId").in(visible);
		}
		Map<String, Integer> result = new LinkedHashMap<>();
		for (WorkItem item : mongo.find(Query.query(criteria), WorkItem.class)) {
			if (item.getProjectId() != null) {
				result.merge(item.getProjectId(), item.getDurationMinutes(), Integer::sum);
			}
		}
		return result;
	}

	public Map<String, Integer> timePerActivity(String projectId, LocalDate from, LocalDate to,
			User user) {
		requireMember(projectId, user);
		List<WorkItem> items = mongo.find(Query.query(Criteria.where("projectId").is(projectId)
				.and("date").gte(from).lte(to)), WorkItem.class);
		Map<String, Integer> result = new LinkedHashMap<>();
		// Nothing is dropped: an entry without an activity counts under the default.
		items.forEach(item -> result.merge(
				item.getActivityType() != null ? item.getActivityType() : DEFAULT_ACTIVITY,
				item.getDurationMinutes(), Integer::sum));
		return result;
	}

	/** 404 for a project that does not exist, 403 for one the caller is not a member of. */
	private void requireMember(String projectId, User user) {
		projects.assertMember(projects.get(projectId), user);
	}

	private Map<String, Long> countBy(String projectId, Function<Issue, String> classifier) {
		List<Issue> issues = mongo.find(
				Query.query(Criteria.where("projectId").is(projectId)), Issue.class);
		Map<String, Long> result = new LinkedHashMap<>();
		issues.forEach(issue -> result.merge(classifier.apply(issue), 1L, Long::sum));
		return result;
	}

	private static boolean isOn(Instant instant, LocalDate day) {
		return instant != null && instant.atZone(ZoneOffset.UTC).toLocalDate().equals(day);
	}
}
