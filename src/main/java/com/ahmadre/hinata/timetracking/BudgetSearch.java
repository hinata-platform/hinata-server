package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.search.SearchCategory;
import com.ahmadre.hinata.search.SearchContext;
import com.ahmadre.hinata.search.SearchHit;
import com.ahmadre.hinata.search.SearchSource;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Project budgets in the global search (HIN-165), in the {@link SearchCategory#BILLING} group.
 *
 * <p>A budget has no name of its own; it is found by its project's name or key. Only projects the
 * caller sees are matched (in memory, on the set the search already holds), and of those only the
 * ones that have a budget at all, through {@code projectId $in} on the unique index of
 * {@code project_time_settings}. One read, none when no project matches. A hit carries no amount:
 * whether somebody sees money is the settings page's decision, and the hit only leads there.
 */
@Component
@RequiredArgsConstructor
class BudgetSearch implements SearchSource {

	private final TimeTrackingSettings settings;
	private final MongoTemplate mongo;

	@Override
	public SearchCategory category() {
		return SearchCategory.BILLING;
	}

	@Override
	public boolean enabled() {
		return settings.advancedEnabled();
	}

	/** Budgets of the projects the caller sees, up to [limit]. */
	@Override
	public long count(SearchContext context, int limit) {
		if (context.projectIds().isEmpty()) return 0;
		return mongo.count(Query.query(new Criteria().andOperator(Criteria.where("projectId").in(context.projectIds()),
				budgeted())).limit(limit), ProjectTimeSettings.class);
	}

	private static Criteria budgeted() {
		return new Criteria().orOperator(Criteria.where("budgetMinutes").ne(null), Criteria.where("budgetCents").ne(null));
	}

	@Override
	public List<SearchHit> search(SearchContext context, int cap) {
		List<String> matching = context.projectsMatching().stream().map(Project::getId).toList();
		if (matching.isEmpty()) return List.of();
		Query query = Query.query(new Criteria().andOperator(Criteria.where("projectId").in(matching),
				budgeted())).limit(cap);
		query.fields().include("projectId", "budgetMinutes");
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
}
