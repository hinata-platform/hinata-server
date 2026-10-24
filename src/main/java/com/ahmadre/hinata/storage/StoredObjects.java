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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

	/** Objects held this many at a time ask for their holds in one go. */
	static final int HOLDS_PER_READ = 500;

	/** The files and previews of all of [issue]'s attachments. */
	public void deleteAttachmentObjects(Issue issue) {
		Batch batch = batch();
		batch.attachmentsOf(issue);
		batch.release();
	}

	/** The file and preview of one of [issue]'s attachments. */
	public void deleteAttachmentObjects(Issue issue, Issue.Attachment attachment) {
		Batch batch = batch();
		batch.attachment(issue, attachment);
		batch.release();
	}

	/** The audio of [comment], a voice message on [issue] (null when the issue is gone already). */
	public void deleteVoice(Issue issue, IssueComment comment) {
		Batch batch = batch();
		batch.voiceOf(issue, comment);
		batch.release();
	}

	/** Objects that go together, such as everything one request deletes; see {@link Batch#release()}. */
	public Batch batch() {
		return new Batch();
	}

	/**
	 * Stored objects gathered to go together. Nothing is deleted until {@link #release()}, which
	 * reads the holds of all of them at once instead of four reads per object.
	 */
	public final class Batch {

		/** One object, the content it belongs to and what holds either. */
		private record Pending(ContentRef ref, ContentRef owner, MediaRef object, Map<String, Object> fields) {
		}

		private final List<Pending> pending = new ArrayList<>();
		private final List<String> previews = new ArrayList<>();

		private Batch() {
		}

		/** The files and previews of all of [issue]'s attachments. */
		public void attachmentsOf(Issue issue) {
			if (issue.getAttachments() != null) {
				issue.getAttachments().forEach(attachment -> attachment(issue, attachment));
			}
		}

		/** The file and preview of one of [issue]'s attachments. */
		public void attachment(Issue issue, Issue.Attachment attachment) {
			if (!storage.isConfigured()) {
				return;
			}
			if (attachment.getObjectKey() != null) {
				Map<String, Object> fields = new LinkedHashMap<>();
				if (attachment.getFileName() != null) {
					fields.put("fileName", attachment.getFileName());
				}
				pending.add(new Pending(
						new ContentRef(ContentKind.ATTACHMENT, attachment.getId(), issue.getId(), issue.getProjectId()),
						issueRef(issue), new MediaRef(attachment.getObjectKey(), attachment.getFileName(),
								attachment.getContentType(), attachment.getSize()), fields));
			}
			// Unconditional: a preview that was never made is a no-op delete, and one that was is
			// derived from the original, nothing to keep.
			if (attachment.getId() != null) {
				previews.add(ImagePreviewService.attachmentThumbnailKey(attachment.getId()));
			}
		}

		/** The audio of [comment], a voice message on [issue] (null when the issue is gone already). */
		public void voiceOf(Issue issue, IssueComment comment) {
			if (!storage.isConfigured() || comment.getVoice() == null || comment.getVoice().getObjectKey() == null) {
				return;
			}
			IssueComment.Voice voice = comment.getVoice();
			String projectId = issue == null ? null : issue.getProjectId();
			pending.add(new Pending(new ContentRef(ContentKind.COMMENT, comment.getId(), comment.getIssueId(), projectId),
					issue == null ? null : issueRef(issue),
					new MediaRef(voice.getObjectKey(), null, voice.getContentType(), voice.getSize()),
					Map.of("type", IssueComment.Type.VOICE.name())));
		}

		/**
		 * Deletes every gathered object, or moves it into the quarantine of the case that holds it
		 * or the content it belongs to. The holds are read {@value StoredObjects#HOLDS_PER_READ} objects at
		 * a time.
		 */
		public void release() {
			for (int from = 0; from < pending.size(); from += HOLDS_PER_READ) {
				List<Pending> chunk = pending.subList(from, Math.min(pending.size(), from + HOLDS_PER_READ));
				Map<ContentRef, String> holds = holdsOf(chunk);
				for (Pending object : chunk) {
					String caseId = holds.get(object.ref());
					StoredObjects.this.release(object, caseId != null || object.owner() == null ? caseId
							: holds.get(object.owner()));
				}
			}
			previews.forEach(storage::delete);
			pending.clear();
			previews.clear();
		}

		private Map<ContentRef, String> holdsOf(List<Pending> chunk) {
			Set<ContentRef> asked = new LinkedHashSet<>();
			for (Pending object : chunk) {
				asked.add(object.ref());
				if (object.owner() != null) {
					asked.add(object.owner());
				}
			}
			Map<ContentRef, String> holds = new HashMap<>();
			for (EvidenceHold hold : evidence.orderedStream().toList()) {
				List<ContentRef> open = asked.stream().filter(ref -> !holds.containsKey(ref)).toList();
				if (open.isEmpty()) {
					break;
				}
				hold.holdingCaseIds(open).forEach(holds::putIfAbsent);
			}
			return holds;
		}
	}

	private static ContentRef issueRef(Issue issue) {
		return new ContentRef(ContentKind.ISSUE, issue.getId(), null, issue.getProjectId());
	}

	/** Deletes [object], or keeps it for [caseId] when a case holds it. */
	private void release(Batch.Pending object, String caseId) {
		if (caseId == null) {
			storage.delete(object.object().objectKey());
			return;
		}
		try {
			Quarantine.Held held = quarantine.hold(caseId, object.ref(), RemovalScope.WHOLE, object.fields(),
					List.of(object.object()), List.of());
			quarantine.deleteOriginals(held);
		}
		catch (RuntimeException ex) {
			// Evidence that could not be copied stays where it is rather than going for good.
			log.warn("Keeping {} for case {} failed; the object stays in place", object.object().objectKey(), caseId,
					ex);
		}
	}
}
