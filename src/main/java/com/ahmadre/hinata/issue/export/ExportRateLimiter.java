package com.ahmadre.hinata.issue.export;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-caller budget for rendering exports.
 *
 * <p>The general API bucket meters requests that cost a query. Rendering a PDF
 * lays out a document, and rendering a spreadsheet or a Word file builds an OPC
 * package in memory — tens of milliseconds of CPU each, on a request thread,
 * for a request the size of a URL. Keyed by the user rather than the address, so
 * an office behind one NAT is not one budget, and so a loop from one account
 * cannot spend everybody else's.
 */
@Component
@RequiredArgsConstructor
public class ExportRateLimiter {

	/**
	 * Callers tracked before idle buckets are swept. A bucket back at full
	 * capacity carries no state worth keeping — dropping it and handing that
	 * caller a fresh one is indistinguishable from keeping it.
	 */
	private static final int MAX_TRACKED_CALLERS = 5_000;

	private final HinataProperties properties;
	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	/**
	 * Consumes one token for [callerId].
	 *
	 * @throws ApiException 429 when the caller is over budget
	 */
	public void require(String callerId) {
		int perMinute = properties.getRateLimit().getExportsPerMinute();
		evictIdle(perMinute);
		Bucket bucket = buckets.computeIfAbsent(callerId, key -> newBucket(perMinute));
		if (!bucket.tryConsume(1)) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
					"error.issue.exportRateLimited", perMinute);
		}
	}

	private void evictIdle(int perMinute) {
		if (buckets.size() <= MAX_TRACKED_CALLERS) {
			return;
		}
		buckets.values().removeIf(bucket -> bucket.getAvailableTokens() >= perMinute);
	}

	private static Bucket newBucket(int perMinute) {
		return Bucket.builder()
				.addLimit(Bandwidth.builder()
						.capacity(perMinute)
						.refillGreedy(perMinute, Duration.ofMinutes(1))
						.build())
				.build();
	}
}
