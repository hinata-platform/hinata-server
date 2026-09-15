package com.ahmadre.hinata.board;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Whether a page is read at all: within the cards a query holds and within the reach of a page. */
class BoardReaderPageTest {

	@Test
	void readsAPageOnlyWithinTheCardsAndTheReachOfAPage() {
		assertThat(BoardReader.readsPage(0, 10, 30)).isTrue();
		assertThat(BoardReader.readsPage(30, 30, 30)).as("past the last card").isFalse();
		assertThat(BoardReader.readsPage(0, 10, 0)).as("a page of no cards").isFalse();
		assertThat(BoardReader.readsPage(BoardReader.MAX_OFFSET, 20_000, 30)).isTrue();
		assertThat(BoardReader.readsPage(BoardReader.MAX_OFFSET + 30, 20_000, 30)).as("beyond the reach").isFalse();
	}
}
