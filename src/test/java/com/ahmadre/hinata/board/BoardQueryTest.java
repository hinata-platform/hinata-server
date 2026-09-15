package com.ahmadre.hinata.board;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoardQueryTest {

	@Test
	void readsEveryFacetTrimmedAndWithoutBlanks() {
		BoardQuery query = BoardQuery.of("  login ", List.of("Open", " ", "Done"), List.of("bug", "TASK"),
				List.of("major"), List.of("u1"), null, List.of("ui"), List.of("s1", "__none__"), List.of("e1"),
				"subtasks");

		assertThat(query.text()).isEqualTo("login");
		assertThat(query.hasText()).isTrue();
		assertThat(query.states()).containsExactlyInAnyOrder("Open", "Done");
		assertThat(query.types()).containsExactlyInAnyOrder(Issue.Type.BUG, Issue.Type.TASK);
		assertThat(query.priorities()).containsExactly(Issue.Priority.MAJOR);
		assertThat(query.assigneeIds()).containsExactly("u1");
		assertThat(query.reporterIds()).isEmpty();
		assertThat(query.labels()).containsExactly("ui");
		assertThat(query.sprintIds()).containsExactly("s1");
		assertThat(query.noSprint()).isTrue();
		assertThat(query.epicIds()).containsExactly("e1");
		assertThat(query.shape()).isEqualTo(BoardQuery.Shape.SUBTASKS);
	}

	@Test
	void withoutParametersIsTheWholeWall() {
		BoardQuery query = BoardQuery.of(null, null, null, null, null, null, null, null, null, " ");

		assertThat(query).isEqualTo(BoardQuery.ALL);
		assertThat(query.hasText()).isFalse();
	}

	@Test
	void refusesWhatNoBoardSends() {
		assertRefused(() -> BoardQuery.of("x".repeat(101), null, null, null, null, null, null, null, null, null));
		assertRefused(() -> BoardQuery.of(null, Collections.nCopies(51, "Open"), null, null, null, null, null, null,
				null, null));
		assertRefused(() -> BoardQuery.of(null, null, null, null, null, null, List.of("x".repeat(201)), null, null,
				null));
		assertRefused(() -> BoardQuery.of(null, null, List.of("STORYBOARD"), null, null, null, null, null, null,
				null));
		assertRefused(() -> BoardQuery.of(null, null, null, List.of("urgent"), null, null, null, null, null, null));
		assertRefused(() -> BoardQuery.of(null, null, null, null, null, null, null, null, null, "kanban"));
	}

	@Test
	void takesTheLongestTextAndListItAllows() {
		BoardQuery query = BoardQuery.of("x".repeat(100), Collections.nCopies(50, "Open"), null, null, null, null,
				List.of("y".repeat(200)), null, null, null);

		assertThat(query.text()).hasSize(100);
		assertThat(query.states()).containsExactly("Open");
		assertThat(query.labels()).hasSize(1);
	}

	private static void assertRefused(ThrowingCallable read) {
		assertThatThrownBy(read).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.getMessageKey()).isEqualTo("error.validationFailed"));
	}
}
