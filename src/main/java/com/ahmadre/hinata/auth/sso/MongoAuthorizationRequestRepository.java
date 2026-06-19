package com.ahmadre.hinata.auth.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persists the pending {@link OAuth2AuthorizationRequest} server-side, keyed by
 * its {@code state} value, instead of in the HTTP session or a browser cookie.
 *
 * <p>Both alternatives fail in a self-hosted SSO behind a tunnel: in-memory
 * sessions die with a restart and don't span replicas, and a cookie does not
 * round-trip the IdP's cross-site redirect when SameSite is enforced or a
 * tunnel interstitial (ngrok free tier) rewrites the response. The IdP always
 * echoes {@code state} back in the callback query string, so a state-keyed
 * lookup recovers the request with zero reliance on the browser cookie jar.
 *
 * <p>Entries auto-expire via a Mongo TTL index (see
 * {@link PendingAuthorizationRequest#getCreatedAt()}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoAuthorizationRequestRepository
		implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

	private final MongoTemplate mongo;

	@Override
	public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
			HttpServletRequest request, HttpServletResponse response) {
		if (authorizationRequest == null) {
			// Contract: a null save clears any request for the incoming state.
			removeByState(request.getParameter(OAuth2ParameterNames.STATE));
			return;
		}
		String state = authorizationRequest.getState();
		mongo.save(PendingAuthorizationRequest.builder()
				.state(state)
				.payload(serialize(authorizationRequest))
				.createdAt(Instant.now())
				.build());
		log.info("SSO authz request saved (state={}…)", abbreviate(state));
	}

	@Override
	public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
		String state = request.getParameter(OAuth2ParameterNames.STATE);
		if (!StringUtils.hasText(state)) {
			return null;
		}
		PendingAuthorizationRequest stored = mongo.findById(state, PendingAuthorizationRequest.class);
		if (stored == null) {
			log.info("SSO authz request lookup MISS (state={}…)", abbreviate(state));
			return null;
		}
		return deserialize(stored.getPayload());
	}

	@Override
	public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
			HttpServletResponse response) {
		OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
		if (authorizationRequest != null) {
			removeByState(authorizationRequest.getState());
			log.info("SSO authz request consumed (state={}…)",
					abbreviate(authorizationRequest.getState()));
		}
		return authorizationRequest;
	}

	private void removeByState(String state) {
		if (StringUtils.hasText(state)) {
			mongo.remove(new org.springframework.data.mongodb.core.query.Query(
					org.springframework.data.mongodb.core.query.Criteria.where("_id").is(state)),
					PendingAuthorizationRequest.class);
		}
	}

	private String serialize(OAuth2AuthorizationRequest authorizationRequest) {
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				GZIPOutputStream gzip = new GZIPOutputStream(bytes);
				ObjectOutputStream out = new ObjectOutputStream(gzip)) {
			out.writeObject(authorizationRequest);
			out.flush();
			gzip.finish();
			return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
		}
		catch (Exception ex) {
			throw new IllegalStateException("Cannot serialize authorization request", ex);
		}
	}

	/**
	 * JEP-290 allow-list: only the Spring OAuth2 / Security types plus core JDK
	 * value types may be deserialized, with bounded depth/array size. This is
	 * defense-in-depth (the data is server-serialized and read back from our own
	 * Mongo, not attacker-supplied over HTTP) but removes the unrestricted
	 * {@link ObjectInputStream} gadget surface (OWASP A08).
	 */
	private static final java.io.ObjectInputFilter AUTH_REQUEST_FILTER =
			java.io.ObjectInputFilter.Config.createFilter(
					"org.springframework.security.oauth2.**;org.springframework.security.**;"
					+ "java.util.**;java.lang.**;java.net.**;java.time.**;"
					+ "maxdepth=20;maxarray=10000;maxrefs=10000;!*");

	private OAuth2AuthorizationRequest deserialize(String value) {
		try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getUrlDecoder().decode(value));
				GZIPInputStream gzip = new GZIPInputStream(bytes);
				ObjectInputStream in = new ObjectInputStream(gzip)) {
			in.setObjectInputFilter(AUTH_REQUEST_FILTER);
			return (OAuth2AuthorizationRequest) in.readObject();
		}
		catch (Exception ex) {
			log.warn("Discarding unreadable stored authorization request: {}", ex.getMessage());
			return null;
		}
	}

	private String abbreviate(String state) {
		if (state == null) {
			return "null";
		}
		return state.length() <= 8 ? state : state.substring(0, 8);
	}
}
