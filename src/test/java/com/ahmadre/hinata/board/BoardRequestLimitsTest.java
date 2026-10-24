package com.ahmadre.hinata.board;

import com.ahmadre.hinata.git.GitController;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Texts a board, a sprint and a repository connection store are bounded before they are stored
 * (HIN-26, HIN-167), and the routes run the checks: without {@code @Valid} on the body the limits
 * on a record are only words.
 */
class BoardRequestLimitsTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void aSprintGoalHoldsTwoThousandCharacters() {
		assertThat(validator.validate(new BoardController.SprintRequest("Sprint 1", "g".repeat(2001), null, null)))
				.extracting(v -> v.getPropertyPath().toString()).containsExactly("goal");
		assertThat(validator.validate(new BoardController.SprintRequest("Sprint 1", "g".repeat(2000), null, null)))
				.isEmpty();
	}

	@Test
	void theColumnsOfABoardUpdateAreCheckedOneByOne() throws Exception {
		BoardController.UpdateBoardRequest update = new BoardController.UpdateBoardRequest(null, null, null, null,
				List.of(new BoardController.ColumnRequest("c".repeat(61), List.of(), null)), null);

		assertThat(validator.validate(update))
				.extracting(v -> v.getPropertyPath().toString()).containsExactly("columns[0].name");
		assertThat(BoardController.class.getMethod("update", String.class, BoardController.UpdateBoardRequest.class)
				.getParameters()[1].isAnnotationPresent(Valid.class)).isTrue();
	}

	@Test
	void aBranchTemplateHoldsTwoHundredCharacters() {
		assertThat(validator.validate(new GitController.BranchTemplateRequest("b".repeat(201))))
				.extracting(v -> v.getPropertyPath().toString()).containsExactly("branchTemplate");
		assertThat(validator.validate(new GitController.BranchTemplateRequest("b".repeat(200)))).isEmpty();
	}
}
