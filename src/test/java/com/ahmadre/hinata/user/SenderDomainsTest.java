package com.ahmadre.hinata.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The sender domain a person trusts with images (HIN-181). */
class SenderDomainsTest {

	@Test
	void normalize_lowerCasesAndSpellsAnInternationalNameInAscii() {
		assertThat(SenderDomains.normalize(" Vodafone.COM. ")).isEqualTo("vodafone.com");
		assertThat(SenderDomains.normalize("Bücher.example")).isEqualTo("xn--bcher-kva.example");
		assertThat(SenderDomains.normalize("xn--bcher-kva.example")).isEqualTo("xn--bcher-kva.example");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "localhost", "vodafone..com", "-bad.example", "bad-.example", "a b.example",
			"evil.example/path", "user@example.org", "*.example.org" })
	void normalize_refusesWhatIsNoDomainName(String domain) {
		assertThat(SenderDomains.normalize(domain)).isNull();
	}

	@Test
	void normalize_refusesANameLongerThanDnsAllows() {
		assertThat(SenderDomains.normalize(("a".repeat(60) + ".").repeat(5) + "example")).isNull();
	}

	@Test
	void ofAddress_takesTheHostAfterTheLastAt() {
		assertThat(SenderDomains.ofAddress("nicht.antworten@KundenService.Vodafone.com"))
				.isEqualTo("kundenservice.vodafone.com");
		assertThat(SenderDomains.ofAddress("unknown")).isNull();
	}

	@Test
	void covers_trustsSubdomainsOfATrustedDomainOnly() {
		List<String> trusted = List.of("vodafone.com");

		assertThat(SenderDomains.covers(trusted, "vodafone.com")).isTrue();
		assertThat(SenderDomains.covers(trusted, "kundenservice.vodafone.com")).isTrue();
		assertThat(SenderDomains.covers(trusted, "evilvodafone.com")).isFalse();
		assertThat(SenderDomains.covers(trusted, "vodafone.com.evil.example")).isFalse();
		assertThat(SenderDomains.covers(trusted, null)).isFalse();
	}
}
