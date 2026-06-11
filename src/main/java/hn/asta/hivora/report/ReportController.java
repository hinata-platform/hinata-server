package hn.asta.hivora.report;

import hn.asta.hivora.auth.CurrentUser;
import hn.asta.hivora.issue.Issue;
import hn.asta.hivora.timetracking.WorkItem;
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

/** Summarized insights, YouTrack-report style: distributions and trends. */
@Tag(name = "Reports")
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

	private final MongoTemplate mongo;
	private final CurrentUser currentUser;

	@GetMapping("/issues-by-state")
	public Map<String, Long> issuesByState(@RequestParam String projectId) {
		currentUser.require();
		return countBy(projectId, Issue::getState);
	}

	@GetMapping("/issues-by-assignee")
	public Map<String, Long> issuesByAssignee(@RequestParam String projectId) {
		currentUser.require();
		return countBy(projectId, issue ->
				issue.getAssigneeId() != null ? issue.getAssigneeId() : "unassigned");
	}

	@GetMapping("/issues-by-priority")
	public Map<String, Long> issuesByPriority(@RequestParam String projectId) {
		currentUser.require();
		return countBy(projectId, issue -> issue.getPriority().name());
	}

	public record TrendPoint(LocalDate date, long created, long resolved) {
	}

	@GetMapping("/created-vs-resolved")
	public List<TrendPoint> createdVsResolved(@RequestParam String projectId,
			@RequestParam(defaultValue = "30") int days) {
		currentUser.require();
		int range = Math.min(days, 180);
		LocalDate today = LocalDate.now();
		List<Issue> issues = mongo.find(
				Query.query(Criteria.where("projectId").is(projectId)), Issue.class);
		return today.minusDays(range - 1).datesUntil(today.plusDays(1))
				.map(day -> new TrendPoint(day,
						issues.stream().filter(i -> isOn(i.getCreatedAt(), day)).count(),
						issues.stream().filter(i -> isOn(i.getResolvedAt(), day)).count()))
				.toList();
	}

	@GetMapping("/time-per-project")
	public Map<String, Integer> timePerProject(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		currentUser.require();
		List<WorkItem> items = mongo.find(
				Query.query(Criteria.where("date").gte(from).lte(to)), WorkItem.class);
		Map<String, Integer> result = new LinkedHashMap<>();
		items.forEach(item -> result.merge(item.getProjectId(), item.getDurationMinutes(), Integer::sum));
		return result;
	}

	@GetMapping("/time-per-activity")
	public Map<String, Integer> timePerActivity(@RequestParam String projectId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		currentUser.require();
		List<WorkItem> items = mongo.find(Query.query(Criteria.where("projectId").is(projectId)
				.and("date").gte(from).lte(to)), WorkItem.class);
		Map<String, Integer> result = new LinkedHashMap<>();
		items.forEach(item -> result.merge(item.getActivityType(), item.getDurationMinutes(), Integer::sum));
		return result;
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
