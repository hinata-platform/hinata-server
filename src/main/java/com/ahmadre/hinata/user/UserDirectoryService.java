package com.ahmadre.hinata.user;

import com.ahmadre.hinata.moderation.freeze.FrozenContentService;
import com.ahmadre.hinata.moderation.freeze.FrozenTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The people directory every picker in the product reads, with frozen accounts
 * left out.
 *
 * <p>{@link FrozenTargetType#USER} used to be checked from no read path at all,
 * which made freezing an account a row in a registry and nothing else. The three
 * endpoints behind {@code UserController} — the capped directory, the batch
 * id→display resolution and the paged type-ahead — are where a person's display
 * name and title are actually served, and all three went straight to
 * {@code UserRepository.searchActive}.
 *
 * <p>The queries live here rather than in the controller for the reason the rest
 * of this codebase gives: a check in a controller is a check the next caller walks
 * around. They are built with {@link MongoTemplate} rather than as derived
 * repository methods because the exclusion has to be <em>in</em> the query — the
 * type-ahead is paged, and post-filtering a page yields a short page and a total
 * that counts rows the caller can never reach, which is the same defect the issue
 * list had.
 *
 * <p>The composition mirrors {@code SearchService.unfrozen}: an active-and-matching
 * criterion, {@code andOperator}-joined with an exclusion that never answers null.
 * With nothing frozen the exclusion is {@code $nin: []}, which matches every
 * document, so an install with no incident runs the query it always ran.
 */
@Service
@RequiredArgsConstructor
public class UserDirectoryService {

	private final MongoTemplate mongo;
	private final FrozenContentService frozen;

	/** One page of active, unfrozen users matching [term] by name, username or title. */
	public Page<User> search(String term, Pageable pageable) {
		Query query = new Query(matching(term));
		long total = mongo.count(query, User.class);
		List<User> content = mongo.find(query.with(pageable), User.class);
		return new PageImpl<>(content, pageable, total);
	}

	/**
	 * The active, unfrozen users among [ids].
	 *
	 * <p>Not paged and not counted, so this could have post-filtered — it resolves
	 * ids the caller already holds. It goes through the same criterion anyway,
	 * because two ways to ask "is this account visible" is one way too many.
	 */
	public List<User> byIds(List<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		return mongo.find(new Query(new Criteria().andOperator(
				Criteria.where("_id").in(ids),
				Criteria.where("active").is(true),
				frozen.exclusion("_id", FrozenTargetType.USER))), User.class);
	}

	/**
	 * Active, matching and not frozen.
	 *
	 * <p>[term] is already regex-escaped by the caller; it is quoted again here
	 * rather than trusted, because this method is a service entry point and a
	 * validated controller says nothing about the next caller.
	 */
	private Criteria matching(String term) {
		String regex = Pattern.quote(term == null ? "" : term.trim());
		return new Criteria().andOperator(
				Criteria.where("active").is(true),
				new Criteria().orOperator(
						Criteria.where("displayName").regex(regex, "i"),
						Criteria.where("username").regex(regex, "i"),
						Criteria.where("title").regex(regex, "i")),
				frozen.exclusion("_id", FrozenTargetType.USER));
	}
}
