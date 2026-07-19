package com.ahmadre.hinata.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
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
		HinataProperties properties = new HinataProperties();
		properties.getJwt().setSecret(SECRET);
		SecretKeySpec key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
		// No admin overrides stored → SecurityPolicy resolves session lifetime from
		// the env defaults (refresh-token seconds), matching the pre-change behaviour.
		SettingsService settings = org.mockito.Mockito.mock(SettingsService.class);
		org.mockito.Mockito.when(settings.get()).thenReturn(new ServerSettings());
		SecurityPolicy securityPolicy = new SecurityPolicy(settings, properties);
		tokenService = new TokenService(
				new NimbusJwtEncoder(new ImmutableSecret<>(key)), properties, securityPolicy);
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

	@Test
	void issuesScopedDownloadToken() {
		User user = User.builder().id("u1").username("ada").email("ada@example.org")
				.roles(Set.of(Role.MEMBER)).build();

		String token = tokenService.issueDownloadToken(
				user, TokenService.PURPOSE_DATA_EXPORT, 3600);

		Jwt jwt = decoder.decode(token);
		assertThat(jwt.getSubject()).isEqualTo("u1");
		assertThat(TokenService.isDownloadToken(jwt, TokenService.PURPOSE_DATA_EXPORT)).isTrue();
		// carries no roles and is not usable as an access/refresh token
		assertThat(jwt.getClaimAsStringList(TokenService.CLAIM_ROLES)).isNull();
		assertThat(TokenService.isRefreshToken(jwt)).isFalse();
		// a token minted for one purpose does not validate for another
		assertThat(TokenService.isDownloadToken(jwt, "something-else")).isFalse();
	}
}
