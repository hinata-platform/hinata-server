package com.ahmadre.hinata.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Addresses people are sent to from public pages and mails: https with a host, or nothing. */
class HttpsUrlTest {

	private final HttpsUrl.Validator validator = new HttpsUrl.Validator();

	@Test
	void takesAnHttpsAddressOrNothing() {
		assertThat(validator.isValid(null, null)).isTrue();
		assertThat(validator.isValid(" ", null)).isTrue();
		assertThat(validator.isValid("https://example.org/impressum", null)).isTrue();
		assertThat(validator.isValid("HTTPS://Example.org", null)).isTrue();
	}

	@Test
	void refusesEverythingElse() {
		assertThat(validator.isValid("http://example.org/impressum", null)).isFalse();
		assertThat(validator.isValid("javascript:alert(1)", null)).isFalse();
		assertThat(validator.isValid("//example.org/impressum", null)).isFalse();
		assertThat(validator.isValid("example.org/impressum", null)).isFalse();
		assertThat(validator.isValid("https://user:pass@example.org", null)).isFalse();
		assertThat(validator.isValid("https://exa mple.org", null)).isFalse();
	}
}
