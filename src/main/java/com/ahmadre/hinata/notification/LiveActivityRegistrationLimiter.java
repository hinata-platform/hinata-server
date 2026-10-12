package com.ahmadre.hinata.notification;

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
 * An hour's budget of Live Activity registrations, per person.
 *
 * <p>The cap of tokens kept per person bounds what is stored, not what is
 * spent: a loop that registers a fresh token every second keeps twenty and
 * still makes the server push to a new one each time the timer moves. Every
 * such push goes through the gateway, whose budget is shared by every instance.
 *
 * <p>Keyed by the person, like {@code timeoff.TimeOffRequestLimiter}: an office
 * behind one address is not one budget.
 */
@Component
@RequiredArgsConstructor
public class LiveActivityRegistrationLimiter {

	/**
	 * People tracked before idle buckets are swept. A bucket back at full capacity
	 * carries no state worth keeping.
	 */
	private static final int MAX_TRACKED = 5_000;

	private final HinataProperties properties;
	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	/**
	 * Spends one of [userId]'s registrations for this hour.
	 *
	 * @throws ApiException 429 when the person is over budget
	 */
	public void consume(String userId) {
		int perHour = properties.getRateLimit().getLiveActivityRegistrationsPerHour();
		evictIdle(perHour);
		if (!buckets.computeIfAbsent(userId, key -> newBucket(perHour)).tryConsume(1)) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "error.rateLimited");
		}
	}

	private void evictIdle(int perHour) {
		if (buckets.size() <= MAX_TRACKED) {
			return;
		}
		buckets.values().removeIf(bucket -> bucket.getAvailableTokens() >= perHour);
	}

	private static Bucket newBucket(int perHour) {
		return Bucket.builder()
				.addLimit(Bandwidth.builder()
						.capacity(perHour)
						.refillGreedy(perHour, Duration.ofHours(1))
						.build())
				.build();
	}
}
