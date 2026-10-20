package com.ahmadre.hinata.search;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.space.Space;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Knowledge spaces by name (HIN-165), in the {@link SearchCategory#DOCS} group after the pages.
 *
 * <p>The rule of {@code SpaceController}: a space is seen by its author and by whoever reads a
 * page filed in it. Both go into the query, the second as {@code name $in} over the spaces of the
 * readable pages (one distinct over {@link SearchContext#pages()}); two reads.
 */
@Component
@Order(10)
@RequiredArgsConstructor
class SpaceSearch implements SearchSource {

	private final MongoTemplate mongo;

	@Override
	public SearchCategory category() {
		return SearchCategory.DOCS;
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public List<SearchHit> search(SearchContext context, int cap) {
		List<String> read = mongo.findDistinct(Query.query(context.pages()), "space", Article.class, String.class)
				.stream().filter(Objects::nonNull).toList();
		Criteria seen = new Criteria().orOperator(Criteria.where("authorId").is(context.userId()),
				Criteria.where("name").in(read));
		Query query = Query.query(new Criteria().andOperator(context.contains("name"), seen))
				.with(Sort.by("sortOrder", "name")).limit(cap);
		return mongo.find(query, Space.class).stream().map(space -> SearchHit.builder()
				.category(SearchCategory.DOCS.name())
				.kind("SPACE")
				.id(space.getId())
				// The app addresses a space by its name, the key its pages carry.
				.route("/knowledge/spaces/" + URLEncoder.encode(space.getName(), StandardCharsets.UTF_8)
						.replace("+", "%20"))
				.title(space.getName())
				.subtitle(space.getDescription() == null || space.getDescription().isBlank() ? null
						: space.getDescription())
				.icon(space.getIcon())
				.hue(space.getHue())
				.build()).toList();
	}
}
