package com.ahmadre.hinata.report;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Summarized insights, YouTrack-report style: distributions and trends.
 *
 * <p>Every endpoint here takes a {@code projectId} straight from the caller and
 * aggregates over it, which makes membership the only thing standing between an
 * account and a profile of a project it has nothing to do with — volume, states,
 * priorities, who is assigned what, and how much time went where. Being signed in
 * used to be enough. {@link #assertAccess} is now the first statement of every
 * handler, and {@code /time-per-project}, which takes no project at all, is
 * restricted to the ones the caller can reach.
 */
@Tag(name = "Reports")
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

	private final MongoTemplate mongo;
	private final CurrentUser currentUser;
	private final ProjectService projects;

	@GetMapping("/issues-by-state")
	public Map<String, Long> issuesByState(@RequestParam String projectId) {
		assertAccess(projectId);
		return countBy(projectId, Issue::getState);
	}

	@GetMapping("/issues-by-assignee")
	public Map<String, Long> issuesByAssignee(@RequestParam String projectId) {
		assertAccess(projectId);
		return countBy(projectId, issue ->
				issue.getAssigneeId() != null ? issue.getAssigneeId() : "unassigned");
	}

	@GetMapping("/issues-by-priority")
	public Map<String, Long> issuesByPriority(@RequestParam String projectId) {
		assertAccess(projectId);
		return countBy(projectId, issue -> issue.getPriority().name());
	}

	public record TrendPoint(LocalDate date, long created, long resolved) {
	}

	@GetMapping("/created-vs-resolved")
	public List<TrendPoint> createdVsResolved(@RequestParam String projectId,
			@RequestParam(defaultValue = "30") int days) {
		assertAccess(projectId);
		int range = Math.min(days, 180);
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		List<Issue> issues = mongo.find(
				Query.query(Criteria.where("projectId").is(projectId)), Issue.class);
		return today.minusDays(range - 1).datesUntil(today.plusDays(1))
				.map(day -> new TrendPoint(day,
						issues.stream().filter(i -> isOn(i.getCreatedAt(), day)).count(),
						issues.stream().filter(i -> isOn(i.getResolvedAt(), day)).count()))
				.toList();
	}

	/**
	 * Time booked per project over a date range.
	 *
	 * <p>The one endpoint here that names no project, and so the one that cannot be
	 * secured by checking the parameter: it used to sum every work item in the
	 * database, handing any account a complete effort — and therefore billing —
	 * picture of the whole organisation. It is now restricted to the projects the
	 * caller can reach, in the query rather than after it.
	 */
	@GetMapping("/time-per-project")
	public Map<String, Integer> timePerProject(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		User user = currentUser.require();
		Criteria criteria = Criteria.where("date").gte(from).lte(to);
		if (!user.isAdmin()) {
			criteria = new Criteria().andOperator(criteria,
					Criteria.where("projectId").in(projects.visibleTo(user).stream()
							.map(Project::getId).toList()));
		}
		List<WorkItem> items = mongo.find(Query.query(criteria), WorkItem.class);
		Map<String, Integer> result = new LinkedHashMap<>();
		items.forEach(item -> result.merge(item.getProjectId(), item.getDurationMinutes(), Integer::sum));
		return result;
	}

	@GetMapping("/time-per-activity")
	public Map<String, Integer> timePerActivity(@RequestParam String projectId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		assertAccess(projectId);
		List<WorkItem> items = mongo.find(Query.query(Criteria.where("projectId").is(projectId)
				.and("date").gte(from).lte(to)), WorkItem.class);
		Map<String, Integer> result = new LinkedHashMap<>();
		items.forEach(item -> result.merge(item.getActivityType(), item.getDurationMinutes(), Integer::sum));
		return result;
	}

	/**
	 * Resolves [projectId] and refuses the caller who may not see it.
	 *
	 * <p>A missing project answers 404 and a forbidden one 403, which does leak that
	 * an id exists. That is the same pair every other project-scoped endpoint in the
	 * product returns ({@code IssueService.getForUser}, {@code GanttController}), and
	 * matching them matters more here than closing a distinction the rest of the API
	 * already makes.
	 */
	private void assertAccess(String projectId) {
		User user = currentUser.require();
		Project project = projects.findOptional(projectId)
				.orElseThrow(() -> ApiException.notFound("project"));
		projects.assertMember(project, user);
	}

	private Map<String, Long> countBy(String projectId, java.util.function.Function<Issue, String> classifier) {
		List<Issue> issues = mongo.find(
				Query.query(Criteria.where("projectId").is(projectId)), Issue.class);
		Map<String, Long> result = new LinkedHashMap<>();
		issues.forEach(issue -> result.merge(classifier.apply(issue), 1L, Long::sum));
		return result;
	}

	private boolean isOn(Instant instant, LocalDate day) {
		return instant != null && instant.atZone(ZoneOffset.UTC).toLocalDate().equals(day);
	}
}
