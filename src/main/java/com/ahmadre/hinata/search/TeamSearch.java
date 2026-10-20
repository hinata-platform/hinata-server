package com.ahmadre.hinata.search;

import com.ahmadre.hinata.team.Team;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Teams by name or key (HIN-165): the teams the caller belongs to, the same set the Teams page
 * lists ({@code TeamService.visibleTo}). The membership is the query's equality on the
 * {@code members_user} index; one read.
 */
@Component
@Order(10)
@RequiredArgsConstructor
class TeamSearch implements SearchSource {

	private final MongoTemplate mongo;

	@Override
	public SearchCategory category() {
		return SearchCategory.TEAMS;
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public List<SearchHit> search(SearchContext context, int cap) {
		Criteria name = new Criteria().orOperator(context.contains("name"), context.prefix("key"));
		return find(new Criteria().andOperator(mine(context), name), cap);
	}

	@Override
	public List<SearchHit> suggest(SearchContext context, int limit) {
		return find(mine(context), limit);
	}

	private static Criteria mine(SearchContext context) {
		return Criteria.where("members.userId").is(context.userId());
	}

	private List<SearchHit> find(Criteria criteria, int limit) {
		Query query = Query.query(criteria).with(Sort.by("name")).limit(limit);
		// colorHue: a primitive the entity's constructor cannot be handed null for.
		query.fields().include("name", "key", "avatarUrl", "icon", "description", "colorHue");
		return mongo.find(query, Team.class).stream().map(team -> SearchHit.builder()
				.category(SearchCategory.TEAMS.name())
				.id(team.getId())
				.route("/teams/" + team.getId())
				.title(team.getName())
				.key(team.getKey())
				.subtitle(team.getDescription() == null || team.getDescription().isBlank() ? null
						: team.getDescription())
				.avatarUrl(team.getAvatarUrl())
				.icon(team.getIcon())
				.build()).toList();
	}
}
