package com.ahmadre.hinata.auth.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ahmadre.hinata.auth.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Unit tests for the SSO handoff code store: the mechanism that keeps
 * bearer tokens out of the callback URL. Mongo is mocked; the focus is the
 * issue/redeem contract (opaque high-entropy code, single-use atomic removal).
 */
class SsoHandoffServiceTest {

	private MongoTemplate mongo;
	private SsoHandoffService service;

	@BeforeEach
	void setUp() {
		mongo = mock(MongoTemplate.class);
		service = new SsoHandoffService(mongo);
	}

	private TokenService.TokenPair pair() {
		return new TokenService.TokenPair("access-abc", "refresh-xyz", 900L);
	}

	@Test
	void issue_storesThePairBehindAnOpaqueCode() {
		String code = service.issue(pair());

		ArgumentCaptor<SsoHandoffCode> stored = ArgumentCaptor.forClass(SsoHandoffCode.class);
		org.mockito.Mockito.verify(mongo).insert(stored.capture());
		// The returned code is the stored id, is high-entropy (url-safe, no
		// padding) and never contains the tokens themselves.
		assertThat(code).isNotBlank().doesNotContain("access-abc", "refresh-xyz");
		assertThat(stored.getValue().getCode()).isEqualTo(code);
		assertThat(stored.getValue().getAccessToken()).isEqualTo("access-abc");
		assertThat(stored.getValue().getRefreshToken()).isEqualTo("refresh-xyz");
		assertThat(stored.getValue().getExpiresInSeconds()).isEqualTo(900L);
		assertThat(stored.getValue().getCreatedAt()).isNotNull();
	}

	@Test
	void issue_generatesADistinctCodeEachTime() {
		assertThat(service.issue(pair())).isNotEqualTo(service.issue(pair()));
	}

	@Test
	void redeem_returnsThePairAndConsumesItAtomically() {
		SsoHandoffCode doc = SsoHandoffCode.builder()
				.code("the-code").accessToken("access-abc").refreshToken("refresh-xyz")
				.expiresInSeconds(900L).build();
		when(mongo.findAndRemove(any(Query.class), eq(SsoHandoffCode.class))).thenReturn(doc);

		TokenService.TokenPair redeemed = service.redeem("the-code");

		assertThat(redeemed).isNotNull();
		assertThat(redeemed.accessToken()).isEqualTo("access-abc");
		assertThat(redeemed.refreshToken()).isEqualTo("refresh-xyz");
		assertThat(redeemed.expiresInSeconds()).isEqualTo(900L);
		// findAndRemove (not find) → single use: a second read can't return it.
		org.mockito.Mockito.verify(mongo).findAndRemove(any(Query.class), eq(SsoHandoffCode.class));
	}

	@Test
	void redeem_returnsNullForAnUnknownOrAlreadyUsedCode() {
		when(mongo.findAndRemove(any(Query.class), eq(SsoHandoffCode.class))).thenReturn(null);
		assertThat(service.redeem("gone")).isNull();
	}

	@Test
	void redeem_returnsNullForNullOrBlankWithoutTouchingMongo() {
		assertThat(service.redeem(null)).isNull();
		assertThat(service.redeem("  ")).isNull();
		org.mockito.Mockito.verifyNoInteractions(mongo);
	}
}
