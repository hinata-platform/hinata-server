package com.ahmadre.hinata.auth.sso;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A short-lived, single-use handoff code that carries a freshly-issued SSO token
 * pair from the {@link SsoLoginSuccessHandler} redirect to the app's callback,
 * so bearer tokens never travel in a URL (query, referrer, proxy/access logs or
 * browser history). The app POSTs the code to {@code /auth/sso/exchange} once to
 * redeem the tokens; the document is deleted on redemption and TTL-expired by
 * Mongo shortly after issuance if never used.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("sso_handoff_codes")
public class SsoHandoffCode {

	/** The opaque code, also the primary key (looked up on exchange). */
	@Id
	private String code;

	private String accessToken;

	private String refreshToken;

	private long expiresInSeconds;

	/**
	 * Creation instant; a TTL index deletes the document a short while after, so a
	 * code that is never redeemed cannot linger. The redemption path additionally
	 * deletes it atomically, guaranteeing single use.
	 */
	@Indexed(expireAfter = "120s")
	private Instant createdAt;
}
