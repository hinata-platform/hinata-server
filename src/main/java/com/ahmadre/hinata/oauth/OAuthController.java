package com.ahmadre.hinata.oauth;

import com.ahmadre.hinata.config.HinataProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public OAuth 2.1 protocol endpoints: Dynamic Client Registration (RFC 7591),
 * the authorization endpoint (which parks the request and hands the browser to
 * the web consent page) and the token endpoint (authorization-code + refresh).
 * These are unauthenticated by design — security comes from PKCE, exact redirect
 * matching, single-use codes and client authentication inside {@link OAuthService}.
 */
@RestController
@RequiredArgsConstructor
public class OAuthController {

	private final OAuthService oauth;
	private final HinataProperties properties;

	// --- Dynamic Client Registration ---------------------------------------

	public record RegistrationRequest(
			@JsonProperty("client_name") String clientName,
			@JsonProperty("redirect_uris") List<String> redirectUris,
			@JsonProperty("grant_types") List<String> grantTypes,
			@JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod) {
	}

	@PostMapping("/oauth/register")
	public ResponseEntity<Map<String, Object>> register(@RequestBody RegistrationRequest request) {
		OAuthService.RegisteredClient client = oauth.register(
				request.clientName(), request.redirectUris(),
				request.grantTypes(), request.tokenEndpointAuthMethod());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("client_id", client.clientId());
		if (client.clientSecret() != null) {
			body.put("client_secret", client.clientSecret());
		}
		body.put("client_name", client.clientName());
		body.put("redirect_uris", client.redirectUris());
		body.put("grant_types", client.grantTypes());
		body.put("token_endpoint_auth_method", client.tokenEndpointAuthMethod());
		return ResponseEntity.status(HttpStatus.CREATED).body(body);
	}

	// --- Authorization endpoint --------------------------------------------

	@GetMapping("/oauth/authorize")
	public ResponseEntity<Void> authorize(
			@RequestParam(name = "response_type", required = false) String responseType,
			@RequestParam(name = "client_id", required = false) String clientId,
			@RequestParam(name = "redirect_uri", required = false) String redirectUri,
			@RequestParam(name = "scope", required = false) String scope,
			@RequestParam(name = "state", required = false) String state,
			@RequestParam(name = "code_challenge", required = false) String codeChallenge,
			@RequestParam(name = "code_challenge_method", required = false) String codeChallengeMethod,
			@RequestParam(name = "resource", required = false) String resource) {
		String requestId = oauth.beginAuthorization(clientId, redirectUri, responseType, scope,
				state, codeChallenge, codeChallengeMethod, resource);
		// Hand the browser to the web consent page; the raw params stay server-side.
		String location = properties.webBase() + "/oauth-consent?request_id="
				+ java.net.URLEncoder.encode(requestId, StandardCharsets.UTF_8);
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
	}

	// --- Token endpoint (application/x-www-form-urlencoded) ----------------

	@PostMapping("/oauth/token")
	public Map<String, Object> token(
			@RequestParam("grant_type") String grantType,
			@RequestParam(name = "code", required = false) String code,
			@RequestParam(name = "redirect_uri", required = false) String redirectUri,
			@RequestParam(name = "code_verifier", required = false) String codeVerifier,
			@RequestParam(name = "refresh_token", required = false) String refreshToken,
			@RequestParam(name = "scope", required = false) String scope,
			@RequestParam(name = "client_id", required = false) String clientId,
			@RequestParam(name = "client_secret", required = false) String clientSecret) {
		OAuthService.TokenResponse token = switch (grantType) {
			case OAuthService.GRANT_AUTHORIZATION_CODE ->
					oauth.exchangeCode(clientId, clientSecret, code, redirectUri, codeVerifier);
			case OAuthService.GRANT_REFRESH_TOKEN ->
					oauth.refresh(clientId, clientSecret, refreshToken, scope);
			default -> throw new OAuthException("unsupported_grant_type",
					"Unsupported grant_type: " + grantType);
		};
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("access_token", token.accessToken());
		body.put("token_type", token.tokenType());
		body.put("expires_in", token.expiresIn());
		body.put("refresh_token", token.refreshToken());
		body.put("scope", token.scope());
		return body;
	}
}
