package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reporting (HIN-170): every report on the same content joins its one open case, a second report
 * of the same person replaces their first, and the reporter learns only that it arrived.
 */
class ReportFlowIntegrationTest extends RemovalIntegrationSupport {

	private static final String INSULT = "Jonas ist zu bloed fuer den Job";

	@Autowired
	private ContentReportService reports;
	@Autowired
	private RichTextService richText;

	private User moderator;
	private Issue issue;
	private IssueComment insult;

	@BeforeEach
	void seedComment() {
		moderator = user("mira", Role.MODERATOR);
		issue = issue("Stand B12", doc("Aufbau"));
		insult = issues.addComment(issue.getId(), richText.fromLexical(doc(INSULT)), null, author);
	}

	private ContentReportService.ReportRequest onComment(ReportCategory category, String explanation,
			boolean alsoBlock) {
		return new ContentReportService.ReportRequest(new TargetRef(ContentKind.COMMENT, insult.getId(),
				issue.getId()), category, explanation, null, alsoBlock);
	}

	private List<ModerationCase> cases() {
		return mongo.findAll(ModerationCase.class);
	}

	private List<Notification> noticesOf(User person, Notification.Type type) {
		return mongo.find(Query.query(Criteria.where("userId").is(person.getId()).and("type").is(type)),
				Notification.class);
	}

	private long stat(StatMetric metric, String key) {
		ModerationStat row = mongo.findOne(Query.query(Criteria.where("metric").is(metric).and("key").is(key)),
				ModerationStat.class);
		return row == null ? 0 : row.getCount();
	}

	@Test
	void aReportOpensACaseWithWhatWasReportedAndTheBellConfirmsIt() {
		reports.submit(onComment(ReportCategory.HARASSMENT, null, false), reader);

		assertThat(cases()).singleElement().satisfies(opened -> {
			assertThat(opened.getStatus()).isEqualTo(CaseStatus.OPEN);
			assertThat(opened.getTargetKey()).isEqualTo("COMMENT:" + insult.getId());
			assertThat(opened.getReportCount()).isEqualTo(1);
			assertThat(opened.getContributorIds()).containsExactly(author.getId());
			assertThat(opened.getEvidence().excerpt()).contains(INSULT);
			assertThat(opened.getListExcerpt()).contains(INSULT);
			assertThat(opened.getLocation()).startsWith("ACME-1");
			assertThat(opened.getHistory()).singleElement()
					.satisfies(event -> assertThat(event.kind()).isEqualTo(CaseEvent.Kind.REPORTED));
		});
		assertThat(mongo.findAll(ContentReport.class)).singleElement().satisfies(report -> {
			assertThat(report.getReporterKey()).isEqualTo(reader.getId());
			assertThat(report.getSource()).isEqualTo(ReportSource.MEMBER);
		});
		assertThat(noticesOf(reader, Notification.Type.SYSTEM)).singleElement()
				.satisfies(receipt -> assertThat(receipt.getLink()).isNull());
		assertThat(noticesOf(moderator, Notification.Type.MODERATION_CASE_OPENED)).singleElement()
				.satisfies(notice -> {
					assertThat(notice.getLink()).isEqualTo("/moderation/cases/" + cases().getFirst().getId());
					// A notice names nothing of what was reported.
					assertThat(notice.getTitle() + notice.getBody()).doesNotContain("Jonas", "HARASSMENT");
				});
		assertThat(mongo.find(Query.query(Criteria.where("userId").is(author.getId())), Notification.class))
				.noneMatch(notice -> notice.getType().name().startsWith("MODERATION_"));
		assertThat(stat(StatMetric.NOTICE, "HARASSMENT")).isEqualTo(1);
	}

	@Test
	void aModeratorIsPingedOnceAQuarterOfAnHourAndTheBellKeepsEveryCase() {
		IssueComment second = issues.addComment(issue.getId(), richText.fromLexical(doc("Du nervst")), null, author);
		IssueComment third = issues.addComment(issue.getId(), richText.fromLexical(doc("Geh weg")), null, author);
		reports.submit(onComment(ReportCategory.HARASSMENT, null, false), reader);
		reports.submit(on(second), reader);

		assertThat(noticesOf(moderator, Notification.Type.MODERATION_CASE_OPENED))
				.extracting(Notification::getSentOut).containsExactlyInAnyOrder(true, null);

		mongo.updateMulti(Query.query(Criteria.where("userId").is(moderator.getId())),
				new org.springframework.data.mongodb.core.query.Update()
						.set("createdAt", Instant.now().minusSeconds(16 * 60)), Notification.class);
		reports.submit(on(third), reader);

		assertThat(noticesOf(moderator, Notification.Type.MODERATION_CASE_OPENED)).hasSize(3)
				.filteredOn(notice -> Boolean.TRUE.equals(notice.getSentOut())).hasSize(2);
	}

	private ContentReportService.ReportRequest on(IssueComment comment) {
		return new ContentReportService.ReportRequest(new TargetRef(ContentKind.COMMENT, comment.getId(),
				issue.getId()), ReportCategory.HARASSMENT, null, null, false);
	}

	@Test
	void contentOutOfSightRemovedOrOwnIsRefused() {
		User outsider = user("olga");
		assertThatThrownBy(() -> reports.submit(onComment(ReportCategory.HARASSMENT, null, false), outsider))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
		assertThatThrownBy(() -> reports.submit(onComment(ReportCategory.HARASSMENT, null, false), author))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getMessageKey()).isEqualTo("error.moderation.ownContent"));

		remove(ContentKind.COMMENT, insult.getId(), issue.getId(), RemovalScope.WHOLE, ReportCategory.HARASSMENT);

		assertThatThrownBy(() -> reports.submit(onComment(ReportCategory.HARASSMENT, null, false), reader))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.GONE));
		assertThat(cases()).isEmpty();
	}

	@Test
	void aSecondReportOfTheSamePersonReplacesTheirFirstAndCountsNothingTwice() {
		reports.submit(onComment(ReportCategory.HARASSMENT, null, false), reader);
		reports.submit(onComment(ReportCategory.SPAM, "Werbung fuer den eigenen Laden", false), reader);

		assertThat(cases()).singleElement().satisfies(opened -> {
			assertThat(opened.getReportCount()).isEqualTo(1);
			assertThat(opened.getCategories()).containsExactlyInAnyOrder(ReportCategory.HARASSMENT,
					ReportCategory.SPAM);
		});
		assertThat(mongo.findAll(ContentReport.class)).singleElement().satisfies(report -> {
			assertThat(report.getCategory()).isEqualTo(ReportCategory.SPAM);
			assertThat(report.getExplanation()).isEqualTo("Werbung fuer den eigenen Laden");
		});
		// One notice: the second reason of the same person changed the first.
		assertThat(stat(StatMetric.NOTICE, "HARASSMENT")).isEqualTo(1);
		assertThat(stat(StatMetric.NOTICE, "SPAM")).isZero();
		// The moderators heard of the case once.
		assertThat(noticesOf(moderator, Notification.Type.MODERATION_CASE_OPENED)).hasSize(1);
	}

	@Test
	void twentyReportsAtOnceByTwoPeopleMakeOneCase() throws Exception {
		List<Callable<Void>> calls = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			User by = i % 2 == 0 ? reader : owner;
			calls.add(() -> {
				reports.submit(onComment(ReportCategory.HARASSMENT, null, false), by);
				return null;
			});
		}
		ExecutorService pool = Executors.newFixedThreadPool(20);
		try {
			for (Future<Void> done : pool.invokeAll(calls)) {
				done.get();
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(cases()).singleElement()
				.satisfies(opened -> assertThat(opened.getReportCount()).isEqualTo(2));
		assertThat(mongo.findAll(ContentReport.class)).hasSize(2);
		assertThat(stat(StatMetric.NOTICE, "HARASSMENT")).isEqualTo(2);
	}

	@Test
	void theTextCheckFindingTheSameAgainCountsAHitNotAReport() {
		com.ahmadre.hinata.content.ContentSnapshot snapshot = surface(ContentKind.COMMENT)
				.forModeration(new TargetRef(ContentKind.COMMENT, insult.getId(), issue.getId())).orElseThrow();
		ContentReportService.Target target = new ContentReportService.Target(snapshot.ref(), snapshot,
				ContentReportService.locationOf(snapshot));
		ContentReportService.Source check = new ContentReportService.Source(ReportSource.AUTOMATED,
				"auto:comment.body", null, null);

		reports.file(target, ReportCategory.HARASSMENT, null, check);
		reports.file(target, ReportCategory.HARASSMENT, null, check);
		reports.submit(onComment(ReportCategory.HARASSMENT, null, false), reader);

		assertThat(cases()).singleElement().satisfies(opened -> {
			assertThat(opened.getReportCount()).isEqualTo(2);
			assertThat(opened.getSources()).containsExactlyInAnyOrder(ReportSource.AUTOMATED, ReportSource.MEMBER);
		});
		assertThat(mongo.findOne(Query.query(Criteria.where("reporterKey").is("auto:comment.body")),
				ContentReport.class)).satisfies(found -> {
			assertThat(found.getHits()).isEqualTo(2);
			assertThat(found.getLastSeenAt()).isNotNull();
		});
		// The text check's find is no notice; the member's report is one.
		assertThat(stat(StatMetric.NOTICE, "HARASSMENT")).isEqualTo(1);
	}

	@Test
	void everyReportOfSomethingElseIsACaseOfItsOwn() {
		ContentReportService.ReportRequest elsewhere = new ContentReportService.ReportRequest(
				new TargetRef(ContentKind.OTHER, null, null), ReportCategory.OTHER,
				"Der Name des Teams beleidigt Mitglieder", "Team Aufbau", false);

		reports.submit(elsewhere, reader);
		reports.submit(elsewhere, owner);

		assertThat(cases()).hasSize(2).allSatisfy(opened -> {
			assertThat(opened.getTargetKey()).isNull();
			assertThat(opened.getLocation()).isEqualTo("Team Aufbau");
			assertThat(opened.getReportCount()).isEqualTo(1);
		});
		assertThatThrownBy(() -> reports.submit(new ContentReportService.ReportRequest(
				new TargetRef(ContentKind.OTHER, null, null), ReportCategory.OTHER,
				"Der Name des Teams beleidigt Mitglieder", " ", false), reader))
				.isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.getMessageKey())
						.isEqualTo("error.moderation.locationRequired"));
	}

	@Test
	void theCommentsAPersonReportedStayFoldedForThemUntilRemoved() {
		IssueComment other = issues.addComment(issue.getId(), richText.fromLexical(doc("Noch einer")), null,
				author);
		reports.submit(onComment(ReportCategory.HARASSMENT, null, false), reader);
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.COMMENT, other.getId(),
				null), ReportCategory.SPAM, null, null, false), reader);
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.ISSUE, issue.getId(),
				null), ReportCategory.SPAM, null, null, false), reader);

		// Comments only, and nobody else's.
		assertThat(reports.reportedCommentIds(reader)).containsExactlyInAnyOrder(other.getId(), insult.getId());
		assertThat(reports.reportedCommentIds(moderator)).isEmpty();

		mongo.updateFirst(Query.query(Criteria.where("targetId").is(insult.getId())),
				new Update().set("removed", true), ContentReport.class);
		assertThat(reports.reportedCommentIds(reader)).containsExactly(other.getId());
	}

	@Test
	void anIllegalReportNeedsAReason() {
		assertThatThrownBy(() -> reports.submit(onComment(ReportCategory.ILLEGAL, "kurz", false), reader))
				.isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.getMessageKey())
						.isEqualTo("error.moderation.explanationRequired"));
		assertThat(cases()).isEmpty();
	}

	@Test
	void alsoBlockBlocksTheAuthorOfACommentAndNothingElse() {
		reports.submit(onComment(ReportCategory.HARASSMENT, null, true), reader);

		assertThat(mongo.getCollection("user_blocks").countDocuments(new org.bson.Document("blockerId",
				reader.getId()).append("blockedId", author.getId()))).isEqualTo(1);
		assertThatThrownBy(() -> reports.submit(new ContentReportService.ReportRequest(
				new TargetRef(ContentKind.ISSUE, issue.getId(), null), ReportCategory.SPAM, null, null, true), reader))
				.isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.getMessageKey())
						.isEqualTo("error.moderation.blockNotApplicable"));
		assertThat(cases()).hasSize(1);
	}

	@Test
	void theLimitsCountWhatEveryInstanceFiled() {
		// Filed by another instance: only the database knows.
		List<ContentReport> earlier = new ArrayList<>();
		for (int i = 0; i < ContentReportService.PER_HOUR; i++) {
			earlier.add(ContentReport.builder().caseId("elsewhere-" + i).reporterKey(reader.getId())
					.reporterId(reader.getId()).category(ReportCategory.SPAM)
					.createdAt(Instant.now().minusSeconds(60)).build());
		}
		mongo.insertAll(earlier);

		assertThatThrownBy(() -> reports.submit(onComment(ReportCategory.HARASSMENT, null, false), reader))
				.isInstanceOfSatisfying(ApiException.class, refused -> {
					assertThat(refused.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
					assertThat(refused.getDetails()).containsEntry("retryAfter", "3600");
				});
		assertThat(cases()).isEmpty();
	}

	@Test
	void theSixthUrgentReportOfADayWaits() {
		List<ContentReport> earlier = new ArrayList<>();
		for (int i = 0; i < ContentReportService.URGENT_PER_DAY; i++) {
			earlier.add(ContentReport.builder().caseId("elsewhere-" + i).reporterKey(reader.getId())
					.reporterId(reader.getId()).category(ReportCategory.THREAT)
					.createdAt(Instant.now().minusSeconds(7200)).build());
		}
		mongo.insertAll(earlier);

		assertThatThrownBy(() -> reports.submit(onComment(ReportCategory.THREAT, null, false), reader))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getDetails()).containsEntry("retryAfter", "86400"));
		reports.submit(onComment(ReportCategory.HARASSMENT, null, false), reader);
		assertThat(cases()).hasSize(1);
	}

	@Test
	void aCaseNobodyMayDecideTellsTheAdmins() {
		User admin = user("ada", Role.ADMIN);
		// The only moderator reports, and may not decide what they reported.
		project.getMemberIds().add(moderator.getId());
		projects.save(project);

		reports.submit(onComment(ReportCategory.HARASSMENT, null, false), moderator);

		assertThat(noticesOf(admin, Notification.Type.MODERATION_ADMIN_ALERT)).singleElement()
				.satisfies(alert -> assertThat(alert.getLink()).isEqualTo("/admin/users"));
		// Reported by somebody else, it has a moderator to decide it.
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.ISSUE, issue.getId(), null),
				ReportCategory.SPAM, null, null, false), reader);
		assertThat(noticesOf(admin, Notification.Type.MODERATION_ADMIN_ALERT)).hasSize(1);
	}
}
