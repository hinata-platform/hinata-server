package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.auth.SecurityPolicy;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One fetch of a calendar feed a minute, per feed (HIN-47).
 *
 * <p>Asked after the token has been checked, so only a real feed gets a bucket: a stranger trying
 * addresses spends the per-address budget of {@code config.RateLimitFilter} and leaves nothing
 * here. Calendar apps fetch every few minutes at most; a feed asked for more often is a script or a
 * loop, and every fetch costs four queries.
 *
 * <p>Off together with the global limit when an administrator switches rate limiting off. Same
 * eviction as {@code notification.LiveActivityRegistrationLimiter}: past {@link #MAX_TRACKED}
 * feeds the buckets back at full capacity are dropped, and should that not be enough, all of them:
 * the map never holds more than {@link #MAX_TRACKED} entries, and the worst a full sweep costs is one
 * extra fetch per feed.
 */
@Component
@RequiredArgsConstructor
public class ShiftFeedLimiter {

	static final int MAX_TRACKED = 10_000;
	static final Duration PERIOD = Duration.ofMinutes(1);

	private final SecurityPolicy policy;
	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	/**
	 * Spends the fetch of feed [tokenId] for this minute.
	 *
	 * @return empty when the fetch may go ahead, else the seconds until it may
	 */
	public OptionalLong consume(String tokenId) {
		if (!policy.rateLimitEnabled()) {
			return OptionalLong.empty();
		}
		Bucket bucket = buckets.get(tokenId);
		if (bucket == null) {
			bucket = track(tokenId);
		}
		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (probe.isConsumed()) {
			return OptionalLong.empty();
		}
		return OptionalLong.of(Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1));
	}

	int tracked() {
		return buckets.size();
	}

	/** A new feed's bucket. Serialised, so no two newcomers push the map past the cap together. */
	private synchronized Bucket track(String tokenId) {
		Bucket bucket = buckets.get(tokenId);
		if (bucket == null) {
			evictIdle();
			bucket = newBucket();
			buckets.put(tokenId, bucket);
		}
		return bucket;
	}

	private void evictIdle() {
		if (buckets.size() < MAX_TRACKED) {
			return;
		}
		buckets.values().removeIf(bucket -> bucket.getAvailableTokens() >= 1);
		if (buckets.size() >= MAX_TRACKED) {
			buckets.clear();
		}
	}

	private static Bucket newBucket() {
		return Bucket.builder()
				.addLimit(Bandwidth.builder().capacity(1).refillIntervally(1, PERIOD).build())
				.build();
	}
}
