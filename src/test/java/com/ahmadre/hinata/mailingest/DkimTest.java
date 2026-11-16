package com.ahmadre.hinata.mailingest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server's own DKIM check (HIN-181), against the signed example of RFC 8463 Appendix A: one
 * message, one Ed25519 and one RSA signature, and the keys published for both. The vector comes
 * from the RFC, not from a signer of ours, so a mistake shared by signer and verifier cannot hide.
 */
class DkimTest {

	static final String ED25519_SIGNATURE = """
			DKIM-Signature: v=1; a=ed25519-sha256; c=relaxed/relaxed;\r
			 d=football.example.com; i=@football.example.com;\r
			 q=dns/txt; s=brisbane; t=1528637909; h=from : to :\r
			 subject : date : message-id : from : subject : date;\r
			 bh=2jUSOH9NhtVGCQWNr9BrIAPreKQjO6Sn7XIkfJVOzv8=;\r
			 b=/gCrinpcQOoIfuHNQIbq4pgh9kyIK3AQUdt9OdqQehSwhEIug4D11Bus\r
			 Fa3bT3FY5OsU7ZbnKELq+eXdp1Q1Dw==\r
			""";

	static final String RSA_SIGNATURE = """
			DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed;\r
			 d=football.example.com; i=@football.example.com;\r
			 q=dns/txt; s=test; t=1528637909; h=from : to : subject :\r
			 date : message-id : from : subject : date;\r
			 bh=2jUSOH9NhtVGCQWNr9BrIAPreKQjO6Sn7XIkfJVOzv8=;\r
			 b=F45dVWDfMbQDGHJFlXUNB2HKfbCeLRyhDXgFpEL8GwpsRe0IeIixNTe3\r
			 DhCVlUrSjV4BwcVcOF6+FF3Zo9Rpo1tFOeS9mPYQTnGdaSGsgeefOsk2Jz\r
			 dA+L10TeYt9BgDfQNZtKdN1WO//KgIqXP7OdEFE4LjFYNcUxZQ4FADY+8=\r
			""";

	static final String HEADERS = """
			From: Joe SixPack <joe@football.example.com>\r
			To: Suzie Q <suzie@shopping.example.net>\r
			Subject: Is dinner ready?\r
			Date: Fri, 11 Jul 2003 21:00:37 -0700 (PDT)\r
			Message-ID: <20030712040037.46341.5F8J@football.example.com>\r
			""";

	static final String BODY = """
			\r
			Hi.\r
			\r
			We lost the game.  Are you hungry yet?\r
			\r
			Joe.\r
			""";

	/** The two key records of RFC 8463 A.2. */
	static final Map<String, List<String>> KEYS = Map.of(
			"brisbane._domainkey.football.example.com",
			List.of("v=DKIM1; k=ed25519; p=11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="),
			"test._domainkey.football.example.com",
			List.of("v=DKIM1; k=rsa; p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDkHlOQoBTzWR"
					+ "iGs5V6NpP3idY6Wk08a5qhdR6wy5bdOKb2jLQiY/J16JYi0Qvx/byYzCNb3W91y3FutAC"
					+ "DfzwQ/BC/e/8uBsCR+yz1Lxj+PL6lHvqMKrM3rG4hstT5QjvHO9PzoxZyVYLzBfO2EeC3"
					+ "Ip3G+2kryOTIKT+l/K4w3QIDAQAB"));

	static final Dkim.KeyLookup DNS = name -> KEYS.getOrDefault(name, List.of());

	private static Dkim.Result verify(String message) {
		return Dkim.verify(message.getBytes(StandardCharsets.ISO_8859_1), DNS);
	}

	@Test
	void theEd25519SignatureOfRfc8463Holds() {
		Dkim.Result result = verify(ED25519_SIGNATURE + HEADERS + BODY);

		assertThat(result.signed()).isTrue();
		assertThat(result.passed()).containsExactly("football.example.com");
	}

	@Test
	void theRsaSignatureOfRfc8463Holds() {
		assertThat(verify(RSA_SIGNATURE + HEADERS + BODY).passed()).containsExactly("football.example.com");
	}

	@Test
	void bothSignaturesTogetherHoldAndAMessageWithBareLineFeedsIsReadToo() {
		String message = ED25519_SIGNATURE + RSA_SIGNATURE + HEADERS + BODY;

		assertThat(verify(message).passed()).containsExactly("football.example.com");
		assertThat(verify(message.replace("\r\n", "\n")).passed()).containsExactly("football.example.com");
	}

	@Test
	void aChangedBodyBreaksTheSignature() {
		Dkim.Result result = verify(RSA_SIGNATURE + HEADERS + BODY.replace("hungry", "Hungry"));

		assertThat(result.signed()).isTrue();
		assertThat(result.passed()).isEmpty();
		assertThat(result.keyUnavailable()).isFalse();
	}

	@Test
	void aChangedSignedHeaderBreaksTheSignature() {
		String forged = HEADERS.replace("Joe SixPack <joe@football.example.com>", "Joe <joe@evil.example>");

		assertThat(verify(ED25519_SIGNATURE + RSA_SIGNATURE + forged + BODY).passed()).isEmpty();
	}

	@Test
	void whitespaceTheRelaxedFormsIgnoreKeepsItValid() {
		String reflowed = HEADERS.replace("Subject: Is dinner ready?", "subject:   Is dinner\r\n\tready?  ");

		assertThat(verify(RSA_SIGNATURE + reflowed + BODY.replace("Hi.", "Hi.   ")).passed())
				.containsExactly("football.example.com");
	}

	@Test
	void anUnsignedCopyOfFromAboveTheSignedOneCountsForNothing() {
		// The parser shows the topmost From; the signature signs the bottom one. Even though the
		// RFC example oversigns From, a second one may not slip in.
		String doubled = "From: Mallory <ceo@football.example.com>\r\n" + HEADERS;

		assertThat(verify(RSA_SIGNATURE + doubled + BODY).passed()).isEmpty();
	}

	@Test
	void anUnsignedCopyOfSubjectAboveTheSignedOneCountsForNothing() {
		String doubled = "Subject: Your invoice is ready\r\n" + HEADERS;

		assertThat(verify(RSA_SIGNATURE + doubled + BODY).passed()).isEmpty();
	}

	@Test
	void unsignedHeadersAddedOnTheWayDoNotMatter() {
		String relayed = "Received: from mx.example.net by mx.example.org; Sun, 15 Nov 2026 12:00:00 +0000\r\n"
				+ "Authentication-Results: mx.example.org; dkim=pass\r\n";

		assertThat(verify(relayed + RSA_SIGNATURE + HEADERS + BODY).passed()).containsExactly("football.example.com");
	}

	@Test
	void aMessageWithoutSignatureIsUnsigned() {
		Dkim.Result result = verify(HEADERS + BODY);

		assertThat(result.signed()).isFalse();
		assertThat(result.passed()).isEmpty();
	}

	@Test
	void aRevokedOrMissingKeyLetsNothingPass() {
		Dkim.KeyLookup revoked = name -> List.of("v=DKIM1; k=rsa; p=");
		Dkim.KeyLookup none = name -> List.of();
		byte[] message = (RSA_SIGNATURE + HEADERS + BODY).getBytes(StandardCharsets.ISO_8859_1);

		assertThat(Dkim.verify(message, revoked).passed()).isEmpty();
		assertThat(Dkim.verify(message, none).passed()).isEmpty();
		assertThat(Dkim.verify(message, none).keyUnavailable()).isFalse();
	}

	@Test
	void aKeyDnsCannotHandOverIsReportedAsNotKnownYet() {
		Dkim.KeyLookup down = name -> {
			throw new IOException("timeout");
		};

		Dkim.Result result = Dkim.verify((RSA_SIGNATURE + HEADERS + BODY).getBytes(StandardCharsets.ISO_8859_1), down);

		assertThat(result.passed()).isEmpty();
		assertThat(result.keyUnavailable()).isTrue();
	}

	@Test
	void theKeyOfAnotherTypeDoesNotVerify() {
		Dkim.KeyLookup swapped = name -> KEYS.get("brisbane._domainkey.football.example.com");

		assertThat(Dkim.verify((RSA_SIGNATURE + HEADERS + BODY).getBytes(StandardCharsets.ISO_8859_1), swapped)
				.passed()).isEmpty();
	}

	@Test
	void aBodyLengthShorterThanTheBodyCountsForNothing() {
		// l= limits the hash to the start of the body; anything could follow below it. The tag is
		// not part of what was signed here, so the header hash breaks too, but the body length is
		// refused first and on its own.
		String limited = RSA_SIGNATURE.replace("q=dns/txt;", "q=dns/txt; l=10;");

		assertThat(verify(limited + HEADERS + BODY).passed()).isEmpty();
	}

	@Test
	void sha1AndUnknownAlgorithmsAreRefused() {
		assertThat(verify(RSA_SIGNATURE.replace("rsa-sha256", "rsa-sha1") + HEADERS + BODY).passed()).isEmpty();
		assertThat(verify(RSA_SIGNATURE.replace("rsa-sha256", "hmac-sha256") + HEADERS + BODY).passed()).isEmpty();
	}

	@Test
	void aSignatureThatDoesNotCoverFromCountsForNothing() {
		String withoutFrom = RSA_SIGNATURE.replace("h=from : to : subject :", "h=to : subject :")
				.replace("message-id : from : subject : date;", "message-id : subject : date;");

		assertThat(verify(withoutFrom + HEADERS + BODY).passed()).isEmpty();
	}

	@Test
	void aSelectorThatIsNoDnsLabelIsNotLookedUp() {
		java.util.List<String> asked = new java.util.ArrayList<>();
		Dkim.KeyLookup recording = name -> {
			asked.add(name);
			return List.of();
		};

		Dkim.verify((RSA_SIGNATURE.replace("s=test;", "s=te st/../x;") + HEADERS + BODY)
				.getBytes(StandardCharsets.ISO_8859_1), recording);

		assertThat(asked).isEmpty();
	}

	/** A signer of our own, for the rules the RFC example cannot show: it signs From and Subject once. */
	static final class TestSigner {

		final java.security.KeyPair keys;

		TestSigner() throws Exception {
			keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		}

		Dkim.KeyLookup dns() {
			byte[] spki = keys.getPublic().getEncoded();
			byte[] raw = java.util.Arrays.copyOfRange(spki, spki.length - 32, spki.length);
			String record = "v=DKIM1; k=ed25519; p=" + java.util.Base64.getEncoder().encodeToString(raw);
			return name -> name.equals("sel._domainkey.example.org") ? List.of(record) : List.of();
		}

		/** [from] and [subject] signed, then [extra] headers on top, as a relay might add them. */
		String message(String extra, String from, String subject, String body, String lengthTag) throws Exception {
			String canonicalBody = Dkim.relaxedBody(body);
			String bh = java.util.Base64.getEncoder().encodeToString(
					java.security.MessageDigest.getInstance("SHA-256")
							.digest(canonicalBody.getBytes(StandardCharsets.ISO_8859_1)));
			String tags = "v=1; a=ed25519-sha256; c=relaxed/relaxed; d=example.org; s=sel; h=from:subject;"
					+ lengthTag + " bh=" + bh + "; b=";
			String data = "from:" + from + "\r\nsubject:" + subject + "\r\ndkim-signature:" + tags;
			java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
			signer.initSign(keys.getPrivate());
			signer.update(java.security.MessageDigest.getInstance("SHA-256")
					.digest(data.getBytes(StandardCharsets.ISO_8859_1)));
			String b = java.util.Base64.getEncoder().encodeToString(signer.sign());
			return extra + "DKIM-Signature: " + tags + b + "\r\nFrom: " + from + "\r\nSubject: " + subject
					+ "\r\n\r\n" + body;
		}
	}

	@Test
	void aSubjectSignedOnceMayNotGetAnUnsignedCopyOnTop() throws Exception {
		TestSigner signer = new TestSigner();
		String body = "Your order shipped.\r\n";

		String genuine = signer.message("", "shop@example.org", "Order shipped", body, "");
		String doubled = signer.message("Subject: Pay this invoice now\r\n", "shop@example.org", "Order shipped",
				body, "");

		assertThat(Dkim.verify(genuine.getBytes(StandardCharsets.ISO_8859_1), signer.dns()).passed())
				.containsExactly("example.org");
		assertThat(Dkim.verify(doubled.getBytes(StandardCharsets.ISO_8859_1), signer.dns()).passed()).isEmpty();
	}

	@Test
	void aBodyLengthCountsOnlyWhenItCoversTheWholeBody() throws Exception {
		TestSigner signer = new TestSigner();
		String body = "Your order shipped.\r\n";
		String whole = signer.message("", "shop@example.org", "Hi", body, " l=" + body.length() + ";");
		// Signed over the first line only, then something appended below it.
		String appended = signer.message("", "shop@example.org", "Hi", body, " l=" + body.length() + ";")
				+ "<img src=https://evil.example/pixel.png>\r\n";

		assertThat(Dkim.verify(whole.getBytes(StandardCharsets.ISO_8859_1), signer.dns()).passed())
				.containsExactly("example.org");
		assertThat(Dkim.verify(appended.getBytes(StandardCharsets.ISO_8859_1), signer.dns()).passed()).isEmpty();
	}

	@Test
	void theSignatureValueIsBlankedAndNothingElse() {
		assertThat(Dkim.withoutSignatureValue("DKIM-Signature: v=1; bh=abc;\r\n b=xy\r\n z=; d=x"))
				.isEqualTo("DKIM-Signature: v=1; bh=abc;\r\n b=; d=x");
	}

	@Test
	void bodiesAreCanonicalisedAsRfc6376Says() {
		assertThat(Dkim.relaxedBody(" C \r\nD \t E\r\n\r\n\r\n")).isEqualTo(" C\r\nD E\r\n");
		assertThat(Dkim.simpleBody(" C \r\nD \t E\r\n\r\n\r\n")).isEqualTo(" C \r\nD \t E\r\n");
		assertThat(Dkim.relaxedBody("")).isEmpty();
		assertThat(Dkim.simpleBody("")).isEqualTo("\r\n");
	}

	@Test
	void aTagListWithATagTwiceIsInvalid() {
		assertThat(Dkim.tags("v=1; d=a.example; d=b.example")).isNull();
		assertThat(Dkim.tags("v=1; h=from :\r\n to")).containsEntry("h", "from:to");
	}

	@Test
	void quotedTxtStringsAreJoined() {
		assertThat(DnsDkimKeys.joined("\"v=DKIM1; p=MIIB\" \"IjAN\"")).isEqualTo("v=DKIM1; p=MIIBIjAN");
		assertThat(DnsDkimKeys.joined("v=DKIM1;k=rsa;p=MIIB IjAN")).isEqualTo("v=DKIM1;k=rsa;p=MIIB IjAN");
	}
}
