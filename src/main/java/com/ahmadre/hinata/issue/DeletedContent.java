package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.CopiesScrub;
import com.ahmadre.hinata.content.CopySource;
import com.ahmadre.hinata.content.Placeholders;
import com.ahmadre.hinata.content.ScrubReason;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.storage.StoredObjects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * What deleting a comment or an issue takes along (HIN-169): its stored objects, unless a case
 * still holds them, and the copies of its words in quotes, the bell and the mail digests. Both are
 * gathered for the whole request and settled once at its end, however much it deleted.
 */
@Component
@RequiredArgsConstructor
class DeletedContent {

	private final StoredObjects objects;
	private final CopiesScrub copies;
	private final MessageSource messages;
	private final SettingsService settings;

	/** What one request deletes, settled by {@link Request#finish()}. */
	Request begin() {
		return new Request(objects.batch());
	}

	/** The stored objects and the copies one request takes along. */
	final class Request {

		private final StoredObjects.Batch stored;
		private final List<CopySource> sources = new ArrayList<>();

		private Request(StoredObjects.Batch stored) {
			this.stored = stored;
		}

		void voiceOf(Issue issue, IssueComment comment) {
			stored.voiceOf(issue, comment);
		}

		void attachmentsOf(Issue issue) {
			stored.attachmentsOf(issue);
		}

		/** Where [source] was copied gets {@code Gelöscht} at the end. */
		void copiesOf(CopySource source) {
			sources.add(source);
		}

		/** Deletes the objects or moves what a case holds, then scrubs the copies. */
		void finish() {
			stored.release();
			if (!sources.isEmpty()) {
				copies.scrub(sources, ScrubReason.DELETED, Placeholders.in(messages, settings.instanceLocale()));
			}
		}
	}

	/** [comment] on [issue] as its copies are found. */
	static CopySource sourceOf(IssueComment comment, Issue issue) {
		String root = comment.getReplyToId() != null && !comment.getReplyToId().isBlank() ? comment.getReplyToId()
				: comment.getId();
		return new CopySource(new ContentRef(ContentKind.COMMENT, comment.getId(), comment.getIssueId(),
				issue.getProjectId()), comment.getAuthorId(), root, null, comment.getTextDoc(),
				IssueContentSurface.keysOf(issue));
	}

	/**
	 * [issue] as its copies are found. Without its words: every notice and digest about a deleted
	 * issue goes whole and its history with it, so nothing looks for them, and a hundred
	 * descriptions of up to a megabyte each would wait in memory for the end of the request.
	 */
	static CopySource sourceOf(Issue issue) {
		return new CopySource(new ContentRef(ContentKind.ISSUE, issue.getId(), null, issue.getProjectId()), null, null,
				null, null, IssueContentSurface.keysOf(issue));
	}
}
