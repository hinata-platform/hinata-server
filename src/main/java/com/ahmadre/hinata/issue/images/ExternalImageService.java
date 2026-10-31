package com.ahmadre.hinata.issue.images;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.richtext.LegacyDocs;
import com.ahmadre.hinata.user.User;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The external images of an issue's texts, loaded by this server for a reader (HIN-181). No open
 * proxy: the reader must be able to read the issue, and the address must be the {@code src} of an
 * image in its description or in one of its comments. What is fetched once is kept a while in a
 * small cache, so a mail opened by a whole team reaches its sender once.
 *
 * <p>Bounded under load: one fetch per address however many readers ask at once (the others wait
 * for it), at most {@value #FETCHES_PER_PERSON} running for one person and {@value #FETCHES_IN_ALL}
 * in all (503 when full, never a queue), a fetch that failed is not tried again for
 * {@link #FAILURE_TTL}, and the addresses an issue's description may load are worked out once per
 * version of it.
 */
@Service
@RequiredArgsConstructor
public class ExternalImageService {

	/** Images kept at most, and the bytes they may take together. */
	static final int CACHE_ENTRIES = 256;
	static final long CACHE_BYTES = 48L * 1024 * 1024;

	/** Fetches one person may cause a minute; a mail has a few dozen images. */
	static final int FETCHES_PER_MINUTE = 120;

	/** Fetches one person may have running at once, and the server in all. */
	static final int FETCHES_PER_PERSON = 4;
	static final int FETCHES_IN_ALL = 16;

	/** How long a failed fetch answers for its address without asking again. */
	static final Duration FAILURE_TTL = Duration.ofMinutes(5);

	/** Failures and issue versions remembered. */
	private static final int REMEMBERED = 1024;

	/** People tracked before full buckets are swept. */
	private static final int MAX_TRACKED = 5_000;

	/** Longest address looked for; a mail's image address is far shorter. */
	static final int MAX_SRC_CHARS = 4096;

	/** Comments scanned for an address: one that holds it is found long before. */
	private static final int MAX_COMMENTS = 50;

	private final IssueService issues;
	private final MongoTemplate mongo;
	private final ExternalImageFetcher fetcher;

	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
	private final Cache cache = new Cache();
	private final Semaphore fetching = new Semaphore(FETCHES_IN_ALL);
	private final Map<String, AtomicInteger> running = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<ExternalImage>> inFlight = new ConcurrentHashMap<>();
	private final Map<String, Failure> failures = lru();
	private final Map<String, Set<String>> allowed = lru();

	/** An image as it goes out. */
	public record ExternalImage(byte[] data, String contentType) {
	}

	/** A fetch that failed, and until when that answers for its address. */
	private record Failure(ApiException error, long until) {
	}

	/**
	 * The image at [src] of issue [idOrKey], for [user].
	 *
	 * @throws ApiException 404 when the issue is not theirs to read or [src] is none of its images,
	 *                      429 past their budget, 503 when the server fetches all it may,
	 *                      400/502 when the image cannot be fetched
	 */
	public ExternalImage load(String idOrKey, String src, User user) {
		Issue issue = issues.getForUser(idOrKey, user);
		String address = src == null ? "" : src.strip();
		if (address.isEmpty() || address.length() > MAX_SRC_CHARS || !shows(issue, address)) {
			throw ApiException.notFound("externalImage");
		}
		ExternalImage cached = cache.get(address);
		if (cached != null) return cached;
		Failure failure = failures.get(address);
		if (failure != null && failure.until() - System.nanoTime() > 0) throw failure.error();
		CompletableFuture<ExternalImage> mine = new CompletableFuture<>();
		CompletableFuture<ExternalImage> other = inFlight.putIfAbsent(address, mine);
		if (other != null) return await(other);
		try {
			ExternalImage image = fetch(address, user);
			cache.put(address, image);
			mine.complete(image);
			return image;
		}
		catch (RuntimeException ex) {
			mine.completeExceptionally(ex);
			throw ex;
		}
		finally {
			inFlight.remove(address, mine);
		}
	}

	/** The fetch of [address] for [user], within their budget and the server's. */
	private ExternalImage fetch(String address, User user) {
		consume(user);
		AtomicInteger own = running.computeIfAbsent(user.getId(), id -> new AtomicInteger());
		if (own.incrementAndGet() > FETCHES_PER_PERSON) {
			release(user, own);
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "error.rateLimited");
		}
		try {
			if (!fetching.tryAcquire()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "error.externalImage.busy");
			try {
				ExternalImageFetcher.Image fetched = fetcher.fetch(address);
				return new ExternalImage(fetched.data(), fetched.contentType());
			}
			catch (ApiException failed) {
				// The sender's answer, not this server's limits: it will be the same in a minute.
				failures.put(address, new Failure(failed, System.nanoTime() + FAILURE_TTL.toNanos()));
				throw failed;
			}
			finally {
				fetching.release();
			}
		}
		finally {
			release(user, own);
		}
	}

	private void release(User user, AtomicInteger own) {
		if (own.decrementAndGet() <= 0) running.remove(user.getId(), own);
	}

	/** The outcome of a fetch another request is running for the same address. */
	private static ExternalImage await(CompletableFuture<ExternalImage> other) {
		try {
			return other.join();
		}
		catch (CompletionException ex) {
			if (ex.getCause() instanceof RuntimeException cause) throw cause;
			throw ex;
		}
	}

	/**
	 * Whether [src] is an external image of the issue's description or of one of its comments.
	 * The description's images are worked out once per version of the issue, so a mail of forty
	 * images is not read forty times. A comment's are looked up on every request: deleting or
	 * moderating a comment leaves the issue's version as it was, and its images must stop loading
	 * the moment it is gone.
	 */
	private boolean shows(Issue issue, String src) {
		String version = issue.getId() + "@" + issue.getUpdatedAt();
		Set<String> description = allowed.computeIfAbsent(version,
				key -> Set.copyOf(LegacyDocs.externalImageSources(issue.getDescriptionDoc())));
		if (description.contains(src)) return true;
		Query query = Query.query(Criteria.where("issueId").is(issue.getId())
				.and("textDoc").regex(Pattern.quote(src))).limit(MAX_COMMENTS);
		query.fields().include("textDoc");
		for (IssueComment comment : mongo.find(query, IssueComment.class)) {
			if (LegacyDocs.externalImageSources(comment.getTextDoc()).contains(src)) return true;
		}
		return false;
	}

	private static <V> Map<String, V> lru() {
		return Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
				return size() > REMEMBERED;
			}
		});
	}

	private void consume(User user) {
		if (buckets.size() > MAX_TRACKED) {
			buckets.values().removeIf(bucket -> bucket.getAvailableTokens() >= FETCHES_PER_MINUTE);
		}
		Bucket bucket = buckets.computeIfAbsent(user.getId(), id -> Bucket.builder()
				.addLimit(Bandwidth.builder().capacity(FETCHES_PER_MINUTE)
						.refillGreedy(FETCHES_PER_MINUTE, Duration.ofMinutes(1)).build())
				.build());
		if (!bucket.tryConsume(1)) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "error.rateLimited");
	}

	/** Least recently used images, bounded in count and in bytes. */
	private static final class Cache {

		private final LinkedHashMap<String, ExternalImage> entries = new LinkedHashMap<>(64, 0.75f, true);
		private long bytes;

		synchronized ExternalImage get(String src) {
			return entries.get(src);
		}

		synchronized void put(String src, ExternalImage image) {
			if (image.data().length > CACHE_BYTES / 8) return;
			ExternalImage previous = entries.put(src, image);
			if (previous != null) bytes -= previous.data().length;
			bytes += image.data().length;
			var oldest = entries.entrySet().iterator();
			while ((entries.size() > CACHE_ENTRIES || bytes > CACHE_BYTES) && oldest.hasNext()) {
				bytes -= oldest.next().getValue().data().length;
				oldest.remove();
			}
		}
	}
}
