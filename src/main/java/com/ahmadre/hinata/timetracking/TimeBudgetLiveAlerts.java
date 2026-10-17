package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.notification.NotificationService.TimeLimit;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Budget and estimate alerts the moment a threshold is crossed (HIN-153), for projects in
 * {@link ProjectTimeSettings.LimitMode#WARN}; projects in {@code OPEN} keep the hourly scan of
 * {@link TimeAlerts}.
 *
 * <p>A project's time against its budget and against the sum of its estimates goes to its leads
 * and to the administrators of the teams it belongs to, at the warning share, at 100 % and again at
 * 110, 125 and 150 %, so an overrun that keeps growing is heard without a message per entry. An
 * issue's time against its own estimate goes to its assignees from their share on, as before, and
 * to the same leads from 100 % on. Every sentence names the project or the issue and the sums,
 * never who recorded them (R7).
 *
 * <p>Behind the instance's {@code alertsEnabled}, like the hourly alerts: whether leads are told
 * about budgets is a rule the works council decides (§ 87 Abs. 1 Nr. 6 BetrVG), and a project's own
 * mode cannot switch it on. Once per crossing, claimed in {@link TimeMarks} under keys of its own;
 * when time falls under a threshold again the claim is given back.
 *
 * <p>After the request, on the async executor: a save never waits for a notification.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TimeBudgetLiveAlerts {

	/** The thresholds beyond 100 % at which an overrun is heard again. */
	static final List<Integer> OVERRUN = List.of(100, 110, 125, 150);

	private final TimeTrackingSettings policy;
	private final TimeUsageService usage;
	private final TimeMarks marks;
	private final NotificationService notifications;
	private final ProjectReach reach;
	private final TeamRepository teams;
	private final MongoTemplate mongo;
	private final org.springframework.beans.factory.ObjectProvider<MoneyBudgets> moneyBudgets;
	private final TimeHistoryService history;

	@Async
	@EventListener
	public void onWrite(TimeEntryWrites.Touched touched) {
		measure(touched);
	}

	/** The measuring itself, on the caller's thread. */
	void measure(TimeEntryWrites.Touched touched) {
		if (!policy.advancedEnabled() || !policy.alertsEnabled()) {
			return;
		}
		try {
			touched.projectIds().forEach(this::measureProject);
			touched.issueIds().forEach(this::measureIssue);
		}
		catch (RuntimeException ex) {
			// An alert is a side effect of a save that already succeeded; it must not
			// surface as an error anywhere. The hourly scan stays as the net.
			log.warn("[time] live budget alert failed: {}", ex.toString());
		}
	}

	static String key(String kind, String subjectId, int percent) {
		return "live:" + kind + ':' + subjectId + ':' + percent;
	}

	private void measureProject(String projectId) {
		ProjectTimeSettings settings = settingsOf(projectId);
		if (settings == null || settings.budgetMode() != ProjectTimeSettings.LimitMode.WARN) {
			return;
		}
		TimeUsageService.ProjectUsage figures = usage.ofProjects(List.of(projectId)).getFirst();
		Set<Integer> thresholds = new TreeSet<>(OVERRUN);
		thresholds.add(figures.nearPercent());
		Integer reachedBudget = settle("BUDGET", projectId, figures.usedMinutes(),
				figures.budgetMinutes() == null ? 0 : figures.budgetMinutes(), thresholds);
		Integer reachedEstimates = settle("ESTIMATES", projectId, figures.usedMinutes(),
				figures.estimatedMinutes(), thresholds);
		// A revenue budget, while billing is on (HIN-155). A cost budget alerts nobody: its figure is
		// an administrator's alone.
		MoneyBudgets money = moneyBudgets.getIfAvailable();
		boolean revenue = money != null && money.enabled() && settings.getBudgetCents() != null
				&& settings.getBudgetCents() > 0 && settings.rateKind() == ProjectTimeSettings.RateKind.BILLABLE;
		long usedCents = revenue ? money.usedCents(projectId, ProjectTimeSettings.RateKind.BILLABLE) : 0;
		Integer reachedMoney = revenue
				? settle("MONEY", projectId, usedCents, settings.getBudgetCents(), thresholds) : null;
		if (reachedBudget == null && reachedEstimates == null && reachedMoney == null) {
			return;
		}
		Project project = activeProject(projectId);
		if (project == null) {
			return;
		}
		Set<String> leads = leadsOf(project);
		if (reachedBudget != null) {
			notifications.notifyTimeBudgetAlert(leads, projectId, project.getName(), TimeLimit.BUDGET,
					reachedBudget, figures.usedMinutes(), figures.budgetMinutes());
		}
		if (reachedEstimates != null) {
			notifications.notifyTimeBudgetAlert(leads, projectId, project.getName(), TimeLimit.ESTIMATES,
					reachedEstimates, figures.usedMinutes(), figures.estimatedMinutes());
		}
		if (reachedMoney != null) {
			notifications.notifyTimeMoneyAlert(projectLeadsOf(project), projectId, project.getName(), reachedMoney,
					usedCents, settings.getBudgetCents(), money.currency());
		}
	}

	private void measureIssue(String issueId) {
		Query byId = Query.query(Criteria.where("_id").is(issueId).and("archived").is(false));
		byId.fields().include("readableId", "projectId", "estimateMinutes", "assigneeIds")
				.include(Issue.PROJECTION_REQUIRED);
		Issue issue = mongo.findOne(byId, Issue.class);
		if (issue == null || issue.getProjectId() == null) {
			return;
		}
		ProjectTimeSettings settings = settingsOf(issue.getProjectId());
		// The sprint follows the project's budget mode, and is measured with or without an estimate.
		if (settings == null || settings.budgetMode() == ProjectTimeSettings.LimitMode.WARN) {
			measureSprint(issue);
		}
		if (issue.getEstimateMinutes() == null || issue.getEstimateMinutes() <= 0) {
			return;
		}
		if (settings != null && settings.estimateMode() != ProjectTimeSettings.LimitMode.WARN) {
			return;
		}
		Integer own = settings == null || settings.getAlertThresholds() == null ? null
				: settings.getAlertThresholds().getEstimatePercent();
		int assigneeShare = own == null ? TimeAlerts.DEFAULT_ESTIMATE_PERCENT : own;
		Set<Integer> thresholds = new TreeSet<>(OVERRUN);
		thresholds.add(assigneeShare);
		Integer reached = settle("ISSUE", issueId, issue.getSpentMinutes(), issue.getEstimateMinutes(), thresholds);
		if (reached == null) {
			return;
		}
		Project project = activeProject(issue.getProjectId());
		if (project == null) {
			return;
		}
		Set<String> told = new LinkedHashSet<>();
		if (reached >= assigneeShare && issue.getAssigneeIds() != null) {
			// Removing a member does not unassign them; what an issue they can no longer open
			// has cost is not theirs to learn.
			told.addAll(reach.whoCanSee(project.getId(), issue.getAssigneeIds()));
		}
		if (reached >= 100) {
			told.addAll(leadsOf(project));
		}
		notifications.notifyTimeEstimateReached(told, issue.getReadableId(), project.getId(), reached,
				issue.getSpentMinutes(), issue.getEstimateMinutes());
	}

	/** The issue's sprint against its budget (HIN-156): the project's leads hear the stages. */
	private void measureSprint(Issue issue) {
		history.sprintOfIssue(issue.getId()).ifPresent(figures -> {
			Set<Integer> thresholds = new TreeSet<>(OVERRUN);
			thresholds.add(TimeAlerts.DEFAULT_BUDGET_PERCENT);
			Integer reached = settle("SPRINT", figures.sprint().getId(), figures.usedMinutes(),
					figures.budgetMinutes(), thresholds);
			if (reached == null) {
				return;
			}
			Project project = activeProject(issue.getProjectId());
			if (project != null) {
				notifications.notifyTimeBudgetAlert(leadsOf(project), project.getId(), figures.sprint().getName(),
						TimeLimit.BUDGET, reached, figures.usedMinutes(), figures.budgetMinutes());
			}
		});
	}

	/**
	 * Claims every threshold of [thresholds] that [used] reaches against [limit] and nobody claimed,
	 * gives back those it is under, and returns the highest newly claimed one, or null.
	 */
	private Integer settle(String kind, String subjectId, long used, long limit, Set<Integer> thresholds) {
		if (limit <= 0) {
			return null;
		}
		List<String> under = new ArrayList<>();
		List<String> reached = new ArrayList<>();
		for (int percent : thresholds) {
			(used * 100 >= limit * percent ? reached : under).add(key(kind, subjectId, percent));
		}
		marks.release(under);
		Set<String> existing = marks.existing(reached);
		Set<String> taken = marks.claimAll(reached.stream().filter(k -> !existing.contains(k)).toList());
		Integer highest = null;
		for (int percent : thresholds) {
			if (taken.contains(key(kind, subjectId, percent))) {
				highest = percent;
			}
		}
		return highest;
	}

	private ProjectTimeSettings settingsOf(String projectId) {
		return mongo.findOne(Query.query(Criteria.where("projectId").is(projectId)), ProjectTimeSettings.class);
	}

	private Project activeProject(String projectId) {
		Query query = Query.query(Criteria.where("_id").is(projectId).and("archived").ne(true));
		query.fields().include("name", "leadId", "leadIds", "archived", "issueCounter");
		return mongo.findOne(query, Project.class);
	}

	/** The project's own leads. */
	private static Set<String> projectLeadsOf(Project project) {
		Set<String> leads = new LinkedHashSet<>();
		if (project.getLeadIds() != null) {
			leads.addAll(project.getLeadIds());
		}
		if (project.getLeadId() != null) {
			leads.add(project.getLeadId());
		}
		return leads;
	}

	/** The project's leads and the administrators of every team it belongs to. */
	private Set<String> leadsOf(Project project) {
		Set<String> leads = projectLeadsOf(project);
		for (Team team : teams.findByProjectIdsContains(project.getId())) {
			if (team.getMembers() == null) continue;
			team.getMembers().stream().filter(TeamMembership::isAdmin).map(TeamMembership::getUserId)
					.forEach(leads::add);
		}
		return leads;
	}
}
