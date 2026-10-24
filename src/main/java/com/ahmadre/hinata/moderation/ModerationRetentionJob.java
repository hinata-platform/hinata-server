package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.Quarantine;
import com.ahmadre.hinata.moderation.quarantine.QuarantinedContent;
import com.ahmadre.hinata.moderation.quarantine.QuarantinedVersion;
import com.ahmadre.hinata.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Deletes decided cases once their time is up (HIN-170), at 03:20. Skipped are cases with an appeal
 * still open and cases under a legal hold; a running suspension joins them with HIN-172.
 *
 * <p>In this order, so an interrupted run leaves nothing nobody can find: the objects under
 * {@code quarantine/<caseId>/} (the orphan sweep knows nothing of that prefix), the quarantine's
 * records and versions, the reports, and the case last. Each case is claimed with
 * {@code findAndModify} first; one claimed an hour ago and still there is taken again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationRetentionJob {

	/** Cases deleted per run at most. */
	static final int BATCH = 500;

	/** A claim this old belongs to a run that broke off. */
	static final Duration STALE_CLAIM = Duration.ofHours(1);

	private final MongoTemplate mongo;
	private final StorageService storage;
	private final Clock clock;

	@Scheduled(cron = "0 20 3 * * *")
	public void run() {
		Instant now = clock.instant();
		int deleted = 0;
		for (int i = 0; i < BATCH; i++) {
			ModerationCase due = claim(now);
			if (due == null) {
				break;
			}
			purge(due);
			deleted++;
		}
		if (deleted > 0) {
			log.info("[moderation] retention deleted {} case(s)", deleted);
		}
	}

	private ModerationCase claim(Instant now) {
		Query due = Query.query(new Criteria().andOperator(
				Criteria.where("status").is(CaseStatus.DECIDED).and("retainUntil").lt(now),
				Criteria.where("appeal.state").ne(Appeal.State.PENDING),
				new Criteria().orOperator(Criteria.where("legalHoldUntil").is(null),
						Criteria.where("legalHoldUntil").lt(now)),
				new Criteria().orOperator(Criteria.where("purgingAt").is(null),
						Criteria.where("purgingAt").lt(now.minus(STALE_CLAIM)))));
		return mongo.findAndModify(due, new Update().set("purgingAt", now),
				FindAndModifyOptions.options().returnNew(true), ModerationCase.class);
	}

	void purge(ModerationCase due) {
		String caseId = due.getId();
		if (storage.isConfigured()) {
			for (StorageService.ObjectInfo object : storage.list(Quarantine.PREFIX + caseId + "/")) {
				storage.delete(object.key());
			}
		}
		Query held = Query.query(Criteria.where("caseId").is(caseId));
		held.fields().include("_id");
		List<String> quarantineIds = new ArrayList<>();
		mongo.find(held, QuarantinedContent.class).forEach(row -> quarantineIds.add(row.getId()));
		if (!quarantineIds.isEmpty()) {
			mongo.remove(Query.query(Criteria.where("quarantineId").in(quarantineIds)), QuarantinedVersion.class);
		}
		mongo.remove(Query.query(Criteria.where("caseId").is(caseId)), QuarantinedContent.class);
		mongo.remove(Query.query(Criteria.where("caseId").is(caseId)), ContentReport.class);
		mongo.remove(Query.query(Criteria.where("_id").is(caseId)), ModerationCase.class);
	}
}
