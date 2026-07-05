package com.ahmadre.hinata.oauth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A pending authorization request, parked server-side between the
 * {@code /oauth/authorize} redirect and the user's decision on the web consent
 * page. Keyed by an unguessable {@code request_id} passed to the consent page,
 * so the raw OAuth parameters never round-trip through the browser. Self-expires
 * after 10 minutes (abandoned consent flows are cleaned up automatically).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("oauth_authorization_requests")
public class OAuthAuthorizationRequest {

	@Id
	private String id;

	private String clientId;
	private String redirectUri;
	/** Space-delimited requested scopes. */
	private String scope;
	private String state;
	private String codeChallenge;
	private String codeChallengeMethod;
	/** RFC 8707 resource indicator (the MCP endpoint URL). */
	private String resource;

	@Indexed(expireAfter = "PT10M")
	private Instant createdAt;
}
