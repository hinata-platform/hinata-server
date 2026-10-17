package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.issue.Issue;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a project and an issue have used against their budget and estimate (HIN-152).
 *
 * <p>Facts about the work, never about a person: a project's minutes are the sum of all of its
 * entries, whoever recorded them, and nothing here can be asked per person. That is why reading it
 * needs no more than seeing the project, the same rule the project's time settings follow.
 *
 * <p>The sums come from the keys of two covering indexes, the ones the budget alerts already read
 * ({@link WorkItem#PROJECT_DURATION_INDEX}, {@link Issue#PROJECT_ESTIMATES_INDEX}), and are held
 * briefly in {@link TimeUsageCache}.
 */
@Service
@RequiredArgsConstructor
public class TimeUsageService {

	/** How many projects one batch read answers; the project list pages in steps of this size. */
	public static final int BATCH_MAX = 100;

	private final MongoTemplate mongo;
	private final TimeUsageCache cache;

	/**
	 * A project's usage. {@code budgetMinutes} is null when the project has none;
	 * {@code nearPercent} is the share from which surfaces warn, the project's own budget alert
	 * threshold or the server's default.
	 */
	public record ProjectUsage(String projectId, Integer budgetMinutes, long usedMinutes, long estimatedMinutes,
			int nearPercent) {
	}

	/** An issue's usage against its own estimate; {@code estimateMinutes} null means none. */
	public record IssueUsage(String issueId, String projectId, Integer estimateMinutes, int spentMinutes,
			int nearPercent) {
	}

	/** The usage of each project in [projectIds], in their order. The caller has checked reach. */
	public List<ProjectUsage> ofProjects(Collection<String> projectIds) {
		if (projectIds.isEmpty()) {
			return List.of();
		}
		Map<String, ProjectTimeSettings> settings = settingsOf(projectIds);
		Map<String, TimeUsageCache.Sums> sums = cache.sumsOf(projectIds, this::load);
		return projectIds.stream().distinct().map(id -> {
			ProjectTimeSettings own = settings.get(id);
			TimeUsageCache.Sums of = sums.get(id);
			return new ProjectUsage(id, budgetOf(own), of.usedMinutes(), of.estimatedMinutes(), nearPercentOf(own));
		}).toList();
	}

	/** An issue's usage. The caller has checked the issue can be seen. */
	public IssueUsage ofIssue(Issue issue) {
		Integer estimate = issue.getEstimateMinutes() != null && issue.getEstimateMinutes() > 0
				? issue.getEstimateMinutes() : null;
		ProjectTimeSettings own = settingsOf(List.of(issue.getProjectId())).get(issue.getProjectId());
		return new IssueUsage(issue.getId(), issue.getProjectId(), estimate, issue.getSpentMinutes(),
				nearPercentOf(own));
	}

	private Map<String, TimeUsageCache.Sums> load(Collection<String> projectIds) {
		Map<String, Long> used = sums(WorkItem.class, Criteria.where("projectId").in(projectIds),
				"durationMinutes", WorkItem.PROJECT_DURATION_INDEX);
		Map<String, Long> estimated = sums(Issue.class, Criteria.where("projectId").in(projectIds)
				.and("archived").is(false).and("estimateMinutes").gt(0),
				"estimateMinutes", Issue.PROJECT_ESTIMATES_INDEX);
		Map<String, TimeUsageCache.Sums> loaded = new LinkedHashMap<>();
		for (String id : projectIds) {
			loaded.put(id, new TimeUsageCache.Sums(used.getOrDefault(id, 0L), estimated.getOrDefault(id, 0L)));
		}
		return loaded;
	}

	/** A sum per project from the keys of [index] alone, which holds both fields. */
	private Map<String, Long> sums(Class<?> collection, Criteria match, String field, String index) {
		Map<String, Long> sums = new HashMap<>();
		mongo.aggregate(Aggregation.newAggregation(Aggregation.match(match),
								Aggregation.group("projectId").sum(field).as("total"))
						.withOptions(AggregationOptions.builder().hint(index).build()), collection, Document.class)
				.forEach(row -> sums.put(String.valueOf(row.get("_id")),
						((Number) row.get("total")).longValue()));
		return sums;
	}

	private Map<String, ProjectTimeSettings> settingsOf(Collection<String> projectIds) {
		Map<String, ProjectTimeSettings> byProject = new HashMap<>();
		mongo.find(Query.query(Criteria.where("projectId").in(projectIds)), ProjectTimeSettings.class)
				.forEach(own -> byProject.put(own.getProjectId(), own));
		return byProject;
	}

	private static Integer budgetOf(ProjectTimeSettings own) {
		return own == null || own.getBudgetMinutes() == null || own.getBudgetMinutes() <= 0
				? null : own.getBudgetMinutes();
	}

	private static int nearPercentOf(ProjectTimeSettings own) {
		Integer percent = own == null || own.getAlertThresholds() == null ? null
				: own.getAlertThresholds().getBudgetPercent();
		return percent == null ? TimeAlerts.DEFAULT_BUDGET_PERCENT : percent;
	}
}
