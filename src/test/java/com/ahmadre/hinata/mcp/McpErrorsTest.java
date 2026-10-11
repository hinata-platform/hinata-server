package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.UserWordsFixture;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A refusal reaches an agent as a sentence in its holder's language, with the facts behind it. */
class McpErrorsTest {

	private final McpErrors errors = new McpErrors(UserWordsFixture.real());

	@Test
	void aKeyBecomesTheSentenceInTheCallersLanguage() {
		User german = User.builder().id("u1").locale("de").build();

		assertThatThrownBy(() -> errors.readable(german, () -> {
			throw ApiException.conflict("error.time.timerAlreadyRunning");
		})).isInstanceOf(McpErrors.Refusal.class).hasMessage("Es läuft bereits ein Timer");
	}

	/** The framework prints the innermost cause's message; with a cause that would be the key. */
	@Test
	void theRefusalCarriesNoCause() {
		User me = User.builder().id("u1").build();

		assertThatThrownBy(() -> errors.readable(me, () -> {
			throw ApiException.notFound("workItem");
		})).hasNoCause().hasMessage("Work item not found");
	}

	@Test
	void theFactsAClientActsOnComeFirst() {
		User me = User.builder().id("u1").build();
		ApiException locked = new ApiException(HttpStatus.FORBIDDEN, "error.mcp.badValue",
				Map.of("periodEnd", "2026-09-30", "remedy", "withdraw", "approvalId", "a1", "reason", "approval",
						"holder", "approver", "blank", " "), "date");

		assertThat(errors.describe(me, locked)).isEqualTo("That is not a value date accepts "
				+ "(reason: approval; holder: approver; remedy: withdraw; approvalId: a1; periodEnd: 2026-09-30)");
	}

	@Test
	void nothingIsRefusedThatIsWithinTheLimits() {
		User me = User.builder().id("u1").build();

		errors.requireLength(null, 5, "note", me);
		errors.requireLength("12345", 5, "note", me);
		errors.requireRange(null, 1, 3, "n", me);
		errors.requireRange(3, 1, 3, "n", me);
		errors.requireValues(null, 1, 1, "tags", me);
		errors.requireEnabled(true, me);
		assertThat(errors.readable(me, () -> "ok")).isEqualTo("ok");
	}
}
