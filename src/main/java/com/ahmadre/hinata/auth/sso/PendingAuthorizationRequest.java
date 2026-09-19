package com.ahmadre.hinata.auth.sso;

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

	/**
	 * When the identity provider's callback took this request. Set instead of deleting the document,
	 * so a second arrival of the same callback can be told apart from a state nobody ever issued —
	 * see {@link SsoCallbackReplay}. A consumed request is never loaded again; its payload is dropped.
	 */
	private Instant consumedAt;

	/** Hash of the callback that consumed it (state and authorization code), never the values. */
	private String callbackKey;

	/**
	 * Where the callback sent the browser — the app link with its handoff code, or the error. Kept
	 * for the seconds in which the duplicate of the same callback may still arrive and must get the
	 * same answer. Handed out exactly once and unset as it goes: the answer carries a handoff code,
	 * and a callback URL that leaked afterwards must not be able to fetch a second copy of it.
	 */
	private String replayTarget;

	/** When the duplicate took the answer, so a third arrival is turned away rather than waiting. */
	private Instant replayedAt;
}
