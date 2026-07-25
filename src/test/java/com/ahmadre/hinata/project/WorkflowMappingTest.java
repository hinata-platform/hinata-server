package com.ahmadre.hinata.project;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping ladder is what makes a cross-project move — and a merged board
 * column — land on a status the target project actually defines, so each rung
 * is pinned down here.
 */
class WorkflowMappingTest {

	private static Project project(String key, List<String> names, List<Integer> hues,
			List<String> resolved) {
		List<Project.WorkflowState> states = new ArrayList<>();
		for (int i = 0; i < names.size(); i++) {
			states.add(Project.WorkflowState.builder()
					.id("s" + i)
					.name(names.get(i))
					.hue(hues.get(i))
					.build());
		}
		return Project.builder()
				.id(key)
				.key(key)
				.name(key)
				.workflowStates(states)
				.resolvedStates(new ArrayList<>(resolved))
				.build();
	}

	private static Project english() {
		return project("VOR", List.of("Open", "In Progress", "Done"),
				List.of(250, 70, 155), List.of("Done"));
	}

	/** Same workflow translated: names differ, the semantic hues don't. */
	private static Project german() {
		return project("ERSTI", List.of("Neu", "In Arbeit", "Fertig"),
				List.of(250, 70, 155), List.of("Fertig"));
	}

	@Test
	void matchesByNameFirst() {
		Project source = english();
		Project target = project("X", List.of("Backlog", "In Progress", "Shipped"),
				List.of(255, 40, 155), List.of("Shipped"));

		assertThat(WorkflowMapping.suggest(source, "In Progress", target)).isEqualTo("In Progress");
	}

	@Test
	void nameMatchIsCaseInsensitiveAndReturnsTheTargetsSpelling() {
		Project source = project("A", List.of("in progress"), List.of(70), List.of());
		Project target = project("B", List.of("In Progress"), List.of(999), List.of());

		assertThat(WorkflowMapping.suggest(source, "in progress", target)).isEqualTo("In Progress");
	}

	@Test
	void fallsBackToTheSameHueWhenTheWorkflowWasMerelyRenamed() {
		// The whole point: "Vorstand" in English, "Ersti-Woche" in German.
		assertThat(WorkflowMapping.suggest(english(), "In Progress", german()))
				.isEqualTo("In Arbeit");
		assertThat(WorkflowMapping.suggest(english(), "Open", german())).isEqualTo("Neu");
		assertThat(WorkflowMapping.suggest(english(), "Done", german())).isEqualTo("Fertig");
	}

	@Test
	void keepsAResolvedStatusResolvedWhenNothingElseMatches() {
		Project source = english();
		Project target = project("X", List.of("Todo", "Doing", "Shipped"),
				List.of(11, 12, 13), List.of("Shipped"));

		assertThat(WorkflowMapping.suggest(source, "Done", target)).isEqualTo("Shipped");
	}

	@Test
	void keepsABacklogStatusInTheBacklog() {
		Project source = project("A", List.of("Backlog", "Doing"), List.of(11, 12), List.of());
		Project target = project("B", List.of("Todo", "Backlog", "Doing2"),
				List.of(21, 22, 23), List.of());

		assertThat(WorkflowMapping.suggest(source, "Backlog", target)).isEqualTo("Backlog");
	}

	@Test
	void fallsBackToThePositionOnlyWhenBothWorkflowsHaveTheSameShape() {
		Project source = project("A", List.of("One", "Two", "Three"),
				List.of(11, 12, 13), List.of());
		Project sameShape = project("B", List.of("Eins", "Zwei", "Drei"),
				List.of(21, 22, 23), List.of());
		assertThat(WorkflowMapping.suggest(source, "Two", sameShape)).isEqualTo("Zwei");

		// Different length: index 1 of the other workflow means nothing, so the
		// suggestion drops to the first working state rather than guessing.
		Project otherShape = project("C", List.of("Eins", "Zwei"), List.of(21, 22), List.of());
		assertThat(WorkflowMapping.suggest(source, "Three", otherShape)).isEqualTo("Eins");
	}

	@Test
	void fallbackSkipsBacklogAndResolvedStates() {
		Project source = project("A", List.of("Weird"), List.of(11), List.of());
		Project target = project("B", List.of("Backlog", "Working", "Done"),
				List.of(21, 22, 23), List.of("Done"));

		assertThat(WorkflowMapping.suggest(source, "Weird", target)).isEqualTo("Working");
	}

	@Test
	void returnsNullOnlyWhenTheTargetHasNoStatesAtAll() {
		Project empty = Project.builder().id("E").key("E").name("E")
				.workflowStates(new ArrayList<>()).resolvedStates(new ArrayList<>()).build();

		assertThat(WorkflowMapping.suggest(english(), "Open", empty)).isNull();
	}

	@Test
	void stateInColumnPicksTheStatusBelongingToTheCardsOwnProject() {
		// A merged column of a cross-project board offers one status per project.
		List<String> column = List.of("Open", "Neu");

		assertThat(WorkflowMapping.stateInColumn(german(), column)).isEqualTo("Neu");
		assertThat(WorkflowMapping.stateInColumn(english(), column)).isEqualTo("Open");
	}

	@Test
	void stateInColumnReturnsNullWhenTheProjectKnowsNoneOfTheColumnsStates() {
		assertThat(WorkflowMapping.stateInColumn(german(), List.of("Open", "Done"))).isNull();
	}
}
