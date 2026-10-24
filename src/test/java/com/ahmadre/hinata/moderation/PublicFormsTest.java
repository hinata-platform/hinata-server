package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.config.HinataProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The token of the form for reports without an account (HIN-175): made here, for one address, at
 * least three seconds and at most two hours old, good once.
 */
class PublicFormsTest {

	private final MovableClock clock = new MovableClock(Instant.parse("2026-10-24T10:00:00Z"));
	private final MongoTemplate mongo = mock(MongoTemplate.class);
	private final PublicForms forms = new PublicForms(mongo, clock, new HinataProperties());

	@Test
	void aTokenIsGoodForItsAddressOnceItIsOldEnough() {
		String token = forms.issue("198.51.100.7");

		assertThat(forms.check(token, "198.51.100.7")).isEqualTo(PublicForms.Check.TOO_YOUNG);
		clock.advance(Duration.ofSeconds(4));
		assertThat(forms.check(token, "198.51.100.7")).isEqualTo(PublicForms.Check.GOOD);
		assertThat(forms.check(token, "203.0.113.9")).as("another address").isEqualTo(PublicForms.Check.INVALID);
		clock.advance(Duration.ofHours(2));
		assertThat(forms.check(token, "198.51.100.7")).as("too old").isEqualTo(PublicForms.Check.INVALID);
	}

	@Test
	void aTokenUsedOnceIsNoGoodAgain() {
		String token = forms.issue("198.51.100.7");
		clock.advance(Duration.ofSeconds(4));
		when(mongo.exists(any(Query.class), eq(PublicFormToken.class))).thenReturn(true);

		assertThat(forms.check(token, "198.51.100.7")).isEqualTo(PublicForms.Check.INVALID);
	}

	@Test
	void whatTheServerDidNotMakeIsNoToken() {
		assertThat(forms.check(null, "198.51.100.7")).isEqualTo(PublicForms.Check.INVALID);
		assertThat(forms.check("not-a-token", "198.51.100.7")).isEqualTo(PublicForms.Check.INVALID);
		String token = forms.issue("198.51.100.7");
		String forged = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
		clock.advance(Duration.ofSeconds(4));
		assertThat(forms.check(forged, "198.51.100.7")).isEqualTo(PublicForms.Check.INVALID);
	}

	@Test
	void anAddressHashesTheSameAndSaysNothingOfItself() {
		assertThat(forms.ipHash("198.51.100.7")).isEqualTo(forms.ipHash("198.51.100.7"))
				.isNotEqualTo(forms.ipHash("198.51.100.8")).doesNotContain("198");
	}

	/** A clock the test moves on by hand. */
	static final class MovableClock extends Clock {

		private Instant now;

		MovableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration by) {
			now = now.plus(by);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
