package hn.asta.hivora.auth.sso;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Server-side store of a pending OAuth2/OIDC authorization request, keyed by the
 * {@code state} parameter. The IdP echoes {@code state} back in the callback
 * query string, so the request can be recovered without relying on any cookie
 * surviving the cross-site redirect — which is exactly what breaks behind
 * tunnels with browser interstitials (ngrok free tier) and strict SameSite.
 */
@Data
@Builder
@Document("oauth2_auth_requests")
public class PendingAuthorizationRequest {

	/** The OAuth2 {@code state} value — the lookup key for the callback. */
	@Id
	private String state;

	/** GZIP + Base64URL serialized {@link org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest}. */
	private String payload;

	/** TTL cleanup: Mongo removes a handshake that never completed after 10 min. */
	@Indexed(expireAfter = "PT10M")
	private Instant createdAt;
}
