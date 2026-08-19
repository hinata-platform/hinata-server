package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.board.AgileBoardRepository;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.common.ByteSize;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueActivity;
import com.ahmadre.hinata.issue.IssueActivityRepository;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueLinkService;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.richtext.LexicalToMarkdown;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gathers everything an export shows, once, in the shape {@link IssueExport}
 * describes — so the four renderers never touch a repository and never have to
 * agree on anything.
 *
 * <p>Authorization is one line and it is the first one: {@link
 * IssueService#getForUser} runs the same project ACL the detail view runs. An
 * export must never show more than the screen it was started from, and the
 * cheapest way to guarantee that is to enter through the same door.
 */
@Service
@RequiredArgsConstructor
public class IssueExportService {

	/**
	 * Comments and activity entries a single export will carry.
	 *
	 * <p>A cap rather than a page: an export is a document somebody files, not a
	 * screen they scroll. Neither collection is bounded in the database, and a
	 * long-running ticket legitimately accumulates hundreds of both — the oldest
	 * comments and the newest history are what a reader of the ticket would
	 * expect on paper, so that is the end each cap keeps.
	 *
	 * <p>The cap is spent in the query and not on the result. An issue's activity
	 * grows by a document per changed field per edit, so it is the one collection
	 * here anybody can inflate cheaply and on purpose; reading all of it in order
	 * to keep the newest five hundred would let a few thousand PATCHes decide how
	 * much memory each later export of that issue costs.
	 */
	private static final int MAX_COMMENTS = 500;
	private static final int MAX_ACTIVITY = 500;

	/**
	 * Issues the "Depends on" field will name.
	 *
	 * <p>{@code dependsOnIds} is the one list on an issue that the update
	 * endpoint stores exactly as it was sent — no length, no membership, no check
	 * that any of the ids is an issue at all. Resolving it costs a read per entry,
	 * so without a ceiling one PATCH decides how many reads every later export of
	 * that issue performs.
	 */
	private static final int MAX_DEPENDS_ON = 50;

	private final IssueService issues;
	private final IssueLinkService links;
	private final IssueCommentRepository comments;
	private final IssueActivityRepository activities;
	private final ProjectRepository projects;
	private final SprintRepository sprints;
	private final AgileBoardRepository boards;
	private final UserRepository users;
	private final SettingsService settings;

	/**
	 * Everything [idOrReadableId] contributes to an export, for a caller who is
	 * allowed to see it.
	 *
	 * @throws com.ahmadre.hinata.common.ApiException 403/404 exactly as the detail
	 *         view would
	 */
	public IssueExport gather(String idOrReadableId, IssueExport.Options options, User user) {
		Issue issue = issues.getForUser(idOrReadableId, user);
		Project project = projects.findById(issue.getProjectId()).orElse(null);
		// The rows first, the names they refer to second: who this document has to
		// name is not knowable until the comments and the history have been read,
		// and reading them first is what lets every id in all of them be resolved
		// in one query rather than one at a time.
		List<IssueComment> thread = options.comments() ? readComments(issue) : List.of();
		List<IssueActivity> history = options.activity() ? readActivity(issue) : List.of();
		List<Issue.Attachment> files = options.attachments() && issue.getAttachments() != null
				? issue.getAttachments() : List.of();
		Map<String, String> names = names(issue, thread, files, history);
		return new IssueExport(
				nz(issue.getReadableId()),
				nz(issue.getTitle()),
				project == null ? "" : nz(project.getName()),
				fields(issue, project, names, user),
				MarkdownBlocks.of(LexicalToMarkdown.fromStored(
						issue.getDescriptionDoc(), issue.getDescription())),
				comments(thread, names),
				options.links() ? links(idOrReadableId, user) : List.of(),
				attachments(files, names),
				activity(history, names),
				nz(settings.get().getOrganizationName()),
				Instant.now());
	}

	// --- sections ------------------------------------------------------------

	private List<IssueExport.Field> fields(Issue issue, Project project, Map<String, String> names,
			User user) {
		List<IssueExport.Field> fields = new ArrayList<>();
		fields.add(new IssueExport.Field("Type", name(issue.getType())));
		fields.add(new IssueExport.Field("Status", nz(issue.getState())));
		fields.add(new IssueExport.Field("Priority", name(issue.getPriority())));
		fields.add(new IssueExport.Field("Assignees", people(issue.getAssigneeIds(), names)));
		fields.add(new IssueExport.Field("Reporter", person(issue.getReporterId(), names)));
		fields.add(new IssueExport.Field("Sprint", sprintName(issue)));
		fields.add(new IssueExport.Field("Start date", date(issue.getStartDate())));
		fields.add(new IssueExport.Field("Due date", date(issue.getDueDate())));
		fields.add(new IssueExport.Field("Story points",
				issue.getStoryPoints() == null ? "" : String.valueOf(issue.getStoryPoints())));
		fields.add(new IssueExport.Field("Estimate", minutes(issue.getEstimateMinutes())));
		fields.add(new IssueExport.Field("Time spent", minutes(issue.getSpentMinutes())));
		fields.add(new IssueExport.Field("Labels", join(issue.getTags())));
		fields.add(new IssueExport.Field("Parent", parent(issue.getParentId(), user)));
		fields.add(new IssueExport.Field("Depends on", dependsOn(issue.getDependsOnIds(), user)));
		fields.add(new IssueExport.Field("Project key", project == null ? "" : nz(project.getKey())));
		fields.add(new IssueExport.Field("Created", instant(issue.getCreatedAt())));
		fields.add(new IssueExport.Field("Updated", instant(issue.getUpdatedAt())));
		return fields;
	}

	private List<IssueComment> readComments(Issue issue) {
		return comments.findByIssueIdOrderByCreatedAtAsc(
				issue.getId(), PageRequest.of(0, MAX_COMMENTS));
	}

	private List<IssueExport.Comment> comments(List<IssueComment> all, Map<String, String> names) {
		List<IssueExport.Comment> out = new ArrayList<>();
		for (IssueComment comment : all) {
			out.add(new IssueExport.Comment(
					person(comment.getAuthorId(), names),
					comment.getCreatedAt(),
					MarkdownBlocks.of(LexicalToMarkdown.fromStored(
							comment.getTextDoc(), comment.getText()))));
		}
		return out;
	}

	/**
	 * The issue's links, read through {@link IssueLinkService#linksOf} — which
	 * already drops dangling ones and anything whose far end this caller may not
	 * see, so an export cannot become a way to learn that an issue exists.
	 */
	private List<IssueExport.Link> links(String idOrReadableId, User user) {
		List<IssueExport.Link> out = new ArrayList<>();
		for (IssueLinkService.LinkView view : links.linksOf(idOrReadableId, user)) {
			out.add(new IssueExport.Link(view.verb(),
					nz(view.issue().getReadableId()), nz(view.issue().getTitle())));
		}
		return out;
	}

	private List<IssueExport.Attachment> attachments(List<Issue.Attachment> files,
			Map<String, String> names) {
		List<IssueExport.Attachment> out = new ArrayList<>();
		for (Issue.Attachment file : files) {
			out.add(new IssueExport.Attachment(nz(file.getFileName()), nz(file.getContentType()),
					ByteSize.human(file.getSize()), person(file.getUploaderId(), names),
					file.getUploadedAt()));
		}
		return out;
	}

	private List<IssueActivity> readActivity(Issue issue) {
		return activities.findByIssueIdOrderByCreatedAtDesc(
				issue.getId(), PageRequest.of(0, MAX_ACTIVITY)).getContent();
	}

	private List<IssueExport.Activity> activity(List<IssueActivity> all, Map<String, String> names) {
		List<IssueExport.Activity> out = new ArrayList<>();
		for (IssueActivity entry : all) {
			out.add(new IssueExport.Activity(instant(entry.getCreatedAt()),
					person(entry.getActorId(), names), describe(entry)));
		}
		return out;
	}

	private static String describe(IssueActivity entry) {
		String field = entry.getField() == null ? "changed" : entry.getField().name();
		String from = entry.getFromValue();
		String to = entry.getToValue();
		if (from == null && to == null) {
			return field;
		}
		return field + ": " + nz(from) + " → " + nz(to);
	}

	// --- naming --------------------------------------------------------------

	/**
	 * Every display name this export is going to print, read in one query.
	 *
	 * <p>Remembering a name once it has been looked up is not enough on its own.
	 * Resolved lazily, a document costs one round trip per distinct person in it,
	 * one after another on the request thread — and the caps allow five hundred
	 * comments and five hundred history entries, which is a document that can
	 * legitimately name several dozen. Sequential round trips are the whole cost
	 * there: forty of them is forty times the network's latency for forty small
	 * reads a single {@code $in} answers in one.
	 *
	 * <p>Ids that name nobody are remembered too, as themselves. That is what a
	 * miss already rendered as — a deleted author has always printed as the id —
	 * and priming it is what stops each one costing the query the batch was meant
	 * to replace.
	 *
	 * <p>Only the sections that will actually be printed contribute ids: naming
	 * the uploader of an attachment an export was asked to leave out is a name
	 * nobody reads.
	 */
	private Map<String, String> names(Issue issue, List<IssueComment> thread,
			List<Issue.Attachment> files, List<IssueActivity> history) {
		Set<String> ids = new LinkedHashSet<>();
		if (issue.getAssigneeIds() != null) {
			ids.addAll(issue.getAssigneeIds());
		}
		ids.add(issue.getReporterId());
		for (IssueComment comment : thread) {
			ids.add(comment.getAuthorId());
		}
		for (Issue.Attachment file : files) {
			ids.add(file.getUploaderId());
		}
		for (IssueActivity entry : history) {
			ids.add(entry.getActorId());
		}
		ids.removeIf(id -> id == null || id.isBlank());
		Map<String, String> names = new HashMap<>();
		if (ids.isEmpty()) {
			return names;
		}
		for (User person : users.findAllById(ids)) {
			names.put(person.getId(),
					firstNonBlank(person.getDisplayName(), person.getUsername(), person.getId()));
		}
		for (String id : ids) {
			names.putIfAbsent(id, id);
		}
		return names;
	}

	/**
	 * Display name for a user id, resolved once per export and remembered.
	 *
	 * <p>Name then username, and never the e-mail address. {@code DirectoryUser}
	 * is what the rest of the platform hands out when it turns an id into
	 * somebody to look at, and it carries id, username, display name, avatar and
	 * title — deliberately not the address. A document that fell back to the
	 * address would publish the mail addresses of everyone who ever commented on
	 * a ticket into a file that then leaves the platform, which is precisely the
	 * disclosure the directory shape exists to prevent.
	 */
	private String person(String userId, Map<String, String> names) {
		if (userId == null || userId.isBlank()) {
			return "";
		}
		// The batch above has already put every id this document prints into the
		// map, so in practice this only reads it. The single-id read stays as the
		// answer for an id the batch was never told about: a section added later
		// that forgets to contribute its ids should cost a query, not print a raw
		// id at somebody.
		return names.computeIfAbsent(userId, id -> users.findById(id)
				.map(user -> firstNonBlank(user.getDisplayName(), user.getUsername(), id))
				.orElse(id));
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	private String people(List<String> userIds, Map<String, String> names) {
		if (userIds == null || userIds.isEmpty()) {
			return "";
		}
		List<String> resolved = new ArrayList<>();
		for (String id : userIds) {
			resolved.add(person(id, names));
		}
		return String.join(", ", resolved);
	}

	/**
	 * The name of the issue's sprint, and only when that sprint belongs here.
	 *
	 * <p>{@code sprintId} is stored the way it is sent, like {@code dependsOnIds},
	 * so an id from a board that spans a project the caller was never added to is
	 * storable — and naming it would turn the export into an id-to-name oracle for
	 * planning that is closed to them. Somebody removed from a project keeps every
	 * id they ever saw; the id is what stops being useful to them, and that only
	 * holds if nothing resolves it afterwards. A sprint whose board does not span
	 * this issue's project is not this issue's sprint in any case.
	 */
	private String sprintName(Issue issue) {
		String sprintId = issue.getSprintId();
		if (sprintId == null || sprintId.isBlank()) {
			return "";
		}
		return sprints.findById(sprintId)
				.filter(sprint -> boards.findById(nz(sprint.getBoardId()))
						.map(board -> board.getProjectIds() != null
								&& board.getProjectIds().contains(issue.getProjectId()))
						.orElse(false))
				.map(Sprint::getName)
				.orElse("");
	}

	/**
	 * The parent's key and title, or nothing when the caller may not read it.
	 *
	 * <p>A parent is required to live in its child's project when it is set, so
	 * in every ordinary case this check passes and costs one project read. It
	 * is here for the case the write path cannot rule out: an issue whose parent
	 * stayed behind in another project after a move still carries the id, and an
	 * export is not the place to learn a stranger's title from it.
	 */
	private String parent(String parentId, User user) {
		Issue parent = related(parentId, user);
		return parent == null ? "" : nz(parent.getReadableId()) + " " + nz(parent.getTitle());
	}

	/**
	 * The keys of the issues this one depends on, dropped to the ones the caller
	 * may see.
	 *
	 * <p>The ACL is the point, not a formality. {@code dependsOnIds} is stored
	 * exactly as it is sent, so anyone who may edit one issue may point it at an
	 * id from a project they were never added to — and turning that id into
	 * {@code OTHER-42} is the export telling them the key, the project and the
	 * issue count of a project that is closed to them. {@link IssueLinkService}
	 * already refuses to be that oracle for links; the same rule applies here.
	 * An id that resolves to nothing, or to something out of reach, is simply not
	 * named.
	 */
	private String dependsOn(List<String> ids, User user) {
		if (ids == null || ids.isEmpty()) {
			return "";
		}
		// Deduplicated before the ceiling is spent, because the list is stored the
		// way it arrives and nothing keeps the same id out of it twice. Every repeat
		// used to cost its own two reads — the issue and the project behind its ACL
		// — and then name the same key again in the field. The ceiling on reads is
		// unchanged at MAX_DEPENDS_ON; they are simply all distinct now.
		Set<String> distinct = new LinkedHashSet<>();
		for (String id : ids) {
			if (id != null && !id.isBlank()) {
				distinct.add(id);
			}
			if (distinct.size() == MAX_DEPENDS_ON) {
				break;
			}
		}
		List<String> keys = new ArrayList<>();
		for (String id : distinct) {
			Issue other = related(id, user);
			if (other != null) {
				keys.add(nz(other.getReadableId()));
			}
		}
		return String.join(", ", keys);
	}

	/** An issue named by another issue's fields, or null when it is out of reach. */
	private Issue related(String id, User user) {
		if (id == null || id.isBlank()) {
			return null;
		}
		Issue other = issues.findOrNull(id);
		return other != null && issues.canAccess(other, user) ? other : null;
	}

	// --- formatting ----------------------------------------------------------

	/** A duration in the "2h 30m" shape the issue's own fields already use. */
	private static String minutes(Integer value) {
		if (value == null || value == 0) {
			return "";
		}
		int hours = value / 60;
		int rest = value % 60;
		if (hours == 0) {
			return rest + "m";
		}
		return rest == 0 ? hours + "h" : hours + "h " + rest + "m";
	}

	private static String instant(Instant value) {
		return value == null ? "" : ExportText.DATE_TIME.format(value);
	}

	private static String date(LocalDate value) {
		return value == null ? "" : value.toString();
	}

	private static String join(List<String> values) {
		return values == null || values.isEmpty() ? "" : String.join(", ", values);
	}

	private static String name(Enum<?> value) {
		return value == null ? "" : value.name();
	}

	private static String nz(String value) {
		return value == null ? "" : value;
	}
}
