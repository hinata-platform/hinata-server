package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.EvidenceHold;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/**
 * The cases that still need content as evidence (HIN-170): an open case on it, or any case on it
 * under a legal hold. Asked by every way content is deleted (HIN-169).
 */
@Component
@RequiredArgsConstructor
class CaseEvidenceHold implements EvidenceHold {

	private final MongoTemplate mongo;
	private final Clock clock;

	@Override
	public Optional<String> holdingCaseId(ContentRef ref) {
		if (ref == null || ref.id() == null) {
			return Optional.empty();
		}
		// Off open_target: the filter repeats its partial filter.
		Query open = Query.query(Criteria.where("targetKey").is(ModerationCase.keyOf(ref)).and("status")
				.is(CaseStatus.OPEN));
		open.fields().include("_id");
		ModerationCase found = mongo.findOne(open, ModerationCase.class);
		if (found != null) {
			return Optional.of(found.getId());
		}
		// Off legal_hold: the $exists repeats its partial filter.
		Query held = Query.query(Criteria.where("target.id").is(ref.id()).and("target.kind").is(ref.kind())
				.and("legalHoldUntil").gt(clock.instant()).exists(true));
		held.fields().include("_id");
		return Optional.ofNullable(mongo.findOne(held, ModerationCase.class)).map(ModerationCase::getId);
	}
}
