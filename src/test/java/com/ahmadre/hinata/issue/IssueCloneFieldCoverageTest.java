package com.ahmadre.hinata.issue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard {@link IssueCloneService} promises. A rule table cannot notice a
 * <em>new</em> field on {@link Issue}: somebody adds {@code severity}, nothing
 * goes red, and every clone silently loses it — or, worse, somebody adds
 * {@code secretToken} and every clone silently gains it. Reflection closes that
 * gap, so a new field fails the build until a person decides which list it
 * belongs on.
 */
class IssueCloneFieldCoverageTest {

	@Test
	void everyIssueFieldIsEitherCarriedOrDeliberatelyReset() {
		List<String> named = new ArrayList<>(IssueCloneService.CARRIED);
		named.addAll(IssueCloneService.RESET);

		List<String> unaccounted = Arrays.stream(Issue.class.getDeclaredFields())
				.filter(field -> !field.isSynthetic())
				.filter(field -> !Modifier.isStatic(field.getModifiers()))
				.map(Field::getName)
				.filter(name -> !named.contains(name))
				.toList();

		assertThat(unaccounted)
				.as("a new Issue field must be added to IssueCloneService.CARRIED or to "
						+ "IssueCloneService.RESET — a clone takes it along, or it is documented "
						+ "why it must not")
				.isEmpty();
	}

	/** A field on both lists is a rule that contradicts itself. */
	@Test
	void noFieldIsOnBothLists() {
		assertThat(IssueCloneService.CARRIED)
				.doesNotContainAnyElementsOf(IssueCloneService.RESET);
	}

	/** Both lists must name real fields, or the coverage check above passes on a typo. */
	@Test
	void bothListsNameFieldsThatExist() {
		List<String> declared = Arrays.stream(Issue.class.getDeclaredFields())
				.map(Field::getName)
				.toList();
		List<String> named = new ArrayList<>(IssueCloneService.CARRIED);
		named.addAll(IssueCloneService.RESET);

		assertThat(named).allMatch(declared::contains,
				"every name on CARRIED/RESET is a declared field of Issue");
	}
}
