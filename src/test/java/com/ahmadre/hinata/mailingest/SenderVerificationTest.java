package com.ahmadre.hinata.mailingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the receiving server vouched for an ingested mail's sender (HIN-181): only then is its
 * domain offered as one to trust with images. Only a header that server added counts, told by
 * its authserv-id, which the ingest connection names.
 */
class SenderVerificationTest {

	private static final String FROM = "nicht.antworten@kundenservice.vodafone.com";
	private static final String OURS = "mx.asta.hn";

	private static boolean verified(String... headers) {
		return SenderVerification.verified(headers, FROM, OURS);
	}

	@Test
	void dmarcPassForTheFromDomainVerifies() {
		assertThat(verified("mx.asta.hn; spf=pass smtp.mailfrom=vodafone.com; dkim=pass header.d=vodafone.com;"
				+ " dmarc=pass (p=REJECT) header.from=kundenservice.vodafone.com")).isTrue();
	}

	@Test
	void dmarcPassForAnotherDomainDoesNot() {
		assertThat(verified("mx.asta.hn; dmarc=pass header.from=evil.example")).isFalse();
		assertThat(verified("mx.asta.hn; dmarc=pass")).as("no header.from").isFalse();
	}

	@Test
	void anAlignedDkimSignatureVerifies() {
		assertThat(verified("mx.asta.hn; dkim=pass (2048-bit key) header.d=vodafone.com header.s=s1")).isTrue();
		assertThat(verified("MX.Asta.HN 1; dkim=pass header.d=kundenservice.vodafone.com")).isTrue();
	}

	@Test
	void aSignatureOfAnotherDomainDoesNot() {
		assertThat(verified("mx.asta.hn; dkim=pass header.d=mailer.example")).isFalse();
		assertThat(verified("mx.asta.hn; dkim=pass header.d=evilvodafone.com")).isFalse();
	}

	@Test
	void spfAloneOrAFailureDoesNot() {
		assertThat(verified("mx.asta.hn; spf=pass smtp.mailfrom=vodafone.com")).isFalse();
		assertThat(verified("mx.asta.hn; dkim=fail header.d=vodafone.com; dmarc=fail")).isFalse();
		assertThat(verified()).isFalse();
		assertThat(SenderVerification.verified(null, FROM, OURS)).isFalse();
	}

	@Test
	void aHeaderOfAnotherServerDoesNotCount() {
		// The receiving server added none: the topmost header is whatever the sender wrote.
		assertThat(verified("forged.example; dmarc=pass header.from=kundenservice.vodafone.com")).isFalse();
		assertThat(verified("mx.asta.hn.evil.example; dmarc=pass header.from=kundenservice.vodafone.com")).isFalse();
	}

	@Test
	void withoutAnAuthservIdNothingIsVerified() {
		String vouched = "mx.asta.hn; dmarc=pass header.from=kundenservice.vodafone.com";

		assertThat(SenderVerification.verified(new String[] { vouched }, FROM, null)).isFalse();
		assertThat(SenderVerification.verified(new String[] { vouched }, FROM, " ")).isFalse();
	}

	@Test
	void onlyTheTopmostHeaderCounts() {
		assertThat(verified("mx.asta.hn; dmarc=fail", "mx.asta.hn; dmarc=pass header.from=kundenservice.vodafone.com"))
				.isFalse();
	}

	@Test
	void aHugeHeaderIsReadOnlyToItsBound() {
		assertThat(verified("mx.asta.hn; " + "x".repeat(100_000) + "; dmarc=pass header.from=kundenservice.vodafone.com"))
				.isFalse();
	}
}
