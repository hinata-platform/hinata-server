package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A change list is only useful if it reads like something a person wrote. These
 * pin down the parts a raw dump would get wrong: an id where a name belongs, an
 * ISO date in a German mail, a whole rich-text body pasted into a push, and two
 * comma lists left for the reader to diff by eye.
 */
class IssueChangeRendererTest {

	private UserRepository users;
	private SprintRepository sprints;
	private IssueRepository issues;
	private ProjectRepository projects;
	private IssueChangeRenderer renderer;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		sprints = mock(SprintRepository.class);
		issues = mock(IssueRepository.class);
		projects = mock(ProjectRepository.class);
		lenient().when(users.findById(anyString())).thenReturn(Optional.empty());
		lenient().when(sprints.findById(anyString())).thenReturn(Optional.empty());
		lenient().when(issues.findById(anyString())).thenReturn(Optional.empty());
		lenient().when(projects.findById(anyString())).thenReturn(Optional.empty());
		renderer = new IssueChangeRenderer(users, sprints, issues, projects,
				com.ahmadre.hinata.common.UserWordsFixture.real());
	}

	private String value(FieldChange change, java.util.Locale locale) {
		return renderer.lines(List.of(change), locale).get(0).value();
	}

	private String label(FieldChange change, java.util.Locale locale) {
		return renderer.lines(List.of(change), locale).get(0).label();
	}

	/**
	 * A whitelisted field with no label renders as nothing at all — the change is
	 * silently dropped from the very list that promised to carry it. This is the
	 * check that couples the whitelist to the copy, in both languages.
	 */
	@Test
	void everyWhitelistedFieldHasALabelInBothLanguages() {
		for (String field : IssueChangeDiff.WATCHED_FIELDS) {
			FieldChange change = new FieldChange(field, "a", "b");
			assertThat(renderer.lines(List.of(change), java.util.Locale.GERMAN))
					.as("German label for %s", field).hasSize(1);
			assertThat(renderer.lines(List.of(change), java.util.Locale.ENGLISH))
					.as("English label for %s", field).hasSize(1);
		}
	}

	@Test
	void labelsAreTranslated() {
		FieldChange priority = new FieldChange(IssueChangeDiff.PRIORITY, "NORMAL", "MAJOR");

		assertThat(label(priority, java.util.Locale.GERMAN)).isEqualTo("Priorität");
		assertThat(label(priority, java.util.Locale.ENGLISH)).isEqualTo("Priority");
	}

	@Test
	void aScalarChangeReadsAsAnArrow() {
		assertThat(value(new FieldChange(IssueChangeDiff.PRIORITY, "NORMAL", "MAJOR"), java.util.Locale.GERMAN))
				.isEqualTo("NORMAL → MAJOR");
	}

	/** "Fällig: 23.08.2026" beats "Fällig: — → 23.08.2026" for a value that was
	 *  simply not set before. */
	@Test
	void settingAPreviouslyEmptyFieldReadsAsAStatement() {
		assertThat(value(new FieldChange(IssueChangeDiff.DUE_DATE, null, "2026-08-23"), java.util.Locale.GERMAN))
				.isEqualTo("23.08.2026");
	}

	@Test
	void datesAreFormattedForTheReadersLanguage() {
		FieldChange due = new FieldChange(IssueChangeDiff.DUE_DATE, "2026-08-20", "2026-08-23");

		assertThat(value(due, java.util.Locale.GERMAN)).isEqualTo("20.08.2026 → 23.08.2026");
		assertThat(value(due, java.util.Locale.ENGLISH)).isEqualTo("Aug 20, 2026 → Aug 23, 2026");
	}

	@Test
	void clearingAFieldSaysSoRatherThanShowingNothing() {
		assertThat(value(new FieldChange(IssueChangeDiff.DUE_DATE, "2026-08-23", null), java.util.Locale.ENGLISH))
				.isEqualTo("Aug 23, 2026 → —");
	}

	/** A description is a whole document; the change list says that it moved, not
	 *  what it now contains. */
	@Test
	void aDescriptionEditIsReportedNotDumped() {
		FieldChange description = new FieldChange(IssueChangeDiff.DESCRIPTION, null, null);

		assertThat(value(description, java.util.Locale.GERMAN)).isEqualTo("geändert");
		assertThat(value(description, java.util.Locale.ENGLISH)).isEqualTo("changed");
	}

	@Test
	void assigneesResolveToDisplayNamesAsAdditionsAndRemovals() {
		when(users.findById("u1")).thenReturn(
				Optional.of(User.builder().id("u1").displayName("Rebar").build()));
		when(users.findById("u2")).thenReturn(
				Optional.of(User.builder().id("u2").displayName("Sam").build()));

		String rendered = value(new FieldChange(IssueChangeDiff.ASSIGNEES, "u1", "u2"), java.util.Locale.GERMAN);

		assertThat(rendered).isEqualTo("+Sam, −Rebar");
	}

	@Test
	void aSprintResolvesToItsName() {
		when(sprints.findById("s1")).thenReturn(
				Optional.of(Sprint.builder().id("s1").name("Sprint 12").build()));

		assertThat(value(new FieldChange(IssueChangeDiff.SPRINT, null, "s1"), java.util.Locale.ENGLISH))
				.isEqualTo("Sprint 12");
	}

	@Test
	void aParentResolvesToItsReadableId() {
		when(issues.findById("i0")).thenReturn(
				Optional.of(Issue.builder().id("i0").readableId("HIN-7").build()));

		assertThat(value(new FieldChange(IssueChangeDiff.PARENT, null, "i0"), java.util.Locale.ENGLISH))
				.isEqualTo("HIN-7");
	}

	/** A watcher reads "45 min", not "45". */
	@Test
	void estimatesReadAsTime() {
		assertThat(value(new FieldChange(IssueChangeDiff.ESTIMATE, null, "45"), java.util.Locale.ENGLISH))
				.isEqualTo("45 min");
		assertThat(value(new FieldChange(IssueChangeDiff.ESTIMATE, null, "150"), java.util.Locale.ENGLISH))
				.isEqualTo("2 h 30 min");
		assertThat(value(new FieldChange(IssueChangeDiff.ESTIMATE, null, "120"), java.util.Locale.ENGLISH))
				.isEqualTo("2 h");
	}

	@Test
	void archivingAndRestoringAreBothStated() {
		assertThat(value(new FieldChange(IssueChangeDiff.ARCHIVED, "false", "true"), java.util.Locale.GERMAN))
				.isEqualTo("ja");
		assertThat(value(new FieldChange(IssueChangeDiff.ARCHIVED, "true", "false"), java.util.Locale.ENGLISH))
				.isEqualTo("no — restored");
	}

	@Test
	void theSummaryReadsAsOneSentenceInEitherLanguage() {
		List<FieldChange> changes = List.of(
				new FieldChange(IssueChangeDiff.PRIORITY, "NORMAL", "MAJOR"),
				new FieldChange(IssueChangeDiff.DUE_DATE, null, "2026-08-23"));

		assertThat(renderer.summary(changes, java.util.Locale.GERMAN))
				.isEqualTo("Priorität: NORMAL → MAJOR · Fällig: 23.08.2026");
		assertThat(renderer.summary(changes, java.util.Locale.ENGLISH))
				.isEqualTo("Priority: NORMAL → MAJOR · Due: Aug 23, 2026");
	}

	/** A push body has room for a sentence; a 300-character title must not turn
	 *  the summary into an essay. */
	@Test
	void longValuesAndLongSummariesAreCutRatherThanShipped() {
		String value = value(new FieldChange(IssueChangeDiff.TITLE, null, "x".repeat(400)), java.util.Locale.ENGLISH);
		assertThat(value).hasSize(80).endsWith("…");

		List<FieldChange> many = List.of(
				new FieldChange(IssueChangeDiff.TITLE, null, "y".repeat(200)),
				new FieldChange(IssueChangeDiff.STATE, "o".repeat(200), "Done"),
				new FieldChange(IssueChangeDiff.PRIORITY, "NORMAL", "MAJOR"));
		assertThat(renderer.summary(many, java.util.Locale.ENGLISH)).hasSize(160).endsWith("…");
	}
}
