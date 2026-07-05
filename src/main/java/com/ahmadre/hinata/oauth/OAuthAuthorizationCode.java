package com.ahmadre.hinata.oauth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A single-use authorization code, minted once the user approves consent and
 * redeemed once at the token endpoint. Bound to the client, exact redirect URI,
 * user, granted scopes, PKCE challenge and resource so none of them can be
 * swapped at redemption. The real (short) lifetime is enforced in code; the TTL
 * index is only a garbage-collection backstop.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("oauth_codes")
public class OAuthAuthorizationCode {

	/** The opaque authorization code (also the document id). */
	@Id
	private String id;

	private String clientId;
	private String redirectUri;
	private String userId;

	@Builder.Default
	private Set<String> scopes = new HashSet<>();

	private String codeChallenge;
	private String codeChallengeMethod;
	private String resource;

	/** Flipped on first redemption; a second attempt is rejected (replay guard). */
	@Builder.Default
	private boolean used = false;

	@Indexed(expireAfter = "PT10M")
	private Instant createdAt;
}
