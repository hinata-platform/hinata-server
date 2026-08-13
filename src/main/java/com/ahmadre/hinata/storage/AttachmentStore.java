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
import java.util.Collection;
import java.util.List;

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

	private static final String FIELD = "attachments";
	private static final String UPDATED_AT = "updatedAt";
	private static final String ISSUE = "issue";

	private final MongoTemplate mongo;

	/** Atomically appends an attachment and returns the updated issue. */
	public Issue add(String issueId, Issue.Attachment attachment) {
		return apply(issueId, new Update().push(FIELD, attachment));
	}

	/** Atomically removes the attachment with the given id; returns the issue. */
	public Issue remove(String issueId, String attachmentId) {
		return apply(issueId, new Update().pull(FIELD, new Document("id", attachmentId)));
	}

	/**
	 * Atomically removes every attachment whose id is in [attachmentIds] in a
	 * single {@code $pull}, so a bulk delete can't race a parallel upload into
	 * removing more than the caller asked for. The ids are matched as plain
	 * values ({@code $in}), never as a user-supplied query fragment.
	 */
	public Issue removeAll(String issueId, Collection<String> attachmentIds) {
		return apply(issueId, new Update()
				.pull(FIELD, new Document("id", new Document("$in", List.copyOf(attachmentIds)))));
	}

	private Issue apply(String issueId, Update update) {
		Issue updated = mongo.findAndModify(
				new Query(Criteria.where("_id").is(issueId)),
				update.set(UPDATED_AT, Instant.now()),
				FindAndModifyOptions.options().returnNew(true),
				Issue.class);
		if (updated == null) {
			throw ApiException.notFound(ISSUE);
		}
		return updated;
	}
}
