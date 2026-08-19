package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The budget that keeps one agent's loop over an issue's files from spending the
 * server's cores — every read behind it decodes an image or parses a PDF. What
 * matters here is that it is keyed by caller: the {@code /mcp} endpoint's own
 * limiter is keyed by IP, which several clients, and everyone behind one proxy,
 * share.
 */
class AttachmentReadLimiterTest {

	private static final int PER_MINUTE = 3;

	private HinataProperties properties;
	private AttachmentReadLimiter limiter;

	@BeforeEach
	void setUp() {
		properties = new HinataProperties();
		properties.getMcp().setAttachmentReadsPerMinute(PER_MINUTE);
		limiter = new AttachmentReadLimiter(properties);
	}

	@Test
	void aCallerOverBudgetIsToldTheBudgetItIsOver() {
		for (int i = 0; i < PER_MINUTE; i++) {
			limiter.require("u1");
		}

		assertThatThrownBy(() -> limiter.require("u1"))
				.isInstanceOfSatisfying(ApiException.class, refused -> {
					assertThat(refused.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
					assertThat(refused.getMessageKey()).isEqualTo("error.mcp.attachmentRateLimited");
					// The number is what makes the message actionable rather than a wall.
					assertThat(refused.getArgs()).containsExactly(PER_MINUTE);
				});
	}

	@Test
	void oneCallerCannotSpendAnothersBudget() {
		for (int i = 0; i < PER_MINUTE; i++) {
			limiter.require("u1");
		}

		assertThatThrownBy(() -> limiter.require("u1")).isInstanceOf(ApiException.class);
		assertThatCode(() -> limiter.require("u2")).doesNotThrowAnyException();
	}

	@Test
	void theBudgetIsWhateverTheServerIsConfiguredFor() {
		// An operator turning this down during an incident is the point of the
		// property; a bucket sized from a constant would ignore them.
		properties.getMcp().setAttachmentReadsPerMinute(1);

		limiter.require("u1");

		assertThatThrownBy(() -> limiter.require("u1")).isInstanceOf(ApiException.class);
	}
}
