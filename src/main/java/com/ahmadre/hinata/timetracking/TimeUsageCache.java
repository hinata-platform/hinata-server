package com.ahmadre.hinata.timetracking;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A project's recorded minutes and summed estimates, kept for a short while (HIN-152).
 *
 * <p>The sums are read where somebody looks at a board head, a project list or the dashboard, and
 * the same project is asked about many times a minute while its entries change a few times an
 * hour. Every write path of an entry forgets its project at once; the short lifetime covers what
 * moves without passing through those paths (an estimate edited, a purge, another instance's
 * writes), so no figure is ever more than {@link #TTL} behind.
 *
 * <p>Bounded: past {@link #MAX_ENTRIES} the whole map is dropped rather than tracked per entry. A
 * dropped map costs one aggregation per project on the next read, which is what the uncached path
 * costs anyway.
 */
@Component
@RequiredArgsConstructor
public class TimeUsageCache {

	static final Duration TTL = Duration.ofSeconds(30);
	static final int MAX_ENTRIES = 10_000;

	private final Clock clock;
	private final Map<String, Held> held = new ConcurrentHashMap<>();

	/** The sums of one project. */
	record Sums(long usedMinutes, long estimatedMinutes) {
	}

	private record Held(Sums sums, Instant until) {
	}

	/**
	 * The sums of every project in [projectIds]; those not held, or held too long, are asked of
	 * [load] in one call.
	 */
	Map<String, Sums> sumsOf(Collection<String> projectIds, Function<Collection<String>, Map<String, Sums>> load) {
		Instant now = clock.instant();
		Map<String, Sums> found = new HashMap<>();
		java.util.List<String> missing = new java.util.ArrayList<>();
		for (String id : projectIds) {
			Held entry = held.get(id);
			if (entry != null && entry.until().isAfter(now)) {
				found.put(id, entry.sums());
			}
			else {
				missing.add(id);
			}
		}
		if (!missing.isEmpty()) {
			Map<String, Sums> loaded = load.apply(missing);
			if (held.size() + missing.size() > MAX_ENTRIES) {
				held.clear();
			}
			Instant until = now.plus(TTL);
			for (String id : missing) {
				Sums sums = loaded.getOrDefault(id, new Sums(0, 0));
				held.put(id, new Held(sums, until));
				found.put(id, sums);
			}
		}
		return found;
	}

	/** Drops what is held for [projectId]; null is ignored, so callers need not ask. */
	public void forget(String projectId) {
		if (projectId != null) {
			held.remove(projectId);
		}
	}

	/** Drops everything, for a write whose projects are not known without reading them. */
	public void forgetAll() {
		held.clear();
	}
}
