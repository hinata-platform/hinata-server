package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.Suspension;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/** A suspended account is refused as suspended, not as deactivated, and told until when (HIN-172). */
class SignInRefusalSuspensionTest {

	private static User suspended(Instant until) {
		return User.builder().id("u-1").active(false).suspension(new Suspension(until, "case-1", "m-1", Instant.now()))
				.build();
	}

	@Test
	void aSuspensionComesBeforeTheDeactivationItIs() {
		assertThat(SignInRefusal.of(suspended(null))).contains(SignInRefusal.ACCOUNT_SUSPENDED);
		assertThat(SignInRefusal.of(User.builder().id("u-2").active(false).build()))
				.contains(SignInRefusal.ACCOUNT_DEACTIVATED);
	}

	@Test
	void theRefusalSaysUntilWhenInTheDetailsAndInWords() {
		Instant until = Instant.parse("2026-11-01T12:00:00Z");

		ApiException refused = SignInRefusal.ACCOUNT_SUSPENDED.refuse(suspended(until));

		assertThat(refused.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(refused.getMessageKey()).isEqualTo("error.auth.accountSuspended");
		assertThat(refused.getDetails()).containsEntry("code", "accountSuspended").containsEntry("until", until.toString());
		assertThat(refused.getArgs()).containsExactly(Date.from(until));
	}

	@Test
	void aSuspensionUntilFurtherNoticeHasItsOwnWords() {
		ApiException refused = SignInRefusal.ACCOUNT_SUSPENDED.refuse(suspended(null));

		assertThat(refused.getMessageKey()).isEqualTo("error.auth.accountSuspendedIndefinitely");
		assertThat(refused.getDetails()).containsEntry("code", "accountSuspended").doesNotContainKey("until");
	}
}
