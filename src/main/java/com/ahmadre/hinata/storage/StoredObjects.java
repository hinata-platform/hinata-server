package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.EvidenceHold;
import com.ahmadre.hinata.content.MediaRef;
import com.ahmadre.hinata.content.Quarantine;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The stored objects that go when content is deleted for good (HIN-169): an attachment's file and
 * preview, a voice message's audio. One way to delete them, for deleting one attachment, one
 * comment, an issue or a whole project.
 *
 * <p>What an open case or a legal hold still needs is not deleted but moved into that case's
 * quarantine ({@link EvidenceHold}); DSA Art. 18 asks for all relevant information. An object that
 * cannot be copied there is left in place rather than lost.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoredObjects {

	private final StorageService storage;
	private final Quarantine quarantine;
	private final ObjectProvider<EvidenceHold> evidence;

	/** The files and previews of all of [issue]'s attachments. */
	public void deleteAttachmentObjects(Issue issue) {
		if (issue.getAttachments() == null) {
			return;
		}
		issue.getAttachments().forEach(attachment -> deleteAttachmentObjects(issue, attachment));
	}

	/** The file and preview of one of [issue]'s attachments. */
	public void deleteAttachmentObjects(Issue issue, Issue.Attachment attachment) {
		if (!storage.isConfigured()) {
			return;
		}
		if (attachment.getObjectKey() != null) {
			Map<String, Object> fields = new LinkedHashMap<>();
			if (attachment.getFileName() != null) {
				fields.put("fileName", attachment.getFileName());
			}
			release(new ContentRef(ContentKind.ATTACHMENT, attachment.getId(), issue.getId(), issue.getProjectId()),
					issueRef(issue),
					new MediaRef(attachment.getObjectKey(), attachment.getFileName(), attachment.getContentType(),
							attachment.getSize()),
					fields);
		}
		// Unconditional: a preview that was never made is a no-op delete, and one that was is
		// derived from the original, nothing to keep.
		if (attachment.getId() != null) {
			storage.delete(ImagePreviewService.attachmentThumbnailKey(attachment.getId()));
		}
	}

	/** The audio of [comment], a voice message on [issue] (null when the issue is gone already). */
	public void deleteVoice(Issue issue, IssueComment comment) {
		if (!storage.isConfigured() || comment.getVoice() == null || comment.getVoice().getObjectKey() == null) {
			return;
		}
		IssueComment.Voice voice = comment.getVoice();
		String projectId = issue == null ? null : issue.getProjectId();
		release(new ContentRef(ContentKind.COMMENT, comment.getId(), comment.getIssueId(), projectId),
				issue == null ? null : issueRef(issue),
				new MediaRef(voice.getObjectKey(), null, voice.getContentType(), voice.getSize()),
				Map.of("type", IssueComment.Type.VOICE.name()));
	}

	private static ContentRef issueRef(Issue issue) {
		return new ContentRef(ContentKind.ISSUE, issue.getId(), null, issue.getProjectId());
	}

	/** Deletes [object], or keeps it for the case that holds [ref] or the content [owner] it belongs to. */
	private void release(ContentRef ref, ContentRef owner, MediaRef object, Map<String, Object> fields) {
		Optional<String> caseId = holdOf(ref).or(() -> owner == null ? Optional.empty() : holdOf(owner));
		if (caseId.isEmpty()) {
			storage.delete(object.objectKey());
			return;
		}
		try {
			Quarantine.Held held = quarantine.hold(caseId.get(), ref, RemovalScope.WHOLE, fields, List.of(object),
					List.of());
			quarantine.deleteOriginals(held);
		}
		catch (RuntimeException ex) {
			// Evidence that could not be copied stays where it is rather than going for good.
			log.warn("Keeping {} for case {} failed; the object stays in place", object.objectKey(), caseId.get(), ex);
		}
	}

	private Optional<String> holdOf(ContentRef ref) {
		return evidence.orderedStream()
				.map(hold -> hold.holdingCaseId(ref))
				.flatMap(Optional::stream)
				.findFirst();
	}
}
