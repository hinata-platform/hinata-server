package com.ahmadre.hinata.board;

import com.ahmadre.hinata.project.Project;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Column merging decides what a cross-project board actually looks like — and,
 * because the merged states are what a drop writes back, whether dragging a
 * card across projects can work at all.
 */
class BoardColumnsTest {

	private static Project project(String key, List<String> names, List<Integer> hues) {
		List<Project.WorkflowState> states = new ArrayList<>();
		for (int i = 0; i < names.size(); i++) {
			states.add(Project.WorkflowState.builder()
					.id(key + i)
					.name(names.get(i))
					.hue(hues.get(i))
					.build());
		}
		return Project.builder().id(key).key(key).name(key)
				.workflowStates(states)
				.resolvedStates(new ArrayList<>())
				.build();
	}

	@Test
	void singleProjectYieldsOneColumnPerState() {
		Project only = project("VOR", List.of("Open", "In Progress", "Done"),
				List.of(250, 70, 155));

		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(only));

		assertThat(columns).extracting(AgileBoard.Column::getName)
				.containsExactly("Open", "In Progress", "Done");
		assertThat(columns).allSatisfy(c -> assertThat(c.getStates()).hasSize(1));
	}

	@Test
	void mergesIdenticallyNamedStatesIntoOneColumn() {
		Project a = project("A", List.of("Open", "In Progress", "Done"), List.of(250, 70, 155));
		Project b = project("B", List.of("Open", "In Progress", "Done"), List.of(1, 2, 3));

		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(a, b));

		assertThat(columns).hasSize(3);
		assertThat(columns.get(1).getStates()).containsExactly("In Progress");
	}

	@Test
	void mergesTranslatedWorkflowsByHue() {
		// The motivating case: two projects with the same workflow, one renamed
		// to German. Six states must collapse into three columns.
		Project vorstand = project("VOR", List.of("Open", "In Progress", "Done"),
				List.of(250, 70, 155));
		Project ersti = project("ERSTI", List.of("Neu", "In Arbeit", "Fertig"),
				List.of(250, 70, 155));

		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(vorstand, ersti));

		assertThat(columns).hasSize(3);
		assertThat(columns.get(0).getStates()).containsExactly("Open", "Neu");
		assertThat(columns.get(1).getStates()).containsExactly("In Progress", "In Arbeit");
		assertThat(columns.get(2).getStates()).containsExactly("Done", "Fertig");
	}

	@Test
	void alignsBySharedPositionWhenNamesAndHuesBothDiffer() {
		Project a = project("A", List.of("One", "Two", "Three"), List.of(10, 20, 30));
		Project b = project("B", List.of("Eins", "Zwei", "Drei"), List.of(40, 50, 60));

		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(a, b));

		assertThat(columns).hasSize(3);
		assertThat(columns.get(0).getStates()).containsExactly("One", "Eins");
		assertThat(columns.get(2).getStates()).containsExactly("Three", "Drei");
	}

	@Test
	void appendsUnalignableStatesAsTheirOwnColumnsRatherThanDroppingThem() {
		Project a = project("A", List.of("Open", "Done"), List.of(250, 155));
		Project b = project("B", List.of("Open", "Review", "Done"), List.of(250, 300, 155));

		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(a, b));

		assertThat(columns).extracting(AgileBoard.Column::getName)
				.containsExactly("Open", "Done", "Review");
		assertThat(columns.get(2).getStates()).containsExactly("Review");
	}

	@Test
	void neverPutsTwoStatesOfTheSameProjectInOneColumn() {
		// Both of B's states share A's "Open" hue. Only the first may take that
		// slot: a column offering two states of the same project would make a
		// drop ambiguous, since the drop resolves the state by the card's project.
		Project a = project("A", List.of("Open", "Done"), List.of(250, 155));
		Project b = project("B", List.of("Neu", "Angefangen"), List.of(250, 250));

		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(a, b));

		assertThat(columns.get(0).getStates()).containsExactly("Open", "Neu");
		for (AgileBoard.Column column : columns) {
			long fromB = column.getStates().stream()
					.filter(s -> b.workflowStateNames().contains(s))
					.count();
			assertThat(fromB).isLessThanOrEqualTo(1);
		}
		// Every state of both projects still has a home somewhere.
		assertThat(columns.stream().flatMap(c -> c.getStates().stream()).toList())
				.containsExactlyInAnyOrder("Open", "Done", "Neu", "Angefangen");
	}

	@Test
	void huesComeFromTheFirstProjectThatKnowsAColumnsState() {
		Project a = project("A", List.of("Open"), List.of(250));
		Project b = project("B", List.of("Neu"), List.of(250));

		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(a, b));

		assertThat(BoardColumns.hues(columns, List.of(a, b))).containsEntry("Open", 250);
	}

	@Test
	void restrictToDropsColumnsOnlyBelongingToInvisibleProjects() {
		Project visible = project("A", List.of("Open", "Done"), List.of(250, 155));
		Project hidden = project("B", List.of("Open", "Review"), List.of(250, 300));
		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(visible, hidden));

		List<AgileBoard.Column> scoped = BoardColumns.restrictTo(columns, List.of(visible));

		assertThat(scoped).extracting(AgileBoard.Column::getName)
				.containsExactly("Open", "Done");
		// The hidden project's state is gone from the surviving column too.
		assertThat(scoped.get(0).getStates()).containsExactly("Open");
	}

	@Test
	void mergeOfNothingIsEmptyRatherThanAnError() {
		assertThat(BoardColumns.merge(List.of())).isEmpty();
		assertThat(BoardColumns.merge(null)).isEmpty();
	}
}
