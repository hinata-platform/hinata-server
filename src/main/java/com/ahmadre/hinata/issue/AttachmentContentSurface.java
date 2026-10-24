package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentMark;
import com.ahmadre.hinata.content.ContentOrigin;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.ContentSnapshot;
import com.ahmadre.hinata.content.ContentSurface;
import com.ahmadre.hinata.content.MediaRef;
import com.ahmadre.hinata.content.Placeholders;
import com.ahmadre.hinata.content.Quarantine;
import com.ahmadre.hinata.content.Removal;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.RestoreOutcome;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.storage.AttachmentEvents;
import com.ahmadre.hinata.storage.AttachmentStore;
import com.ahmadre.hinata.storage.ImagePreviewService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Attachments as moderation reaches them (HIN-169). The target names the attachment and, as its
 * parent, the issue it lives in.
 *
 * <p>Removing one moves the file into the quarantine, deletes its preview and leaves the entry in
 * place with no object behind it, named {@code Entfernte Datei}: every reader then answers 410
 * ({@link Issue.Attachment#isRemoved()}), and nothing builds a preview from the original again.
 */
@Component
@RequiredArgsConstructor
class AttachmentContentSurface implements ContentSurface {

	/** The key an attachment's mark sits under. */
	static final String FIELD = "file";

	private static final String ATTACHMENTS = "attachments";

	private final IssueRepository issues;
	private final IssueReach reach;
	private final Quarantine quarantine;
	private final StorageService storage;
	private final MongoTemplate mongo;
	private final AttachmentEvents events;

	@Override
	public ContentKind kind() {
		return ContentKind.ATTACHMENT;
	}

	@Override
	public Set<RemovalScope> scopes() {
		return Set.of(RemovalScope.WHOLE);
	}

	@Override
	public Optional<ContentSnapshot> snapshot(TargetRef target, User viewer) {
		return find(target).filter(found -> reach.canSee(found.issue(), viewer)).map(this::snapshotOf);
	}

	@Override
	public Optional<ContentSnapshot> forModeration(TargetRef target) {
		return find(target).map(this::snapshotOf);
	}

	/** An attachment with the issue it lives in. */
	private record Found(Issue.Attachment attachment, Issue issue) {
	}

	private Optional<Found> find(TargetRef target) {
		if (target == null || target.id() == null || target.parentId() == null) {
			return Optional.empty();
		}
		return issues.findById(target.parentId()).flatMap(issue -> attachmentOf(issue, target.id())
				.map(attachment -> new Found(attachment, issue)));
	}

	private static Optional<Issue.Attachment> attachmentOf(Issue issue, String attachmentId) {
		return issue.getAttachments() == null ? Optional.empty()
				: issue.getAttachments().stream().filter(a -> attachmentId.equals(a.getId())).findFirst();
	}

	private static ContentRef refOf(Issue.Attachment attachment, Issue issue) {
		return new ContentRef(ContentKind.ATTACHMENT, attachment.getId(), issue.getId(), issue.getProjectId());
	}

	private ContentSnapshot snapshotOf(Found found) {
		Issue.Attachment attachment = found.attachment();
		Issue issue = found.issue();
		// A file nobody uploaded came with the mail the issue was made of.
		boolean mailed = attachment.getUploaderId() == null && issue.getInboundMessageId() != null;
		return new ContentSnapshot(
				refOf(attachment, issue),
				mailed ? ContentOrigin.EMAIL : ContentOrigin.MEMBER,
				mailed ? issue.getReporterEmail() : null,
				attachment.getUploaderId() == null ? Set.of() : Set.of(attachment.getUploaderId()),
				attachment.getUploaderId(),
				attachment.getFileName(),
				"",
				ContentSnapshot.clip(issue.getTitle(), ContentSnapshot.CONTEXT_MAX),
				issue.getReadableId(),
				attachment.getObjectKey() == null ? List.of() : List.of(mediaOf(attachment)),
				attachment.getUploadedAt(),
				null,
				ContentSnapshot.hashOf(attachment.getObjectKey(), attachment.getFileName()));
	}

	private static MediaRef mediaOf(Issue.Attachment attachment) {
		return new MediaRef(attachment.getObjectKey(), attachment.getFileName(), attachment.getContentType(),
				attachment.getSize());
	}

	@Override
	public Removal remove(TargetRef target, RemovalScope scope, String caseId, ReportCategory category,
			Placeholders placeholders) {
		requireScope(scope);
		Found found = find(target).orElseThrow(() -> ApiException.notFound("attachment"));
		Issue.Attachment attachment = found.attachment();
		ContentRef ref = refOf(attachment, found.issue());
		if (attachment.isRemoved()) {
			return Removal.nothing(ref, scope);
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("fileName", attachment.getFileName());
		fields.put("objectKey", attachment.getObjectKey());
		putPresent(fields, "blurHash", attachment.getBlurHash());
		Quarantine.Held held = quarantine.hold(caseId, ref, scope, fields,
				attachment.getObjectKey() == null ? List.of() : List.of(mediaOf(attachment)), List.of());
		try {
			rewrite(found, caseId, placeholders);
		}
		catch (RuntimeException ex) {
			quarantine.discard(held);
			throw ex;
		}
		List<String> pending = quarantine.deleteOriginals(held);
		if (storage.isConfigured()) {
			storage.delete(ImagePreviewService.attachmentThumbnailKey(attachment.getId()));
		}
		announce(found.issue().getId(), attachment.getId());
		return new Removal(ref, scope, fields, held.media(), held.id(), pending, List.of(), null);
	}

	private void rewrite(Found found, String caseId, Placeholders placeholders) {
		Update update = new Update()
				.set(ATTACHMENTS + ".$[a].objectKey", null)
				.set(ATTACHMENTS + ".$[a].blurHash", null)
				.set(ATTACHMENTS + ".$[a].fileName", placeholders.removedFile())
				.set(ATTACHMENTS + ".$[a].moderation." + FIELD, ContentMark.removed(caseId));
		update.filterArray(AttachmentStore.byId(found.attachment().getId()));
		mongo.updateFirst(Query.query(Criteria.where("_id").is(found.issue().getId())), update, Issue.class);
	}

	/** The open issue swaps the tile: an app that knows only added and removed still shows it right. */
	private void announce(String issueId, String attachmentId) {
		issues.findById(issueId).flatMap(issue -> attachmentOf(issue, attachmentId)).ifPresent(changed -> {
			events.publishRemoved(issueId, attachmentId);
			events.publishAdded(issueId, changed);
		});
	}

	@Override
	public RestoreOutcome restore(Removal removal) {
		Issue issue = issues.findById(removal.ref().parentId()).orElse(null);
		Issue.Attachment attachment = issue == null ? null : attachmentOf(issue, removal.ref().id()).orElse(null);
		if (attachment == null) {
			return RestoreOutcome.GONE;
		}
		if (!attachment.isRemoved()) {
			return RestoreOutcome.REWRITTEN_SINCE;
		}
		if (removal.quarantineId() != null && !quarantine.restoreObjects(removal.quarantineId())) {
			throw new ApiException(HttpStatus.BAD_GATEWAY, "error.storage.unavailable");
		}
		Map<String, Object> fields = removal.fields();
		Update update = new Update()
				.set(ATTACHMENTS + ".$[a].objectKey", fields.get("objectKey"))
				.set(ATTACHMENTS + ".$[a].fileName", fields.get("fileName"))
				.set(ATTACHMENTS + ".$[a].blurHash", fields.get("blurHash"))
				.unset(ATTACHMENTS + ".$[a].moderation");
		update.filterArray(AttachmentStore.byId(attachment.getId()));
		mongo.updateFirst(Query.query(Criteria.where("_id").is(issue.getId())), update, Issue.class);
		announce(issue.getId(), attachment.getId());
		return RestoreOutcome.RESTORED;
	}

	private static void putPresent(Map<String, Object> fields, String name, Object value) {
		if (value != null) {
			fields.put(name, value);
		}
	}
}
