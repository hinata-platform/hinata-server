package com.ahmadre.hinata.oauth;

import com.ahmadre.hinata.auth.TokenService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.pat.Scopes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The MCP OAuth 2.1 authorization server. Runs Dynamic Client Registration
 * (RFC 7591), the authorization-code + PKCE flow, and refresh-token rotation —
 * reusing hinata's own {@link TokenService} to mint session-less, audience-bound
 * MCP access tokens (validated by this same server, so no external JWKS).
 *
 * <p>Security-critical invariants enforced here: exact redirect-URI matching,
 * mandatory PKCE S256, single-use short-lived codes bound to client/redirect/
 * user/scope/challenge/resource, hashed + rotated refresh tokens, and client
 * authentication at the token endpoint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthService {

	public static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
	public static final String GRANT_REFRESH_TOKEN = "refresh_token";
	public static final String AUTH_METHOD_NONE = "none";
	public static final String AUTH_METHOD_SECRET_POST = "client_secret_post";
	public static final String RESPONSE_TYPE_CODE = "code";

	private static final String CLIENT_ID_PREFIX = "hn_client_";
	private static final String CLIENT_SECRET_PREFIX = "hn_cs_";
	private static final String REFRESH_TOKEN_PREFIX = "hn_rt_";
	private static final int TOKEN_BYTES = 32;

	private final OAuthClientRepository clients;
	private final OAuthAuthorizationRequestRepository authRequests;
	private final OAuthAuthorizationCodeRepository codes;
	private final OAuthGrantRepository grants;
	private final TokenService tokenService;
	private final HinataProperties properties;
	private final PasswordEncoder passwordEncoder;
	private final SecureRandom random = new SecureRandom();

	// --- results ------------------------------------------------------------

	public record RegisteredClient(String clientId, String clientSecret, String clientName,
			List<String> redirectUris, List<String> grantTypes, String tokenEndpointAuthMethod) {
	}

	public record ConsentInfo(String requestId, String clientName, String redirectHost,
			List<String> scopes) {
	}

	public record TokenResponse(String accessToken, String tokenType, long expiresIn,
			String refreshToken, String scope) {
	}

	// --- config-derived -----------------------------------------------------

	/** The canonical MCP resource URI that access tokens are bound to (RFC 8707). */
	public String resourceUrl() {
		return trimSlash(properties.getBaseUrl()) + "/mcp";
	}

	public String issuer() {
		return trimSlash(properties.getBaseUrl());
	}

	private HinataProperties.Mcp cfg() {
		return properties.getMcp();
	}

	private void requireOauthEnabled() {
		if (!cfg().isEnabled() || !cfg().isOauthEnabled()) {
			throw new OAuthException("access_denied", "OAuth is disabled on this server",
					HttpStatus.FORBIDDEN);
		}
	}

	// --- Dynamic Client Registration (RFC 7591) -----------------------------

	public RegisteredClient register(String clientName, List<String> redirectUris,
			List<String> grantTypes, String tokenEndpointAuthMethod) {
		requireOauthEnabled();
		if (!cfg().isDynamicClientRegistration()) {
			throw new OAuthException("access_denied", "Dynamic client registration is disabled",
					HttpStatus.FORBIDDEN);
		}
		if (redirectUris == null || redirectUris.isEmpty()) {
			throw new OAuthException("invalid_redirect_uri", "At least one redirect_uri is required");
		}
		for (String uri : redirectUris) {
			validateRegisteredRedirectUri(uri);
		}
		List<String> grants = (grantTypes == null || grantTypes.isEmpty())
				? List.of(GRANT_AUTHORIZATION_CODE, GRANT_REFRESH_TOKEN)
				: new ArrayList<>(grantTypes);
		for (String grant : grants) {
			if (!GRANT_AUTHORIZATION_CODE.equals(grant) && !GRANT_REFRESH_TOKEN.equals(grant)) {
				throw new OAuthException("invalid_client_metadata", "Unsupported grant_type: " + grant);
			}
		}
		String authMethod = (tokenEndpointAuthMethod == null || tokenEndpointAuthMethod.isBlank())
				? AUTH_METHOD_NONE
				: tokenEndpointAuthMethod;
		if (!AUTH_METHOD_NONE.equals(authMethod) && !AUTH_METHOD_SECRET_POST.equals(authMethod)) {
			throw new OAuthException("invalid_client_metadata",
					"Unsupported token_endpoint_auth_method: " + authMethod);
		}

		String clientId = CLIENT_ID_PREFIX + randomSecret();
		String plaintextSecret = null;
		String secretHash = null;
		if (!AUTH_METHOD_NONE.equals(authMethod)) {
			plaintextSecret = CLIENT_SECRET_PREFIX + randomSecret();
			secretHash = passwordEncoder.encode(plaintextSecret);
		}

		OAuthClient client = OAuthClient.builder()
				.id(clientId)
				.clientName(clientName == null || clientName.isBlank() ? "MCP client" : clientName.trim())
				.redirectUris(new ArrayList<>(redirectUris))
				.grantTypes(grants)
				.tokenEndpointAuthMethod(authMethod)
				.clientSecretHash(secretHash)
				.createdAt(Instant.now())
				.build();
		clients.save(client);
		return new RegisteredClient(clientId, plaintextSecret, client.getClientName(),
				client.getRedirectUris(), client.getGrantTypes(), authMethod);
	}

	/** OAuth 2.1: redirect URIs must be absolute; only HTTPS or loopback HTTP. */
	private void validateRegisteredRedirectUri(String uri) {
		URI parsed;
		try {
			parsed = URI.create(uri);
		}
		catch (IllegalArgumentException ex) {
			throw new OAuthException("invalid_redirect_uri", "Malformed redirect_uri: " + uri);
		}
		if (!parsed.isAbsolute() || parsed.getFragment() != null) {
			throw new OAuthException("invalid_redirect_uri",
					"redirect_uri must be absolute and carry no fragment: " + uri);
		}
		String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase();
		String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase();
		boolean https = scheme.equals("https");
		boolean loopback = scheme.equals("http")
				&& (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
		if (!https && !loopback) {
			throw new OAuthException("invalid_redirect_uri",
					"redirect_uri must use https (or http on loopback): " + uri);
		}
	}

	// --- Authorization endpoint ---------------------------------------------

	/**
	 * Validates an {@code /oauth/authorize} request and parks it server-side.
	 * Returns the {@code request_id} the browser carries to the consent page.
	 * Throws {@link OAuthException} for pre-redirect failures (unknown client /
	 * bad redirect) and {@link OAuthRedirectException} once the client+redirect
	 * are trusted.
	 */
	public String beginAuthorization(String clientId, String redirectUri, String responseType,
			String scope, String state, String codeChallenge, String codeChallengeMethod,
			String resource) {
		requireOauthEnabled();
		OAuthClient client = clients.findById(clientId == null ? "" : clientId)
				.orElseThrow(() -> new OAuthException("invalid_client", "Unknown client"));
		if (!client.allowsRedirect(redirectUri)) {
			throw new OAuthException("invalid_request", "redirect_uri is not registered for this client");
		}
		// From here errors are safe to redirect back to the client.
		if (!RESPONSE_TYPE_CODE.equals(responseType)) {
			throw new OAuthRedirectException(redirectUri, state, "unsupported_response_type",
					"Only response_type=code is supported");
		}
		if (!client.allowsGrant(GRANT_AUTHORIZATION_CODE)) {
			throw new OAuthRedirectException(redirectUri, state, "unauthorized_client",
					"Client may not use the authorization_code grant");
		}
		if (codeChallenge == null || codeChallenge.isBlank()
				|| !Pkce.METHOD_S256.equals(codeChallengeMethod)) {
			throw new OAuthRedirectException(redirectUri, state, "invalid_request",
					"PKCE code_challenge with method S256 is required");
		}
		if (resource != null && !resource.isBlank() && !resource.equals(resourceUrl())) {
			throw new OAuthRedirectException(redirectUri, state, "invalid_target",
					"resource does not match this MCP server");
		}
		Set<String> requested = normalizeScopes(scope);
		if (requested.isEmpty()) {
			// No explicit scope → offer the full MCP scope set on the consent screen.
			requested = new LinkedHashSet<>(Scopes.ALL);
		}
		else if (!Scopes.ALL.containsAll(requested)) {
			throw new OAuthRedirectException(redirectUri, state, "invalid_scope",
					"Unknown scope requested");
		}

		OAuthAuthorizationRequest request = OAuthAuthorizationRequest.builder()
				.id(randomSecret())
				.clientId(clientId)
				.redirectUri(redirectUri)
				.scope(String.join(" ", requested))
				.state(state)
				.codeChallenge(codeChallenge)
				.codeChallengeMethod(codeChallengeMethod)
				.resource(resourceUrl())
				.createdAt(Instant.now())
				.build();
		authRequests.save(request);
		return request.getId();
	}

	// --- Consent (authenticated web page) -----------------------------------

	public ConsentInfo consentInfo(String requestId) {
		OAuthAuthorizationRequest request = authRequests.findById(requestId)
				.orElseThrow(() -> ApiException.notFound("oauthRequest"));
		OAuthClient client = clients.findById(request.getClientId())
				.orElseThrow(() -> ApiException.notFound("oauthRequest"));
		return new ConsentInfo(requestId, client.getClientName(),
				URI.create(request.getRedirectUri()).getHost(),
				new ArrayList<>(normalizeScopes(request.getScope())));
	}

	/**
	 * Records the user's decision. On approval mints a single-use code and returns
	 * the client redirect carrying {@code code}+{@code state}; on denial returns
	 * the redirect carrying {@code error=access_denied}. The pending request is
	 * consumed either way.
	 */
	public String decide(String requestId, String userId, boolean approved, List<String> grantedScopes) {
		OAuthAuthorizationRequest request = authRequests.findById(requestId)
				.orElseThrow(() -> ApiException.notFound("oauthRequest"));
		authRequests.deleteById(requestId); // one-shot

		if (!approved) {
			return appendQuery(request.getRedirectUri(), "error", "access_denied", "state", request.getState());
		}
		Set<String> requested = normalizeScopes(request.getScope());
		Set<String> granted = new LinkedHashSet<>();
		if (grantedScopes != null) {
			for (String s : grantedScopes) {
				if (requested.contains(s)) {
					granted.add(s);
				}
			}
		}
		if (granted.isEmpty()) {
			// Approving with no scopes is equivalent to a denial.
			return appendQuery(request.getRedirectUri(), "error", "access_denied", "state", request.getState());
		}

		OAuthAuthorizationCode code = OAuthAuthorizationCode.builder()
				.id(randomSecret())
				.clientId(request.getClientId())
				.redirectUri(request.getRedirectUri())
				.userId(userId)
				.scopes(granted)
				.codeChallenge(request.getCodeChallenge())
				.codeChallengeMethod(request.getCodeChallengeMethod())
				.resource(request.getResource())
				.used(false)
				.createdAt(Instant.now())
				.build();
		codes.save(code);
		return appendQuery(request.getRedirectUri(), "code", code.getId(), "state", request.getState());
	}

	// --- Token endpoint -----------------------------------------------------

	public TokenResponse exchangeCode(String clientId, String clientSecret, String code,
			String redirectUri, String codeVerifier) {
		requireOauthEnabled();
		OAuthClient client = authenticateClient(clientId, clientSecret);
		OAuthAuthorizationCode stored = codes.findById(code == null ? "" : code)
				.orElseThrow(() -> new OAuthException("invalid_grant", "Unknown or expired code"));
		try {
			if (stored.isUsed()) {
				throw new OAuthException("invalid_grant", "Authorization code already used");
			}
			if (Instant.now().isAfter(stored.getCreatedAt().plusSeconds(cfg().getAuthCodeTtlSeconds()))) {
				throw new OAuthException("invalid_grant", "Authorization code expired");
			}
			if (!stored.getClientId().equals(client.getId())) {
				throw new OAuthException("invalid_grant", "Code was issued to a different client");
			}
			if (!stored.getRedirectUri().equals(redirectUri)) {
				throw new OAuthException("invalid_grant", "redirect_uri does not match the authorization request");
			}
			if (!Pkce.verifyS256(codeVerifier, stored.getCodeChallenge())) {
				throw new OAuthException("invalid_grant", "PKCE verification failed");
			}
		}
		finally {
			// Burn the code on any redemption attempt (success or PKCE/replay failure).
			stored.setUsed(true);
			codes.save(stored);
		}
		return issueTokens(client.getId(), stored.getUserId(), stored.getScopes(), stored.getResource());
	}

	public TokenResponse refresh(String clientId, String clientSecret, String refreshToken,
			String requestedScope) {
		requireOauthEnabled();
		OAuthClient client = authenticateClient(clientId, clientSecret);
		OAuthGrant grant = grants.findByRefreshTokenHash(sha256(nullToEmpty(refreshToken)))
				.filter(g -> g.isUsable(Instant.now()))
				.orElseThrow(() -> new OAuthException("invalid_grant", "Unknown or expired refresh token"));
		if (!grant.getClientId().equals(client.getId())) {
			throw new OAuthException("invalid_grant", "Refresh token was issued to a different client");
		}
		Set<String> scopes = grant.getScopes();
		Set<String> narrowed = normalizeScopes(requestedScope);
		if (!narrowed.isEmpty()) {
			if (!scopes.containsAll(narrowed)) {
				throw new OAuthException("invalid_scope", "Requested scope exceeds the grant");
			}
			scopes = narrowed;
		}
		// Rotate the refresh token.
		String newRefresh = REFRESH_TOKEN_PREFIX + randomSecret();
		grant.setRefreshTokenHash(sha256(newRefresh));
		grant.setLastUsedAt(Instant.now());
		grants.save(grant);
		String accessToken = tokenService.issueMcpAccessToken(grant.getUserId(), client.getId(),
				scopes, grant.getResource(), cfg().getAccessTokenTtlSeconds());
		return new TokenResponse(accessToken, "Bearer", cfg().getAccessTokenTtlSeconds(),
				newRefresh, String.join(" ", scopes));
	}

	private TokenResponse issueTokens(String clientId, String userId, Set<String> scopes, String resource) {
		String accessToken = tokenService.issueMcpAccessToken(userId, clientId, scopes, resource,
				cfg().getAccessTokenTtlSeconds());
		String refreshToken = REFRESH_TOKEN_PREFIX + randomSecret();
		Instant now = Instant.now();
		OAuthGrant grant = OAuthGrant.builder()
				.userId(userId)
				.clientId(clientId)
				.scopes(new LinkedHashSet<>(scopes))
				.resource(resource)
				.refreshTokenHash(sha256(refreshToken))
				.createdAt(now)
				.lastUsedAt(now)
				.expiresAt(now.plusSeconds(cfg().getRefreshTokenTtlDays() * 86400L))
				.revoked(false)
				.build();
		grants.save(grant);
		return new TokenResponse(accessToken, "Bearer", cfg().getAccessTokenTtlSeconds(),
				refreshToken, String.join(" ", scopes));
	}

	private OAuthClient authenticateClient(String clientId, String clientSecret) {
		OAuthClient client = clients.findById(clientId == null ? "" : clientId)
				.orElseThrow(() -> new OAuthException("invalid_client", "Unknown client",
						HttpStatus.UNAUTHORIZED));
		if (!client.isPublic()) {
			if (clientSecret == null || !passwordEncoder.matches(clientSecret, client.getClientSecretHash())) {
				throw new OAuthException("invalid_client", "Client authentication failed",
						HttpStatus.UNAUTHORIZED);
			}
		}
		return client;
	}

	// --- helpers ------------------------------------------------------------

	private Set<String> normalizeScopes(String scope) {
		Set<String> out = new LinkedHashSet<>();
		if (scope != null) {
			for (String s : scope.trim().split("\\s+")) {
				if (!s.isBlank()) {
					out.add(s);
				}
			}
		}
		return out;
	}

	private String appendQuery(String base, String... kv) {
		StringBuilder sb = new StringBuilder(base);
		boolean first = base.indexOf('?') < 0;
		for (int i = 0; i + 1 < kv.length; i += 2) {
			if (kv[i + 1] == null) {
				continue;
			}
			sb.append(first ? '?' : '&');
			first = false;
			sb.append(urlEncode(kv[i])).append('=').append(urlEncode(kv[i + 1]));
		}
		return sb.toString();
	}

	private static String urlEncode(String value) {
		return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private String randomSecret() {
		byte[] buffer = new byte[TOKEN_BYTES];
		random.nextBytes(buffer);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private static String trimSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xf, 16));
				hex.append(Character.forDigit(b & 0xf, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required but unavailable", ex);
		}
	}
}
