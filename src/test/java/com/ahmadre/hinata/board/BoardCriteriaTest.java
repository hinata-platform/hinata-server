package com.ahmadre.hinata.board;

import com.ahmadre.hinata.issue.IssueSearchText;
import com.ahmadre.hinata.project.Project;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.ahmadre.hinata.board.BoardCriteria.Place.BACKLOG;
import static com.ahmadre.hinata.board.BoardCriteria.Place.BOARD;
import static com.ahmadre.hinata.board.BoardCriteria.index;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** The criteria of a board read and the index it names, without a database. */
class BoardCriteriaTest {

	private static final BoardCriteria.IdLookup NOTHING_TO_LOOK_UP =
			(criteria, index) -> fail("this read looks nothing up");

	private final BoardScope scope = new BoardScope(AgileBoard.builder().name("Board").build(),
			List.of(Project.builder().id("p1").key("HIN").name("Hinata").build()), List.of(), Map.of(), Set::of);

	@Test
	void searchesTheLowerCaseSearchTextLiterally() {
		String sent = sent(scope, BoardQuery.of("Login.Screen", null, null, null, null, null, null, null, null, null));

		assertThat(sent).contains("\"" + IssueSearchText.FIELD + "\"")
				.contains(Pattern.quote("login.screen").replace("\\", "\\\\"))
				.doesNotContain("\"title\"", "\"options\": \"i\"");
	}

	@Test
	void looksUpTheChildrenOfEpicsOnlyWhereSubTasksAreCards() {
		assertThat(sent(scope, epicFilter("wall"))).as("a work item names its epic as its parent")
				.contains("\"parentId\": {\"$in\": [\"epic\"]}");

		List<String> indexes = new ArrayList<>();
		String subtasks = BoardCriteria.of(scope, BOARD, epicFilter("subtasks"), (lookup, index) -> {
			indexes.add(index);
			return List.of("story");
		}).orElseThrow().getCriteriaObject().toJson();

		assertThat(indexes).containsExactly("parent_number");
		// The epics and their children in one list, which the parent index bounds.
		assertThat(subtasks).contains("\"parentId\": {\"$in\": [\"epic\", \"story\"]}").doesNotContain("$or");
	}

	@Test
	void asksForAPersonInTheListOfAssigneesAlone() {
		assertThat(sent(scope, BoardQuery.of(null, null, null, null, List.of("u1"), null, null, null, null, null)))
				.contains("\"assigneeIds\": {\"$in\": [\"u1\"]}")
				.doesNotContain("\"assigneeId\"", "$or");
	}

	@Test
	void asksForTheCardsInNoSprintAsTheSprintNullInTheSameList() {
		assertThat(sent(scope, BoardQuery.of(null, null, null, null, null, null, null,
				List.of("s1", BoardQuery.NO_SPRINT), null, null)))
				.contains("\"sprintId\": {\"$in\": [\"s1\", null]}")
				.doesNotContain("$or");
	}

	@Test
	void matchesAStateInEverySpellingItsIssuesStoreItIn() {
		BoardScope spelled = new BoardScope(scope.board(), scope.projects(), List.of(), Map.of(),
				() -> Set.of("in PROGRESS", "Open"));

		assertThat(spelled.spellings(List.of("In Progress"))).containsExactly("In Progress", "in PROGRESS");
		assertThat(sent(spelled, BoardQuery.of(null, List.of("in progress"), null, null, null, null, null, null, null,
				null))).contains("\"state\": {\"$in\": [\"In Progress\", \"in PROGRESS\"]}");
	}

	@Test
	void namesTheIndexOfEveryPlace() {
		assertThat(index(BOARD, BoardQuery.ALL, null)).isEqualTo("board_by_state");
		assertThat(index(BoardCriteria.Place.sprint("s1"), BoardQuery.ALL, null)).isEqualTo("board_by_sprint");
		assertThat(index(BACKLOG, BoardQuery.ALL, null)).isEqualTo("board_by_sprint");
		assertThat(index(BOARD, BoardQuery.all(BoardQuery.Shape.TIMELINE), false)).isEqualTo("board_by_dates");
		// The sub-tasks of a sprint come in by two ways, and the planner combines them.
		assertThat(index(BoardCriteria.Place.sprint("s1"), BoardQuery.all(BoardQuery.Shape.SUBTASKS), null)).isNull();
	}

	@Test
	void namesTheIndexOfTheFilterThatKeepsTheFewestCards() {
		BoardQuery person = BoardQuery.of(null, null, null, null, List.of("u1"), null, null, null, null, null);
		BoardQuery reporter = BoardQuery.of(null, null, null, null, null, List.of("u1"), null, null, null, null);
		BoardQuery label = BoardQuery.of(null, null, null, null, null, null, List.of("ui"), null, null, null);
		BoardQuery inSprint = BoardQuery.of(null, null, null, null, null, null, null, List.of("s1"), null, null);
		BoardQuery personUnderEpic = BoardQuery.of(null, null, null, null, List.of("u1"), null, null, null,
				List.of("epic"), null);

		assertThat(index(BOARD, personUnderEpic, null)).isEqualTo("parent_number");
		assertThat(index(BoardCriteria.Place.sprint("s1"), person, null)).as("a sprint holds fewer cards than a person")
				.isEqualTo("board_by_sprint");
		assertThat(index(BOARD, person, null)).isEqualTo("board_by_assignee");
		assertThat(index(BACKLOG, person, null)).isEqualTo("board_by_assignee");
		assertThat(index(BOARD, reporter, true)).isEqualTo("board_by_reporter");
		assertThat(index(BOARD, label, null)).isEqualTo("board_by_label");
		assertThat(index(BOARD, inSprint, null)).isEqualTo("board_by_sprint");
		assertThat(index(BOARD, inSprint, true)).as("the dates keep the timeline's order").isEqualTo("board_by_dates");
		assertThat(index(BoardCriteria.Place.sprint("s1"), BoardQuery.all(BoardQuery.Shape.TIMELINE), true))
				.isEqualTo("board_by_sprint");
	}

	private static String sent(BoardScope scope, BoardQuery query) {
		return BoardCriteria.of(scope, BOARD, query, NOTHING_TO_LOOK_UP).orElseThrow().getCriteriaObject().toJson();
	}

	private static BoardQuery epicFilter(String shape) {
		return BoardQuery.of(null, null, null, null, null, null, null, null, List.of("epic"), shape);
	}
}
