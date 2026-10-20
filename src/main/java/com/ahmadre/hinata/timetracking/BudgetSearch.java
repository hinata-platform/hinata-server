package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.search.SearchCategory;
import com.ahmadre.hinata.search.SearchContext;
import com.ahmadre.hinata.search.SearchHit;
import com.ahmadre.hinata.search.SearchSource;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Project budgets in the global search (HIN-165), in the {@link SearchCategory#BILLING} group.
 *
 * <p>A budget has no name of its own; it is found by its project's name or key. Only projects the
 * caller sees are matched (in memory, on the set the search already holds), and of those only the
 * ones with a budget the caller may see, through {@code projectId $in} on the unique index of
 * {@code project_time_settings}. One read, none when no project matches. A hit carries no amount:
 * whether somebody sees money is the settings page's decision, and the hit only leads there.
 *
 * <p>A time budget is seen by everyone who sees the project. A money budget only by whom
 * {@link ProjectTimeSettings#moneyReadable} lets see it, and only while billing is on, the same
 * as on the settings page; that is part of the query, so a project whose only budget is money the
 * caller may not see is neither found nor counted.
 *
 * <p>Budgets come before rates (BillingSearch) in the group: a project has one budget and may
 * have many rates, and the rates should not crowd its budget out of the places a group has.
 */
@Component
@Order(1)
@RequiredArgsConstructor
class BudgetSearch implements SearchSource {

	private final TimeTrackingSettings settings;
	private final ObjectProvider<MoneyBudgets> moneyBudgets;
	private final ProjectService projects;
	private final MongoTemplate mongo;

	@Override
	public SearchCategory category() {
		return SearchCategory.BILLING;
	}

	@Override
	public boolean enabled() {
		return settings.advancedEnabled();
	}

	/** Budgets the caller may see of the projects they see, up to [limit]. */
	@Override
	public long count(SearchContext context, int limit) {
		if (context.projectIds().isEmpty()) return 0;
		return mongo.count(context.bounded(Query.query(seen(context, context.projectIds())).limit(limit)),
				ProjectTimeSettings.class);
	}

	@Override
	public List<SearchHit> search(SearchContext context, int cap) {
		List<String> matching = context.projectsMatching().stream().map(Project::getId).toList();
		if (matching.isEmpty()) return List.of();
		Query query = context.bounded(Query.query(seen(context, matching)).limit(cap));
		query.fields().include("projectId");
		return mongo.find(query, ProjectTimeSettings.class).stream().map(budget -> {
			Project project = context.project(budget.getProjectId());
			return SearchHit.builder()
					.category(SearchCategory.BILLING.name())
					.kind("BUDGET")
					.id(budget.getId())
					.route("/projects/" + budget.getProjectId() + "/settings?section=timeTracking")
					.title(project.getName())
					.projectKey(project.getKey())
					.projectColor(project.getColor())
					.projectId(project.getId())
					.projectName(project.getName())
					.build();
		}).toList();
	}

	/**
	 * The settings of [among] with a budget the caller sees: a time budget, or a money budget while
	 * billing is on, any for an organisation administrator and a revenue one of a led project for a
	 * lead ({@link ProjectTimeSettings#moneyReadable}).
	 */
	private Criteria seen(SearchContext context, Collection<String> among) {
		Criteria time = Criteria.where("budgetMinutes").ne(null);
		Criteria budget = time;
		MoneyBudgets money = moneyBudgets.getIfAvailable();
		if (money != null && money.enabled()) {
			Criteria cents = Criteria.where("budgetCents").ne(null);
			if (context.user().isOrgAdmin()) {
				budget = new Criteria().orOperator(time, cents);
			} else {
				List<String> led = among.stream().map(context::project)
						.filter(project -> project != null && projects.isLead(project, context.user()))
						.map(Project::getId).toList();
				if (!led.isEmpty()) {
					// A stored null is a revenue budget (ProjectTimeSettings.RateKind#of).
					budget = new Criteria().orOperator(time, new Criteria().andOperator(cents,
							Criteria.where("budgetRateKind").ne(ProjectTimeSettings.RateKind.COST),
							Criteria.where("projectId").in(led)));
				}
			}
		}
		return new Criteria().andOperator(Criteria.where("projectId").in(among), budget);
	}
}
