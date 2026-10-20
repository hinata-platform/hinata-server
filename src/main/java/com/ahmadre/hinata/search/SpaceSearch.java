package com.ahmadre.hinata.search;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.space.Space;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.aggregation.TypedAggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Knowledge spaces by name (HIN-165), in the {@link SearchCategory#DOCS} group after the pages.
 *
 * <p>The rule of {@code SpaceController}: a space is seen by its author and by whoever reads a
 * page filed in it. The spaces are few and the pages many, so the name goes first: at most
 * {@link #CANDIDATES_MAX} spaces whose name matches, and then, among the pages filed in those
 * of them the caller did not write, which hold a page they read (on the {@code space} index of
 * the pages, never across every page they read). Two reads.
 */
@Component
@RequiredArgsConstructor
class SpaceSearch implements SearchSource {

	/** Most spaces one search or count looks at before it asks which of them the caller sees. */
	static final int CANDIDATES_MAX = 200;

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
		return seen(context, context.contains("name")).stream().limit(cap).map(space -> SearchHit.builder()
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

	@Override
	public long count(SearchContext context, int limit) {
		return Math.min(limit, seen(context, new Criteria()).size());
	}

	/** Of the first {@link #CANDIDATES_MAX} spaces matching [named], those the caller sees, in order. */
	private List<Space> seen(SearchContext context, Criteria named) {
		Query query = context.bounded(Query.query(named).with(Sort.by("sortOrder", "name")).limit(CANDIDATES_MAX));
		List<Space> candidates = mongo.find(query, Space.class);
		List<String> others = candidates.stream().filter(space -> !context.userId().equals(space.getAuthorId()))
				.map(Space::getName).toList();
		Set<String> read = others.isEmpty() ? Set.of() : read(context, others);
		return candidates.stream()
				.filter(space -> context.userId().equals(space.getAuthorId()) || read.contains(space.getName()))
				.toList();
	}

	/** Which of the spaces [names] hold a page the caller reads. */
	private Set<String> read(SearchContext context, List<String> names) {
		Criteria pages = new Criteria().andOperator(Criteria.where("space").in(names), context.pages());
		// Typed, so the page ids a grant names are mapped the way the pages store them.
		TypedAggregation<Article> spaces = Aggregation.newAggregation(Article.class,
				Aggregation.match(pages), Aggregation.group("space"))
				.withOptions(AggregationOptions.builder().maxTime(context.timeLeft()).build());
		Set<String> read = new HashSet<>();
		mongo.aggregate(spaces, Document.class).forEach(row -> read.add(String.valueOf(row.get("_id"))));
		return read;
	}
}
