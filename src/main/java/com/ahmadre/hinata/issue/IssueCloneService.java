package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * {@link #CARRIED} and {@link #RESET} — two lists that together must name every
 * field of {@link Issue}, so a field added later cannot silently start (or stop)
 * being copied.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueCloneService {

	/**
	 * The prefix a cloned title is offered with. Deliberately not localized: it
	 * is written into stored data that a whole organisation reads and searches,
	 * and a per-user prefix would mean the same action produces different titles
	 * depending on who ran it — so half the clones fall out of any search for
	 * them. The dialog prefills it and the user is free to delete it.
	 */
	public static final String TITLE_PREFIX = "CLONE - ";

	/** Mirrors {@code CreateIssueRequest.title}; the clone is an issue like any other. */
	public static final int MAX_TITLE_CHARS = 300;

	/**
	 * Fields the clone can end up carrying — either verbatim from the original
	 * (the description, its type and priority, the planning values) or as chosen
	 * in the clone dialog (the title, the assignees, and the sprint and
	 * dependencies behind their checkboxes).
	 */
	static final List<String> CARRIED = List.of(
			"projectId", "title", "description", "descriptionDoc", "type", "priority", "tags",
			"parentId", "estimateMinutes", "storyPoints", "startDate", "dueDate",
			"assigneeId", "assigneeIds", "sprintId", "dependsOnIds");

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
	 *   <li>{@code attachments}: the files stay with the issue they were uploaded
	 *       to (a copy would need a storage-side copy of every object, which is its
	 *       own ticket);
	 *   <li>{@code rank}: set fresh by {@code create} so the copy lands where a new
	 *       issue lands;
	 *   <li>{@code subtaskCount} / {@code subtaskDoneCount}: not stored at all,
	 *       computed per request.
	 * </ul>
	 *
	 * <p>Comments and the activity log are not fields and are equally not copied —
	 * they are other collections keyed by issue id, and nothing here reads them.
	 */
	static final List<String> RESET = List.of(
			"id", "numberInProject", "readableId", "formerReadableIds", "state", "reporterId",
			"reporterEmail", "inboundMessageId", "inboundSubject", "ingestConnectionId",
			"watcherIds", "spentMinutes", "attachments", "rank", "resolvedAt", "archived",
			"archivedAt", "dueReminderFor", "createdAt", "updatedAt",
			"subtaskCount", "subtaskDoneCount");

	private final IssueService issues;
	private final IssueLinkService links;
	private final AuditService audit;

	/** What the clone dialog lets a caller decide. */
	public record Options(String title, List<String> assigneeIds, boolean includeLinks,
			boolean includeSprint) {
	}

	/**
	 * Clones [idOrReadableId] into a new issue in the same project. The caller
	 * must be able to read the original and to create in the project — both are
	 * checked by the services this delegates to, not here.
	 */
	public Issue clone(String idOrReadableId, Options options, User user) {
		Issue original = issues.getForUser(idOrReadableId, user);
		Issue copy = Issue.builder()
				.projectId(original.getProjectId())
				.title(requireTitle(options.title()))
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
				.build();
		Issue saved = issues.create(copy, user, IssueService.Mentions.SUPPRESS);
		// Written the moment the copy exists, before any of the link work below.
		// From here on the issue is in the project whatever happens next, and an
		// audit log that only records the clones whose links happened to save is
		// an audit log that misses exactly the ones worth looking at. The two
		// flags describe what was asked for, which is what an auditor is reading
		// this to learn.
		audit.event(AuditAction.ISSUE_CLONED).actor(user)
				.meta("source", original.getReadableId())
				.meta("clone", saved.getReadableId())
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
		links.addLinks(copy.getId(), IssueLinkType.CLONES, true, List.of(original.getId()), user);
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
	 */
	private void copyLinks(Issue copy, Issue original, User user) {
		Map<Fan, List<String>> byFan = new LinkedHashMap<>();
		for (IssueLinkService.LinkView link : links.linksOf(original.getId(), user)) {
			if (link.type() == IssueLinkType.CLONES) {
				continue;
			}
			byFan.computeIfAbsent(new Fan(link.type(), link.outward()), key -> new ArrayList<>())
					.add(link.issue().getId());
		}
		for (Map.Entry<Fan, List<String>> fan : byFan.entrySet()) {
			try {
				links.addLinks(copy.getId(), fan.getKey().type(), fan.getKey().outward(),
						fan.getValue(), user);
			}
			catch (RuntimeException failed) {
				// The clone exists. A link that will not copy is worth a line in the
				// log and nothing more — rolling the issue back over it would throw
				// away the thing the user actually asked for.
				log.warn("Copying {} links from {} to clone {} failed: {}",
						fan.getKey().type(), original.getReadableId(), copy.getReadableId(),
						failed.toString());
			}
		}
	}

	/**
	 * One batch of links to add: a type and an orientation, which is everything
	 * {@link IssueLinkService#addLinks} needs to be told once for a whole list of
	 * targets.
	 *
	 * <p>The grouping is not tidiness. {@code addLinks} answers with the issue's
	 * <em>entire</em> link list — re-reading every link and loading the issue
	 * behind each one — so calling it once per link makes copying n links cost on
	 * the order of n² reads, every one of them thrown away here. An issue with a
	 * couple of hundred links is an ordinary hub ticket that any member can clone
	 * for the price of one small request, and that is not an amount of work any
	 * request should be able to buy. There are seven link types and two
	 * orientations, so this bounds the wasted re-reads to at most fourteen
	 * regardless of how many links the original carries.
	 *
	 * <p>The cost is granularity when something goes wrong: a target that becomes
	 * unreachable between the read above and the write below now aborts the rest
	 * of its batch rather than only itself. {@code addLinks} saves as it goes, so
	 * what it managed before the failure stays — and the failure needs a
	 * membership change or a database fault landing inside a window microseconds
	 * wide, against a copy that survives either way.
	 */
	private record Fan(IssueLinkType type, boolean outward) {
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
