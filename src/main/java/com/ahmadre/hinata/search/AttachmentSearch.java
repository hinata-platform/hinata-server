package com.ahmadre.hinata.search;

import com.ahmadre.hinata.issue.Issue;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Attachments by file name (HIN-165). An attachment lives inside its issue, so the query is the
 * issue reach ({@link SearchContext#issues}) with the file name as one more condition, newest
 * issue first on the {@code proj_arch_updated} and {@code participants_updated} indexes. One read;
 * of a found issue only the attachments whose name matched become hits.
 */
@Component
@Order(10)
@RequiredArgsConstructor
class AttachmentSearch implements SearchSource {

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
		Query query = Query.query(new Criteria().andOperator(context.issues(false),
						context.contains("attachments.fileName")))
				.with(Sort.by(Sort.Direction.DESC, "updatedAt")).limit(cap);
		query.fields().include("title", "readableId", "attachments").include(Issue.PROJECTION_REQUIRED);
		List<SearchHit> hits = new ArrayList<>();
		for (Issue issue : mongo.find(query, Issue.class)) {
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
}
