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

import java.util.List;

/**
 * What deleting a comment or an issue takes along (HIN-169): its stored objects, unless a case
 * still holds them, and the copies of its words in quotes, the bell and the mail digests, scrubbed
 * once per request with everything the request deleted.
 */
@Component
@RequiredArgsConstructor
class DeletedContent {

	private final StoredObjects objects;
	private final CopiesScrub copies;
	private final MessageSource messages;
	private final SettingsService settings;

	void voiceOf(Issue issue, IssueComment comment) {
		objects.deleteVoice(issue, comment);
	}

	void attachmentsOf(Issue issue) {
		objects.deleteAttachmentObjects(issue);
	}

	/** Puts {@code Gelöscht} where [sources] were copied. */
	void scrubCopies(List<CopySource> sources) {
		if (sources.isEmpty()) {
			return;
		}
		copies.scrub(sources, ScrubReason.DELETED, Placeholders.in(messages, settings.instanceLocale()));
	}

	/** [comment] on [issue] as its copies are found. */
	static CopySource sourceOf(IssueComment comment, Issue issue) {
		String root = comment.getReplyToId() != null && !comment.getReplyToId().isBlank() ? comment.getReplyToId()
				: comment.getId();
		return new CopySource(new ContentRef(ContentKind.COMMENT, comment.getId(), comment.getIssueId(),
				issue.getProjectId()), comment.getAuthorId(), root, null, comment.getTextDoc(),
				IssueContentSurface.keysOf(issue));
	}

	/** [issue] as its copies are found. */
	static CopySource sourceOf(Issue issue) {
		return new CopySource(new ContentRef(ContentKind.ISSUE, issue.getId(), null, issue.getProjectId()), null, null,
				issue.getTitle(), issue.getDescriptionDoc(), IssueContentSurface.keysOf(issue));
	}
}
