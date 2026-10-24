package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.content.ContentCopies;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.CopySource;
import com.ahmadre.hinata.content.Placeholders;
import com.ahmadre.hinata.content.ScrubReason;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The mail digests of an issue (HIN-169): an open bundle keeps every title and a window of every
 * description it will report, and a sent one keeps them for its seven days. A deleted issue takes
 * its bundles with it; a removed title or description is replaced in them.
 */
@Component
@RequiredArgsConstructor
class DigestCopies implements ContentCopies {

	private final MongoTemplate mongo;

	@Override
	public void scrub(List<CopySource> sources, ScrubReason reason, Placeholders placeholders) {
		for (CopySource source : sources) {
			if (source.ref().kind() != ContentKind.ISSUE) {
				continue;
			}
			Query digests = Query.query(Criteria.where("issueId").is(source.ref().id()));
			if (reason == ScrubReason.DELETED) {
				mongo.remove(digests, IssueMailDigest.class);
				continue;
			}
			for (IssueMailDigest digest : mongo.find(digests, IssueMailDigest.class)) {
				List<FieldChange> changes = digest.getChanges().stream()
						.map(change -> scrubbed(change, source, placeholders))
						.toList();
				if (!changes.equals(digest.getChanges())) {
					digest.setChanges(new java.util.ArrayList<>(changes));
					mongo.save(digest);
				}
			}
		}
	}

	private static FieldChange scrubbed(FieldChange change, CopySource source, Placeholders placeholders) {
		if (IssueChangeDiff.TITLE.equals(change.field()) && source.title() != null) {
			return new FieldChange(change.field(),
					source.title().equals(change.oldValue()) ? placeholders.removedTitle() : change.oldValue(),
					source.title().equals(change.newValue()) ? placeholders.removedTitle() : change.newValue());
		}
		if (IssueChangeDiff.DESCRIPTION.equals(change.field()) && source.textDoc() != null) {
			// The windows a description change keeps are pieces of the very text that went.
			return new FieldChange(change.field(), placeholders.removedText(), placeholders.removedText());
		}
		return change;
	}
}
