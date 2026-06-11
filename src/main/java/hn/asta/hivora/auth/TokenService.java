package hn.asta.hivora.auth;

import hn.asta.hivora.config.HivoraProperties;
import hn.asta.hivora.user.Role;
import hn.asta.hivora.user.User;
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
	public static final String TYPE_ACCESS = "access";
	public static final String TYPE_REFRESH = "refresh";

	private final JwtEncoder encoder;
	private final HivoraProperties properties;

	public TokenService(JwtEncoder encoder, HivoraProperties properties) {
		this.encoder = encoder;
		this.properties = properties;
	}

	public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {
	}

	public TokenPair issue(User user) {
		return new TokenPair(
				encode(user, TYPE_ACCESS, properties.getJwt().getAccessTokenSeconds()),
				encode(user, TYPE_REFRESH, properties.getJwt().getRefreshTokenSeconds()),
				properties.getJwt().getAccessTokenSeconds());
	}

	private String encode(User user, String type, long ttlSeconds) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.getBaseUrl())
				.subject(user.getId())
				.issuedAt(now)
				.expiresAt(now.plusSeconds(ttlSeconds))
				.claim(CLAIM_TYPE, type)
				.claim("username", user.getUsername())
				.claim(CLAIM_ROLES, user.getRoles().stream().map(Role::name).collect(Collectors.toList()))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS512).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	public static boolean isRefreshToken(Jwt jwt) {
		return TYPE_REFRESH.equals(jwt.getClaimAsString(CLAIM_TYPE));
	}
}
