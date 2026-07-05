package com.ahmadre.hinata.oauth;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders OAuth protocol errors in the RFC 6749 shape. Pre-redirect failures
 * ({@link OAuthException}) become a JSON {@code {error, error_description}} body;
 * post-validation authorize failures ({@link OAuthRedirectException}) are
 * delivered back to the client by redirecting to its registered redirect URI.
 * Scoped to the {@code oauth} package so it never shadows the app's global
 * {@code ApiException} handling.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.ahmadre.hinata.oauth")
public class OAuthExceptionHandler {

	@ExceptionHandler(OAuthException.class)
	public ResponseEntity<Map<String, Object>> handle(OAuthException ex) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", ex.getError());
		body.put("error_description", ex.description());
		return ResponseEntity.status(ex.getStatus()).body(body);
	}

	@ExceptionHandler(OAuthRedirectException.class)
	public ResponseEntity<Void> handle(OAuthRedirectException ex) {
		StringBuilder location = new StringBuilder(ex.getRedirectUri());
		location.append(ex.getRedirectUri().indexOf('?') < 0 ? '?' : '&');
		location.append("error=").append(enc(ex.getError()));
		if (ex.getState() != null) {
			location.append("&state=").append(enc(ex.getState()));
		}
		return ResponseEntity.status(HttpStatus.FOUND)
				.location(URI.create(location.toString())).build();
	}

	private static String enc(String value) {
		return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}
}
