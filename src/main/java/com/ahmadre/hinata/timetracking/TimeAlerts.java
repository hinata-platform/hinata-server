package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Budget and estimate alerts (HIN-92): facts about the work, never about the people doing it.
 *
 * <p>A project's recorded time against its budget and against the sum of its issues' estimates
 * goes to the project's leads, at the project's warning threshold (80 % unless set) and at
 * 100 %. An issue's recorded time against its own estimate goes to its assignees, at the
 * project's estimate threshold (100 % unless set). The sentences name the project or the issue
 * and the sums, never a person.
 *
 * <p>Once per crossing. A threshold that is reached is claimed ({@link TimeReminderMark}); when
 * the time falls under it again, because the budget or an estimate was raised or entries were
 * removed, the claim is given back and the threshold can alert again.
 *
 * <p>Only what moved is measured. A run reads the projects and issues whose entries were
 * created or edited since the last complete run, and the projects whose settings changed, and
 * sums those: a whole instance summed every hour would read every entry every hour. What moves
 * without an entry, an estimate lowered or an issue moved, is measured with the next entry.
 */
@Service
@RequiredArgsConstructor
class TimeAlerts {

	static final int BATCH = 500;
	static final Duration RUN_BUDGET = Duration.ofMinutes(4);

	/** How far back the first run ever looks, and the furthest any run looks. */
	static final Duration FIRST_LOOKBACK = Duration.ofHours(2);
	static final Duration LONGEST_LOOKBACK = Duration.ofDays(7);

	static final int DEFAULT_BUDGET_PERCENT = 80;
	static final int DEFAULT_ESTIMATE_PERCENT = 100;

	/** The mark holding when the last complete run started. */
	static final String SCANNED = "alerts:scanned";

	private final MongoTemplate mongo;
	private final TimeTrackingSettings policy;
	private final TimeMarks marks;
	private final NotificationService notifications;
	private final Clock clock;

	enum Kind { BUDGET, ESTIMATES, ISSUE }

	/** One subject measured against one limit at the thresholds that alert. */
	record Measure(Kind kind, String subjectId, long used, long limit, Set<Integer> percents) {

		String key(int percent) {
			return TimeMarks.alertKey(kind.name(), subjectId, percent);
		}

		boolean reaches(int percent) {
			return limit > 0 && used * 100 >= limit * percent;
		}
	}

	/** Measures what moved since the last run and returns how many alerts went out. */
	int run() {
		if (!policy.advancedEnabled() || !policy.alertsEnabled()) {
			return 0;
		}
		Instant started = clock.instant();
		Instant deadline = started.plus(RUN_BUDGET);
		Instant since = marks.watermark(SCANNED).orElse(started.minus(FIRST_LOOKBACK));
		Instant longest = started.minus(LONGEST_LOOKBACK);
		if (since.isBefore(longest)) {
			since = longest;
		}

		Criteria touched = new Criteria().orOperator(
				Criteria.where("createdAt").gte(since), Criteria.where("updatedAt").gte(since));
		Set<String> projectIds = distinct(touched, "projectId", WorkItem.class);
		projectIds.addAll(distinct(Criteria.where("updatedAt").gte(since), "projectId", ProjectTimeSettings.class));
		Set<String> issueIds = distinct(touched, "issueId", WorkItem.class);

		int sent = 0;
		for (List<String> batch : batches(projectIds)) {
			if (clock.instant().isAfter(deadline)) {
				return sent;
			}
			sent += alertProjects(batch);
		}
		for (List<String> batch : batches(issueIds)) {
			if (clock.instant().isAfter(deadline)) {
				return sent;
			}
			sent += alertIssues(batch);
		}
		// Only a complete run moves the mark; a run cut short reads the same span again, and the
		// claims keep that from alerting twice.
		marks.advance(SCANNED, started);
		return sent;
	}

	private int alertProjects(List<String> ids) {
		Map<String, ProjectTimeSettings> settings = settingsOf(ids);
		Query query = Query.query(Criteria.where("_id").in(ids).and("archived").ne(true));
		query.fields().include("name", "leadId", "leadIds");
		Map<String, Project> projects = new HashMap<>();
		mongo.find(query, Project.class).forEach(project -> projects.put(project.getId(), project));
		Map<String, Long> recorded = sums(WorkItem.class, Criteria.where("projectId").in(projects.keySet()),
				"projectId", "durationMinutes");
		Map<String, Long> estimated = sums(Issue.class, Criteria.where("projectId").in(projects.keySet())
				.and("archived").ne(true).and("estimateMinutes").gt(0), "projectId", "estimateMinutes");

		List<Measure> measures = new ArrayList<>();
		for (Project project : projects.values()) {
			ProjectTimeSettings own = settings.get(project.getId());
			Integer warning = own == null || own.getAlertThresholds() == null ? null
					: own.getAlertThresholds().getBudgetPercent();
			Set<Integer> percents = new TreeSet<>(List.of(
					warning == null ? DEFAULT_BUDGET_PERCENT : warning, 100));
			long used = recorded.getOrDefault(project.getId(), 0L);
			long budget = own == null || own.getBudgetMinutes() == null ? 0 : own.getBudgetMinutes();
			measures.add(new Measure(Kind.BUDGET, project.getId(), used, budget, percents));
			measures.add(new Measure(Kind.ESTIMATES, project.getId(), used,
					estimated.getOrDefault(project.getId(), 0L), percents));
		}

		int sent = 0;
		for (Map.Entry<Measure, Integer> reached : settle(measures).entrySet()) {
			Measure measure = reached.getKey();
			Project project = projects.get(measure.subjectId());
			Set<String> leads = new LinkedHashSet<>();
			if (project.getLeadIds() != null) {
				leads.addAll(project.getLeadIds());
			}
			if (project.getLeadId() != null) {
				leads.add(project.getLeadId());
			}
			notifications.notifyTimeBudgetAlert(leads, project.getId(), project.getName(),
					measure.kind() == Kind.ESTIMATES, reached.getValue(), measure.used(), measure.limit());
			sent++;
		}
		return sent;
	}

	private int alertIssues(List<String> ids) {
		Query query = Query.query(Criteria.where("_id").in(ids).and("archived").ne(true)
				.and("estimateMinutes").gt(0));
		query.fields().include("readableId", "projectId", "estimateMinutes", "spentMinutes", "assigneeIds");
		Map<String, Issue> issues = new HashMap<>();
		mongo.find(query, Issue.class).forEach(issue -> issues.put(issue.getId(), issue));
		Map<String, ProjectTimeSettings> settings = settingsOf(issues.values().stream()
				.map(Issue::getProjectId).filter(Objects::nonNull).distinct().toList());

		List<Measure> measures = new ArrayList<>();
		for (Issue issue : issues.values()) {
			ProjectTimeSettings own = settings.get(issue.getProjectId());
			Integer percent = own == null || own.getAlertThresholds() == null ? null
					: own.getAlertThresholds().getEstimatePercent();
			measures.add(new Measure(Kind.ISSUE, issue.getId(), issue.getSpentMinutes(), issue.getEstimateMinutes(),
					Set.of(percent == null ? DEFAULT_ESTIMATE_PERCENT : percent)));
		}

		int sent = 0;
		for (Map.Entry<Measure, Integer> reached : settle(measures).entrySet()) {
			Issue issue = issues.get(reached.getKey().subjectId());
			if (issue.getAssigneeIds() == null || issue.getAssigneeIds().isEmpty()) {
				continue;
			}
			notifications.notifyTimeEstimateReached(Set.copyOf(issue.getAssigneeIds()), issue.getReadableId(),
					issue.getProjectId(), reached.getValue(), issue.getSpentMinutes(), issue.getEstimateMinutes());
			sent++;
		}
		return sent;
	}

	/**
	 * Claims every threshold a measure reaches and has not claimed yet, gives back the claims of
	 * thresholds it is under, and returns the highest newly claimed threshold per measure. From
	 * nothing to past both thresholds in one run is one alert, at the higher one.
	 */
	private Map<Measure, Integer> settle(List<Measure> measures) {
		Set<String> existing = marks.existing(measures.stream()
				.flatMap(measure -> measure.percents().stream().map(measure::key)).toList());
		List<String> fallen = new ArrayList<>();
		Map<Measure, Integer> reached = new LinkedHashMap<>();
		for (Measure measure : measures) {
			for (int percent : measure.percents()) {
				String key = measure.key(percent);
				if (!measure.reaches(percent)) {
					if (existing.contains(key)) {
						fallen.add(key);
					}
				}
				else if (!existing.contains(key) && marks.claim(key)) {
					reached.merge(measure, percent, Math::max);
				}
			}
		}
		marks.release(fallen);
		return reached;
	}

	private Map<String, ProjectTimeSettings> settingsOf(List<String> projectIds) {
		Map<String, ProjectTimeSettings> byProject = new HashMap<>();
		if (!projectIds.isEmpty()) {
			mongo.find(Query.query(Criteria.where("projectId").in(projectIds)), ProjectTimeSettings.class)
					.forEach(settings -> byProject.put(settings.getProjectId(), settings));
		}
		return byProject;
	}

	private Map<String, Long> sums(Class<?> collection, Criteria match, String by, String field) {
		Map<String, Long> sums = new HashMap<>();
		mongo.aggregate(Aggregation.newAggregation(Aggregation.match(match),
						Aggregation.group(by).sum(field).as("total")), collection, Document.class)
				.forEach(row -> sums.put(String.valueOf(row.get("_id")), ((Number) row.get("total")).longValue()));
		return sums;
	}

	private Set<String> distinct(Criteria criteria, String field, Class<?> collection) {
		Set<String> values = new LinkedHashSet<>();
		mongo.findDistinct(Query.query(criteria), field, collection, String.class).stream()
				.filter(Objects::nonNull).forEach(values::add);
		return values;
	}

	private static <T> List<List<T>> batches(Set<T> values) {
		List<T> all = new ArrayList<>(values);
		List<List<T>> batches = new ArrayList<>();
		for (int start = 0; start < all.size(); start += BATCH) {
			batches.add(all.subList(start, Math.min(all.size(), start + BATCH)));
		}
		return batches;
	}
}
