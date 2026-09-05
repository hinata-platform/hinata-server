package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns the raw {@link FieldChange}s of an update into lines a human reads, in
 * the recipient's language.
 *
 * <p>It runs at <em>send</em> time, not at diff time, which is the point: an
 * issue's watchers may read different languages, and a bundled change sits in
 * the queue for up to half an hour before anyone words it. Everything that could
 * have moved in the meantime — a display name, a sprint name, the parent's
 * readable id — is resolved here rather than frozen into the queue.
 *
 * <p>Every line is produced as a small list of {@link TextDiff.Segment}s rather
 * than as one string: what a reader wants from a change notice is the state
 * <em>before</em> next to the state <em>after</em>, which is a two-coloured
 * thing an e-mail can paint and a one-line push body can only spell out with an
 * arrow. Both fall out of the same segments, so the mail and the push can never
 * disagree about what changed.
 *
 * <p>The copy is resolved through {@code UserWords} against the recipient's
 * stored locale: this text is composed per recipient inside the fan-out, where
 * there is no request and therefore no {@code MessageSource} context to resolve
 * against.
 */
@Component
@RequiredArgsConstructor
public class IssueChangeRenderer {

	// Repositories rather than the matching services, for the same reason
	// ProjectReach states: this component is reached from NotificationService,
	// which IssueService and the board services depend on. Injecting IssueService
	// or SprintService back would close the cycle and fail the context with a
	// BeanCurrentlyInCreationException at startup. Reading is all this needs.
	private final UserRepository users;
	private final SprintRepository sprints;
	private final IssueRepository issues;
	private final ProjectRepository projects;
	private final com.ahmadre.hinata.common.UserWords words;

	/**
	 * One rendered change, in both shapes its readers need.
	 *
	 * <p>{@code segments} is the diff itself — what went, what arrived, what
	 * stayed — for a surface that can colour it. {@code wordDiff} says how to set
	 * them: {@code false} is the ordinary two-part change, read as "before →
	 * after"; {@code true} is a word-level diff of a longer text, whose segments
	 * interleave and are read as one running sentence. {@code value} is the same
	 * information flattened to a single line, for the push body and the bell
	 * entry, which have room for a sentence and no room for colour.
	 */
	public record Line(String label, List<TextDiff.Segment> segments, boolean wordDiff,
			String value) {
	}

	/** Longest a single value is allowed to be before it is cut — a 300-character
	 *  title must not turn a change list into an essay. */
	private static final int VALUE_MAX = 80;

	/**
	 * Longest one side of a change may be in the e-mail, where there is room for
	 * a whole list of labels or assignees but not for a wall of text.
	 */
	private static final int SIDE_MAX = 240;

	/** Cap on the one-line summary that goes into a push body / bell entry. */
	private static final int SUMMARY_MAX = 160;

	/**
	 * Unchanged words kept on each side of an edit inside a long text. Enough to
	 * place the change in its sentence; not so much that the mail becomes a copy
	 * of the description.
	 */
	private static final int TEXT_CONTEXT = 6;

	/** Stands in for "nothing" on both sides of an arrow. */
	private static final String NONE = "—";

	/** Most segments a word diff may contribute. A heavily interleaved edit
	 *  produces one segment per word, and every one of them is a styled span in
	 *  the mail — enough of those and the message reaches the size at which
	 *  clients start clipping it. */
	private static final int SEGMENT_MAX = 40;

	/** Between two changes in the one-line summary. */
	private static final String SUMMARY_SEPARATOR = " · ";

	/** Room the summary keeps for the "+N more" that names what did not fit. */
	private static final int MORE_RESERVE = 20;

	/** Field id -> message key. The words themselves live in the bundles. */
	private static final Map<String, String> LABEL_KEYS = Map.ofEntries(
			Map.entry(IssueChangeDiff.TITLE, "change.field.title"),
			Map.entry(IssueChangeDiff.DESCRIPTION, "change.field.description"),
			Map.entry(IssueChangeDiff.STATE, "change.field.state"),
			Map.entry(IssueChangeDiff.PRIORITY, "change.field.priority"),
			Map.entry(IssueChangeDiff.TYPE, "change.field.type"),
			Map.entry(IssueChangeDiff.ASSIGNEES, "change.field.assignees"),
			Map.entry(IssueChangeDiff.SPRINT, "change.field.sprint"),
			Map.entry(IssueChangeDiff.PARENT, "change.field.parent"),
			Map.entry(IssueChangeDiff.PROJECT, "change.field.project"),
			Map.entry(IssueChangeDiff.START_DATE, "change.field.startDate"),
			Map.entry(IssueChangeDiff.DUE_DATE, "change.field.dueDate"),
			Map.entry(IssueChangeDiff.ESTIMATE, "change.field.estimate"),
			Map.entry(IssueChangeDiff.STORY_POINTS, "change.field.storyPoints"),
			Map.entry(IssueChangeDiff.TAGS, "change.field.tags"),
			Map.entry(IssueChangeDiff.DEPENDS_ON, "change.field.dependsOn"),
			Map.entry(IssueChangeDiff.ARCHIVED, "change.field.archived"));

	/** Every change as a diffed line, ready for a mail panel or a list row. */
	public List<Line> lines(List<FieldChange> changes, Locale locale) {
		return lines(changes, locale, true);
	}

	/**
	 * The same lines for a recipient who cannot open the issue.
	 *
	 * <p>A notice still goes to the assignees and the reporter when they are not
	 * members of the issue's project — an e-mail-ingested ticket is attributed to
	 * its sender whether or not they belong there, and they must still hear that
	 * their own request moved. What they must <em>not</em> get is the issue's
	 * body: everything else on a change list is a field they can already be told
	 * about, but a description is the document itself, and mailing it to someone
	 * the project would answer with a 403 hands them the content by another door.
	 * They are told the description changed, which is all it ever said before it
	 * was diffed at all.
	 */
	public List<Line> linesWithoutBodyText(List<FieldChange> changes, Locale locale) {
		return lines(changes, locale, false);
	}

	private List<Line> lines(List<FieldChange> changes, Locale locale, boolean bodyText) {
		List<Line> lines = new ArrayList<>();
		if (changes == null || changes.isEmpty()) return List.of();
		// One memo for the whole list, filled in bulk first. An assignee who is on
		// the issue both before and after appears on both sides of the same line,
		// and a sprint or a parent can appear on two lines of one update — each of
		// those is a point read, and a twenty-strong assignee list should cost one
		// query rather than twenty.
		Map<String, String> resolved = prefetch(changes);
		for (FieldChange change : changes) {
			if (change == null || change.field() == null) continue;
			String label = label(change.field(), locale);
			if (label == null) continue; // a field id this build no longer knows
			boolean wordDiff = bodyText && IssueChangeDiff.longText(change.field());
			List<TextDiff.Segment> segments = segments(change, locale, resolved, bodyText);
			lines.add(new Line(label, segments, wordDiff, oneLine(segments)));
		}
		// Immutable, because the result is handed to an @Async mail send: a caller
		// cannot append to what another thread is already rendering, and the copy is
		// what safely publishes it to that thread. TextDiff hands back immutable
		// segment lists for the same reason.
		return List.copyOf(lines);
	}

	/**
	 * Resolves every id the change list names, one query per collection.
	 *
	 * <p>The alternative is a point read per value, and a value can be one of
	 * twenty assignees or twenty dependencies — read twice over, once for each
	 * side of the change. Asked in bulk this is two queries for a whole update
	 * however long its lists are.
	 */
	private Map<String, String> prefetch(List<FieldChange> changes) {
		Map<String, String> resolved = new HashMap<>();
		Set<String> userIds = new LinkedHashSet<>();
		Set<String> issueIds = new LinkedHashSet<>();
		for (FieldChange change : changes) {
			if (change == null || change.field() == null) continue;
			switch (change.field()) {
				case IssueChangeDiff.ASSIGNEES -> ids(change, userIds);
				case IssueChangeDiff.PARENT, IssueChangeDiff.DEPENDS_ON -> ids(change, issueIds);
				default -> { /* a scalar name is one read at most; not worth a query */ }
			}
		}
		if (!userIds.isEmpty()) {
			users.findAllById(userIds).forEach(user -> resolved.put(
					key(IssueChangeDiff.ASSIGNEES, user.getId()),
					clip(user.getDisplayName(), VALUE_MAX)));
		}
		if (!issueIds.isEmpty()) {
			issues.findAllById(issueIds).forEach(issue -> {
				String readable = clip(issue.getReadableId(), VALUE_MAX);
				resolved.put(key(IssueChangeDiff.PARENT, issue.getId()), readable);
				resolved.put(key(IssueChangeDiff.DEPENDS_ON, issue.getId()), readable);
			});
		}
		return resolved;
	}

	private static void ids(FieldChange change, Set<String> into) {
		into.addAll(split(change.oldValue()));
		into.addAll(split(change.newValue()));
	}

	/**
	 * The same changes squeezed onto one line, for a push body and the bell entry
	 * — both of which have room for a sentence, not a table.
	 *
	 * <p>Whole changes, as many as fit, and a count for the rest. Cutting the
	 * sentence at {@link #SUMMARY_MAX} instead would let one long field — a
	 * description almost always is one — hide every short change behind it, which
	 * is exactly the "something changed, go and look" the diff exists to replace.
	 *
	 * <p>Takes the lines rather than the changes because every value in one may
	 * have cost a read to resolve, and a caller that needs both the table and the
	 * one-liner must pay for that exactly once.
	 */
	public String summaryOf(List<Line> lines, Locale locale) {
		if (lines == null || lines.isEmpty()) return "";
		List<String> parts = new ArrayList<>(lines.size());
		for (Line line : lines) parts.add(line.label() + ": " + line.value());
		String whole = String.join(SUMMARY_SEPARATOR, parts);
		if (whole.length() <= SUMMARY_MAX) return whole;

		// Which changes fit is decided shortest-first, so the budget buys as many of
		// them as it can; they are then said in the order the fields are read, so
		// the sentence still sounds like one. Taking them in order instead would let
		// whichever change happens to come first spend the whole budget alone.
		List<Integer> order = new ArrayList<>();
		for (int index = 0; index < parts.size(); index++) order.add(index);
		order.sort(Comparator.comparingInt(index -> parts.get(index).length()));
		Set<Integer> taken = new LinkedHashSet<>();
		int length = 0;
		for (int index : order) {
			int cost = (taken.isEmpty() ? 0 : SUMMARY_SEPARATOR.length()) + parts.get(index).length();
			if (length + cost > SUMMARY_MAX - MORE_RESERVE) break;
			taken.add(index);
			length += cost;
		}
		StringBuilder text = new StringBuilder();
		if (taken.isEmpty()) {
			// Not even the shortest change fits beside the tail. Half of one is still
			// more use to a reader than a bare count.
			text.append(clip(parts.getFirst(), SUMMARY_MAX - MORE_RESERVE));
			taken.add(0);
		}
		else {
			for (int index = 0; index < parts.size(); index++) {
				if (!taken.contains(index)) continue;
				if (!text.isEmpty()) text.append(SUMMARY_SEPARATOR);
				text.append(parts.get(index));
			}
		}
		return text + SUMMARY_SEPARATOR
				+ words.in(locale, "change.summary.more", parts.size() - taken.size());
	}

	private String label(String field, Locale locale) {
		String key = LABEL_KEYS.get(field);
		return key == null ? null : words.in(locale, key);
	}

	/**
	 * One change as the diff a reader sees.
	 *
	 * <p>Three shapes, one vocabulary. A long text is diffed word by word, so the
	 * reader sees the sentence that moved rather than two copies of a paragraph.
	 * Everything else is the pair it always was — the value before, the value
	 * after — which the e-mail paints red and green and the push spells with an
	 * arrow.
	 */
	private List<TextDiff.Segment> segments(FieldChange change, Locale locale,
			Map<String, String> resolved, boolean bodyText) {
		String field = change.field();
		if (IssueChangeDiff.ARCHIVED.equals(field)) {
			// "Archived: no → yes" says less than "Archived: yes" does, because the
			// answer is a single bit and its opposite is implied. The restore reads as
			// its own statement for the same reason.
			boolean archived = Boolean.parseBoolean(change.newValue());
			return List.of(added(words.in(locale,
					archived ? "change.value.archivedYes" : "change.value.archivedNo")));
		}
		if (IssueChangeDiff.longText(field)) {
			List<TextDiff.Segment> diff = bodyText
					? TextDiff.words(change.oldValue(), change.newValue(), TEXT_CONTEXT, SEGMENT_MAX)
					: List.of();
			// Nothing to show: the recipient may not be told the body, or the edit was
			// formatting only — bold, a table, a link — which leaves the plain-text
			// projection untouched. Saying so is honest; inventing a diff would not be.
			return diff.isEmpty()
					? List.of(new TextDiff.Segment(TextDiff.Part.SAME,
							words.in(locale, "change.value.changed")))
					: diff;
		}
		return pair(side(field, change.oldValue(), locale, resolved),
				side(field, change.newValue(), locale, resolved));
	}

	/**
	 * The before/after pair, with the "before" dropped when there was none: a
	 * field that was empty reads better as a plain statement of its new value than
	 * as "— → 23.08.2026".
	 */
	private static List<TextDiff.Segment> pair(String from, String to) {
		TextDiff.Segment after = added(to != null ? to : NONE);
		if (from == null) return List.of(after);
		return List.of(new TextDiff.Segment(TextDiff.Part.REMOVED, from), after);
	}

	private static TextDiff.Segment added(String text) {
		return new TextDiff.Segment(TextDiff.Part.ADDED, text);
	}

	/**
	 * One whole side of a change — a single value, or the entire list for a
	 * multi-valued field.
	 *
	 * <p>The list is given in full on both sides rather than as "+Rebar, −Sam".
	 * The shorthand was smaller but it answered the wrong question: a watcher told
	 * only that an assignee was removed still has to open the issue to learn who
	 * is on it now, which is the trip the notification exists to save.
	 */
	private String side(String field, String stored, Locale locale,
			Map<String, String> resolved) {
		if (stored == null || stored.isBlank()) return null;
		if (!IssueChangeDiff.multiValued(field)) return render(field, stored, locale, resolved);
		List<String> rendered = new ArrayList<>();
		for (String raw : split(stored)) {
			String value = render(field, raw, locale, resolved);
			if (value != null) rendered.add(value);
		}
		if (rendered.isEmpty()) return null;
		return clip(String.join(DISPLAY_SEPARATOR, rendered), SIDE_MAX);
	}

	/**
	 * The segments as one line: the state before, an arrow, the state after.
	 *
	 * <p>For a word diff the two halves are the changed words alone, with the
	 * context between them standing as an ellipsis — the mail has room to show a
	 * sentence with its edit inside it, a push body has room for the edit.
	 */
	private static String oneLine(List<TextDiff.Segment> segments) {
		String from = clip(TextDiff.summaryBefore(segments), VALUE_MAX);
		String to = clip(TextDiff.summaryAfter(segments), VALUE_MAX);
		if (from.isEmpty()) return to;
		if (to.isEmpty()) return from + " → " + NONE;
		if (from.equals(to)) return to; // nothing to diff: the "changed" fallback
		return from + " → " + to;
	}

	/** How several rendered values are presented to the reader — never how they
	 *  are stored (see {@code IssueChangeDiff.LIST_SEPARATOR}). */
	private static final String DISPLAY_SEPARATOR = ", ";

	private static final Pattern STORED_SEPARATOR =
			Pattern.compile(Pattern.quote(IssueChangeDiff.LIST_SEPARATOR));

	private static Set<String> split(String joined) {
		if (joined == null || joined.isEmpty()) return Set.of();
		return new LinkedHashSet<>(Arrays.asList(STORED_SEPARATOR.split(joined)));
	}

	/**
	 * One stored value, resolved and formatted; {@code null} for "nothing".
	 * {@code resolved} memoises the point reads for the duration of one render —
	 * see {@link #lines}.
	 */
	private String render(String field, String raw, Locale locale, Map<String, String> resolved) {
		if (raw == null || raw.isBlank()) return null;
		return resolved.computeIfAbsent(key(field, raw), memo -> format(field, raw, locale));
	}

	/** The memo's key. The field id qualifies the value because two fields can
	 *  name the same id and mean different things; the separator is one that no
	 *  field id and no stored value can contain. */
	private static String key(String field, String raw) {
		return field + IssueChangeDiff.LIST_SEPARATOR + raw;
	}

	/** Never {@code null} for a non-blank {@code raw} — every branch either
	 *  formats the value or falls back to it, which is what lets the memo above
	 *  cache the answer rather than re-reading on every repeat. */
	private String format(String field, String raw, Locale locale) {
		return switch (field) {
			case IssueChangeDiff.ASSIGNEES -> clip(displayName(raw), VALUE_MAX);
			case IssueChangeDiff.SPRINT -> clip(sprintName(raw), VALUE_MAX);
			case IssueChangeDiff.PROJECT -> clip(projectName(raw), VALUE_MAX);
			case IssueChangeDiff.PARENT, IssueChangeDiff.DEPENDS_ON -> clip(issueKey(raw), VALUE_MAX);
			case IssueChangeDiff.START_DATE, IssueChangeDiff.DUE_DATE -> date(raw, locale);
			case IssueChangeDiff.ESTIMATE -> duration(raw);
			default -> clip(raw, VALUE_MAX);
		};
	}

	/** Only reached for an id {@link #prefetch} could not answer — a user who has
	 *  since been deleted. The id itself is the honest fallback. */
	private String displayName(String userId) {
		return users.findById(userId).map(User::getDisplayName).orElse(userId);
	}

	private String sprintName(String sprintId) {
		return sprints.findById(sprintId).map(sprint -> sprint.getName()).orElse(sprintId);
	}

	private String projectName(String projectId) {
		return projects.findById(projectId).map(Project::getName).orElse(projectId);
	}

	private String issueKey(String issueId) {
		return issues.findById(issueId).map(Issue::getReadableId).orElse(issueId);
	}

	/**
	 * A date the recipient recognises: 23.08.2026 for a German reader, Aug 23,
	 * 2026 for an English one. Falls back to the stored ISO form if the value
	 * predates a format change and no longer parses.
	 */
	private String date(String iso, Locale locale) {
		try {
			return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
					.withLocale(locale)
					.format(LocalDate.parse(iso));
		}
		catch (RuntimeException unparseable) {
			return iso;
		}
	}

	/** Minutes as people say them: "45 min", "2 h", "2 h 30 min". */
	private String duration(String raw) {
		try {
			int total = Integer.parseInt(raw.trim());
			int hours = total / 60;
			int minutes = total % 60;
			if (hours == 0) return minutes + " min";
			if (minutes == 0) return hours + " h";
			return hours + " h " + minutes + " min";
		}
		catch (NumberFormatException notANumber) {
			return raw;
		}
	}

	private static String clip(String value, int max) {
		if (value == null || value.length() <= max) return value;
		return value.substring(0, max - 1).trim() + "…";
	}
}
