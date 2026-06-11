package hn.asta.hivora.config;

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

	private final HivoraProperties properties;
	private final Map<String, Bucket> apiBuckets = new ConcurrentHashMap<>();
	private final Map<String, Bucket> authBuckets = new ConcurrentHashMap<>();

	public RateLimitFilter(HivoraProperties properties) {
		this.properties = properties;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		if (!properties.getRateLimit().isEnabled()) {
			chain.doFilter(request, response);
			return;
		}
		String ip = clientIp(request);
		boolean isAuth = request.getRequestURI().startsWith("/api/v1/auth/");
		Bucket bucket = isAuth
				? authBuckets.computeIfAbsent(ip,
						k -> newBucket(properties.getRateLimit().getAuthPerMinute()))
				: apiBuckets.computeIfAbsent(ip,
						k -> newBucket(properties.getRateLimit().getApiPerMinute()));
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

	private String clientIp(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Forwarded-For");
		return forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
	}
}
