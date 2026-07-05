package com.ahmadre.hinata.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * PKCE (RFC 7636) verification. OAuth 2.1 mandates the {@code S256} challenge
 * method for public clients; the plaintext method is intentionally not accepted.
 */
public final class Pkce {

	public static final String METHOD_S256 = "S256";

	private Pkce() {
	}

	/** {@code base64url(sha256(verifier)) == challenge}, compared in constant time. */
	public static boolean verifyS256(String verifier, String challenge) {
		if (verifier == null || challenge == null) {
			return false;
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
			String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
			return MessageDigest.isEqual(
					computed.getBytes(StandardCharsets.US_ASCII),
					challenge.getBytes(StandardCharsets.US_ASCII));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required but unavailable", ex);
		}
	}
}
