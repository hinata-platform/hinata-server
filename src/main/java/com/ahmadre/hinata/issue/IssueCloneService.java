package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.ImagePreviewService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cloning an issue: a new ticket that starts where an existing one did, for a
 * recurring task, a template, or a piece split off something larger.
 *
 * <p>The copy is created through {@link IssueService#create} like any other
 * issue rather than written here. That is deliberate and it is the whole design:
 * the project-scoped number and its collision retry, the workflow state, the
 * hierarchy check, the rank, the label merge and the CREATED activity all live
 * in that one method, and a second persistence path is exactly how a numbering
 * scheme and an activity history drift apart.
 *
 * <p>What the copy carries and what it deliberately does not is spelled out in
 * {@link #CARRIED} and {@link #LEFT_BEHIND} — two lists that together must name
 * every field of {@link Issue}, so a field added later cannot silently start (or
 * stop) being copied.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueCloneService {

	/** Mirrors {@code CreateIssueRequest.title}; the clone is an issue like any other. */
	private static final int MAX_TITLE_CHARS = 300;

	/**
	 * Ceilings on what one clone will duplicate — how many files, and how many
	 * bytes across them.
	 *
	 * <p>They are not about disk. Uploading costs the caller the file: every byte
	 * that reaches the bucket had to be sent, so the store can only grow as fast
	 * as somebody can upload into it. Copying breaks that coupling. A clone
	 * request is a couple of hundred bytes of JSON that tells the store to write
	 * however much hangs off the original — and an issue may carry as many
	 * attachments as anyone cares to upload, while the same request repeats up to
	 * {@code hinata.rate-limit.api-per-minute} times a minute from one address.
	 * Without a ceiling here the bucket a member can grow per request is bounded
	 * by nothing at all, which is the one thing the upload path never allowed.
	 *
	 * <p>Both are needed. The byte budget alone still lets ten thousand one-byte
	 * files cost twenty thousand round trips to the store on a servlet thread, and
	 * the file budget alone still lets fifty files of a gigabyte through.
	 */
	static final int MAX_COPIED_FILES = 50;

	/** The byte half of the same budget — see {@link #MAX_COPIED_FILES}. */
	static final long MAX_COPIED_BYTES = 100L * 1024 * 1024;

	/**
	 * Fields the clone can end up carrying — either verbatim from the original
	 * (the description, its type and priority, the planning values) or as chosen
	 * in the clone dialog (the title, the assignees, and the attachments, sprint
	 * and dependencies behind their switches).
	 */
	static final List<String> CARRIED = List.of(
			"projectId", "title", "description", "descriptionDoc", "type", "priority", "tags",
			"parentId", "estimateMinutes", "storyPoints", "startDate", "dueDate",
			"assigneeId", "assigneeIds", "attachments", "sprintId", "dependsOnIds");

	/**
	 * Fields the clone never inherits, and why they are each their own decision:
	 *
	 * <ul>
	 *   <li>identity — {@code id}, {@code numberInProject}, {@code readableId},
	 *       {@code formerReadableIds}: the copy is a new ticket, not the same one;
	 *   <li>{@code state}: cloning a finished issue must produce work to do, not a
	 *       second finished issue — {@code create} resets it to the first state;
	 *   <li>{@code reporterId}: the person cloning is the author of the copy;
	 *   <li>the e-mail-ingest trio ({@code reporterEmail}, {@code inboundMessageId},
	 *       {@code inboundSubject}, {@code ingestConnectionId}): the copy did not
	 *       arrive by mail, and duplicating the message id would break the dedupe
	 *       that stops a re-polled mail becoming a second ticket;
	 *   <li>{@code watcherIds}: a subscription is a person's own decision about one
	 *       ticket, never something a copy inherits on their behalf;
	 *   <li>history and progress — {@code spentMinutes}, {@code resolvedAt},
	 *       {@code archived}, {@code archivedAt}, {@code dueReminderFor},
	 *       {@code createdAt}, {@code updatedAt}: none of it happened to the copy;
	 *   <li>{@code rank}: set fresh by {@code create} so the copy lands where a new
	 *       issue lands;
	 *   <li>{@code subtaskCount} / {@code subtaskDoneCount}: not stored at all,
	 *       computed per request.
	 * </ul>
	 *
	 * <p>Comments and the activity log are not fields and are equally not copied —
	 * they are other collections keyed by issue id, and nothing here reads them.
	 */
	static final List<String> LEFT_BEHIND = List.of(
			"id", "numberInProject", "readableId", "formerReadableIds", "state", "reporterId",
			"reporterEmail", "inboundMessageId", "inboundSubject", "ingestConnectionId",
			"watcherIds", "spentMinutes", "rank", "resolvedAt", "archived",
			"archivedAt", "dueReminderFor", "createdAt", "updatedAt",
			"subtaskCount", "subtaskDoneCount");

	private final IssueService issues;
	private final StorageService storage;
	private final IssueLinkService links;
	private final AuditService audit;

	/** What the clone dialog lets a caller decide. */
	public record Options(String title, List<String> assigneeIds, boolean includeAttachments,
			boolean includeLinks, boolean includeSprint) {
	}

	/**
	 * Clones [idOrReadableId] into a new issue in the same project. The caller
	 * must be able to read the original and to create in the project — both are
	 * checked by the services this delegates to, not here.
	 */
	public Issue clone(String idOrReadableId, Options options, User user) {
		Issue original = issues.getForUser(idOrReadableId, user);
		String title = requireTitle(options.title());
		// Both hoisted out of the builder below, and in this order. The files are
		// duplicated before the issue is saved so the copy is never briefly visible
		// with an empty attachment grid that then fills in — which means everything
		// able to refuse this clone has to be asked before a single byte is copied,
		// and it means the copies have a name here in case the save refuses anyway.
		List<Issue.Attachment> attachments = options.includeAttachments()
				? copyAttachments(original, user)
				: new ArrayList<>();
		Issue copy = Issue.builder()
				.projectId(original.getProjectId())
				.title(title)
				.description(original.getDescription())
				.descriptionDoc(original.getDescriptionDoc())
				.type(original.getType())
				.priority(original.getPriority())
				.tags(copyOf(original.getTags()))
				.parentId(original.getParentId())
				.estimateMinutes(original.getEstimateMinutes())
				.storyPoints(original.getStoryPoints())
				.startDate(original.getStartDate())
				.dueDate(original.getDueDate())
				.assigneeIds(copyOf(options.assigneeIds()))
				// Without the sprint the copy starts in the backlog; with it,
				// create() promotes it onto the sprint board like any new issue
				// added straight to a sprint.
				.sprintId(options.includeSprint() ? original.getSprintId() : null)
				.dependsOnIds(options.includeLinks() ? copyOf(original.getDependsOnIds())
						: new ArrayList<>())
				.attachments(attachments)
				.build();
		Issue saved;
		try {
			saved = issues.create(copy, user, IssueService.Mentions.SUPPRESS);
		}
		catch (RuntimeException failed) {
			// The bytes exist before the row that points at them, so a create that
			// refuses — a parent that no longer resolves, a project that went away,
			// a number that could not be reserved — leaves objects in the bucket
			// that nothing will ever name again. Nothing reaps them either: the
			// orphan sweep only knows the media/ prefix, and an attachment's key is
			// a bare UUID outside it. A clone the create path rejects is a request
			// anybody can repeat as fast as the rate limiter allows, so the objects
			// go back with the failure rather than accumulating behind it.
			discardCopiedObjects(attachments);
			throw failed;
		}
		// Written the moment the copy exists, before any of the link work below.
		// From here on the issue is in the project whatever happens next, and an
		// audit log that only records the clones whose links happened to save is
		// an audit log that misses exactly the ones worth looking at. The three
		// flags describe what was asked for, which is what an auditor is reading
		// this to learn. The file count is the one thing a flag cannot say: the
		// difference between somebody cloning a ticket and somebody using the clone
		// endpoint to write fifty files into the bucket per request is a number, and
		// it is bounded by MAX_COPIED_FILES rather than by anything a caller sends.
		audit.event(AuditAction.ISSUE_CLONED).actor(user)
				.meta("source", original.getReadableId())
				.meta("clone", saved.getReadableId())
				.meta("attachments", String.valueOf(options.includeAttachments()))
				.meta("attachmentsCopied", String.valueOf(attachments.size()))
				.meta("links", String.valueOf(options.includeLinks()))
				.meta("sprint", String.valueOf(options.includeSprint()))
				.log();
		recordOrigin(saved, original, user);
		if (options.includeLinks()) {
			copyLinks(saved, original, user);
		}
		return saved;
	}

	/**
	 * Links the copy to what it was copied from, whatever the caller ticked. Two
	 * weeks on, a ticket that reads like another one with no relationship
	 * recorded is a question nobody can answer — and the link type already
	 * existed, so this costs a row, not a model.
	 */
	private void recordOrigin(Issue copy, Issue original, User user) {
		// Not addLinks: the difference is only that this one does not re-read and
		// re-render the copy's whole link list on the way out, which is a view
		// nothing here looks at. See IssueLinkService#addLinksWithoutView.
		links.addLinksWithoutView(copy.getId(), IssueLinkType.CLONES, true,
				List.of(original.getId()), user);
	}

	/**
	 * Gives the copy the original's links. Read through
	 * {@link IssueLinkService#linksOf} first, which already drops dangling links
	 * and links to issues this caller cannot see — so a link the caller could not
	 * have created by hand is not created for them here either, and one bad
	 * target cannot abort a clone that is already saved.
	 *
	 * <p>Each link is re-added from the copy's side with the orientation it had
	 * from the original's, since the copy stands where the original stood.
	 *
	 * <p>Except the clone links, which are the one kind that does not travel.
	 * Every other type states something about the work — this blocks that, this
	 * duplicates that — and a copy of the work inherits the statement. A clone
	 * link states where a ticket <em>came from</em>, which is history, not a
	 * property. Copied along, an issue that had been cloned once would hand its
	 * next copy "is cloned by HIN-43" — a claim that HIN-43 is a copy of an issue
	 * that did not exist when HIN-43 was made. The copy's own origin is recorded
	 * separately by {@link #recordOrigin}, and that is the only lineage it has.
	 *
	 * <p>{@code linksOf} is more than this needs — all that survives the loop below
	 * is a type, an orientation and an id, while it loads every linked issue whole,
	 * description and all. Reading them anyway is the deliberate half of the trade:
	 * it is the one place that knows which way a link points from a given side and
	 * whether this caller may see the far end, and a leaner projection here would be
	 * a second copy of that rule sitting in the path that decides what a clone is
	 * allowed to link to. One read of it per clone is a price worth paying; the
	 * repeats are not, which is why the writes below use
	 * {@code addLinksWithoutView}.
	 */
	private void copyLinks(Issue copy, Issue original, User user) {
		Map<LinkKind, List<String>> byKind = new LinkedHashMap<>();
		for (IssueLinkService.LinkView link : links.linksOf(original.getId(), user)) {
			if (link.type() == IssueLinkType.CLONES) {
				continue;
			}
			LinkKind kind = new LinkKind(link.type(), link.outward());
			byKind.computeIfAbsent(kind, key -> new ArrayList<>()).add(link.issue().getId());
		}
		for (Map.Entry<LinkKind, List<String>> batch : byKind.entrySet()) {
			try {
				links.addLinksWithoutView(copy.getId(), batch.getKey().type(),
						batch.getKey().outward(), batch.getValue(), user);
			}
			catch (RuntimeException failed) {
				// The clone exists. A link that will not copy is worth a line in the
				// log and nothing more — rolling the issue back over it would throw
				// away the thing the user actually asked for.
				log.warn("Copying {} links from {} to clone {} failed: {}",
						batch.getKey().type(), original.getReadableId(), copy.getReadableId(),
						failed.toString());
			}
		}
	}

	/**
	 * The kind of link one batched call writes: a type and an orientation, which
	 * is everything {@link IssueLinkService#addLinksWithoutView} needs to be told
	 * once for a whole list of targets.
	 *
	 * <p>The grouping is not tidiness. Every call re-resolves the copy and its
	 * project to authorize the caller against them, and pings the copy's live
	 * viewers once it is done — fixed costs that a call per link would multiply by
	 * the number of links. An issue with a couple of hundred links is an ordinary
	 * hub ticket that any member can clone for the price of one small request, so
	 * what that request buys has to stay proportional to something other than the
	 * links. There are seven link types and two orientations, which bounds the
	 * repeats to fourteen however many links the original carries.
	 *
	 * <p>The cost is granularity when something goes wrong: a target that becomes
	 * unreachable between the read above and the write below now aborts the rest
	 * of its batch rather than only itself. The write saves as it goes, so what it
	 * managed before the failure stays — and the failure needs a membership change
	 * or a database fault landing inside a window microseconds wide, against a
	 * copy that survives either way.
	 */
	private record LinkKind(IssueLinkType type, boolean outward) {
	}

	/**
	 * Duplicates the original's files onto the copy: a new attachment id, a new
	 * object key, and a store-side copy of the bytes into it.
	 *
	 * <p>Store-side is the point. Reading each file into this application and
	 * writing it back would drag every megabyte through the heap and across the
	 * network twice, for a copy the object store will make on its own.
	 *
	 * <p>How much this is allowed to duplicate is checked first and refuses the
	 * whole clone — see {@link #assertWithinCopyBudget}.
	 *
	 * <p>A file that will not copy is dropped from the list and logged, never
	 * raised: the clone is what the user asked for, and an issue that fails to
	 * exist because one of its pictures could not be duplicated is a worse answer
	 * than an issue with one picture missing. The derived thumbnail is copied on
	 * the same terms, and only where one can exist at all — losing it costs a
	 * preview, which the viewer regenerates on demand anyway.
	 *
	 * <p>The uploader recorded on the copy is whoever cloned, for the same reason
	 * the copy's reporter is: they are the person who put this file on this issue.
	 */
	private List<Issue.Attachment> copyAttachments(Issue original, User user) {
		List<Issue.Attachment> copies = new ArrayList<>();
		if (original.getAttachments() == null) {
			return copies;
		}
		assertWithinCopyBudget(original.getAttachments());
		Instant now = Instant.now();
		for (Issue.Attachment source : original.getAttachments()) {
			String id = UUID.randomUUID().toString();
			// Minted by the store, in the namespace an upload lands in — the copy is
			// an attachment like any other, and a key of its own is what keeps
			// deleting one issue from emptying the other.
			String objectKey = StorageService.newObjectKey();
			if (!storage.copyObject(source.getObjectKey(), objectKey)) {
				log.warn("Cloning {}: attachment {} could not be copied, leaving it out",
						original.getReadableId(), source.getId());
				continue;
			}
			// Only for the types that can have one. A thumbnail is written for
			// pictures and PDFs and for nothing else, so asking the store to copy
			// one for a ZIP or a Word file buys a round trip and a warning line per
			// file to be told what the content type already said — half the store
			// traffic of a clone whose attachments are documents.
			if (ImagePreviewService.isPreviewable(source.getContentType())) {
				storage.copyObject(ImagePreviewService.attachmentThumbnailKey(source.getId()),
						ImagePreviewService.attachmentThumbnailKey(id));
			}
			copies.add(Issue.Attachment.builder()
					.id(id)
					.fileName(source.getFileName())
					.contentType(source.getContentType())
					.size(source.getSize())
					.objectKey(objectKey)
					.uploaderId(user.getId())
					.uploadedAt(now)
					.blurHash(source.getBlurHash())
					.build());
		}
		return copies;
	}

	/**
	 * Refuses a clone that would duplicate more than {@link #MAX_COPIED_FILES}
	 * files or {@link #MAX_COPIED_BYTES} bytes, before anything is copied.
	 *
	 * <p>Loud rather than quietly truncating, unlike the per-file store failure in
	 * {@link #copyAttachments}. That one is the store's answer about a single file
	 * and there is nothing the caller can do with it; this is a rule of the product,
	 * and the caller has an obvious way around it — clone the issue without its
	 * attachments, which is what the switch is off for by default. A clone that
	 * silently arrived carrying the first fifty of two hundred files would be a
	 * half-answer nobody notices until the missing file is the one that was needed.
	 *
	 * <p>The sizes are the store's own, recorded when each file was written, never
	 * a number a client sent.
	 */
	private static void assertWithinCopyBudget(List<Issue.Attachment> sources) {
		if (sources.size() > MAX_COPIED_FILES) {
			throw ApiException.badRequest("error.issue.cloneTooManyAttachments", MAX_COPIED_FILES);
		}
		long bytes = 0;
		for (Issue.Attachment source : sources) {
			bytes += source.getSize();
		}
		if (bytes > MAX_COPIED_BYTES) {
			throw ApiException.badRequest("error.issue.cloneAttachmentsTooLarge",
					MAX_COPIED_BYTES / (1024 * 1024));
		}
	}

	/**
	 * Removes the objects copied for a clone that then failed to be written, so a
	 * refused request leaves the bucket as it found it.
	 *
	 * <p>Best-effort by construction — {@link StorageService#delete} logs and
	 * swallows — because the caller is already on the way out with the original
	 * failure, and replacing it with a storage error would report the wrong thing.
	 */
	private void discardCopiedObjects(List<Issue.Attachment> attachments) {
		for (Issue.Attachment attachment : attachments) {
			storage.delete(attachment.getObjectKey());
			storage.delete(ImagePreviewService.attachmentThumbnailKey(attachment.getId()));
		}
	}

	private static String requireTitle(String title) {
		String trimmed = title == null ? "" : title.trim();
		if (trimmed.isEmpty()) {
			throw ApiException.badRequest("error.issue.cloneTitleRequired");
		}
		if (trimmed.length() > MAX_TITLE_CHARS) {
			throw ApiException.badRequest("error.issue.cloneTitleTooLong", MAX_TITLE_CHARS);
		}
		return trimmed;
	}

	/** A defensive copy that also turns a legacy document's null list into an empty one. */
	private static List<String> copyOf(List<String> values) {
		return values == null ? new ArrayList<>() : new ArrayList<>(values);
	}
}
