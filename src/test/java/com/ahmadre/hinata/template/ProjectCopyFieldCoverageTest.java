package com.ahmadre.hinata.template;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard {@link ProjectCopyService} promises, and the only real protection the copy has.
 *
 * <p>A rule table cannot notice a <em>new</em> field. Somebody adds {@code riskLevel} to
 * {@code Project}, nothing goes red, and every copy from that day on silently loses it — or
 * somebody adds a second credential beside the Git token and every copy silently gains it.
 * Reflection closes the gap: a new field fails the build until a person decides which list it
 * belongs on.
 *
 * <p>Both entities are covered by one pair of lists. The two are copied by the same call, in the
 * same decision, and splitting the lists would only make it possible for one of them to be
 * forgotten.
 */
class ProjectCopyFieldCoverageTest {

	private static List<String> named() {
		List<String> names = new ArrayList<>(ProjectCopyService.CARRIED);
		names.addAll(ProjectCopyService.LEFT_BEHIND);
		return names;
	}

	private static List<String> fieldsOf(Class<?> type) {
		return Arrays.stream(type.getDeclaredFields())
				.filter(field -> !field.isSynthetic())
				.filter(field -> !Modifier.isStatic(field.getModifiers()))
				.map(Field::getName)
				.toList();
	}

	@Test
	@DisplayName("every Project field is either carried or deliberately left behind")
	void everyProjectFieldIsAccountedFor() {
		List<String> named = named();

		assertThat(fieldsOf(Project.class).stream().filter(name -> !named.contains(name)).toList())
				.as("a new Project field must be added to ProjectCopyService.CARRIED or to "
						+ "ProjectCopyService.LEFT_BEHIND — a copy takes it along, or it is "
						+ "documented why it must not")
				.isEmpty();
	}

	@Test
	@DisplayName("every Issue field is either carried or deliberately left behind")
	void everyIssueFieldIsAccountedFor() {
		List<String> named = named();

		assertThat(fieldsOf(Issue.class).stream().filter(name -> !named.contains(name)).toList())
				.as("a new Issue field must be added to ProjectCopyService.CARRIED or to "
						+ "ProjectCopyService.LEFT_BEHIND — a copy takes it along, or it is "
						+ "documented why it must not")
				.isEmpty();
	}

	@Test
	@DisplayName("a field on both lists would be a rule contradicting itself")
	void noFieldIsOnBothLists() {
		assertThat(ProjectCopyService.CARRIED)
				.doesNotContainAnyElementsOf(ProjectCopyService.LEFT_BEHIND);
	}

	@Test
	@DisplayName("both lists name fields that exist, or the coverage check passes on a typo")
	void bothListsNameRealFields() {
		Set<String> declared = new LinkedHashSet<>(fieldsOf(Project.class));
		declared.addAll(fieldsOf(Issue.class));

		assertThat(named()).allMatch(declared::contains,
				"every name on CARRIED/LEFT_BEHIND is a declared field of Project or Issue");
	}

	@Test
	@DisplayName("the credentials are on the list that never travels")
	void theGitConnectionNeverTravels() {
		// Stated as a test rather than only as prose: a connection carries an encrypted access
		// token and a webhook registered for one project. Two projects reacting to one push
		// under one set of credentials is not a copy, it is a second owner.
		assertThat(ProjectCopyService.LEFT_BEHIND).contains("git", "extraRepos");
		// And the history of the original, which never happened to the copy.
		assertThat(ProjectCopyService.LEFT_BEHIND)
				.contains("spentMinutes", "resolvedAt", "watcherIds", "sprintId", "reporterId",
						"inboundMessageId");
	}
}
