package com.ahmadre.hinata.board;

import com.ahmadre.hinata.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How long the reads of a board may take, alone and within one request. */
class BoardTimeTest {

	@Test
	void aReadOutsideARequestTakesItsOwnLimit() {
		assertThat(BoardTime.left()).isEqualTo(BoardTime.MAX_READ_TIME);
	}

	@Test
	void aReadWithinARequestTakesNoMoreThanTheRequestHasLeft() {
		assertThat(BoardTime.within(Duration.ofSeconds(2), BoardTime::left))
				.isPositive().isLessThanOrEqualTo(Duration.ofSeconds(2));
		assertThat(BoardTime.within(Duration.ofMinutes(1), BoardTime::left)).isEqualTo(BoardTime.MAX_READ_TIME);
		// Reads within a request's reads share the request's time.
		assertThat(BoardTime.within(Duration.ofSeconds(2),
				() -> BoardTime.within(Duration.ofMinutes(1), BoardTime::left))).isLessThanOrEqualTo(Duration.ofSeconds(2));
		assertThat(BoardTime.left()).as("the time ends with the request").isEqualTo(BoardTime.MAX_READ_TIME);
	}

	@Test
	void aRequestOutOfTimeSaysTheServerIsBusy() {
		assertThatThrownBy(() -> BoardTime.within(Duration.ZERO, BoardTime::left))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(ex.getMessageKey()).isEqualTo("error.board.busy");
				});
		assertThat(BoardTime.left()).as("a request out of time leaves no time behind").isEqualTo(BoardTime.MAX_READ_TIME);
	}
}
