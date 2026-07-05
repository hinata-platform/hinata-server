package com.ahmadre.hinata.oauth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An OAuth 2.1 client registered against this server — typically self-registered
 * by an AI client (e.g. Claude) via RFC 7591 Dynamic Client Registration. Public
 * clients ({@code token_endpoint_auth_method = none}) hold no secret and rely on
 * PKCE; confidential clients carry a hashed secret.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("oauth_clients")
public class OAuthClient {

	/** The generated {@code client_id}. */
	@Id
	private String id;

	private String clientName;

	/** Exact-match allow-list of redirect URIs (OAuth 2.1 forbids fuzzy matching). */
	@Builder.Default
	private List<String> redirectUris = new ArrayList<>();

	@Builder.Default
	private List<String> grantTypes = new ArrayList<>();

	/** {@code none} (public + PKCE) or {@code client_secret_post} (confidential). */
	private String tokenEndpointAuthMethod;

	/** BCrypt hash of the client secret; null for public clients. Never serialized. */
	@JsonIgnore
	private String clientSecretHash;

	private Instant createdAt;

	public boolean isPublic() {
		return clientSecretHash == null;
	}

	public boolean allowsRedirect(String redirectUri) {
		return redirectUri != null && redirectUris.contains(redirectUri);
	}

	public boolean allowsGrant(String grantType) {
		return grantTypes.contains(grantType);
	}
}
