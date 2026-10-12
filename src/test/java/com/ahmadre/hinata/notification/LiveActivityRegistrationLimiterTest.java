package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A person registers a handful of Live Activities an hour; a loop meets a 429. */
class LiveActivityRegistrationLimiterTest {

	@Test
	void theRegistrationAfterTheHoursBudgetIsRefused_andOnlyForThatPerson() {
		HinataProperties properties = new HinataProperties();
		LiveActivityRegistrationLimiter limiter = new LiveActivityRegistrationLimiter(properties);
		int perHour = properties.getRateLimit().getLiveActivityRegistrationsPerHour();
		assertThat(perHour).isEqualTo(20);

		for (int i = 0; i < perHour; i++) {
			limiter.consume("user-1");
		}

		assertThatThrownBy(() -> limiter.consume("user-1")).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
			assertThat(ex.getMessageKey()).isEqualTo("error.rateLimited");
		});
		assertThatCode(() -> limiter.consume("user-2")).doesNotThrowAnyException();
	}
}
