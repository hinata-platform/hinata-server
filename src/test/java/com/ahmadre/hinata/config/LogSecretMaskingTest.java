package com.ahmadre.hinata.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSecretMaskingTest {

	private static final String TOKEN = "q8Zr1xV0b3nB4mK_9pLd-2wQyTe5sAcHfJu7iGo6NkE";

	@Test
	@DisplayName("the feed token in a logged path is replaced, the rest of the line stays")
	void masksTheFeedToken() {
		assertThat(LogSecretMasking.mask("Securing GET /api/v1/shifts/feed/" + TOKEN + ".ics"))
				.isEqualTo("Securing GET /api/v1/shifts/feed/***.ics");
		assertThat(LogSecretMasking.mask("GET /api/v1/shifts/feed/" + TOKEN + "?x=1 and /shifts/feed/" + TOKEN))
				.doesNotContain(TOKEN).contains("?x=1");
	}

	@Test
	@DisplayName("lines without a feed path pass unchanged")
	void leavesOtherLinesAlone() {
		assertThat(LogSecretMasking.mask("Securing GET /api/v1/shifts/events")).isEqualTo(
				"Securing GET /api/v1/shifts/events");
		assertThat(LogSecretMasking.mask(null)).isNull();
	}

}
