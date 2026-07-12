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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiting per client IP (bucket4j). Auth endpoints get a
 * much stricter budget than the general API (OWASP A04/A07).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

	private final HinataProperties properties;
	private final ClientIpResolver clientIpResolver;
	private final MessageSource messages;
	private final Map<String, Bucket> apiBuckets = new ConcurrentHashMap<>();
	private final Map<String, Bucket> authBuckets = new ConcurrentHashMap<>();
	private final Map<String, Bucket> mcpBuckets = new ConcurrentHashMap<>();

	public RateLimitFilter(HinataProperties properties, ClientIpResolver clientIpResolver,
			MessageSource messages) {
		this.properties = properties;
		this.clientIpResolver = clientIpResolver;
		this.messages = messages;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		if (!properties.getRateLimit().isEnabled()) {
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
		else if (uri.startsWith("/api/v1/auth/")) {
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
