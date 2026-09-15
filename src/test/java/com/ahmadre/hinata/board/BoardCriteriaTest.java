package com.ahmadre.hinata.board;

import com.ahmadre.hinata.issue.IssueSearchText;
import com.ahmadre.hinata.project.Project;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** The criteria of a board read and the index it names, without a database. */
class BoardCriteriaTest {

	private final BoardScope scope = new BoardScope(AgileBoard.builder().name("Board").build(),
			List.of(Project.builder().id("p1").key("HIN").name("Hinata").build()), List.of(), Map.of());

	@Test
	void searchesTheLowerCaseSearchTextLiterally() {
		Criteria criteria = BoardCriteria.of(scope, BoardCriteria.Place.BOARD,
				BoardQuery.of("Login.Screen", null, null, null, null, null, null, null, null, null),
				lookup -> fail("a search looks nothing up")).orElseThrow();

		String sent = criteria.getCriteriaObject().toJson();
		assertThat(sent).contains("\"" + IssueSearchText.FIELD + "\"")
				.contains(Pattern.quote("login.screen").replace("\\", "\\\\"))
				.doesNotContain("\"title\"", "\"options\": \"i\"");
	}

	@Test
	void looksUpTheChildrenOfEpicsOnlyWhereSubTasksAreCards() {
		List<Criteria> lookups = new ArrayList<>();
		BoardCriteria.of(scope, BoardCriteria.Place.BOARD, epicFilter("wall"), lookup -> {
			lookups.add(lookup);
			return List.of();
		});
		assertThat(lookups).as("a work item names its epic as its parent").isEmpty();

		BoardCriteria.of(scope, BoardCriteria.Place.BOARD, epicFilter("subtasks"), lookup -> {
			lookups.add(lookup);
			return List.of("story");
		});
		assertThat(lookups).hasSize(1);
	}

	@Test
	void namesTheIndexOfEveryRead() {
		assertThat(BoardCriteria.index(BoardCriteria.Place.BOARD, BoardQuery.ALL, null)).isEqualTo("board_column");
		assertThat(BoardCriteria.index(BoardCriteria.Place.sprint("s1"), BoardQuery.ALL, null))
				.isEqualTo("board_sprint");
		assertThat(BoardCriteria.index(BoardCriteria.Place.BACKLOG, BoardQuery.ALL, null)).isEqualTo("board_sprint");
		assertThat(BoardCriteria.index(BoardCriteria.Place.BOARD, BoardQuery.all(BoardQuery.Shape.TIMELINE), false))
				.isEqualTo("board_timeline");
		// The sub-tasks of a sprint come in by two ways, and the planner combines them.
		assertThat(BoardCriteria.index(BoardCriteria.Place.sprint("s1"), BoardQuery.all(BoardQuery.Shape.SUBTASKS),
				null)).isNull();
	}

	private static BoardQuery epicFilter(String shape) {
		return BoardQuery.of(null, null, null, null, null, null, null, null, List.of("epic"), shape);
	}
}
