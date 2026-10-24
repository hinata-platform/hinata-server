package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * What moderation keeps of a deleted account (HIN-170, Art. 17 DSGVO): nothing that names it. Its
 * reports stay as a member's, it leaves the cases about its content and those it held; the cases
 * themselves stay until their time is up.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ModerationErasure {

	private final MongoTemplate mongo;

	@EventListener
	public void onUserDeleted(UserService.UserDeletedEvent event) {
		String id = event.userId();
		try {
			// The key named the account too: each report gets one of its own, still unique per case.
			mongo.getCollection("moderation_reports").updateMany(new Document("reporterId", id), List.of(
					new Document("$set", new Document("reporterKey",
							new Document("$concat", List.of("deleted:", new Document("$toString", "$_id"))))),
					new Document("$unset", "reporterId")));
			mongo.updateMulti(Query.query(Criteria.where("contributorIds").is(id).and("responsibleId").is(id)),
					new Update().unset("responsibleId").unset("decision.responsibleId"), ModerationCase.class);
			mongo.updateMulti(Query.query(Criteria.where("contributorIds").is(id)),
					new Update().pull("contributorIds", id), ModerationCase.class);
			mongo.updateMulti(Query.query(Criteria.where("status").is(CaseStatus.OPEN).and("assigneeId").is(id)),
					new Update().unset("assigneeId").unset("claimedAt"), ModerationCase.class);
		}
		catch (RuntimeException ex) {
			// The account is gone already; a failure here must not fail the deletion.
			log.warn("[moderation] could not unname deleted user {}: {}", id, ex.toString());
		}
	}
}
