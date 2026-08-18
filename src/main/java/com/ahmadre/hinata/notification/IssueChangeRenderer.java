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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;

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
 * <p>The copy is hard-coded German/English, matching the {@code L10n} lambdas in
 * {@link NotificationService}: this text is composed per recipient inside the
 * fan-out, where there is no request locale and no {@code MessageSource}
 * context to resolve against.
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

	/** One rendered change: the field's name, and what happened to it. */
	public record Line(String label, String value) {
	}

	/** Longest a single value is allowed to be before it is cut — a 300-character
	 *  title must not turn a change list into an essay. */
	private static final int VALUE_MAX = 80;

	/** Cap on the one-line summary that goes into a push body / bell entry. */
	private static final int SUMMARY_MAX = 160;

	/** Stands in for "nothing" on both sides of an arrow. */
	private static final String NONE = "—";

	private static final Map<String, String> LABELS_DE = Map.ofEntries(
			Map.entry(IssueChangeDiff.TITLE, "Titel"),
			Map.entry(IssueChangeDiff.DESCRIPTION, "Beschreibung"),
			Map.entry(IssueChangeDiff.STATE, "Status"),
			Map.entry(IssueChangeDiff.PRIORITY, "Priorität"),
			Map.entry(IssueChangeDiff.TYPE, "Typ"),
			Map.entry(IssueChangeDiff.ASSIGNEES, "Zuständig"),
			Map.entry(IssueChangeDiff.SPRINT, "Sprint"),
			Map.entry(IssueChangeDiff.PARENT, "Übergeordnet"),
			Map.entry(IssueChangeDiff.PROJECT, "Projekt"),
			Map.entry(IssueChangeDiff.START_DATE, "Start"),
			Map.entry(IssueChangeDiff.DUE_DATE, "Fällig"),
			Map.entry(IssueChangeDiff.ESTIMATE, "Schätzung"),
			Map.entry(IssueChangeDiff.STORY_POINTS, "Story Points"),
			Map.entry(IssueChangeDiff.TAGS, "Labels"),
			Map.entry(IssueChangeDiff.DEPENDS_ON, "Abhängigkeiten"),
			Map.entry(IssueChangeDiff.ARCHIVED, "Archiviert"));

	private static final Map<String, String> LABELS_EN = Map.ofEntries(
			Map.entry(IssueChangeDiff.TITLE, "Title"),
			Map.entry(IssueChangeDiff.DESCRIPTION, "Description"),
			Map.entry(IssueChangeDiff.STATE, "Status"),
			Map.entry(IssueChangeDiff.PRIORITY, "Priority"),
			Map.entry(IssueChangeDiff.TYPE, "Type"),
			Map.entry(IssueChangeDiff.ASSIGNEES, "Assignees"),
			Map.entry(IssueChangeDiff.SPRINT, "Sprint"),
			Map.entry(IssueChangeDiff.PARENT, "Parent"),
			Map.entry(IssueChangeDiff.PROJECT, "Project"),
			Map.entry(IssueChangeDiff.START_DATE, "Start"),
			Map.entry(IssueChangeDiff.DUE_DATE, "Due"),
			Map.entry(IssueChangeDiff.ESTIMATE, "Estimate"),
			Map.entry(IssueChangeDiff.STORY_POINTS, "Story points"),
			Map.entry(IssueChangeDiff.TAGS, "Labels"),
			Map.entry(IssueChangeDiff.DEPENDS_ON, "Dependencies"),
			Map.entry(IssueChangeDiff.ARCHIVED, "Archived"));

	/** Every change as a label/value pair, ready for a mail panel or a list row. */
	public List<Line> lines(List<FieldChange> changes, boolean de) {
		List<Line> lines = new ArrayList<>();
		if (changes == null) return lines;
		for (FieldChange change : changes) {
			if (change == null || change.field() == null) continue;
			String label = label(change.field(), de);
			if (label == null) continue; // a field id this build no longer knows
			lines.add(new Line(label, value(change, de)));
		}
		return lines;
	}

	/**
	 * The same changes squeezed onto one line, for a push body and the bell entry
	 * — both of which have room for a sentence, not a table.
	 */
	public String summary(List<FieldChange> changes, boolean de) {
		return summaryOf(lines(changes, de));
	}

	/**
	 * As {@link #summary(List, boolean)} for a caller that already rendered the
	 * lines. Every value in a line may have cost a point read to resolve — a
	 * display name, a sprint name, a parent's key — so a caller that needs both
	 * the table and the one-liner must pay for that exactly once.
	 */
	public String summaryOf(List<Line> lines) {
		StringBuilder text = new StringBuilder();
		for (Line line : lines) {
			if (!text.isEmpty()) text.append(" · ");
			text.append(line.label()).append(": ").append(line.value());
		}
		if (text.length() > SUMMARY_MAX) {
			return text.substring(0, SUMMARY_MAX - 1).trim() + "…";
		}
		return text.toString();
	}

	private String label(String field, boolean de) {
		return (de ? LABELS_DE : LABELS_EN).get(field);
	}

	private String value(FieldChange change, boolean de) {
		String field = change.field();
		if (IssueChangeDiff.valueless(field)) {
			return de ? "geändert" : "changed";
		}
		if (IssueChangeDiff.ARCHIVED.equals(field)) {
			boolean archived = Boolean.parseBoolean(change.newValue());
			if (de) return archived ? "ja" : "nein — wiederhergestellt";
			return archived ? "yes" : "no — restored";
		}
		if (IssueChangeDiff.multiValued(field)) {
			return delta(field, change.oldValue(), change.newValue(), de);
		}
		String from = render(field, change.oldValue(), de);
		String to = render(field, change.newValue(), de);
		// A field that was empty reads better as a plain statement of the new value
		// than as "— → 23.08.2026".
		if (from == null) return to != null ? to : NONE;
		return from + " → " + (to != null ? to : NONE);
	}

	/**
	 * Additions and removals rather than two comma lists: "+Rebar, −Sam" is read
	 * at a glance, while "Rebar, Nora → Nora, Sam" makes the reader diff by eye.
	 */
	private String delta(String field, String oldValue, String newValue, boolean de) {
		Set<String> before = split(oldValue);
		Set<String> after = split(newValue);
		List<String> parts = new ArrayList<>();
		for (String added : after) {
			if (!before.contains(added)) parts.add("+" + render(field, added, de));
		}
		for (String removed : before) {
			if (!after.contains(removed)) parts.add("−" + render(field, removed, de));
		}
		return parts.isEmpty() ? NONE : String.join(DISPLAY_SEPARATOR, parts);
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

	/** One stored value, resolved and formatted; {@code null} for "nothing". */
	private String render(String field, String raw, boolean de) {
		if (raw == null || raw.isBlank()) return null;
		return switch (field) {
			case IssueChangeDiff.ASSIGNEES -> clip(displayName(raw));
			case IssueChangeDiff.SPRINT -> clip(sprintName(raw));
			case IssueChangeDiff.PROJECT -> clip(projectName(raw));
			case IssueChangeDiff.PARENT, IssueChangeDiff.DEPENDS_ON -> clip(issueKey(raw));
			case IssueChangeDiff.START_DATE, IssueChangeDiff.DUE_DATE -> date(raw, de);
			case IssueChangeDiff.ESTIMATE -> duration(raw);
			default -> clip(raw);
		};
	}

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
	private String date(String iso, boolean de) {
		try {
			return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
					.withLocale(de ? Locale.GERMAN : Locale.ENGLISH)
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

	private static String clip(String value) {
		if (value == null || value.length() <= VALUE_MAX) return value;
		return value.substring(0, VALUE_MAX - 1).trim() + "…";
	}
}
