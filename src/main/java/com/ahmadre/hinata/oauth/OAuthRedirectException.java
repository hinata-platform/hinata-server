package com.ahmadre.hinata.oauth;

import lombok.Getter;

/**
 * An authorize-endpoint error raised <em>after</em> the client and redirect URI
 * have been validated. Per OAuth 2.1 such errors must be delivered back to the
 * client by redirecting to its registered {@code redirect_uri} with an
 * {@code error} (and the original {@code state}), never shown on our own page.
 */
@Getter
public class OAuthRedirectException extends RuntimeException {

	private final String redirectUri;
	private final String state;
	private final String error;

	public OAuthRedirectException(String redirectUri, String state, String error, String description) {
		super(description);
		this.redirectUri = redirectUri;
		this.state = state;
		this.error = error;
	}
}
