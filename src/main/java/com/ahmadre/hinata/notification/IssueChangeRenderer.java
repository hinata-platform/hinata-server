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
	private final com.ahmadre.hinata.common.UserWords words;

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

	/** Every change as a label/value pair, ready for a mail panel or a list row. */
	public List<Line> lines(List<FieldChange> changes, Locale locale) {
		List<Line> lines = new ArrayList<>();
		if (changes == null) return lines;
		for (FieldChange change : changes) {
			if (change == null || change.field() == null) continue;
			String label = label(change.field(), locale);
			if (label == null) continue; // a field id this build no longer knows
			lines.add(new Line(label, value(change, locale)));
		}
		return lines;
	}

	/**
	 * The same changes squeezed onto one line, for a push body and the bell entry
	 * — both of which have room for a sentence, not a table.
	 */
	public String summary(List<FieldChange> changes, Locale locale) {
		return summaryOf(lines(changes, locale));
	}

	/**
	 * As {@link #summary(List, Locale)} for a caller that already rendered the
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

	private String label(String field, Locale locale) {
		String key = LABEL_KEYS.get(field);
		return key == null ? null : words.in(locale, key);
	}

	private String value(FieldChange change, Locale locale) {
		String field = change.field();
		if (IssueChangeDiff.valueless(field)) {
			return words.in(locale, "change.value.changed");
		}
		if (IssueChangeDiff.ARCHIVED.equals(field)) {
			boolean archived = Boolean.parseBoolean(change.newValue());
			return words.in(locale,
					archived ? "change.value.archivedYes" : "change.value.archivedNo");
		}
		if (IssueChangeDiff.multiValued(field)) {
			return delta(field, change.oldValue(), change.newValue(), locale);
		}
		String from = render(field, change.oldValue(), locale);
		String to = render(field, change.newValue(), locale);
		// A field that was empty reads better as a plain statement of the new value
		// than as "— → 23.08.2026".
		if (from == null) return to != null ? to : NONE;
		return from + " → " + (to != null ? to : NONE);
	}

	/**
	 * Additions and removals rather than two comma lists: "+Rebar, −Sam" is read
	 * at a glance, while "Rebar, Nora → Nora, Sam" makes the reader diff by eye.
	 */
	private String delta(String field, String oldValue, String newValue, Locale locale) {
		Set<String> before = split(oldValue);
		Set<String> after = split(newValue);
		List<String> parts = new ArrayList<>();
		for (String added : after) {
			if (!before.contains(added)) parts.add("+" + render(field, added, locale));
		}
		for (String removed : before) {
			if (!after.contains(removed)) parts.add("−" + render(field, removed, locale));
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
	private String render(String field, String raw, Locale locale) {
		if (raw == null || raw.isBlank()) return null;
		return switch (field) {
			case IssueChangeDiff.ASSIGNEES -> clip(displayName(raw));
			case IssueChangeDiff.SPRINT -> clip(sprintName(raw));
			case IssueChangeDiff.PROJECT -> clip(projectName(raw));
			case IssueChangeDiff.PARENT, IssueChangeDiff.DEPENDS_ON -> clip(issueKey(raw));
			case IssueChangeDiff.START_DATE, IssueChangeDiff.DUE_DATE -> date(raw, locale);
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

	private static String clip(String value) {
		if (value == null || value.length() <= VALUE_MAX) return value;
		return value.substring(0, VALUE_MAX - 1).trim() + "…";
	}
}
