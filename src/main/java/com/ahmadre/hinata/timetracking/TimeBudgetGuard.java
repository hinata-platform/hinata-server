package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.issue.Issue;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * What a saved entry did to its project's budget and its issue's estimate (HIN-153), said back to
 * the person who saved it.
 *
 * <p>Advice, never a refusal: recording working time is never stopped over a budget (§ 16 Abs. 2
 * ArbZG, EuGH C-55/18), so this runs after the save and only describes. A project in
 * {@link ProjectTimeSettings.LimitMode#OPEN} says nothing here; its leads keep the hourly alert.
 * The figures are sums of the whole project or issue and name nobody.
 */
@Service
@RequiredArgsConstructor
public class TimeBudgetGuard {

	private final TimeTrackingSettings policy;
	private final TimeUsageService usage;
	private final MongoTemplate mongo;

	/** Which limit a warning speaks about. */
	public enum Kind {
		/** The project's time budget. */
		BUDGET,
		/** The issue's own estimate. */
		ESTIMATE
	}

	/** How far: from the warning share, or from the limit on. */
	public enum Level {
		NEAR, OVER
	}

	/** One limit the entry brought near or past. */
	public record BudgetWarning(Kind kind, Level level, String subjectId, long used, long limit, int percent) {
	}

	/** The warnings for [saved], fullest first; empty when nothing is near a limit. */
	public List<BudgetWarning> warningsAfter(WorkItem saved) {
		List<BudgetWarning> warnings = new ArrayList<>();
		if (saved == null || saved.getProjectId() == null || !policy.advancedEnabled()) {
			return warnings;
		}
		ProjectTimeSettings settings = mongo.findOne(
				Query.query(Criteria.where("projectId").is(saved.getProjectId())), ProjectTimeSettings.class);
		int near = TimeUsageService.nearPercentOf(settings);
		if (settings != null && settings.budgetMode() == ProjectTimeSettings.LimitMode.WARN
				&& settings.getBudgetMinutes() != null && settings.getBudgetMinutes() > 0) {
			long used = usage.ofProjects(List.of(saved.getProjectId())).getFirst().usedMinutes();
			add(warnings, Kind.BUDGET, saved.getProjectId(), used, settings.getBudgetMinutes(), near);
		}
		ProjectTimeSettings.LimitMode estimateMode = settings == null ? ProjectTimeSettings.LimitMode.WARN
				: settings.estimateMode();
		if (saved.getIssueId() != null && estimateMode == ProjectTimeSettings.LimitMode.WARN) {
			Query byId = Query.query(Criteria.where("_id").is(saved.getIssueId()));
			byId.fields().include("estimateMinutes", "spentMinutes").include(Issue.PROJECTION_REQUIRED);
			Issue issue = mongo.findOne(byId, Issue.class);
			if (issue != null && issue.getEstimateMinutes() != null && issue.getEstimateMinutes() > 0) {
				add(warnings, Kind.ESTIMATE, issue.getId(), issue.getSpentMinutes(), issue.getEstimateMinutes(),
						near);
			}
		}
		return warnings;
	}

	private static void add(List<BudgetWarning> into, Kind kind, String subject, long used, long limit,
			int near) {
		if (limit <= 0 || used * 100 < limit * near) {
			return;
		}
		Level level = used >= limit ? Level.OVER : Level.NEAR;
		// Rounded towards the limit's side, as the app shows it: down below, up past it.
		int percent = (int) (used > limit ? (used * 100 + limit - 1) / limit : used * 100 / limit);
		into.add(new BudgetWarning(kind, level, subject, used, limit, percent));
	}
}
