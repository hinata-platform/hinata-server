package com.ahmadre.hinata.board;

import com.ahmadre.hinata.issue.IssueSearchText;
import com.ahmadre.hinata.project.Project;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;

import java.util.ArrayList;
import java.util.Collection;
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

	private final BoardScope scope = new BoardScope(AgileBoard.builder().name("Board").build(),
			List.of(Project.builder().id("p1").key("HIN").name("Hinata").build()), List.of(), Map.of());

	@Test
	void searchesTheLowerCaseSearchTextLiterally() {
		String sent = sent(BoardQuery.of("Login.Screen", null, null, null, null, null, null, null, null, null),
				lookingUp());

		assertThat(sent).contains("\"" + IssueSearchText.FIELD + "\"")
				.contains(Pattern.quote("login.screen").replace("\\", "\\\\"))
				.doesNotContain("\"title\"", "\"options\": \"i\"");
	}

	@Test
	void looksUpTheChildrenOfEpicsOnlyWhereSubTasksAreCards() {
		assertThat(sent(epicFilter("wall"), lookingUp())).as("a work item names its epic as its parent")
				.contains("\"parentId\": {\"$in\": [\"epic\"]}");

		List<String> indexes = new ArrayList<>();
		String subtasks = sent(epicFilter("subtasks"), new BoardCriteria.Lookup() {
			@Override
			public List<String> idsOf(Criteria criteria, String index) {
				indexes.add(index);
				return List.of("story");
			}

			@Override
			public Set<String> spellings(Collection<String> states) {
				return fail("an epic filter matches no state");
			}
		});

		assertThat(indexes).containsExactly("parent_number");
		// The epics and their children in one list, which the parent index bounds.
		assertThat(subtasks).contains("\"parentId\": {\"$in\": [\"epic\", \"story\"]}").doesNotContain("$or");
	}

	@Test
	void asksForAPersonInTheListOfAssigneesAlone() {
		assertThat(sent(BoardQuery.of(null, null, null, null, List.of("u1"), null, null, null, null, null), lookingUp()))
				.contains("\"assigneeIds\": {\"$in\": [\"u1\"]}")
				.doesNotContain("\"assigneeId\"", "$or");
	}

	@Test
	void asksForTheCardsInNoSprintAsTheSprintNullInTheSameList() {
		assertThat(sent(BoardQuery.of(null, null, null, null, null, null, null, List.of("s1", BoardQuery.NO_SPRINT),
				null, null), lookingUp()))
				.contains("\"sprintId\": {\"$in\": [\"s1\", null]}")
				.doesNotContain("$or");
	}

	@Test
	void matchesAStateInEverySpellingItsIssuesStoreItIn() {
		assertThat(BoardCriteria.spellings(List.of("In Progress"), Set.of("in PROGRESS", "Open")))
				.containsExactly("In Progress", "in PROGRESS");
		assertThat(sent(BoardQuery.of(null, List.of("in progress"), null, null, null, null, null, null, null, null),
				lookingUp("in PROGRESS", "Open"))).contains("\"state\": {\"$in\": [\"In Progress\", \"in PROGRESS\"]}");
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
		assertThat(index(BACKLOG, personUnderEpic, null)).isEqualTo("parent_number");
		assertThat(index(BoardCriteria.Place.sprint("s1"), personUnderEpic, null))
				.as("a sprint holds fewer cards than an epic's tree, which may span many sprints")
				.isEqualTo("board_by_sprint");
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

	private String sent(BoardQuery query, BoardCriteria.Lookup lookup) {
		return BoardCriteria.of(scope, BOARD, query, lookup).orElseThrow().getCriteriaObject().toJson();
	}

	/** Looks no issue up, and knows the states of the issues in the spellings [stored]. */
	private static BoardCriteria.Lookup lookingUp(String... stored) {
		return new BoardCriteria.Lookup() {
			@Override
			public List<String> idsOf(Criteria criteria, String index) {
				return fail("this read looks no issue up");
			}

			@Override
			public Set<String> spellings(Collection<String> states) {
				return BoardCriteria.spellings(states, Set.of(stored));
			}
		};
	}

	private static BoardQuery epicFilter(String shape) {
		return BoardQuery.of(null, null, null, null, null, null, null, null, List.of("epic"), shape);
	}
}
