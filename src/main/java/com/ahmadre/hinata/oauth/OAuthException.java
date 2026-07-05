package com.ahmadre.hinata.oauth;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * An OAuth 2.1 protocol error carrying the RFC 6749 {@code error} code (e.g.
 * {@code invalid_request}, {@code invalid_grant}, {@code invalid_client},
 * {@code access_denied}) plus a human description. The token/registration
 * endpoints render it as the standard JSON error body; the authorize endpoint
 * decides separately whether it is safe to redirect the error to the client.
 */
@Getter
public class OAuthException extends RuntimeException {

	private final String error;
	private final HttpStatus status;

	public OAuthException(String error, String description) {
		this(error, description, HttpStatus.BAD_REQUEST);
	}

	public OAuthException(String error, String description, HttpStatus status) {
		super(description);
		this.error = error;
		this.status = status;
	}

	public String description() {
		return getMessage();
	}
}
