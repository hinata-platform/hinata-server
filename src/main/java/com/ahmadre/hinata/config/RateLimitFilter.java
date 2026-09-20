package com.ahmadre.hinata.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.context.MessageSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiting per client IP (bucket4j). Auth endpoints get a
 * much stricter budget than the general API (OWASP A04/A07).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

	/**
	 * Paths that live under the auth prefix but are not credential traffic:
	 * unauthenticated, side-effect-free lookups that reveal nothing an attacker
	 * could guess at. They stay on the general API budget instead of competing
	 * with sign-in attempts for the deliberately tiny auth one.
	 *
	 * <p>{@code /sso/providers} is what every visit to the sign-in screen calls to
	 * decide which buttons to draw. On the strict budget a couple of app starts
	 * within a minute — or several clients behind one proxy IP, which is what a
	 * reverse proxy or tunnel looks like — answered it with 429, and a client
	 * cannot tell that apart from "this server has no SSO": the buttons simply
	 * went missing. Matched by exact equality, so anything unusual (a trailing
	 * slash, a traversal attempt) still falls through to the strict bucket.
	 */
	private static final Set<String> PUBLIC_AUTH_LOOKUPS = Set.of(
			"/api/v1/auth/sso/providers");

	/**
	 * Presenting a signed token this server issued is not a sign-in attempt, so it does not
	 * compete with one for the strict budget.
	 *
	 * <p>The strict bucket exists to slow somebody guessing a password. A refresh token cannot be
	 * guessed — it is refused by its signature before anything is looked up — so the ten a minute
	 * bought nothing here and cost a great deal: every client on one address shares the bucket,
	 * and a machine running the phone simulator, the desktop app and a browser tab spends it in
	 * seconds. The refresh that lands on the eleventh request comes back 429, and to a client that
	 * is indistinguishable from "your session is over".
	 *
	 * <p>It stays on its own budget rather than the general one, wide enough that no honest client
	 * reaches it and narrow enough to bound the work a flood of forged tokens can ask for.
	 */
	private static final String REFRESH_PATH = "/api/v1/auth/refresh";

	private final HinataProperties properties;
	private final com.ahmadre.hinata.auth.SecurityPolicy securityPolicy;
	private final ClientIpResolver clientIpResolver;
	private final MessageSource messages;
	private final Map<String, Bucket> apiBuckets = new ConcurrentHashMap<>();
	private final Map<String, Bucket> authBuckets = new ConcurrentHashMap<>();
	private final Map<String, Bucket> refreshBuckets = new ConcurrentHashMap<>();
	private final Map<String, Bucket> mcpBuckets = new ConcurrentHashMap<>();
	private final Map<String, Bucket> ssoBuckets = new ConcurrentHashMap<>();

	public RateLimitFilter(HinataProperties properties,
			com.ahmadre.hinata.auth.SecurityPolicy securityPolicy,
			ClientIpResolver clientIpResolver, MessageSource messages) {
		this.properties = properties;
		this.securityPolicy = securityPolicy;
		this.clientIpResolver = clientIpResolver;
		this.messages = messages;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		// Master switch resolved by SecurityPolicy: the admin "Rate-Limiting"
		// toggle (DB) wins over the env default. Cached + event-refreshed, so this
		// per-request check never hits Mongo.
		if (!securityPolicy.rateLimitEnabled()) {
			chain.doFilter(request, response);
			return;
		}
		String ip = clientIpResolver.resolve(request);
		String uri = request.getRequestURI();
		// Long-lived SSE streams (…/stream) are opened once per view and then
		// transparently reconnect; metering them like discrete API calls would let
		// a single viewer's streams + reconnects drain the shared per-IP budget.
		// Worse, a 429 on an event-stream reconnect is indistinguishable from a
		// normal disconnect, so the client just reconnects and burns another token
		// — a self-amplifying loop. Never rate-limit the stream endpoints.
		if (uri.endsWith("/stream")) {
			chain.doFilter(request, response);
			return;
		}
		Bucket bucket;
		if (uri.startsWith("/mcp")) {
			bucket = mcpBuckets.computeIfAbsent(ip,
					k -> newBucket(properties.getRateLimit().getMcpPerMinute()));
		}
		else if (REFRESH_PATH.equals(uri)) {
			bucket = refreshBuckets.computeIfAbsent(ip,
					k -> newBucket(properties.getRateLimit().getRefreshPerMinute()));
		}
		else if (uri.startsWith("/api/v1/auth/") && !PUBLIC_AUTH_LOOKUPS.contains(uri)) {
			bucket = authBuckets.computeIfAbsent(ip,
					k -> newBucket(properties.getRateLimit().getAuthPerMinute()));
		}
		// The other sign-in door: the redirect that starts a single sign-on and the callback the
		// identity provider comes back to. Anybody can reach the callback with a state and any
		// code, and answering the duplicate of one holds a request thread for a moment — on the
		// general budget that is three hundred parked threads a minute from one address.
		else if (uri.startsWith("/login/oauth2/code/") || uri.startsWith("/oauth2/authorization/")
				|| uri.startsWith("/login/saml2/")) {
			bucket = ssoBuckets.computeIfAbsent(ip,
					k -> newBucket(properties.getRateLimit().getSsoPerMinute()));
		}
		else {
			bucket = apiBuckets.computeIfAbsent(ip,
					k -> newBucket(properties.getRateLimit().getApiPerMinute()));
		}
		if (bucket.tryConsume(1)) {
			chain.doFilter(request, response);
		}
		else {
			LocalizedErrorResponse.write(response,
					org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
					messages.getMessage("error.rateLimited", null, "error.rateLimited",
							request.getLocale()));
		}
	}

	private Bucket newBucket(int perMinute) {
		return Bucket.builder()
				.addLimit(Bandwidth.builder()
						.capacity(perMinute)
						.refillGreedy(perMinute, Duration.ofMinutes(1))
						.build())
				.build();
	}
}
