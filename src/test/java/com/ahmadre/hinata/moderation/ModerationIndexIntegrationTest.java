package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.ReportCategory;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reads of moderation run off their indexes (HIN-170): the three lists without sorting in
 * memory, the one open case of a target, the limits per person, the cases somebody wrote content
 * for, and the legal hold every deletion asks about.
 */
class ModerationIndexIntegrationTest extends RemovalIntegrationSupport {

	@Test
	void theReadsOfModerationRunOffTheirIndexes() {
		seed();

		assertThat(plan("moderation_cases", and(new Document("status", "OPEN"), notMine()),
				new Document("urgent", -1).append("firstReportedAt", 1).append("_id", 1)))
				.contains("open_queue").doesNotContain("\"SORT\"");
		assertThat(plan("moderation_cases", and(new Document("status", "DECIDED"), notMine()),
				new Document("decidedAt", -1).append("_id", -1)))
				.contains("done_queue").doesNotContain("\"SORT\"");
		assertThat(plan("moderation_cases", and(new Document("appeal.state", "PENDING"), notMine()),
				new Document("appeal.filedAt", 1).append("_id", 1)))
				.contains("appeal_queue").doesNotContain("\"SORT\"");
		assertThat(plan("moderation_cases", new Document("targetKey", "COMMENT:c-7").append("status", "OPEN"), null))
				.contains("open_target");
		assertThat(plan("moderation_cases", and(new Document("contributorIds", "u-3"), new Document("status", "OPEN")),
				null)).contains("contributor_status");
		// An embedded id is stored as _id, and the index's key is mapped the same way.
		assertThat(plan("moderation_cases", new Document("target._id", "c-7").append("target.kind", "COMMENT")
				.append("legalHoldUntil", new Document("$gt", new Date()).append("$exists", true)), null))
				.contains("legal_hold");
		// The limits are counts answered from the index alone.
		Document lastDay = new Document("reporterId", "u-3")
				.append("createdAt", new Document("$gte", Date.from(Instant.now().minusSeconds(86400))));
		assertThat(countPlan(lastDay)).contains("reporter_created").doesNotContain("FETCH");
		assertThat(countPlan(new Document(lastDay).append("category", new Document("$in", List.of("THREAT",
				"CHILD_SAFETY"))))).contains("reporter_created").doesNotContain("FETCH");
		assertThat(plan("moderation_reports", new Document("caseId", "case-7"),
				new Document("createdAt", 1).append("_id", 1)))
				.contains("case_created").doesNotContain("\"SORT\"");
	}

	private void seed() {
		List<ModerationCase> cases = new ArrayList<>();
		List<ContentReport> reports = new ArrayList<>();
		Instant now = Instant.now();
		for (int i = 0; i < 3000; i++) {
			boolean open = i % 3 != 0;
			ContentRef target = new ContentRef(ContentKind.COMMENT, "c-" + i, "i-" + (i % 40), "p-1");
			cases.add(ModerationCase.builder().target(target).targetKey(ModerationCase.keyOf(target))
					.status(open ? CaseStatus.OPEN : CaseStatus.DECIDED).urgent(i % 7 == 0)
					.contributorIds(new java.util.LinkedHashSet<>(Set.of("u-" + (i % 50))))
					.firstReportedAt(now.minusSeconds(i)).decidedAt(open ? null : now.minusSeconds(i))
					.appeal(i % 11 == 0 ? new Appeal(Appeal.State.PENDING, null, now.minusSeconds(i), null, null) : null)
					.legalHoldUntil(i % 97 == 0 ? now.plusSeconds(3600) : null)
					.build());
			reports.add(ContentReport.builder().caseId("case-" + (i % 300)).reporterKey("u-" + i)
					.reporterId("u-" + (i % 60)).category(ReportCategory.SPAM).createdAt(now.minusSeconds(i)).build());
		}
		mongo.insertAll(cases);
		mongo.insertAll(reports);
	}

	private static Document notMine() {
		return new Document("contributorIds", new Document("$ne", "u-3"));
	}

	private static Document and(Document... parts) {
		return new Document("$and", List.of(parts));
	}

	private String countPlan(Document filter) {
		Document explained = mongo.getDb().runCommand(new Document("explain",
				new Document("count", "moderation_reports").append("query", filter)).append("verbosity", "queryPlanner"));
		return explained.get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson();
	}

	private String plan(String collection, Document filter, Document sort) {
		var find = mongo.getCollection(collection).find(filter).limit(25);
		if (sort != null) {
			find = find.sort(sort);
		}
		return find.explain().get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson();
	}
}
