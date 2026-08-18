package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.issue.Issue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Turns "before" and "after" of one issue into the list of changes a watcher
 * should hear about.
 *
 * <p>The rule table below is the whole policy, in one place: it is the honest
 * answer to "what does <em>every change</em> mean". Scattering that decision
 * across {@code if} branches in the update path is how the previous behaviour
 * ended up notifying about a state change but silently swallowing a due date, a
 * re-prioritisation or a re-parenting.
 *
 * <p>{@link #WATCHED_FIELDS} is <em>derived</em> from the same table
 * {@link #between} walks, so the published whitelist and the code that produces
 * the changes cannot drift apart: adding a rule adds the field id, and a field
 * id with no rule cannot exist. What the table cannot do by itself is notice a
 * <em>new</em> field on {@link Issue} — that is what {@link #EXCLUDED} and the
 * reflective guard in {@code IssueChangeDiffTest} are for: every field of the
 * document must be named on one list or the other, so a new one fails the build
 * until somebody decides which it is.
 */
public final class IssueChangeDiff {

	// Field ids. Stable strings, not an enum: they are persisted inside queued
	// digest entries, so a renamed constant must not silently orphan a queue.
	// They deliberately match the Issue field they describe — that is what lets
	// the reflective guard test pair the two lists up.
	public static final String TITLE = "title";
	public static final String DESCRIPTION = "description";
	public static final String STATE = "state";
	public static final String PRIORITY = "priority";
	public static final String TYPE = "type";
	public static final String ASSIGNEES = "assigneeIds";
	public static final String SPRINT = "sprintId";
	public static final String PARENT = "parentId";
	public static final String PROJECT = "projectId";
	public static final String START_DATE = "startDate";
	public static final String DUE_DATE = "dueDate";
	public static final String ESTIMATE = "estimateMinutes";
	public static final String STORY_POINTS = "storyPoints";
	public static final String TAGS = "tags";
	public static final String DEPENDS_ON = "dependsOnIds";
	public static final String ARCHIVED = "archived";

	/**
	 * Fields of {@link Issue} a watcher is deliberately <em>not</em> told about.
	 * Named rather than merely absent, so adding a field to the document is a
	 * decision somebody makes instead of silence nobody notices:
	 * <ul>
	 *   <li>{@code id} / {@code numberInProject} / {@code readableId} /
	 *       {@code formerReadableIds} — identity and its history. They move as a
	 *       consequence of a move, which is reported as {@link #PROJECT}.</li>
	 *   <li>{@code descriptionDoc} — the source of truth that {@link #DESCRIPTION}
	 *       is diffed on; reporting both would announce one edit twice.</li>
	 *   <li>{@code assigneeId} — the denormalised primary assignee, always the
	 *       head of {@code assigneeIds}, which is reported.</li>
	 *   <li>{@code reporterId} / {@code reporterEmail} / {@code inboundMessageId} /
	 *       {@code inboundSubject} / {@code ingestConnectionId} — provenance,
	 *       written once when the issue is raised and never edited by a human.</li>
	 *   <li>{@code watcherIds} — who else subscribed is nobody's business but
	 *       theirs, and a mail per new subscriber would make watching viral.</li>
	 *   <li>{@code spentMinutes} — time tracking ticks with every logged work item;
	 *       a watcher would be mailed for someone else's stopwatch.</li>
	 *   <li>{@code dueReminderFor} — an internal idempotency marker written by the
	 *       reminder job, invisible to users and meaningless to them.</li>
	 *   <li>{@code attachments} — a known gap, not an opinion: files are added and
	 *       removed through their own endpoints, which never route through
	 *       {@code IssueService.update}, so listing it here would promise a notice
	 *       that nothing raises. It belongs on the list the day that path emits a
	 *       change of its own.</li>
	 *   <li>{@code rank} / {@code resolvedAt} / {@code archivedAt} /
	 *       {@code createdAt} / {@code updatedAt} — derived or internal; they move
	 *       as a consequence of the real edit that is already reported.</li>
	 *   <li>{@code subtaskCount} / {@code subtaskDoneCount} — {@code @Transient},
	 *       computed per response and never stored at all.</li>
	 * </ul>
	 */
	public static final List<String> EXCLUDED = List.of(
			"id", "numberInProject", "readableId", "formerReadableIds", "descriptionDoc",
			"assigneeId", "reporterId", "reporterEmail", "inboundMessageId", "inboundSubject",
			"ingestConnectionId", "watcherIds", "spentMinutes", "dueReminderFor", "attachments",
			"rank", "resolvedAt", "archivedAt", "createdAt", "updatedAt",
			"subtaskCount", "subtaskDoneCount");

	/**
	 * One whitelisted field and how a change to it is read off two snapshots.
	 * {@code diff} returns {@code null} when the field did not move.
	 */
	private record Rule(String field, BiFunction<Issue, Issue, FieldChange> diff) {
	}

	/**
	 * The policy, in reading order. {@link #between} walks it and
	 * {@link #WATCHED_FIELDS} is projected from it, so neither can list a field
	 * the other does not.
	 */
	private static final List<Rule> RULES = List.of(
			new Rule(TITLE, (a, b) -> scalar(TITLE, a.getTitle(), b.getTitle())),
			// Diffed on the stored document, not on its derived plain text: bolding a
			// word or adding a table is an edit the watchers asked to hear about, and
			// it leaves the plain-text projection byte-identical.
			new Rule(DESCRIPTION, (a, b) -> Objects.equals(a.getDescriptionDoc(), b.getDescriptionDoc())
					? null
					: new FieldChange(DESCRIPTION, null, null)),
			new Rule(STATE, (a, b) -> scalar(STATE, a.getState(), b.getState())),
			new Rule(PRIORITY, (a, b) -> scalar(PRIORITY, name(a.getPriority()), name(b.getPriority()))),
			new Rule(TYPE, (a, b) -> scalar(TYPE, name(a.getType()), name(b.getType()))),
			new Rule(ASSIGNEES, (a, b) -> list(ASSIGNEES, a.getAssigneeIds(), b.getAssigneeIds())),
			new Rule(SPRINT, (a, b) -> scalar(SPRINT, a.getSprintId(), b.getSprintId())),
			new Rule(PARENT, (a, b) -> scalar(PARENT, a.getParentId(), b.getParentId())),
			// Ordinarily moved by IssueMoveService rather than by an update, but a
			// mutator that re-homes an issue must not do so in silence either.
			new Rule(PROJECT, (a, b) -> scalar(PROJECT, a.getProjectId(), b.getProjectId())),
			new Rule(START_DATE, (a, b) -> scalar(START_DATE, str(a.getStartDate()), str(b.getStartDate()))),
			new Rule(DUE_DATE, (a, b) -> scalar(DUE_DATE, str(a.getDueDate()), str(b.getDueDate()))),
			new Rule(ESTIMATE, (a, b) -> scalar(ESTIMATE, str(a.getEstimateMinutes()),
					str(b.getEstimateMinutes()))),
			new Rule(STORY_POINTS, (a, b) -> scalar(STORY_POINTS, str(a.getStoryPoints()),
					str(b.getStoryPoints()))),
			new Rule(TAGS, (a, b) -> list(TAGS, a.getTags(), b.getTags())),
			new Rule(DEPENDS_ON, (a, b) -> list(DEPENDS_ON, a.getDependsOnIds(), b.getDependsOnIds())),
			new Rule(ARCHIVED, (a, b) -> scalar(ARCHIVED, flag(a.isArchived()), flag(b.isArchived()))));

	/** Every field a change to which reaches watchers, in reading order. */
	public static final List<String> WATCHED_FIELDS = RULES.stream().map(Rule::field).toList();

	/**
	 * Fields recorded as "this changed" without a before/after pair. A description
	 * is a whole rich-text document: dumping it into a push body, a bell entry and
	 * an e-mail would be unreadable, and carrying it through a 30-minute digest
	 * queue would store the issue body twice.
	 */
	public static boolean valueless(String field) {
		return DESCRIPTION.equals(field);
	}

	/** Whether the list encodes several values (rendered as additions/removals). */
	public static boolean multiValued(String field) {
		return ASSIGNEES.equals(field) || TAGS.equals(field) || DEPENDS_ON.equals(field);
	}

	/**
	 * Storage separator for the multi-valued fields — the ASCII unit separator,
	 * not the {@code ", "} the reader sees.
	 *
	 * <p>It has to be a character the values themselves cannot contain. Tags are
	 * free-text project labels, so a label literally called {@code "Security,
	 * high"} joined on {@code ", "} splits back into two labels and the watcher is
	 * told about additions and removals that never happened. Encoding and
	 * presentation are therefore two different things: this joins,
	 * {@code IssueChangeRenderer} presents.
	 */
	public static final String LIST_SEPARATOR = "\u001F";

	/**
	 * Longest a stored value may be. The renderer clips again at 80 for display,
	 * so nothing readable is lost here — what this protects is the document: an
	 * automation retry-looping on one watched issue with a long title would
	 * otherwise push megabytes of duplicated strings into a single bundle and
	 * eventually break it against Mongo's 16 MB limit.
	 */
	private static final int VALUE_MAX = 200;

	private IssueChangeDiff() {
	}

	/**
	 * The whitelisted fields that actually differ between the two snapshots.
	 * Returns an empty list for an edit that changed nothing a watcher cares
	 * about — which is what keeps a stopwatch tick or a rank nudge from mailing
	 * everyone.
	 */
	public static List<FieldChange> between(Issue before, Issue after) {
		List<FieldChange> changes = new ArrayList<>();
		if (before == null || after == null) return changes;
		for (Rule rule : RULES) {
			FieldChange change = rule.diff().apply(before, after);
			if (change != null) changes.add(change);
		}
		return changes;
	}

	/** The single change describing an archive / restore, for the archive path. */
	public static List<FieldChange> archiveChange(boolean archived) {
		return List.of(new FieldChange(ARCHIVED, flag(!archived), flag(archived)));
	}

	/**
	 * The single change describing a cross-project move, for
	 * {@code IssueMoveService}. Carries the project ids; the renderer resolves
	 * them to names at send time, exactly as it does for a sprint.
	 */
	public static FieldChange projectChange(String fromProjectId, String toProjectId) {
		return new FieldChange(PROJECT, clip(fromProjectId), clip(toProjectId));
	}

	private static FieldChange scalar(String field, String from, String to) {
		String a = blankToNull(from);
		String b = blankToNull(to);
		if (Objects.equals(a, b)) return null;
		return new FieldChange(field, clip(a), clip(b));
	}

	private static FieldChange list(String field, List<String> from, List<String> to) {
		List<String> a = from != null ? from : List.of();
		List<String> b = to != null ? to : List.of();
		if (a.equals(b)) return null;
		return new FieldChange(field, join(a), join(b));
	}

	private static String join(List<String> values) {
		if (values.isEmpty()) return null;
		List<String> clipped = new ArrayList<>(values.size());
		for (String value : values) clipped.add(clip(value));
		return String.join(LIST_SEPARATOR, clipped);
	}

	/** Cuts a stored value down to {@link #VALUE_MAX}; no ellipsis, the renderer
	 *  adds one when it clips for display. */
	private static String clip(String value) {
		if (value == null || value.length() <= VALUE_MAX) return value;
		return value.substring(0, VALUE_MAX);
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}

	private static String name(Enum<?> value) {
		return value != null ? value.name() : null;
	}

	private static String flag(boolean value) {
		return Boolean.toString(value);
	}

	private static String str(Object value) {
		return value != null ? value.toString() : null;
	}
}
