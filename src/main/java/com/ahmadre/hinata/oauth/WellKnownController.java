package com.ahmadre.hinata.oauth;

import com.ahmadre.hinata.pat.Scopes;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth discovery documents. AI clients read these to learn how to authorize:
 * the MCP endpoint publishes RFC 9728 Protected Resource Metadata pointing at
 * this server as its authorization server, which in turn publishes RFC 8414
 * Authorization Server Metadata (endpoints, PKCE method, grant types). Both are
 * public, unauthenticated.
 *
 * <p>The <em>root</em> {@code /.well-known/oauth-protected-resource} is served by
 * Spring Security's built-in {@code OAuth2ProtectedResourceMetadataFilter}
 * (customized in {@code SecurityConfig}); this controller serves the RFC 9728
 * <em>path-based</em> variant ({@code …/mcp}) that clients derive from the
 * resource path, plus the authorization-server document.
 */
@RestController
@RequiredArgsConstructor
public class WellKnownController {

	private final OAuthService oauth;

	/** RFC 9728, path-based variant for the {@code /mcp} resource. */
	@GetMapping("/.well-known/oauth-protected-resource/mcp")
	public Map<String, Object> protectedResource() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("resource", oauth.resourceUrl());
		body.put("authorization_servers", List.of(oauth.issuer()));
		body.put("scopes_supported", new ArrayList<>(Scopes.ALL));
		body.put("bearer_methods_supported", List.of("header"));
		return body;
	}

	/** RFC 8414 Authorization Server Metadata. */
	@GetMapping("/.well-known/oauth-authorization-server")
	public Map<String, Object> authorizationServer() {
		String base = oauth.issuer();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("issuer", base);
		body.put("authorization_endpoint", base + "/oauth/authorize");
		body.put("token_endpoint", base + "/oauth/token");
		body.put("registration_endpoint", base + "/oauth/register");
		body.put("scopes_supported", new ArrayList<>(Scopes.ALL));
		body.put("response_types_supported", List.of("code"));
		body.put("grant_types_supported",
				List.of(OAuthService.GRANT_AUTHORIZATION_CODE, OAuthService.GRANT_REFRESH_TOKEN));
		body.put("code_challenge_methods_supported", List.of(Pkce.METHOD_S256));
		body.put("token_endpoint_auth_methods_supported",
				List.of(OAuthService.AUTH_METHOD_NONE, OAuthService.AUTH_METHOD_SECRET_POST));
		return body;
	}
}
