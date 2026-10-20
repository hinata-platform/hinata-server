package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.search.SearchCategory;
import com.ahmadre.hinata.search.SearchContext;
import com.ahmadre.hinata.search.SearchHit;
import com.ahmadre.hinata.search.SearchSource;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rates in the global search (HIN-165), in the {@link SearchCategory#BILLING} group.
 *
 * <p>Asked only while billing is on ({@link BillingSettings#enabled()}), and answered only for
 * somebody {@link BillingAccess} gives billing at all: an organisation administrator, or a lead for
 * the projects they lead. A rate has no name; it is found by its project's name or key, among the
 * projects the caller sees and may price, with {@code projectId $in} on the {@code project_kind}
 * index. Costs only for whoever sees costs (R7). Two reads at most: the led projects (none for an
 * administrator) and the rates.
 */
@Component
@RequiredArgsConstructor
class BillingSearch implements SearchSource {

	private final BillingSettings settings;
	private final BillingAccess access;
	private final MongoTemplate mongo;

	@Override
	public SearchCategory category() {
		return SearchCategory.BILLING;
	}

	@Override
	public boolean enabled() {
		return settings.enabled();
	}

	@Override
	public List<SearchHit> search(SearchContext context, int cap) {
		List<Project> matching = context.projectsMatching();
		if (matching.isEmpty()) return List.of();
		BillingAccess.Reach reach = access.of(context.user());
		List<String> priced = matching.stream().map(Project::getId).filter(reach::leads).toList();
		if (priced.isEmpty()) return List.of();
		Criteria rates = Criteria.where("projectId").in(priced);
		if (!reach.costs()) rates = rates.and("kind").is(BillingRate.Kind.BILLABLE);
		Query query = Query.query(rates).with(Sort.by(Sort.Direction.DESC, "validFrom", "_id")).limit(cap);
		return mongo.find(query, BillingRate.class).stream().map(rate -> {
			Project project = context.project(rate.getProjectId());
			return SearchHit.builder()
					.category(SearchCategory.BILLING.name())
					.kind("RATE")
					.id(rate.getId())
					.route("/time/rates?rate=" + rate.getId())
					.title(project.getName())
					.type(rate.getScope() == null ? null : rate.getScope().name())
					.status(rate.getKind() == null ? null : rate.getKind().name())
					.amountCents(rate.getAmountCents())
					.currency(rate.getCurrency())
					.from(rate.getValidFrom())
					.to(rate.getValidTo())
					.projectKey(project.getKey())
					.projectColor(project.getColor())
					.projectId(project.getId())
					.projectName(project.getName())
					.build();
		}).toList();
	}

	/** The rates of the projects the caller sees and may price, revenue only without costs, up to [limit]. */
	@Override
	public long count(SearchContext context, int limit) {
		BillingAccess.Reach reach = access.of(context.user());
		List<String> priced = context.projectIds().stream().filter(reach::leads).toList();
		if (priced.isEmpty()) return 0;
		Criteria rates = Criteria.where("projectId").in(priced);
		if (!reach.costs()) rates = rates.and("kind").is(BillingRate.Kind.BILLABLE);
		return mongo.count(Query.query(rates).limit(limit), BillingRate.class);
	}
}
