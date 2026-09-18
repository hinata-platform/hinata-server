package com.ahmadre.hinata.timeoff;

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
 * A day's budget of requests and sick reports, per person.
 *
 * <p>Each one of these writes a document, reads a capacity window and puts a notice in somebody
 * else's inbox, and the inbox is the part that matters: a loop could otherwise bury the people who
 * decide leave under a thousand entries and make the feature useless for everybody. A day rather
 * than a minute, because filing leave is a deliberate act somebody does a handful of times a year
 * — a per-minute budget would refuse nothing that matters and still let the loop through.
 *
 * <p>Keyed by the person, never by the address: an office behind one NAT is not one budget.
 *
 * <p><b>A sick report is metered and never refused.</b> It spends from the same budget so the
 * count is honest, but § 5 EFZG knows a notification and not a permission, so reaching the limit
 * cannot be a wall in front of it (R11) — the caller asks {@link #allow} for that and nothing
 * happens when the answer is no.
 */
@Component
@RequiredArgsConstructor
public class TimeOffRequestLimiter {

	/**
	 * People tracked before idle buckets are swept. A bucket back at full capacity carries no
	 * state worth keeping — dropping it and handing that person a fresh one is indistinguishable
	 * from keeping it.
	 */
	private static final int MAX_TRACKED = 5_000;

	private final HinataProperties properties;
	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	/**
	 * Spends one of [userId]'s requests for today.
	 *
	 * @throws ApiException 429 when the person is over budget
	 */
	public void require(String userId) {
		if (!allow(userId)) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "error.timeOff.tooManyRequests",
					properties.getRateLimit().getTimeOffRequestsPerDay());
		}
	}

	/** Spends one if there is one, and says whether there was. Nothing is refused on a false. */
	public boolean allow(String userId) {
		int perDay = properties.getRateLimit().getTimeOffRequestsPerDay();
		evictIdle(perDay);
		return buckets.computeIfAbsent(userId, key -> newBucket(perDay)).tryConsume(1);
	}

	private void evictIdle(int perDay) {
		if (buckets.size() <= MAX_TRACKED) {
			return;
		}
		buckets.values().removeIf(bucket -> bucket.getAvailableTokens() >= perDay);
	}

	private static Bucket newBucket(int perDay) {
		return Bucket.builder()
				.addLimit(Bandwidth.builder()
						.capacity(perDay)
						.refillGreedy(perDay, Duration.ofDays(1))
						.build())
				.build();
	}
}
