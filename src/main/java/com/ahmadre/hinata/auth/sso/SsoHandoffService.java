package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.auth.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Issues and redeems the short-lived, single-use SSO handoff codes that keep
 * bearer tokens out of the callback URL (see {@link SsoHandoffCode}). A code is
 * high-entropy (256-bit), stored with the freshly-issued token pair, and can be
 * redeemed exactly once: redemption atomically removes the document, so a
 * replayed code returns nothing.
 */
@Service
@RequiredArgsConstructor
public class SsoHandoffService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final MongoTemplate mongo;

	/** Stores [pair] behind a fresh opaque code and returns the code. */
	public String issue(TokenService.TokenPair pair) {
		byte[] raw = new byte[32];
		RANDOM.nextBytes(raw);
		String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		mongo.insert(SsoHandoffCode.builder()
				.code(code)
				.accessToken(pair.accessToken())
				.refreshToken(pair.refreshToken())
				.expiresInSeconds(pair.expiresInSeconds())
				.createdAt(Instant.now())
				.build());
		return code;
	}

	/**
	 * Atomically redeems [code] into its token pair, or returns {@code null} if the
	 * code is unknown, already used or expired. Uses {@code findAndRemove} so two
	 * concurrent redemptions can never both succeed (single use).
	 */
	public TokenService.TokenPair redeem(String code) {
		if (code == null || code.isBlank()) {
			return null;
		}
		SsoHandoffCode found = mongo.findAndRemove(
				Query.query(Criteria.where("_id").is(code)), SsoHandoffCode.class);
		if (found == null) {
			return null;
		}
		return new TokenService.TokenPair(found.getAccessToken(), found.getRefreshToken(),
				found.getExpiresInSeconds());
	}
}
