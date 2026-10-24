package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.Quarantine;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.me.PersonalDataExport;
import com.ahmadre.hinata.moderation.quarantine.QuarantinedContent;
import com.ahmadre.hinata.moderation.quarantine.QuarantinedVersion;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What runs without anybody asking (HIN-170): reminders and the word to the admins, the re-drive
 * of deletions a decision left, retention, the personal data export and a deleted account.
 */
class ModerationJobsIntegrationTest extends RemovalIntegrationSupport {

	private static final Pattern NUMBER = Pattern.compile("\\d+");

	@Autowired
	private ContentReportService reports;
	@Autowired
	private ModerationFollowUpJob followUp;
	@Autowired
	private ModerationRetentionJob retention;
	@Autowired
	private ModerationPersonalData personalData;
	@Autowired
	private ApplicationEventPublisher events;
	@Autowired
	private RichTextService richText;
	@Autowired
	private tools.jackson.databind.json.JsonMapper json;

	private User mira;
	private User rita;
	private Issue issue;

	@BeforeEach
	void seedModerators() {
		mira = user("mira", Role.MODERATOR);
		rita = user("rita", Role.MODERATOR);
		issue = issue("Stand B12", doc("Aufbau"));
	}

	private IssueComment comment(String text) {
		return issues.addComment(issue.getId(), richText.fromLexical(doc(text)), null, author);
	}

	private ModerationCase reported(IssueComment comment, ReportCategory category, String explanation, User by) {
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.COMMENT, comment.getId(),
				issue.getId()), category, explanation, null, false), by);
		return mongo.findOne(Query.query(Criteria.where("target.id").is(comment.getId())), ModerationCase.class);
	}

	private void age(ModerationCase c, Duration by) {
		mongo.updateFirst(Query.query(Criteria.where("_id").is(c.getId())),
				new Update().set("firstReportedAt", Instant.now().minus(by)), ModerationCase.class);
	}

	private List<Notification> notices(User person, Notification.Type type) {
		return mongo.find(Query.query(Criteria.where("userId").is(person.getId()).and("type").is(type)),
				Notification.class);
	}

	/** How many cases [notice] counts: its number, or one when it words it ("One report has …"). */
	private static int countIn(Notification notice) {
		Matcher number = NUMBER.matcher(notice.getBody().replace("20", ""));
		return number.find() ? Integer.parseInt(number.group()) : 1;
	}

	// --- follow-up -------------------------------------------------------------------------------

	@Test
	void eachModeratorHearsOnceOfTheirWaitingCasesAndTheAdminsOnceADayLater() {
		User admin = user("ada", Role.ADMIN);
		ModerationCase first = reported(comment("Halt den Mund"), ReportCategory.HARASSMENT, null, reader);
		ModerationCase second = reported(comment("Du nervst"), ReportCategory.HARASSMENT, null, reader);
		reported(comment("Neu"), ReportCategory.HARASSMENT, null, reader);
		age(first, Duration.ofHours(21));
		age(second, Duration.ofHours(21));

		followUp.run();
		followUp.run();

		for (User moderator : List.of(mira, rita)) {
			assertThat(notices(moderator, Notification.Type.MODERATION_CASE_OVERDUE)).singleElement()
					.satisfies(notice -> {
						assertThat(countIn(notice)).isEqualTo(2);
						assertThat(notice.getLink()).isEqualTo("/moderation");
					});
		}
		assertThat(notices(admin, Notification.Type.MODERATION_ADMIN_ALERT)).isEmpty();

		age(first, Duration.ofHours(25));
		followUp.run();
		followUp.run();
		assertThat(notices(admin, Notification.Type.MODERATION_ADMIN_ALERT)).hasSize(1);
		assertThat(notices(mira, Notification.Type.MODERATION_CASE_OVERDUE)).hasSize(1);
	}

	@Test
	void twoRunsAtOnceRemindOfEveryCaseOnce() {
		for (int i = 0; i < 10; i++) {
			age(reported(comment("Beleidigung " + i), ReportCategory.HARASSMENT, null, reader), Duration.ofHours(21));
		}
		Instant now = Instant.now();

		CompletableFuture.allOf(CompletableFuture.runAsync(() -> followUp.remind(now)),
				CompletableFuture.runAsync(() -> followUp.remind(now))).join();

		assertThat(notices(mira, Notification.Type.MODERATION_CASE_OVERDUE).stream()
				.mapToInt(ModerationJobsIntegrationTest::countIn).sum()).isEqualTo(10);
		assertThat(mongo.count(Query.query(Criteria.where("remindedAt").exists(true)), ModerationCase.class))
				.isEqualTo(10);
	}

	@Test
	void theReDriveDeletesWhatADecisionLeftBehind() {
		store.put("object-a", "a");
		store.put("object-b", "b");
		store.refuseDeletes.add("object-b");
		ModerationCase decided = mongo.insert(ModerationCase.builder().status(CaseStatus.DECIDED)
				.decidedAt(Instant.now().minus(Duration.ofHours(1))).removalState(RemovalState.INCOMPLETE)
				.pendingDeletes(new ArrayList<>(List.of("object-a"))).build());
		ModerationCase stuck = mongo.insert(ModerationCase.builder().status(CaseStatus.DECIDED)
				.decidedAt(Instant.now().minus(Duration.ofHours(1))).removalState(RemovalState.INCOMPLETE)
				.pendingDeletes(new ArrayList<>(List.of("object-b"))).build());

		followUp.redrive(Instant.now());

		assertThat(store.has("object-a")).isFalse();
		ModerationCase done = mongo.findById(decided.getId(), ModerationCase.class);
		assertThat(done.getPendingDeletes()).isEmpty();
		assertThat(done.getRemovalState()).isEqualTo(RemovalState.DONE);
		ModerationCase left = mongo.findById(stuck.getId(), ModerationCase.class);
		assertThat(left.getPendingDeletes()).containsExactly("object-b");
		assertThat(left.getRemovalState()).isEqualTo(RemovalState.INCOMPLETE);
	}

	// --- retention ---------------------------------------------------------------------------------

	@Test
	void decidedCasesGoWhenTheirTimeIsUpWithEverythingTheyHeld() {
		Instant past = Instant.now().minus(Duration.ofDays(1));
		Instant future = Instant.now().plus(Duration.ofDays(30));
		ModerationCase due = decided(past, null, null, null);
		ModerationCase broken = decided(past, null, null, Instant.now().minus(Duration.ofHours(2)));
		ModerationCase appealed = decided(past, new Appeal(Appeal.State.PENDING, "Das war Satire", past, null, null),
				null, null);
		ModerationCase held = decided(past, null, future, null);
		ModerationCase running = decided(past, null, null, Instant.now().minus(Duration.ofMinutes(10)));
		ModerationCase notYet = decided(future, null, null, null);
		ModerationCase open = mongo.insert(ModerationCase.builder().status(CaseStatus.OPEN).build());
		for (ModerationCase c : List.of(due, broken, appealed, held)) {
			heldFor(c);
		}

		retention.run();

		for (ModerationCase gone : List.of(due, broken)) {
			assertThat(mongo.findById(gone.getId(), ModerationCase.class)).as(gone.getId()).isNull();
			assertThat(mongo.count(Query.query(Criteria.where("caseId").is(gone.getId())), ContentReport.class))
					.isZero();
			assertThat(mongo.count(Query.query(Criteria.where("caseId").is(gone.getId())), QuarantinedContent.class))
					.isZero();
			assertThat(mongo.count(Query.query(Criteria.where("quarantineId").is("q-" + gone.getId())),
					QuarantinedVersion.class)).isZero();
			assertThat(store.has(Quarantine.keyFor(gone.getId(), "object-1"))).isFalse();
		}
		for (ModerationCase kept : List.of(appealed, held, running, notYet, open)) {
			assertThat(mongo.findById(kept.getId(), ModerationCase.class)).as(kept.getId()).isNotNull();
		}
		assertThat(store.has(Quarantine.keyFor(appealed.getId(), "object-1"))).isTrue();
		assertThat(mongo.count(Query.query(Criteria.where("caseId").is(held.getId())), ContentReport.class))
				.isEqualTo(1);
	}

	private ModerationCase decided(Instant retainUntil, Appeal appeal, Instant legalHoldUntil, Instant purgingAt) {
		return mongo.insert(ModerationCase.builder().status(CaseStatus.DECIDED).decidedAt(Instant.now())
				.retainUntil(retainUntil).appeal(appeal).legalHoldUntil(legalHoldUntil).purgingAt(purgingAt).build());
	}

	/** A report, a quarantine with one version and one object, as a decision leaves them. */
	private void heldFor(ModerationCase c) {
		mongo.insert(ContentReport.builder().caseId(c.getId()).reporterKey(reader.getId())
				.category(ReportCategory.HARASSMENT).createdAt(Instant.now()).build());
		mongo.insert(QuarantinedContent.builder().id("q-" + c.getId()).caseId(c.getId())
				.ref(new ContentRef(ContentKind.COMMENT, "comment-" + c.getId(), null, null))
				.createdAt(Instant.now()).build());
		mongo.insert(QuarantinedVersion.builder().quarantineId("q-" + c.getId()).number(1).title("alt").build());
		store.put(Quarantine.keyFor(c.getId(), "object-1"), "bytes");
	}

	// --- personal data -------------------------------------------------------------------------

	@Test
	@SuppressWarnings("unchecked")
	void theExportShowsWhatAPersonReportedAndWhatWasReportedOfThemButNeverByWhom() {
		IssueComment insult = comment("Halt den Mund");
		ModerationCase c = reported(insult, ReportCategory.HARASSMENT, "Beleidigt Lena seit Wochen", reader);
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.COMMENT, insult.getId(),
				issue.getId()), ReportCategory.SPAM, null, null, false), owner);
		mongo.updateFirst(Query.query(Criteria.where("_id").is(c.getId())), new Update()
				.set("decision", decision(Decision.Outcome.ACTION, Decision.Ground.RULES, mira.getId())),
				ModerationCase.class);

		String authorsExport = json.writeValueAsString(personalData.data(author));
		assertThat(authorsExport).contains("casesAboutMyContent", "ACTION", "RULES", "HARASSMENT")
				.doesNotContain(reader.getId(), owner.getId(), "lena", "Beleidigt Lena");
		List<PersonalDataExport.Table> tables = personalData.tables(author, Locale.GERMAN);
		assertThat(tables).extracting(PersonalDataExport.Table::title).contains("Meldungen zu deinen Inhalten");
		assertThat(tables.toString()).doesNotContain("lena", reader.getId());

		Map<String, Object> readers = (Map<String, Object>) personalData.data(reader);
		assertThat((List<?>) readers.get("reports")).singleElement().satisfies(row -> assertThat((Map<String, Object>) row)
				.containsEntry("category", ReportCategory.HARASSMENT)
				.containsEntry("kind", ContentKind.COMMENT)
				.containsEntry("outcome", "ACTION"));
	}

	// --- a deleted account -------------------------------------------------------------------------

	@Test
	void aDeletedAccountIsNamedNowhereAndTheCasesStay() {
		ModerationCase c = reported(comment("Halt den Mund"), ReportCategory.HARASSMENT, null, reader);
		mongo.updateFirst(Query.query(Criteria.where("_id").is(c.getId())), new Update()
				.set("assigneeId", mira.getId()).set("claimedAt", Instant.now()), ModerationCase.class);

		for (User gone : List.of(reader, author, mira)) {
			events.publishEvent(new UserService.UserDeletedEvent(gone.getId()));
		}

		ModerationCase after = mongo.findById(c.getId(), ModerationCase.class);
		assertThat(after).isNotNull();
		assertThat(after.getContributorIds()).doesNotContain(author.getId());
		assertThat(after.getAssigneeId()).isNull();
		assertThat(after.getClaimedAt()).isNull();
		assertThat(mongo.findAll(ContentReport.class)).singleElement().satisfies(report -> {
			assertThat(report.getReporterId()).isNull();
			assertThat(report.getReporterKey()).isEqualTo("deleted:" + report.getId());
		});
	}
}
