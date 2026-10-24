package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.auth.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Issues and redeems the short-lived, single-use SSO handoff codes that keep
 * bearer tokens out of the callback URL (see {@link SsoHandoffCode}). A code is
 * high-entropy (256-bit), stored with the freshly-issued token pair, and can be
 * redeemed exactly once: redemption atomically removes the document, so a
 * replayed code returns nothing.
 *
 * <p>A code is bound, the way PKCE (RFC 7636, S256) binds an authorization code, to the
 * challenge the app sent to the start of the flow: only the verifier behind that challenge
 * redeems it. A link carrying someone's code, or the code of a sign-in the person clicking it
 * never started, signs no one in.
 */
@Service
@RequiredArgsConstructor
public class SsoHandoffService {

	private static final SecureRandom RANDOM = new SecureRandom();
	/** A base64url SHA-256 digest without padding. */
	private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");
	/** RFC 7636, section 4.1. */
	private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");

	private final MongoTemplate mongo;

	/**
	 * Stores [pair] behind a fresh opaque code bound to [challenge] (null for an app from before
	 * the binding) and returns the code.
	 */
	public String issue(TokenService.TokenPair pair, String challenge) {
		byte[] raw = new byte[32];
		RANDOM.nextBytes(raw);
		String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		mongo.insert(SsoHandoffCode.builder()
				.code(code)
				.accessToken(pair.accessToken())
				.refreshToken(pair.refreshToken())
				.expiresInSeconds(pair.expiresInSeconds())
				.challenge(challenge)
				.createdAt(Instant.now())
				.build());
		return code;
	}

	/**
	 * Atomically redeems [code] into its token pair, or returns {@code null} if the
	 * code is unknown, already used, expired or bound to another verifier than
	 * [verifier]. Uses {@code findAndRemove} so two concurrent redemptions can never
	 * both succeed (single use); a code asked for with the wrong verifier stays for
	 * the app it belongs to.
	 */
	public TokenService.TokenPair redeem(String code, String verifier) {
		if (code == null || code.isBlank() || (verifier != null && !VERIFIER.matcher(verifier).matches())) {
			return null;
		}
		String challenge = verifier == null ? null : challengeOf(verifier);
		SsoHandoffCode found = mongo.findAndRemove(
				Query.query(Criteria.where("_id").is(code).and("challenge").is(challenge)), SsoHandoffCode.class);
		if (found == null) {
			return null;
		}
		return new TokenService.TokenPair(found.getAccessToken(), found.getRefreshToken(),
				found.getExpiresInSeconds());
	}

	/** Whether [value] has the shape of an S256 challenge. */
	static boolean isChallenge(String value) {
		return value != null && CHALLENGE.matcher(value).matches();
	}

	/** The S256 challenge of [verifier]: its SHA-256 digest, base64url without padding. */
	static String challengeOf(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException absent) {
			throw new IllegalStateException("every Java runtime has SHA-256", absent);
		}
	}
}
