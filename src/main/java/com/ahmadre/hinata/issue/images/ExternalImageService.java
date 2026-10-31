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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The external images of an issue's texts, loaded by this server for a reader (HIN-181). No open
 * proxy: the reader must be able to read the issue, and the address must be the {@code src} of an
 * image in its description or in one of its comments. What is fetched once is kept a while in a
 * small cache, so a mail opened by a whole team reaches its sender once.
 */
@Service
@RequiredArgsConstructor
public class ExternalImageService {

	/** Images kept at most, and the bytes they may take together. */
	static final int CACHE_ENTRIES = 256;
	static final long CACHE_BYTES = 48L * 1024 * 1024;

	/** Fetches one person may cause a minute; a mail has a few dozen images. */
	static final int FETCHES_PER_MINUTE = 120;

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

	/** An image as it goes out. */
	public record ExternalImage(byte[] data, String contentType) {
	}

	/**
	 * The image at [src] of issue [idOrKey], for [user].
	 *
	 * @throws ApiException 404 when the issue is not theirs to read or [src] is none of its images,
	 *                      429 past the fetch budget, 400/502 when the image cannot be fetched
	 */
	public ExternalImage load(String idOrKey, String src, User user) {
		Issue issue = issues.getForUser(idOrKey, user);
		String address = src == null ? "" : src.strip();
		if (address.isEmpty() || address.length() > MAX_SRC_CHARS || !shows(issue, address)) throw ApiException.notFound("externalImage");
		ExternalImage cached = cache.get(address);
		if (cached != null) return cached;
		consume(user);
		ExternalImageFetcher.Image fetched = fetcher.fetch(address);
		ExternalImage image = new ExternalImage(fetched.data(), fetched.contentType());
		cache.put(address, image);
		return image;
	}

	/** Whether [src] is an external image of the issue's description or of one of its comments. */
	private boolean shows(Issue issue, String src) {
		if (LegacyDocs.externalImageSources(issue.getDescriptionDoc()).contains(src)) return true;
		Query query = Query.query(Criteria.where("issueId").is(issue.getId())
				.and("textDoc").regex(Pattern.quote(src))).limit(MAX_COMMENTS);
		query.fields().include("textDoc");
		for (IssueComment comment : mongo.find(query, IssueComment.class)) {
			if (LegacyDocs.externalImageSources(comment.getTextDoc()).contains(src)) return true;
		}
		return false;
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
