package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.moderation.CaseViews.PersonRef;
import com.ahmadre.hinata.user.Suspension;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The accounts moderation suspended (HIN-172), newest first, a page at a time. A suspension whose
 * case is about content the reader wrote is not theirs to see, like the case itself.
 */
@Service
@RequiredArgsConstructor
public class SuspendedAccounts {

	private static final String USERS = "users";

	private final ModerationAccess moderators;
	private final MongoTemplate mongo;

	/** One suspended account: who, since when, until when (null: further notice), from which case. */
	public record SuspendedAccount(PersonRef person, Instant since, Instant until, String caseId) {
	}

	public Page<SuspendedAccount> page(int page, int size) {
		User viewer = moderators.require();
		PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, CaseService.MAX_PAGE));
		Criteria filter = Criteria.where("suspension.at").exists(true);
		Set<String> mine = casesAbout(viewer);
		if (!mine.isEmpty()) {
			filter = filter.and("suspension.caseId").nin(mine);
		}
		Query query = Query.query(filter)
				.with(Sort.by(Sort.Order.desc("suspension.at"), Sort.Order.desc("_id"))).with(request);
		query.fields().include("displayName", "avatarUrl", "suspension");
		// Raw rows: a User built from three fields would refuse its missing primitives.
		List<SuspendedAccount> rows = mongo.find(query, Document.class, USERS).stream()
				.map(this::rowOf)
				.toList();
		long total = mongo.count(Query.query(filter), User.class);
		return new PageImpl<>(rows, request, total);
	}

	private SuspendedAccount rowOf(Document row) {
		Suspension suspension = mongo.getConverter().read(Suspension.class, row.get("suspension", Document.class));
		Object id = row.get("_id");
		String personId = id instanceof ObjectId objectId ? objectId.toHexString() : String.valueOf(id);
		return new SuspendedAccount(new PersonRef(personId, row.getString("displayName"), row.getString("avatarUrl")),
				suspension.at(), suspension.until(), suspension.caseId());
	}

	/** The cases about content [viewer] wrote, off {@code contributor_status}: usually none. */
	private Set<String> casesAbout(User viewer) {
		Query query = Query.query(Criteria.where("contributorIds").is(viewer.getId()));
		query.fields().include("_id");
		Set<String> ids = new HashSet<>();
		mongo.find(query, ModerationCase.class).forEach(c -> ids.add(c.getId()));
		return ids;
	}
}
