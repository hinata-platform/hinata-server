package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.notification.NotificationService.TimeLimit;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectReach;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Budget and estimate alerts (HIN-92): facts about the work, never about the people doing it.
 *
 * <p>A project's recorded time against its budget and against the sum of its issues' estimates
 * goes to the project's leads, at the project's warning threshold (80 % unless set) and at
 * 100 %. An issue's recorded time against its own estimate goes to those of its assignees who can
 * still see the project, at the project's estimate threshold (100 % unless set). The sentences name
 * the project or the issue and the sums, never a person. Archived projects are not measured.
 *
 * <p>Once per crossing. A threshold that is reached is claimed ({@link TimeMark}); when the time
 * falls under it again, because the budget or an estimate was raised or entries were removed, the
 * claim is given back and the threshold can alert again. A measure with nobody to tell claims
 * nothing, so the alert still comes once somebody is there.
 *
 * <p>Only what moved is measured, an hour of changes at a time. Each slice reads the projects and
 * issues whose entries were created or edited in it, from the keys of two covering indexes, and
 * the projects whose settings changed in it; each finished slice moves the watermark, so a run cut
 * short resumes where it stopped and a backlog is worked off instead of growing. A project is
 * summed only when it has a budget or estimates, and from the keys of {@code project_duration}.
 * What moves without an entry, an estimate lowered or an issue moved, is measured with the next
 * entry.
 */
@Service
@RequiredArgsConstructor
class TimeAlerts {

	static final int BATCH = 500;
	static final Duration RUN_BUDGET = Duration.ofMinutes(4);

	/** How far back the first run ever looks, and the furthest any run looks. */
	static final Duration FIRST_LOOKBACK = Duration.ofHours(2);
	static final Duration LONGEST_LOOKBACK = Duration.ofDays(7);

	/** The span of changes one step of a run reads, so a step stays bounded however far behind it is. */
	static final Duration SLICE = Duration.ofHours(1);

	static final int DEFAULT_BUDGET_PERCENT = 80;
	static final int DEFAULT_ESTIMATE_PERCENT = 100;

	/** The mark holding how far the scan has got. */
	static final String SCANNED = "alerts:scanned";

	private final MongoTemplate mongo;
	private final TimeTrackingSettings policy;
	private final TimeMarks marks;
	private final NotificationService notifications;
	private final ProjectReach reach;
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
		Instant now = clock.instant();
		Instant deadline = now.plus(RUN_BUDGET);
		Instant from = marks.watermark(SCANNED).orElse(now.minus(FIRST_LOOKBACK));
		if (from.isBefore(now.minus(LONGEST_LOOKBACK))) {
			from = now.minus(LONGEST_LOOKBACK);
		}
		int sent = 0;
		while (from.isBefore(now) && !clock.instant().isAfter(deadline)) {
			Instant until = from.plus(SLICE).isBefore(now) ? from.plus(SLICE) : now;
			sent += alertSlice(from, until);
			marks.advance(SCANNED, until);
			from = until;
		}
		return sent;
	}

	/** Everything whose entries or settings changed from [from] up to [until]. */
	private int alertSlice(Instant from, Instant until) {
		Set<String> projectIds = new LinkedHashSet<>();
		List<String> issueIds = new ArrayList<>();
		int sent = 0;
		Aggregation touched = Aggregation.newAggregation(
						Aggregation.match(new Criteria().orOperator(
								Criteria.where("createdAt").gte(from).lt(until),
								Criteria.where("updatedAt").gte(from).lt(until))),
						Aggregation.project("projectId", "issueId").andExclude("_id"),
						Aggregation.group("projectId", "issueId"))
				.withOptions(AggregationOptions.builder().allowDiskUse(true).cursorBatchSize(BATCH).build());
		try (Stream<Document> rows = mongo.aggregateStream(touched, WorkItem.class, Document.class)) {
			for (Iterator<Document> it = rows.iterator(); it.hasNext(); ) {
				Document key = it.next().get("_id", Document.class);
				if (key.getString("projectId") != null) {
					projectIds.add(key.getString("projectId"));
				}
				if (key.getString("issueId") != null) {
					issueIds.add(key.getString("issueId"));
				}
				if (issueIds.size() == BATCH) {
					sent += alertIssues(List.copyOf(issueIds));
					issueIds.clear();
				}
			}
		}
		if (!issueIds.isEmpty()) {
			sent += alertIssues(List.copyOf(issueIds));
		}
		mongo.findDistinct(Query.query(Criteria.where("updatedAt").gte(from).lt(until)), "projectId",
				ProjectTimeSettings.class, String.class).stream().filter(Objects::nonNull).forEach(projectIds::add);
		List<String> projects = List.copyOf(projectIds);
		for (int start = 0; start < projects.size(); start += BATCH) {
			sent += alertProjects(projects.subList(start, Math.min(projects.size(), start + BATCH)));
		}
		return sent;
	}

	private int alertProjects(List<String> ids) {
		Map<String, ProjectTimeSettings> settings = settingsOf(ids);
		Map<String, Long> estimated = sums(Issue.class, Criteria.where("projectId").in(ids)
				.and("archived").is(false).and("estimateMinutes").gt(0), "projectId", "estimateMinutes", "project_estimates");
		List<String> measurable = ids.stream()
				.filter(id -> budgetOf(settings.get(id)) > 0 || estimated.getOrDefault(id, 0L) > 0)
				.toList();
		if (measurable.isEmpty()) {
			return 0;
		}
		Query query = Query.query(Criteria.where("_id").in(measurable).and("archived").ne(true)).withHint("_id_");
		query.fields().include("name", "leadId", "leadIds");
		Map<String, Project> projects = new HashMap<>();
		Map<String, Set<String>> leads = new HashMap<>();
		for (Project project : mongo.find(query, Project.class)) {
			Set<String> ofProject = leadsOf(project);
			if (!ofProject.isEmpty()) {
				projects.put(project.getId(), project);
				leads.put(project.getId(), ofProject);
			}
		}
		if (projects.isEmpty()) {
			return 0;
		}
		Map<String, Long> recorded = sums(WorkItem.class, Criteria.where("projectId").in(projects.keySet()),
				"projectId", "durationMinutes", "project_duration");

		List<Measure> measures = new ArrayList<>();
		for (Project project : projects.values()) {
			ProjectTimeSettings own = settings.get(project.getId());
			Integer warning = own == null || own.getAlertThresholds() == null ? null
					: own.getAlertThresholds().getBudgetPercent();
			Set<Integer> percents = new TreeSet<>(List.of(warning == null ? DEFAULT_BUDGET_PERCENT : warning, 100));
			long used = recorded.getOrDefault(project.getId(), 0L);
			measures.add(new Measure(Kind.BUDGET, project.getId(), used, budgetOf(own), percents));
			measures.add(new Measure(Kind.ESTIMATES, project.getId(), used,
					estimated.getOrDefault(project.getId(), 0L), percents));
		}

		int sent = 0;
		for (Map.Entry<Measure, Integer> reached : settle(measures).entrySet()) {
			Measure measure = reached.getKey();
			Project project = projects.get(measure.subjectId());
			notifications.notifyTimeBudgetAlert(leads.get(project.getId()), project.getId(), project.getName(),
					measure.kind() == Kind.ESTIMATES ? TimeLimit.ESTIMATES : TimeLimit.BUDGET,
					reached.getValue(), measure.used(), measure.limit());
			sent++;
		}
		return sent;
	}

	private int alertIssues(List<String> ids) {
		Query query = Query.query(Criteria.where("_id").in(ids).and("archived").is(false)
				.and("estimateMinutes").gt(0)).withHint("_id_");
		query.fields().include("readableId", "projectId", "estimateMinutes", "spentMinutes", "assigneeIds");
		List<Issue> issues = mongo.find(query, Issue.class).stream()
				.filter(issue -> issue.getProjectId() != null && issue.getAssigneeIds() != null
						&& !issue.getAssigneeIds().isEmpty())
				.toList();
		if (issues.isEmpty()) {
			return 0;
		}
		Map<String, Set<String>> recipients = recipientsOf(issues);
		Map<String, Issue> byId = new HashMap<>();
		issues.stream().filter(issue -> recipients.containsKey(issue.getId()))
				.forEach(issue -> byId.put(issue.getId(), issue));
		Map<String, ProjectTimeSettings> settings = settingsOf(byId.values().stream()
				.map(Issue::getProjectId).distinct().toList());

		List<Measure> measures = new ArrayList<>();
		for (Issue issue : byId.values()) {
			ProjectTimeSettings own = settings.get(issue.getProjectId());
			Integer percent = own == null || own.getAlertThresholds() == null ? null
					: own.getAlertThresholds().getEstimatePercent();
			measures.add(new Measure(Kind.ISSUE, issue.getId(), issue.getSpentMinutes(), issue.getEstimateMinutes(),
					Set.of(percent == null ? DEFAULT_ESTIMATE_PERCENT : percent)));
		}

		int sent = 0;
		for (Map.Entry<Measure, Integer> reached : settle(measures).entrySet()) {
			Issue issue = byId.get(reached.getKey().subjectId());
			notifications.notifyTimeEstimateReached(recipients.get(issue.getId()), issue.getReadableId(),
					issue.getProjectId(), reached.getValue(), issue.getSpentMinutes(), issue.getEstimateMinutes());
			sent++;
		}
		return sent;
	}

	/**
	 * Who of each issue's assignees is told: those who can still see its project, which is not
	 * archived. Removing a member does not unassign them, and what an issue they can no longer open
	 * has cost is not theirs to learn. One question per project, not per issue.
	 */
	private Map<String, Set<String>> recipientsOf(List<Issue> issues) {
		Map<String, List<Issue>> byProject = issues.stream().collect(Collectors.groupingBy(Issue::getProjectId));
		Query open = Query.query(Criteria.where("_id").in(byProject.keySet()).and("archived").ne(true)).withHint("_id_");
		open.fields().include("_id");
		Map<String, Set<String>> recipients = new HashMap<>();
		for (Project project : mongo.find(open, Project.class)) {
			List<Issue> ofProject = byProject.get(project.getId());
			Set<String> assignees = ofProject.stream().flatMap(issue -> issue.getAssigneeIds().stream())
					.collect(Collectors.toSet());
			Set<String> seeing = new HashSet<>(reach.whoCanSee(project.getId(), assignees));
			for (Issue issue : ofProject) {
				Set<String> told = issue.getAssigneeIds().stream().filter(seeing::contains).collect(Collectors.toSet());
				if (!told.isEmpty()) {
					recipients.put(issue.getId(), told);
				}
			}
		}
		return recipients;
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
		List<String> wanted = new ArrayList<>();
		for (Measure measure : measures) {
			for (int percent : measure.percents()) {
				String key = measure.key(percent);
				if (!measure.reaches(percent) && existing.contains(key)) {
					fallen.add(key);
				}
				else if (measure.reaches(percent) && !existing.contains(key)) {
					wanted.add(key);
				}
			}
		}
		Set<String> taken = marks.claimAll(wanted);
		Map<Measure, Integer> reached = new LinkedHashMap<>();
		for (Measure measure : measures) {
			for (int percent : measure.percents()) {
				if (taken.contains(measure.key(percent))) {
					reached.merge(measure, percent, Math::max);
				}
			}
		}
		marks.release(fallen);
		return reached;
	}

	private static long budgetOf(ProjectTimeSettings settings) {
		return settings == null || settings.getBudgetMinutes() == null ? 0 : settings.getBudgetMinutes();
	}

	private static Set<String> leadsOf(Project project) {
		Set<String> leads = new LinkedHashSet<>();
		if (project.getLeadIds() != null) {
			leads.addAll(project.getLeadIds());
		}
		if (project.getLeadId() != null) {
			leads.add(project.getLeadId());
		}
		return leads;
	}

	private Map<String, ProjectTimeSettings> settingsOf(List<String> projectIds) {
		Map<String, ProjectTimeSettings> byProject = new HashMap<>();
		if (!projectIds.isEmpty()) {
			mongo.find(Query.query(Criteria.where("projectId").in(projectIds)), ProjectTimeSettings.class)
					.forEach(settings -> byProject.put(settings.getProjectId(), settings));
		}
		return byProject;
	}

	/** A sum per group from the keys of [index] alone, which holds both fields. */
	private Map<String, Long> sums(Class<?> collection, Criteria match, String by, String field, String index) {
		Map<String, Long> sums = new HashMap<>();
		mongo.aggregate(Aggregation.newAggregation(Aggregation.match(match),
								Aggregation.group(by).sum(field).as("total"))
						.withOptions(AggregationOptions.builder().hint(index).build()), collection, Document.class)
				.forEach(row -> sums.put(String.valueOf(row.get("_id")), ((Number) row.get("total")).longValue()));
		return sums;
	}
}
