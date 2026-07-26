package com.ahmadre.hinata.board;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
	void keepsUnalignableStatesAsTheirOwnColumnWhereTheProjectPutsThem() {
		Project a = project("A", List.of("Open", "Done"), List.of(250, 155));
		Project b = project("B", List.of("Open", "Review", "Done"), List.of(250, 300, 155));

		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(a, b));

		// B says Review sits between Open and Done — that is where it belongs,
		// not appended after everything the first project happens to know.
		assertThat(columns).extracting(AgileBoard.Column::getName)
				.containsExactly("Open", "Review", "Done");
		assertThat(columns.get(1).getStates()).containsExactly("Review");
	}

	@Test
	void doesNotMergeTwoStatesThatMerelyKeptTheDefaultHue() {
		// The reported bug, in the shape it actually occurred: both projects gained
		// an extra status in project settings, and a new status inherits the neutral
		// default hue (250) that "Open" already carries. Reading identity into that
		// hue put A's trailing status and B's leading one in one column — two steps
		// that share nothing but an unpicked colour.
		Project a = project("A",
				List.of("Open", "In Progress", "In Parking", "In Review", "Done", "Signed Off"),
				List.of(250, 70, 255, 300, 155, 250));
		Project b = project("B",
				List.of("Intake", "Open", "In Progress", "In Parking", "In Review", "Done"),
				List.of(250, 250, 70, 255, 300, 155));

		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(a, b));

		assertThat(columns).extracting(AgileBoard.Column::getName)
				.containsExactly("Intake", "Open", "In Progress", "In Parking", "In Review",
						"Done", "Signed Off");
		assertThat(columns.get(0).getStates()).containsExactly("Intake");
		assertThat(columns.get(6).getStates()).containsExactly("Signed Off");
	}

	@Test
	void stillMergesByHueWhileTheColourIdentifiesOneStatePerSide() {
		// The guard above must not cost the rung its purpose: a hue carried by a
		// single state on each side is still the reason a translated workflow lines
		// up, even where a second pair happens to share a different colour.
		Project a = project("A", List.of("Open", "In Progress", "Done"), List.of(250, 70, 155));
		Project b = project("B", List.of("Neu", "In Arbeit", "Fertig"), List.of(250, 70, 155));

		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(a, b));

		assertThat(columns).hasSize(3);
		assertThat(columns.get(1).getStates()).containsExactly("In Progress", "In Arbeit");
	}

	// --- ordering ------------------------------------------------------------

	@Test
	void putsALeadingStateOfTheSecondProjectFirst() {
		// The reported shape: B's workflow starts one step earlier than A's. That
		// step belongs first, and nothing about its *name* says so — only B's
		// sequence does.
		Project a = project("A", List.of("Open", "In Progress", "In Parking", "In Review", "Done"),
				List.of(250, 70, 255, 300, 155));
		Project b = project("B",
				List.of("Intake", "Open", "In Progress", "In Parking", "In Review", "Done"),
				List.of(200, 250, 70, 255, 300, 155));

		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(a, b));

		assertThat(columns).extracting(AgileBoard.Column::getName)
				.containsExactly("Intake", "Open", "In Progress", "In Parking", "In Review", "Done");
	}

	@Test
	void ordersByEveryProjectsSequenceNotJustTheFirstOnes() {
		Project a = project("A", List.of("Open", "Done"), List.of(250, 155));
		Project b = project("B", List.of("Triage", "Open", "Building", "Done"),
				List.of(200, 250, 70, 155));

		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(a, b));

		assertThat(columns).extracting(AgileBoard.Column::getName)
				.containsExactly("Triage", "Open", "Building", "Done");
	}

	@Test
	void breaksATieBetweenTwoExtrasInFavourOfTheFirstProject() {
		// Nothing orders X against Y: each sits between Open and Done, in a
		// different project, at the same position. The board's first project
		// decides, so the layout is stable rather than arbitrary.
		Project a = project("A", List.of("Open", "X", "Done"), List.of(250, 20, 155));
		Project b = project("B", List.of("Open", "Y", "Done", "Extra"),
				List.of(250, 40, 155, 90));

		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(a, b));

		assertThat(columns).extracting(AgileBoard.Column::getName)
				.containsExactly("Open", "X", "Y", "Done", "Extra");
	}

	@Test
	void keepsEveryColumnWhenTwoProjectsContradictEachOther() {
		// A says Review before Done, B says the opposite — there is no order that
		// satisfies both. Losing a column would be far worse than an odd one.
		Project a = project("A", List.of("Open", "Review", "Done"), List.of(250, 300, 155));
		Project b = project("B", List.of("Open", "Done", "Review"), List.of(1, 2, 3));

		List<AgileBoard.Column> columns = BoardColumns.merge(List.of(a, b));

		assertThat(columns).extracting(AgileBoard.Column::getName)
				.containsExactlyInAnyOrder("Open", "Review", "Done");
		assertThat(columns).allSatisfy(column -> assertThat(column.getStates()).isNotEmpty());
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

	// --- manual layouts ------------------------------------------------------

	private static AgileBoard.Column column(String name, String... states) {
		return AgileBoard.Column.builder()
				.name(name)
				.states(new ArrayList<>(List.of(states)))
				.build();
	}

	@Test
	void reconcileGivesAStateAddedLaterItsOwnColumn() {
		Project a = project("A", List.of("Open", "In Review", "Done"), List.of(250, 300, 155));
		// A layout made when "In Review" did not exist yet.
		List<AgileBoard.Column> stored = new ArrayList<>(List.of(
				column("Todo", "Open"), column("Fertig", "Done")));

		List<AgileBoard.Column> live = BoardColumns.reconcile(stored, List.of(a));

		// The hand-made names survive, and the new status gets a home rather than
		// silently taking its issues off the board.
		assertThat(live).extracting(AgileBoard.Column::getName)
				.containsExactly("Todo", "Fertig", "In Review");
	}

	@Test
	void reconcileDropsAStateNoProjectDefinesAnymore() {
		Project a = project("A", List.of("Open", "Done"), List.of(250, 155));
		List<AgileBoard.Column> stored = new ArrayList<>(List.of(
				column("Todo", "Open", "Gelöscht"), column("Fertig", "Done")));

		List<AgileBoard.Column> live = BoardColumns.reconcile(stored, List.of(a));

		assertThat(live.get(0).getStates()).containsExactly("Open");
	}

	@Test
	void reconcileKeepsWipLimitsOfTheStoredLayout() {
		Project a = project("A", List.of("Open", "Done"), List.of(250, 155));
		AgileBoard.Column limited = column("Todo", "Open");
		limited.setWipLimit(3);
		List<AgileBoard.Column> stored = new ArrayList<>(List.of(limited, column("Fertig", "Done")));

		List<AgileBoard.Column> live = BoardColumns.reconcile(stored, List.of(a));

		assertThat(live.get(0).getWipLimit()).isEqualTo(3);
	}

	@Test
	void validateAcceptsALayoutThatCoversEveryStateOnce() {
		Project a = project("A", List.of("Open", "Done"), List.of(250, 155));
		Project b = project("B", List.of("Neu", "Fertig"), List.of(1, 2));

		assertThatCode(() -> BoardColumns.validate(
				List.of(column("Los", "Open", "Neu"), column("Erledigt", "Done", "Fertig")),
				List.of(a, b))).doesNotThrowAnyException();
	}

	@Test
	void validateRejectsAStateWithoutAColumn() {
		Project a = project("A", List.of("Open", "Done"), List.of(250, 155));

		// Leaving a status out would hide its issues from the board entirely.
		assertThatThrownBy(() -> BoardColumns.validate(List.of(column("Los", "Open")), List.of(a)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.board.statesUnassigned");
	}

	@Test
	void validateRejectsTwoStatesOfOneProjectInOneColumn() {
		Project a = project("A", List.of("Open", "Done"), List.of(250, 155));

		// The drop resolves the state from the card's own project — two candidates
		// in one column would leave it no way to choose.
		assertThatThrownBy(() -> BoardColumns.validate(
				List.of(column("Alles", "Open", "Done")), List.of(a)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.board.ambiguousColumn");
	}

	@Test
	void validateRejectsAStateNoProjectKnows() {
		Project a = project("A", List.of("Open", "Done"), List.of(250, 155));

		assertThatThrownBy(() -> BoardColumns.validate(
				List.of(column("Los", "Open"), column("Erledigt", "Done", "Erfunden")), List.of(a)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.board.unknownState");
	}

	@Test
	void validateRejectsTheSameStateInTwoColumns() {
		Project a = project("A", List.of("Open", "Done"), List.of(250, 155));

		assertThatThrownBy(() -> BoardColumns.validate(
				List.of(column("Los", "Open"), column("Auch los", "Open"), column("Fertig", "Done")),
				List.of(a)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.board.duplicateState");
	}

	@Test
	void validateRejectsAnEmptyLayout() {
		Project a = project("A", List.of("Open"), List.of(250));

		assertThatThrownBy(() -> BoardColumns.validate(List.of(), List.of(a)))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.board.noColumns");
	}
}
