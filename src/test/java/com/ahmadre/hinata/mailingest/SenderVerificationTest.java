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

	@Test
	void theIdentityOfASignatureCountsWhenTheServerNamesNoSigningDomain() {
		// How IONOS (kundenserver.de) writes it: header.i, no header.d.
		assertThat(SenderVerification.verified(new String[] { "kundenserver.de; dkim=pass"
				+ " header.i=@kundenservice.vodafone.com header.s=vfdetimo-p17-20apr; dkim=pass"
				+ " header.i=@amazonses.com header.s=x; spf=pass" }, FROM, "kundenserver.de")).isTrue();
		assertThat(SenderVerification.verified(new String[] { "kundenserver.de; dkim=pass header.i=@amazonses.com" },
				FROM, "kundenserver.de")).isFalse();
	}

	@Test
	void alignmentIsByOrganisationalDomain() {
		assertThat(SenderVerification.aligned("vodafone.com", "kundenservice.vodafone.com")).isTrue();
		assertThat(SenderVerification.aligned("mail.vodafone.com", "kundenservice.vodafone.com")).isTrue();
		assertThat(SenderVerification.aligned("evilvodafone.com", "vodafone.com")).isFalse();
		assertThat(SenderVerification.aligned("vodafone.com.evil.example", "vodafone.com")).isFalse();
		// Two customers below one public suffix are two organisations.
		assertThat(SenderVerification.aligned("mallory.github.io", "alice.github.io")).isFalse();
		assertThat(SenderVerification.aligned("example.co.uk", "shop.example.co.uk")).isTrue();
		assertThat(SenderVerification.aligned("co.uk", "example.co.uk")).isFalse();
	}

	private static byte[] rfc8463(String headers) {
		return (DkimTest.RSA_SIGNATURE + headers + DkimTest.BODY).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
	}

	@Test
	void anAlignedSignatureVerifiesWithoutAnyMailServer() {
		assertThat(SenderVerification.check(rfc8463(DkimTest.HEADERS), "joe@football.example.com", null, null,
				DkimTest.DNS)).isEqualTo(SenderVerification.Verdict.VERIFIED);
	}

	@Test
	void aValidSignatureOfAnotherDomainDoesNotVerifyTheFromDomain() {
		// The mail is genuinely signed by football.example.com; that says nothing about vodafone.
		assertThat(SenderVerification.check(rfc8463(DkimTest.HEADERS), FROM, null, null, DkimTest.DNS))
				.isEqualTo(SenderVerification.Verdict.UNVERIFIED);
	}

	@Test
	void theVerdictTellsUnsignedFromUnverifiedFromUnknown() {
		byte[] unsigned = (DkimTest.HEADERS + DkimTest.BODY).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		Dkim.KeyLookup down = name -> {
			throw new java.io.IOException("timeout");
		};

		assertThat(SenderVerification.check(unsigned, "joe@football.example.com", null, null, DkimTest.DNS))
				.isEqualTo(SenderVerification.Verdict.UNSIGNED);
		assertThat(SenderVerification.check(rfc8463(DkimTest.HEADERS), "joe@football.example.com", null, null, down))
				.isEqualTo(SenderVerification.Verdict.UNKNOWN);
		assertThat(SenderVerification.check(null, "joe@football.example.com", null, null, DkimTest.DNS))
				.isEqualTo(SenderVerification.Verdict.UNVERIFIED);
	}

	@Test
	void theNamedMailServerStillCountsForAnUnsignedMail() {
		byte[] unsigned = (DkimTest.HEADERS + DkimTest.BODY).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

		assertThat(SenderVerification.check(unsigned, FROM,
				new String[] { "mx.asta.hn; dmarc=pass header.from=kundenservice.vodafone.com" }, OURS, DkimTest.DNS))
				.isEqualTo(SenderVerification.Verdict.VERIFIED);
	}

	@Test
	void theIssuesAddressHasToBeTheSignedOne() {
		// Same domain, but not the address the signed From names.
		assertThat(SenderVerification.check(rfc8463(DkimTest.HEADERS), "boss@football.example.com", null, null,
				DkimTest.DNS)).isEqualTo(SenderVerification.Verdict.UNVERIFIED);
	}
}
