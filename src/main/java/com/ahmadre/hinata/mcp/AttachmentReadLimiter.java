package com.ahmadre.hinata.mcp;

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
 * A per-caller budget for reading attachment <em>content</em> over MCP.
 *
 * <p>The {@code /mcp} endpoint already has a per-IP bucket, but that one meters
 * cheap JSON calls and is keyed by an address several clients can share. Reading
 * an attachment is different: every call decodes an image or parses a PDF, both
 * CPU- and memory-bound. This bucket is keyed by the authenticated user, so one
 * agent looping over an issue's files cannot spend anybody else's budget — nor
 * the server's cores.
 */
@Component
@RequiredArgsConstructor
public class AttachmentReadLimiter {

	/**
	 * Callers tracked before idle buckets are swept. A bucket back at full
	 * capacity has no state worth keeping — dropping it and handing the caller a
	 * fresh one is indistinguishable from keeping it.
	 */
	private static final int MAX_TRACKED_CALLERS = 5_000;

	private final HinataProperties properties;
	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	/**
	 * Consumes one token for [callerId].
	 *
	 * @throws ApiException 429 when the caller is over budget.
	 */
	public void require(String callerId) {
		int perMinute = properties.getMcp().getAttachmentReadsPerMinute();
		evictIdle(perMinute);
		Bucket bucket = buckets.computeIfAbsent(callerId, key -> newBucket(perMinute));
		if (!bucket.tryConsume(1)) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
					"error.mcp.attachmentRateLimited", perMinute);
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
