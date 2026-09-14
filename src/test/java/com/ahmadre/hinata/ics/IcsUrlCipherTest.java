package com.ahmadre.hinata.ics;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ahmadre.hinata.config.HinataProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IcsUrlCipherTest {

	private static final String URL =
			"https://calendar.google.com/calendar/ical/ada%40example.org/private-5ecr3t70k3n/basic.ics";

	private static final String SECRET = base64("0123456789abcdef0123456789abcdef");

	/** The record a value is stored in; any string the caller chooses. */
	private static final String ADA = "subscription-of-ada";

	@Test
	void withoutASecretNothingIsConfiguredAndNothingIsEncrypted() {
		for (String secret : new String[] { null, "", "   " }) {
			IcsUrlCipher cipher = new IcsUrlCipher(secret);
			assertThat(cipher.isConfigured()).isFalse();
			assertThatThrownBy(() -> cipher.encrypt(URL, ADA)).isInstanceOf(IllegalStateException.class);
		}
		// The property's own default is no secret. An instance that never set one
		// must not quietly encrypt under a key from this repository.
		assertThat(new IcsUrlCipher(new HinataProperties()).isConfigured()).isFalse();
	}

	@Test
	void aSecretThatIsTooShortOrNotBase64IsNoSecret() {
		assertThat(new IcsUrlCipher(base64("0123456789abcdef0123456789abcde")).isConfigured()).isFalse();
		assertThat(new IcsUrlCipher("not base64 at all!").isConfigured()).isFalse();
	}

	@Test
	void anAddressSurvivesTheRoundTripAndCannotBeReadAtRest() {
		IcsUrlCipher cipher = new IcsUrlCipher(SECRET);

		String stored = cipher.encrypt(URL, ADA);

		assertThat(stored).startsWith("v1:").doesNotContain("calendar.google.com").doesNotContain("5ecr3t70k3n");
		assertThat(cipher.decrypt(stored, ADA)).isEqualTo(URL);
	}

	@Test
	void aValueCopiedIntoSomebodyElsesRecordDoesNotDecryptThere() {
		IcsUrlCipher cipher = new IcsUrlCipher(SECRET);

		String stored = cipher.encrypt(URL, ADA);

		assertThatThrownBy(() -> cipher.decrypt(stored, "subscription-of-grace"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasNoCause();
	}

	@Test
	void everyValueGetsItsOwnNonce() {
		IcsUrlCipher cipher = new IcsUrlCipher(SECRET);

		String first = cipher.encrypt(URL, ADA);
		String second = cipher.encrypt(URL, ADA);

		assertThat(Arrays.copyOf(sealed(first), 12)).isNotEqualTo(Arrays.copyOf(sealed(second), 12));
		assertThat(cipher.decrypt(first, ADA)).isEqualTo(URL);
		assertThat(cipher.decrypt(second, ADA)).isEqualTo(URL);
	}

	@Test
	void aLongerSecretWorksAndSoDoesOneWrappedOverTwoLines() {
		// openssl rand -base64 64 breaks its output after 64 characters.
		String longer = base64("0123456789abcdef".repeat(4));
		String wrapped = longer.substring(0, 64) + "\n" + longer.substring(64);

		IcsUrlCipher cipher = new IcsUrlCipher(wrapped);

		assertThat(cipher.isConfigured()).isTrue();
		assertThat(cipher.decrypt(cipher.encrypt(URL, ADA), ADA)).isEqualTo(URL);
	}

	@Test
	void aValueThatWasTamperedWithOrWrittenUnderAnotherKeyIsRefusedWithoutQuotingIt() {
		IcsUrlCipher cipher = new IcsUrlCipher(SECRET);
		String stored = cipher.encrypt(URL, ADA);
		byte[] flipped = sealed(stored);
		flipped[flipped.length - 1] ^= 1;
		String tampered = "v1:" + Base64.getEncoder().encodeToString(flipped);
		String foreign = new IcsUrlCipher(base64("fedcba9876543210fedcba9876543210")).encrypt(URL, ADA);

		for (String unreadable : List.of(tampered, foreign, "v2:" + stored.substring(3), "v1:###", "v1:", URL)) {
			assertThatThrownBy(() -> cipher.decrypt(unreadable, ADA))
					.isInstanceOf(IllegalArgumentException.class)
					.hasNoCause()
					.satisfies(ex -> assertThat(ex.getMessage())
							.doesNotContain(unreadable)
							.doesNotContain("calendar.google.com"));
		}
	}

	@Test
	void theSecretNeverReachesTheLog() {
		String tooShort = base64("too short but still a secret");

		List<String> lines = captureLog(() -> new IcsUrlCipher(tooShort));

		assertThat(lines).isNotEmpty().noneMatch(line -> line.contains(tooShort));
	}

	private static String base64(String raw) {
		return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
	}

	private static byte[] sealed(String stored) {
		return Base64.getDecoder().decode(stored.substring(3));
	}

	private static List<String> captureLog(Runnable action) {
		Logger logger = (Logger) LoggerFactory.getLogger(IcsUrlCipher.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			action.run();
			return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		}
		finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}
}
