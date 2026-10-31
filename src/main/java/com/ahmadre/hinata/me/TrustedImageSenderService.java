package com.ahmadre.hinata.me;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.SenderDomains;
import com.ahmadre.hinata.user.TrustedImageSender;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * The sender domains a person loads external images from without being asked (HIN-181): "Always
 * load from vodafone.com" under an ingested mail, and the list in their account settings. Their
 * own and nobody else's; every write is one atomic update of their user document, so two devices
 * trusting at once never lose one another's domain.
 */
@Service
@RequiredArgsConstructor
public class TrustedImageSenderService {

	/** Domains one person may trust: far above any real list, and a bound on the document. */
	public static final int MAX_DOMAINS = 500;

	/** Largest page of the list. */
	static final int MAX_PAGE = 100;

	private static final String FIELD = "trustedImageSenders";

	private final MongoTemplate mongo;

	/** One page of the list, newest first, and how many there are. */
	public record TrustedSenderPage(List<TrustedImageSender> items, long total) {
	}

	/** [user]'s trusted domains, newest first; at most {@link #MAX_PAGE} to a page. */
	public TrustedSenderPage list(User user, int page, int size) {
		List<TrustedImageSender> all = user.getTrustedImageSenders().stream()
				.sorted(Comparator.comparing(TrustedImageSender::addedAt,
						Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();
		int pageSize = Math.clamp(size, 1, MAX_PAGE);
		long from = Math.min((long) Math.max(0, page) * pageSize, all.size());
		int to = (int) Math.min(from + pageSize, all.size());
		return new TrustedSenderPage(all.subList((int) from, to), all.size());
	}

	/**
	 * Trusts [domain] for [user]. Idempotent: a domain already trusted stays as it was.
	 *
	 * @throws ApiException 400 {@code error.trustedSender.invalid} for no domain name and
	 *                      {@code error.trustedSender.publicSuffix} for a public suffix, 409
	 *                      {@code error.trustedSender.tooMany} past {@link #MAX_DOMAINS}
	 */
	public void trust(User user, String domain) {
		String normal = SenderDomains.normalize(domain);
		if (normal == null) throw ApiException.badRequest("error.trustedSender.invalid");
		if (SenderDomains.isPublicSuffix(normal)) throw ApiException.badRequest("error.trustedSender.publicSuffix");
		Query notYetAndRoom = Query.query(Criteria.where("_id").is(user.getId())
				.and(FIELD + ".domain").ne(normal)
				.and(FIELD + "." + (MAX_DOMAINS - 1)).exists(false));
		Update push = new Update().push(FIELD, new TrustedImageSender(normal, Instant.now()));
		if (mongo.updateFirst(notYetAndRoom, push, User.class).getModifiedCount() > 0) return;
		boolean already = mongo.exists(Query.query(Criteria.where("_id").is(user.getId())
				.and(FIELD + ".domain").is(normal)), User.class);
		if (!already) throw new ApiException(HttpStatus.CONFLICT, "error.trustedSender.tooMany", MAX_DOMAINS);
	}

	/** Stops trusting [domain]. Idempotent; a string that is no domain is trusted by nobody. */
	public void distrust(User user, String domain) {
		String normal = SenderDomains.normalize(domain);
		if (normal == null) return;
		mongo.updateFirst(Query.query(Criteria.where("_id").is(user.getId())),
				new Update().pull(FIELD, new Document("domain", normal)), User.class);
	}
}
