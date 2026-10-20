package com.ahmadre.hinata.search;

import com.ahmadre.hinata.issue.Issue;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Attachments by file name (HIN-165). An attachment lives inside its issue, so the query is the
 * issue reach ({@link SearchContext#issues}) with the file name as one more condition.
 *
 * <p>Only the issues that hold a file are read: the caller's projects on
 * {@link Issue#WITH_FILES_INDEX}, which holds no other issue, and the issues shared with them
 * outside those projects on {@code participants_updated}, one person's handful. Each read names
 * its index, newest issue first, and takes the issue's title, key and files, never its body. Two
 * reads; of a found issue only the files whose name matched become hits.
 */
@Component
@RequiredArgsConstructor
class AttachmentSearch implements SearchSource {

	/** The issue reach's other branch: the issues shared with the caller (HIN-136). */
	static final String SHARED_INDEX = "participants_updated";

	private final MongoTemplate mongo;

	@Override
	public SearchCategory category() {
		return SearchCategory.ATTACHMENTS;
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public List<SearchHit> search(SearchContext context, int cap) {
		List<Issue> found = new ArrayList<>();
		for (Query query : queries(context, cap)) {
			found.addAll(mongo.find(context.bounded(query), Issue.class));
		}
		found.sort(Comparator.comparing(Issue::getId).reversed());
		List<SearchHit> hits = new ArrayList<>();
		for (Issue issue : found) {
			if (issue.getAttachments() == null) continue;
			for (Issue.Attachment file : issue.getAttachments()) {
				if (hits.size() >= cap) return hits;
				if (!context.matches(file.getFileName())) continue;
				hits.add(SearchHit.builder()
						.category(SearchCategory.ATTACHMENTS.name())
						.id(file.getId())
						.route("/issues/" + issue.getId() + "?attachment=" + file.getId())
						.title(file.getFileName())
						.subtitle(issue.getTitle())
						.readableId(issue.getReadableId())
						.issueId(issue.getId())
						.contentType(file.getContentType())
						.size(file.getSize())
						.createdAt(file.getUploadedAt())
						.build());
			}
		}
		return hits;
	}

	/**
	 * The two reads of a search, the caller's projects first: issues with a file whose name holds
	 * the query, newest first, at most [cap] each.
	 */
	List<Query> queries(SearchContext context, int cap) {
		Criteria named = context.contains("attachments.fileName");
		Query projects = Query.query(context.projectIssues(withFiles(), named))
				.with(Sort.by(Sort.Direction.DESC, "_id")).limit(cap).withHint(Issue.WITH_FILES_INDEX);
		Query shared = Query.query(context.sharedIssues(withFiles(), named))
				.with(Sort.by(Sort.Direction.DESC, "_id")).limit(cap).withHint(SHARED_INDEX);
		List<Query> both = List.of(projects, shared);
		both.forEach(query -> query.fields().include("title", "readableId", "attachments")
				.include(Issue.PROJECTION_REQUIRED));
		return both;
	}

	/**
	 * Attachments on issues the caller reaches, at most [limit]: of the first [limit] issues that
	 * have any, in each branch of the reach, their files added up. Each such issue holds at least
	 * one, so the cap still holds.
	 */
	@Override
	public long count(SearchContext context, int limit) {
		long files = count(context, context.projectIssues(withFiles()), Issue.WITH_FILES_INDEX, limit)
				+ count(context, context.sharedIssues(withFiles()), SHARED_INDEX, limit);
		return Math.min(limit, files);
	}

	private long count(SearchContext context, Criteria issues, String index, int limit) {
		List<Document> pipeline = List.of(
				new Document("$match", issues.getCriteriaObject()),
				new Document("$limit", limit),
				new Document("$group", new Document("_id", null)
						.append("n", new Document("$sum", new Document("$size", "$attachments")))));
		Document counted = mongo.getCollection(mongo.getCollectionName(Issue.class)).aggregate(pipeline)
				.hintString(index).maxTime(context.timeLeftMillis(), TimeUnit.MILLISECONDS).first();
		return counted == null ? 0 : ((Number) counted.get("n")).longValue();
	}

	/** An issue with at least one file: the condition {@link Issue#WITH_FILES_INDEX} is built on. */
	private static Criteria withFiles() {
		return Criteria.where("attachments.0").exists(true);
	}
}
