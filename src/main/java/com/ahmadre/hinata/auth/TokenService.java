package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.stream.Collectors;

/** Issues short-lived access tokens and long-lived refresh tokens (HS512). */
@Service
public class TokenService {

	public static final String CLAIM_ROLES = "roles";
	public static final String CLAIM_TYPE = "type";
	public static final String CLAIM_SID = "sid";
	public static final String TYPE_ACCESS = "access";
	public static final String TYPE_REFRESH = "refresh";
	public static final String TYPE_MFA = "mfa";
	public static final String TYPE_DOWNLOAD = "download";
	public static final String CLAIM_PURPOSE = "purpose";
	public static final String PURPOSE_DATA_EXPORT = "data-export";

	private final JwtEncoder encoder;
	private final HinataProperties properties;

	public TokenService(JwtEncoder encoder, HinataProperties properties) {
		this.encoder = encoder;
		this.properties = properties;
	}

	public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {
	}

	public TokenPair issue(User user) {
		return issue(user, null);
	}

	/**
	 * Issues a token pair carrying {@code sessionId} as the {@code sid} claim, so
	 * the device's session can be tracked and revoked. A null session id keeps
	 * the token session-less (legacy / service tokens).
	 */
	public TokenPair issue(User user, String sessionId) {
		return new TokenPair(
				encode(user, TYPE_ACCESS, properties.getJwt().getAccessTokenSeconds(), sessionId),
				encode(user, TYPE_REFRESH, properties.getJwt().getRefreshTokenSeconds(), sessionId),
				properties.getJwt().getAccessTokenSeconds());
	}

	/** A short-lived token proving a password was accepted, pending a 2FA code. */
	public String issueMfaChallenge(User user) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.getBaseUrl())
				.subject(user.getId())
				.issuedAt(now)
				.expiresAt(now.plusSeconds(300))
				.claim(CLAIM_TYPE, TYPE_MFA)
				.build();
		return encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS512).build(), claims)).getTokenValue();
	}

	/**
	 * A short-lived, signed token authorising an unauthenticated browser GET of a
	 * download link (e.g. the GDPR data export e-mailed to the user). Carries no
	 * roles, so it cannot be replayed against the regular API.
	 */
	public String issueDownloadToken(User user, String purpose, long ttlSeconds) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.getBaseUrl())
				.subject(user.getId())
				.issuedAt(now)
				.expiresAt(now.plusSeconds(ttlSeconds))
				.claim(CLAIM_TYPE, TYPE_DOWNLOAD)
				.claim(CLAIM_PURPOSE, purpose)
				.build();
		return encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS512).build(), claims)).getTokenValue();
	}

	/** True when {@code jwt} is a download token minted for the given purpose. */
	public static boolean isDownloadToken(Jwt jwt, String purpose) {
		return TYPE_DOWNLOAD.equals(jwt.getClaimAsString(CLAIM_TYPE))
				&& purpose.equals(jwt.getClaimAsString(CLAIM_PURPOSE));
	}

	private String encode(User user, String type, long ttlSeconds, String sessionId) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.issuer(properties.getBaseUrl())
				.subject(user.getId())
				.issuedAt(now)
				.expiresAt(now.plusSeconds(ttlSeconds))
				.claim(CLAIM_TYPE, type)
				.claim("username", user.getUsername())
				.claim(CLAIM_ROLES, user.getRoles().stream().map(Role::name).collect(Collectors.toList()));
		if (sessionId != null) claims.claim(CLAIM_SID, sessionId);
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS512).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}

	public static boolean isRefreshToken(Jwt jwt) {
		return TYPE_REFRESH.equals(jwt.getClaimAsString(CLAIM_TYPE));
	}

	public static boolean isMfaToken(Jwt jwt) {
		return TYPE_MFA.equals(jwt.getClaimAsString(CLAIM_TYPE));
	}

	public static String sessionId(Jwt jwt) {
		return jwt.getClaimAsString(CLAIM_SID);
	}
}
