package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.issue.Issue;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whitelist is the promise "you will hear about every change" — and the
 * exclusions are the promise "and about nothing else". Both are worth pinning
 * down: a field that quietly falls out of the list is silence nobody notices,
 * and a field that quietly falls in is a mailbox full of someone else's
 * stopwatch.
 */
class IssueChangeDiffTest {

	private Issue issue() {
		return Issue.builder().id("i1").projectId("p1").readableId("HIN-1")
				.title("Login bug")
				.state("Open")
				.priority(Issue.Priority.NORMAL)
				.type(Issue.Type.TASK)
				.assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>())
				.dependsOnIds(new ArrayList<>())
				.watcherIds(new ArrayList<>())
				.build();
	}

	/** The same issue with one mutation applied, as an update would leave it. */
	private List<FieldChange> after(java.util.function.Consumer<Issue> mutation) {
		Issue before = issue();
		Issue after = issue();
		mutation.accept(after);
		return IssueChangeDiff.between(before, after);
	}

	@Test
	void everyWhitelistedFieldProducesAChange() {
		assertThat(after(i -> i.setPriority(Issue.Priority.MAJOR)))
				.singleElement().returns(IssueChangeDiff.PRIORITY, FieldChange::field)
				.returns("NORMAL", FieldChange::oldValue)
				.returns("MAJOR", FieldChange::newValue);
		assertThat(after(i -> i.setDueDate(LocalDate.of(2026, 8, 23))))
				.singleElement().returns(IssueChangeDiff.DUE_DATE, FieldChange::field);
		assertThat(after(i -> i.setStartDate(LocalDate.of(2026, 8, 1))))
				.singleElement().returns(IssueChangeDiff.START_DATE, FieldChange::field);
		assertThat(after(i -> i.setDescriptionDoc("{\"root\":{}}")))
				.singleElement().returns(IssueChangeDiff.DESCRIPTION, FieldChange::field);
		assertThat(after(i -> i.setTitle("Login bug on iOS")))
				.singleElement().returns(IssueChangeDiff.TITLE, FieldChange::field);
		assertThat(after(i -> i.setTags(new ArrayList<>(List.of("ui")))))
				.singleElement().returns(IssueChangeDiff.TAGS, FieldChange::field);
		assertThat(after(i -> i.setSprintId("s1")))
				.singleElement().returns(IssueChangeDiff.SPRINT, FieldChange::field);
		assertThat(after(i -> i.setStoryPoints(5)))
				.singleElement().returns(IssueChangeDiff.STORY_POINTS, FieldChange::field);
		assertThat(after(i -> i.setEstimateMinutes(90)))
				.singleElement().returns(IssueChangeDiff.ESTIMATE, FieldChange::field);
		assertThat(after(i -> i.setParentId("i0")))
				.singleElement().returns(IssueChangeDiff.PARENT, FieldChange::field);
		assertThat(after(i -> i.setDependsOnIds(new ArrayList<>(List.of("i2")))))
				.singleElement().returns(IssueChangeDiff.DEPENDS_ON, FieldChange::field);
		assertThat(after(i -> i.setState("In Progress")))
				.singleElement().returns(IssueChangeDiff.STATE, FieldChange::field);
		assertThat(after(i -> i.setAssigneeIds(new ArrayList<>(List.of("u2")))))
				.singleElement().returns(IssueChangeDiff.ASSIGNEES, FieldChange::field);
		assertThat(after(i -> i.setType(Issue.Type.BUG)))
				.singleElement().returns(IssueChangeDiff.TYPE, FieldChange::field);
		assertThat(after(i -> i.setArchived(true)))
				.singleElement().returns(IssueChangeDiff.ARCHIVED, FieldChange::field);
	}

	/**
	 * Time tracking ticks with every logged work item and the reminder marker is
	 * written by a nightly job. Either on the list would mail a watcher about
	 * something no human did.
	 */
	@Test
	void timeTrackingAndTheReminderMarkerAreNotChanges() {
		assertThat(after(i -> i.setSpentMinutes(240))).isEmpty();
		assertThat(after(i -> i.setDueReminderFor(LocalDate.of(2026, 8, 23)))).isEmpty();
	}

	@Test
	void derivedAndInternalFieldsAreNotChanges() {
		assertThat(after(i -> i.setRank(12.5))).isEmpty();
		assertThat(after(i -> i.setResolvedAt(java.time.Instant.now()))).isEmpty();
		assertThat(after(i -> i.setUpdatedAt(java.time.Instant.now()))).isEmpty();
	}

	@Test
	void anEditThatChangesNothingProducesNothing() {
		assertThat(IssueChangeDiff.between(issue(), issue())).isEmpty();
	}

	/** Blank and absent are the same thing to a reader, so they must not diff. */
	@Test
	void blankAndNullAreTheSameValue() {
		Issue before = issue();
		before.setSprintId(null);
		Issue after = issue();
		after.setSprintId("  ");

		assertThat(IssueChangeDiff.between(before, after)).isEmpty();
	}

	// --- collapsing -----------------------------------------------------------

	@Test
	void repeatedEditsOfOneFieldCollapseToWhereItStartedAndWhereItEnded() {
		List<FieldChange> collapsed = FieldChange.collapse(List.of(
				new FieldChange(IssueChangeDiff.STATE, "Open", "In Progress"),
				new FieldChange(IssueChangeDiff.STATE, "In Progress", "In Review"),
				new FieldChange(IssueChangeDiff.STATE, "In Review", "Done")));

		assertThat(collapsed).singleElement()
				.returns("Open", FieldChange::oldValue)
				.returns("Done", FieldChange::newValue);
	}

	@Test
	void aFieldThatEndsWhereItStartedDisappears() {
		List<FieldChange> collapsed = FieldChange.collapse(List.of(
				new FieldChange(IssueChangeDiff.PRIORITY, "NORMAL", "MAJOR"),
				new FieldChange(IssueChangeDiff.PRIORITY, "MAJOR", "NORMAL")));

		assertThat(collapsed).isEmpty();
	}

	/** A description carries no values, so "unchanged" can't be read off it — it
	 *  is only ever recorded when the document really did differ. */
	@Test
	void aValuelessDescriptionEditSurvivesCollapsing() {
		List<FieldChange> collapsed = FieldChange.collapse(List.of(
				new FieldChange(IssueChangeDiff.DESCRIPTION, null, null),
				new FieldChange(IssueChangeDiff.DESCRIPTION, null, null)));

		assertThat(collapsed).singleElement()
				.returns(IssueChangeDiff.DESCRIPTION, FieldChange::field);
	}

	@Test
	void collapsingKeepsTheOrderTheWorkHappenedIn() {
		List<FieldChange> collapsed = FieldChange.collapse(List.of(
				new FieldChange(IssueChangeDiff.STATE, "Open", "In Progress"),
				new FieldChange(IssueChangeDiff.PRIORITY, "NORMAL", "MAJOR"),
				new FieldChange(IssueChangeDiff.STATE, "In Progress", "Done")));

		assertThat(collapsed).extracting(FieldChange::field)
				.containsExactly(IssueChangeDiff.STATE, IssueChangeDiff.PRIORITY);
	}

	/**
	 * The guard the class Javadoc promises. The rule table cannot notice a
	 * <em>new</em> field on {@link Issue}: somebody adds {@code severity}, nothing
	 * goes red, and watchers silently never hear about it — which is the exact bug
	 * class this whole feature was written to end. Reflection closes that gap, so
	 * a new field fails the build until someone decides which list it belongs on.
	 */
	@Test
	void everyIssueFieldIsEitherWatchedOrDeliberatelyExcluded() {
		List<String> named = new ArrayList<>(IssueChangeDiff.WATCHED_FIELDS);
		named.addAll(IssueChangeDiff.EXCLUDED);

		List<String> unaccounted = java.util.Arrays.stream(Issue.class.getDeclaredFields())
				.filter(field -> !field.isSynthetic())
				.filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
				.map(java.lang.reflect.Field::getName)
				.filter(name -> !named.contains(name))
				.toList();

		assertThat(unaccounted)
				.as("a new Issue field must be added to the diff's rule table or to "
						+ "IssueChangeDiff.EXCLUDED — watchers hear about it, or it is "
						+ "documented why they must not")
				.isEmpty();
	}

	/**
	 * The classic diff bug: a legacy document stores {@code null} where a fresh one
	 * builds an empty list. Read as a change, every such issue announces "tags
	 * removed" the first time anyone touches it.
	 */
	@Test
	void aNullListAndAnEmptyListAreTheSameThing() {
		Issue before = issue();
		before.setTags(null);
		before.setAssigneeIds(null);
		before.setDependsOnIds(null);

		assertThat(IssueChangeDiff.between(before, issue())).isEmpty();
	}

	/**
	 * An update never re-homes an issue — {@code IssueMoveService} does, and reports
	 * the move itself. The rule stays as a backstop, but it reads {@code projectId}
	 * off the snapshot, and a snapshot that drops the field makes every ordinary
	 * save announce "project: — → whatever it always was" to every watcher.
	 */
	@Test
	void anUnrelatedEditNeverAnnouncesAProjectChange() {
		Issue before = issue();
		Issue after = issue();
		after.setPriority(Issue.Priority.MAJOR);

		assertThat(IssueChangeDiff.between(before, after)).extracting(FieldChange::field)
				.containsExactly(IssueChangeDiff.PRIORITY)
				.doesNotContain(IssueChangeDiff.PROJECT);
	}
}
