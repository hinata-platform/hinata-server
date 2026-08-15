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

	private final HinataProperties properties;
	private final com.ahmadre.hinata.auth.SecurityPolicy securityPolicy;
	private final ClientIpResolver clientIpResolver;
	private final MessageSource messages;
	private final Map<String, Bucket> apiBuckets = new ConcurrentHashMap<>();
	private final Map<String, Bucket> authBuckets = new ConcurrentHashMap<>();
	private final Map<String, Bucket> mcpBuckets = new ConcurrentHashMap<>();

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
		else if (uri.startsWith("/api/v1/auth/") && !PUBLIC_AUTH_LOOKUPS.contains(uri)) {
			bucket = authBuckets.computeIfAbsent(ip,
					k -> newBucket(properties.getRateLimit().getAuthPerMinute()));
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
