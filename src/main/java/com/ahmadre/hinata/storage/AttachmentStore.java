package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Atomic mutations of an issue's embedded {@code attachments} list. Uses
 * MongoDB {@code $push} / {@code $pull} so several files uploaded in parallel
 * (or by different users) can never clobber each other through a
 * read-modify-write race. Authorization is enforced by the controller before
 * these methods are called.
 */
@Component
@RequiredArgsConstructor
public class AttachmentStore {

	private final MongoTemplate mongo;

	/** Atomically appends an attachment and returns the updated issue. */
	public Issue add(String issueId, Issue.Attachment attachment) {
		Issue updated = mongo.findAndModify(
				new Query(Criteria.where("_id").is(issueId)),
				new Update().push("attachments", attachment).set("updatedAt", Instant.now()),
				FindAndModifyOptions.options().returnNew(true),
				Issue.class);
		if (updated == null) {
			throw ApiException.notFound("issue");
		}
		return updated;
	}

	/** Atomically removes the attachment with the given id; returns the issue. */
	public Issue remove(String issueId, String attachmentId) {
		Issue updated = mongo.findAndModify(
				new Query(Criteria.where("_id").is(issueId)),
				new Update().pull("attachments", new Document("id", attachmentId))
						.set("updatedAt", Instant.now()),
				FindAndModifyOptions.options().returnNew(true),
				Issue.class);
		if (updated == null) {
			throw ApiException.notFound("issue");
		}
		return updated;
	}
}
