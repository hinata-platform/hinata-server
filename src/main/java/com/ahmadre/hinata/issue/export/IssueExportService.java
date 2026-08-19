package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.board.SprintRepository;
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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
	 * <p>A cap rather than a page: an export is a document, and a document that
	 * silently stops is worse than one that says where it stopped — so the
	 * renderers are told the count and print a line when it was reached. Neither
	 * collection is bounded in the database, and a long-running ticket
	 * legitimately accumulates hundreds of both.
	 */
	static final int MAX_COMMENTS = 500;
	static final int MAX_ACTIVITY = 500;

	private final IssueService issues;
	private final IssueLinkService links;
	private final IssueCommentRepository comments;
	private final IssueActivityRepository activities;
	private final ProjectRepository projects;
	private final SprintRepository sprints;
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
		Map<String, String> names = new HashMap<>();
		return new IssueExport(
				nz(issue.getReadableId()),
				nz(issue.getTitle()),
				project == null ? "" : nz(project.getName()),
				fields(issue, project, names),
				MarkdownBlocks.of(LexicalToMarkdown.fromStored(
						issue.getDescriptionDoc(), issue.getDescription())),
				options.comments() ? comments(issue, names) : List.of(),
				options.links() ? links(idOrReadableId, user) : List.of(),
				options.attachments() ? attachments(issue, names) : List.of(),
				options.activity() ? activity(issue, names) : List.of(),
				nz(settings.get().getOrganizationName()),
				Instant.now());
	}

	// --- sections ------------------------------------------------------------

	private List<IssueExport.Field> fields(Issue issue, Project project, Map<String, String> names) {
		List<IssueExport.Field> fields = new ArrayList<>();
		fields.add(new IssueExport.Field("Type", name(issue.getType())));
		fields.add(new IssueExport.Field("Status", nz(issue.getState())));
		fields.add(new IssueExport.Field("Priority", name(issue.getPriority())));
		fields.add(new IssueExport.Field("Assignees", people(issue.getAssigneeIds(), names)));
		fields.add(new IssueExport.Field("Reporter", person(issue.getReporterId(), names)));
		fields.add(new IssueExport.Field("Sprint", sprintName(issue.getSprintId())));
		fields.add(new IssueExport.Field("Start date", date(issue.getStartDate())));
		fields.add(new IssueExport.Field("Due date", date(issue.getDueDate())));
		fields.add(new IssueExport.Field("Story points",
				issue.getStoryPoints() == null ? "" : String.valueOf(issue.getStoryPoints())));
		fields.add(new IssueExport.Field("Estimate", minutes(issue.getEstimateMinutes())));
		fields.add(new IssueExport.Field("Time spent", minutes(issue.getSpentMinutes())));
		fields.add(new IssueExport.Field("Labels", join(issue.getTags())));
		fields.add(new IssueExport.Field("Parent", parent(issue.getParentId())));
		fields.add(new IssueExport.Field("Depends on", dependsOn(issue.getDependsOnIds())));
		fields.add(new IssueExport.Field("Project key", project == null ? "" : nz(project.getKey())));
		fields.add(new IssueExport.Field("Created", instant(issue.getCreatedAt())));
		fields.add(new IssueExport.Field("Updated", instant(issue.getUpdatedAt())));
		return fields;
	}

	private List<IssueExport.Comment> comments(Issue issue, Map<String, String> names) {
		List<IssueComment> all = comments.findByIssueIdOrderByCreatedAtAsc(issue.getId());
		List<IssueExport.Comment> out = new ArrayList<>();
		for (IssueComment comment : all.subList(0, Math.min(all.size(), MAX_COMMENTS))) {
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

	private List<IssueExport.Attachment> attachments(Issue issue, Map<String, String> names) {
		List<IssueExport.Attachment> out = new ArrayList<>();
		if (issue.getAttachments() == null) {
			return out;
		}
		for (Issue.Attachment file : issue.getAttachments()) {
			out.add(new IssueExport.Attachment(nz(file.getFileName()), nz(file.getContentType()),
					humanSize(file.getSize()), person(file.getUploaderId(), names),
					file.getUploadedAt()));
		}
		return out;
	}

	private List<IssueExport.Activity> activity(Issue issue, Map<String, String> names) {
		List<IssueActivity> all = activities.findByIssueIdOrderByCreatedAtDesc(issue.getId());
		List<IssueExport.Activity> out = new ArrayList<>();
		for (IssueActivity entry : all.subList(0, Math.min(all.size(), MAX_ACTIVITY))) {
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

	/** Display name for a user id, resolved once per export and remembered. */
	private String person(String userId, Map<String, String> names) {
		if (userId == null || userId.isBlank()) {
			return "";
		}
		return names.computeIfAbsent(userId, id -> users.findById(id)
				.map(user -> user.getDisplayName() != null && !user.getDisplayName().isBlank()
						? user.getDisplayName() : user.getEmail())
				.orElse(id));
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

	private String sprintName(String sprintId) {
		if (sprintId == null || sprintId.isBlank()) {
			return "";
		}
		return sprints.findById(sprintId).map(Sprint::getName).orElse(sprintId);
	}

	private String parent(String parentId) {
		if (parentId == null || parentId.isBlank()) {
			return "";
		}
		Issue parent = issues.findOrNull(parentId);
		return parent == null ? "" : nz(parent.getReadableId()) + " " + nz(parent.getTitle());
	}

	private String dependsOn(List<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return "";
		}
		List<String> keys = new ArrayList<>();
		for (String id : ids) {
			Issue other = issues.findOrNull(id);
			keys.add(other == null ? id : nz(other.getReadableId()));
		}
		return String.join(", ", keys);
	}

	// --- formatting ----------------------------------------------------------

	static String humanSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		String[] units = { "KB", "MB", "GB" };
		double value = bytes;
		int unit = -1;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
		return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
	}

	private static String minutes(Integer value) {
		return value == null || value == 0 ? "" : minutes(value.intValue());
	}

	private static String minutes(int value) {
		if (value == 0) {
			return "";
		}
		int hours = value / 60;
		int rest = value % 60;
		return hours == 0 ? rest + "m" : (rest == 0 ? hours + "h" : hours + "h " + rest + "m");
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
