package com.ahmadre.hinata.board;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A link to a board has to name the board. Before HIN-114 a search hit for a board or a sprint led
 * to the overview, and a sprint notification to the list of a project's boards.
 */
class BoardLinksTest {

	@Test
	void aBoardLinksToItsOwnPage() {
		assertThat(BoardLinks.of("6a6406a34ac0c09725692e2b")).isEqualTo("/boards/6a6406a34ac0c09725692e2b");
	}

	@Test
	void withoutABoardTheLinkLeadsToTheOverview() {
		assertThat(BoardLinks.of(null)).isEqualTo("/board");
		assertThat(BoardLinks.of(" ")).isEqualTo("/board");
	}
}
