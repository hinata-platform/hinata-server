package com.ahmadre.hinata.issue;

import org.bson.Document;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.stream.Stream;

/**
 * The text a board search matches an issue by: its key, its title and its labels in lower case, one
 * per line.
 *
 * <p>Boards search by substring, which no index can bound. Kept on the issue as
 * {@link Issue#getSearchText()} and held by the board indexes as their last key, it lets a search
 * read index keys instead of documents. {@link IssueSearchTextCallback} computes it on every save; a
 * write that changes a key, a title or a label past the entity calls {@link #refresh}. Lower case is
 * taken in the root locale on both sides, so the text and what is searched for always agree.
 */
public final class IssueSearchText {

	public static final String FIELD = "searchText";

	private static final String ISSUES = "issues";
	private static final int BATCH = 500;

	private IssueSearchText() {
	}

	/** The search text of an issue with [readableId], [title] and [tags]. */
	public static String of(String readableId, String title, Collection<String> tags) {
		StringJoiner text = new StringJoiner("\n");
		add(text, readableId);
		add(text, title);
		if (tags != null) {
			tags.forEach(tag -> add(text, tag));
		}
		return text.toString();
	}

	/** [text] as a search matches it against what {@link #of} stores. */
	public static String needle(String text) {
		return text.toLowerCase(Locale.ROOT);
	}

	/**
	 * Computes the search text of every issue [criteria] matches again, from the key, the title and
	 * the labels it holds now, and writes it where it changed.
	 *
	 * @return how many issues changed
	 */
	public static long refresh(MongoTemplate mongo, Criteria criteria) {
		Query query = Query.query(criteria);
		query.fields().include("readableId", "title", "tags", FIELD);
		long changed = 0;
		BulkOperations bulk = null;
		try (Stream<Document> issues = mongo.stream(query, Document.class, ISSUES)) {
			for (Document issue : (Iterable<Document>) issues::iterator) {
				String text = of(issue.getString("readableId"), issue.getString("title"), tags(issue));
				if (text.equals(issue.getString(FIELD))) {
					continue;
				}
				if (bulk == null) {
					bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, ISSUES);
				}
				bulk.updateOne(Query.query(Criteria.where("_id").is(issue.get("_id"))), Update.update(FIELD, text));
				if (++changed % BATCH == 0) {
					bulk.execute();
					bulk = null;
				}
			}
		}
		if (bulk != null) {
			bulk.execute();
		}
		return changed;
	}

	private static void add(StringJoiner text, String value) {
		if (value != null && !value.isBlank()) {
			text.add(value.toLowerCase(Locale.ROOT));
		}
	}

	private static List<String> tags(Document issue) {
		if (!(issue.get("tags") instanceof List<?> tags)) {
			return List.of();
		}
		return tags.stream().filter(String.class::isInstance).map(String.class::cast).toList();
	}
}
