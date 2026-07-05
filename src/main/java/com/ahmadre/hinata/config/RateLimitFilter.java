package com.ahmadre.hinata.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
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
	private final Map<String, Bucket> apiBuckets = new ConcurrentHashMap<>();
	private final Map<String, Bucket> authBuckets = new ConcurrentHashMap<>();
	private final Map<String, Bucket> mcpBuckets = new ConcurrentHashMap<>();

	public RateLimitFilter(HinataProperties properties, ClientIpResolver clientIpResolver) {
		this.properties = properties;
		this.clientIpResolver = clientIpResolver;
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
			response.setStatus(429);
			response.setContentType("application/json");
			response.getWriter().write("{\"status\":429,\"message\":\"Too many requests\"}");
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
