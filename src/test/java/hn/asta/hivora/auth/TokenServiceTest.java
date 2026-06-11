package hn.asta.hivora.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import hn.asta.hivora.config.HivoraProperties;
import hn.asta.hivora.user.Role;
import hn.asta.hivora.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

	private static final String SECRET =
			"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	private TokenService tokenService;
	private NimbusJwtDecoder decoder;

	@BeforeEach
	void setUp() {
		HivoraProperties properties = new HivoraProperties();
		properties.getJwt().setSecret(SECRET);
		SecretKeySpec key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
		tokenService = new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(key)), properties);
		decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS512).build();
	}

	@Test
	void issuesDecodableAccessAndRefreshTokens() {
		User user = User.builder().id("u1").username("ada").email("ada@example.org")
				.roles(Set.of(Role.ADMIN, Role.MEMBER)).build();

		TokenService.TokenPair pair = tokenService.issue(user);

		Jwt access = decoder.decode(pair.accessToken());
		assertThat(access.getSubject()).isEqualTo("u1");
		assertThat(access.getClaimAsStringList(TokenService.CLAIM_ROLES))
				.containsExactlyInAnyOrder("ADMIN", "MEMBER");
		assertThat(TokenService.isRefreshToken(access)).isFalse();

		Jwt refresh = decoder.decode(pair.refreshToken());
		assertThat(TokenService.isRefreshToken(refresh)).isTrue();
		assertThat(refresh.getExpiresAt()).isAfter(access.getExpiresAt());
	}
}
