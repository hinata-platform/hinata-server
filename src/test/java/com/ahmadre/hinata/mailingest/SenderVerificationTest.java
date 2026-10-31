package com.ahmadre.hinata.mailingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the receiving server vouched for an ingested mail's sender (HIN-181): only then is its
 * domain offered as one to trust with images.
 */
class SenderVerificationTest {

	private static final String FROM = "nicht.antworten@kundenservice.vodafone.com";

	private static boolean verified(String... headers) {
		return SenderVerification.verified(headers, FROM);
	}

	@Test
	void dmarcPassVerifies() {
		assertThat(verified("mx.asta.hn; spf=pass smtp.mailfrom=vodafone.com; dkim=pass header.d=vodafone.com;"
				+ " dmarc=pass (p=REJECT) header.from=kundenservice.vodafone.com")).isTrue();
	}

	@Test
	void anAlignedDkimSignatureVerifies() {
		assertThat(verified("mx.asta.hn; dkim=pass (2048-bit key) header.d=vodafone.com header.s=s1")).isTrue();
		assertThat(verified("mx.asta.hn; dkim=pass header.d=kundenservice.vodafone.com")).isTrue();
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
		assertThat(verified("mx.asta.hn; dmarc=none")).isFalse();
		assertThat(verified()).isFalse();
		assertThat(SenderVerification.verified(null, FROM)).isFalse();
	}

	@Test
	void onlyTheHeaderTheReceivingServerAddedCounts() {
		// The topmost one is the receiving server's; any below it came with the mail and is the sender's word.
		assertThat(verified("mx.asta.hn; dmarc=fail", "forged.example; dmarc=pass")).isFalse();
	}

	@Test
	void aHugeHeaderIsReadOnlyToItsBound() {
		assertThat(verified("mx.asta.hn; " + "x".repeat(100_000) + "; dmarc=pass")).isFalse();
	}
}
