package com.ahmadre.hinata.template;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.user.User;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-person budget for copying projects.
 *
 * <p>One copy is bounded — 500 issues, 50 files, 100 MB — and nothing bounded the number of
 * copies. A member of a project that sits at those limits could loop the route and write tens of
 * gigabytes of object storage and tens of thousands of issue documents, none of which any sweep
 * ever removes, because a finished copy is a real project somebody meant to have.
 *
 * <p>Keyed by the person, not the address, for the reason {@code mcp.AttachmentReadLimiter} is:
 * a shared office address is one budget for a dozen people, and an agent holding a token is one
 * caller however many addresses it arrives from. The same bucket covers the REST route and the
 * MCP tool, because both go through the service.
 *
 * <p>Deliberately generous per hour: somebody setting up a term's worth of events makes a handful
 * of copies in a sitting, and an operator can raise it.
 */
@Component
@RequiredArgsConstructor
public class ProjectCopyLimiter {

	/**
	 * Callers tracked before idle buckets are swept. A bucket back at full capacity holds no
	 * state worth keeping — dropping it and handing the caller a fresh one is indistinguishable
	 * from keeping it.
	 */
	private static final int MAX_TRACKED_CALLERS = 5_000;

	private final HinataProperties properties;
	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	/**
	 * Consumes one copy for {@code user}.
	 *
	 * @throws ApiException 429 when they are over budget
	 */
	public void consume(User user) {
		int perHour = properties.getProjectTemplates().getCopiesPerHour();
		if (perHour <= 0 || user == null) {
			// Zero or less switches the budget off, which is what an operator who wants no limit
			// sets. A caller without an identity never reaches here: the security chain refuses
			// an anonymous request long before the service.
			return;
		}
		evictIdle(perHour);
		Bucket bucket = buckets.computeIfAbsent(user.getId(), key -> newBucket(perHour));
		if (!bucket.tryConsume(1)) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
					"error.project.copyRateLimited", perHour);
		}
	}

	private void evictIdle(int perHour) {
		if (buckets.size() <= MAX_TRACKED_CALLERS) {
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
