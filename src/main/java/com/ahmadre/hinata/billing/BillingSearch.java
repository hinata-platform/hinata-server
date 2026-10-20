package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.search.SearchCategory;
import com.ahmadre.hinata.search.SearchContext;
import com.ahmadre.hinata.search.SearchHit;
import com.ahmadre.hinata.search.SearchSource;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rates in the global search (HIN-165), in the {@link SearchCategory#BILLING} group.
 *
 * <p>Asked only while billing is on ({@link BillingSettings#enabled()}), and answered only for
 * somebody {@link BillingAccess} gives billing at all.
 * <ul>
 * <li>A project's rates (project, member, issue) are found by the project's name or key, among
 * the projects the caller sees and may price: a lead their led projects, revenue only; an
 * organisation administrator every project they see, costs included (R7).</li>
 * <li>The rates only an organisation administrator writes, for a person, a team or the instance
 * default, are found by the person's name, the team's name or key, and the default rate's label
 * in the caller's language, and only for an organisation administrator.</li>
 * </ul>
 * One rate query with an {@code $or} over what matched, after at most three lookups: the led
 * projects, the matching people and the matching teams. All of them lead to the rates page, where
 * every kind of rate is kept.
 */
@Component
// After the budgets (BudgetSearch): see there.
@Order(2)
@RequiredArgsConstructor
class BillingSearch implements SearchSource {

	/** Most people or teams one query matches by name before their rates are asked for. */
	static final int NAME_MATCH_MAX = 50;

	private static final List<BillingRate.Scope> ORGANISATION_SCOPES =
			List.of(BillingRate.Scope.USER, BillingRate.Scope.TEAM, BillingRate.Scope.DEFAULT);

	private final BillingSettings settings;
	private final BillingAccess access;
	private final MongoTemplate mongo;
	private final UserWords words;

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
		BillingAccess.Reach reach = access.of(context.user());
		if (!reach.admin() && reach.ledProjects().isEmpty()) return List.of();
		List<Criteria> branches = new ArrayList<>();
		List<String> priced = context.projectsMatching().stream().map(Project::getId).filter(reach::leads).toList();
		if (!priced.isEmpty()) branches.add(Criteria.where("projectId").in(priced));
		Map<String, String> people = Map.of();
		Map<String, Team> teams = Map.of();
		if (reach.costs()) {
			people = people(context);
			teams = teams(context);
			if (!people.isEmpty()) {
				branches.add(Criteria.where("scope").is(BillingRate.Scope.USER).and("scopeId").in(people.keySet()));
			}
			if (!teams.isEmpty()) {
				branches.add(Criteria.where("scope").is(BillingRate.Scope.TEAM).and("scopeId").in(teams.keySet()));
			}
			if (context.matches(defaultLabel(context))) {
				branches.add(Criteria.where("scope").is(BillingRate.Scope.DEFAULT));
			}
		}
		if (branches.isEmpty()) return List.of();
		Criteria rates = new Criteria().orOperator(branches.toArray(Criteria[]::new));
		if (!reach.costs()) rates = new Criteria().andOperator(rates, Criteria.where("kind").is(BillingRate.Kind.BILLABLE));
		Query query = context.bounded(Query.query(rates).with(Sort.by(Sort.Direction.DESC, "validFrom", "_id"))
				.limit(cap));
		List<SearchHit> hits = new ArrayList<>();
		for (BillingRate rate : mongo.find(query, BillingRate.class)) {
			hits.add(hit(rate, context, people, teams));
		}
		return hits;
	}

	/**
	 * The rates the caller may see, up to [limit]: a lead's revenue rates of the led projects they
	 * see; an organisation administrator's every rate of the projects they see and every rate for a
	 * person, a team or the default.
	 */
	@Override
	public long count(SearchContext context, int limit) {
		BillingAccess.Reach reach = access.of(context.user());
		if (!reach.admin() && reach.ledProjects().isEmpty()) return 0;
		List<String> priced = context.projectIds().stream().filter(reach::leads).toList();
		Criteria projects = Criteria.where("projectId").in(priced);
		Criteria rates = reach.costs()
				? new Criteria().orOperator(projects, Criteria.where("scope").in(ORGANISATION_SCOPES))
				: new Criteria().andOperator(projects, Criteria.where("kind").is(BillingRate.Kind.BILLABLE));
		return mongo.count(context.bounded(Query.query(rates).limit(limit)), BillingRate.class);
	}

	private SearchHit hit(BillingRate rate, SearchContext context, Map<String, String> people,
			Map<String, Team> teams) {
		SearchHit.SearchHitBuilder hit = SearchHit.builder()
				.category(SearchCategory.BILLING.name())
				.kind("RATE")
				.id(rate.getId())
				.route("/time/rates?rate=" + rate.getId())
				.rateLevel(rate.getScope() == null ? null : rate.getScope().name())
				.rateKind(rate.getKind() == null ? null : rate.getKind().name())
				.amountCents(rate.getAmountCents())
				.currency(rate.getCurrency())
				.from(rate.getValidFrom())
				.to(rate.getValidTo());
		Project project = context.project(rate.getProjectId());
		if (project != null) {
			return hit.title(project.getName()).projectKey(project.getKey()).projectColor(project.getColor())
					.projectId(project.getId()).projectName(project.getName()).build();
		}
		return switch (rate.getScope()) {
			case USER -> hit.title(people.get(rate.getScopeId())).build();
			case TEAM -> {
				Team team = teams.get(rate.getScopeId());
				yield hit.title(team == null ? null : team.getName()).key(team == null ? null : team.getKey()).build();
			}
			default -> hit.title(defaultLabel(context)).build();
		};
	}

	/** Active people by name or account name, id to name. */
	private Map<String, String> people(SearchContext context) {
		Query query = context.bounded(Query.query(new Criteria().andOperator(Criteria.where("active").is(true),
				new Criteria().orOperator(context.contains("displayName"), context.contains("username"))))
				.limit(NAME_MATCH_MAX));
		query.fields().include("displayName", "username");
		Map<String, String> byId = new LinkedHashMap<>();
		for (Document user : mongo.find(query, Document.class, mongo.getCollectionName(User.class))) {
			String name = user.getString("displayName");
			byId.put(String.valueOf(user.get("_id")), name == null || name.isBlank() ? user.getString("username") : name);
		}
		return byId;
	}

	/** Teams by name or key prefix, whatever the administrator's membership: they price every team. */
	private Map<String, Team> teams(SearchContext context) {
		Query query = context.bounded(Query.query(new Criteria().orOperator(context.contains("name"),
				context.prefix("key"))).limit(NAME_MATCH_MAX));
		// colorHue: a primitive the entity's constructor cannot be handed null for.
		query.fields().include("name", "key", "colorHue");
		Map<String, Team> byId = new LinkedHashMap<>();
		mongo.find(query, Team.class).forEach(team -> byId.put(team.getId(), team));
		return byId;
	}

	private String defaultLabel(SearchContext context) {
		return words.of(context.user(), "search.rate.default");
	}
}
