package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.RestoreOutcome;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Appeals and undoing a removal (HIN-171): once, within six months, from the app or entered from a
 * reply, decided by somebody other than who decided, and told on the notice's own path.
 */
class AppealIntegrationTest extends RemovalIntegrationSupport {

	private static final String APPEAL = "Das war Satire unter Kollegen, alle wussten das.";
	private static final String NOTE = "Im Zusammenhang gelesen ist der Kommentar Satire.";

	@Autowired
	private ModerationCaseController moderation;
	@Autowired
	private MemberModerationController member;
	@Autowired
	private ContentReportService reports;
	@Autowired
	private RichTextService richText;
	@Autowired
	private IssueCommentRepository comments;
	@Autowired
	private SettingsService settings;

	private User mira;
	private User rita;
	private Issue issue;
	private IssueComment insult;
	private ModerationCase decided;

	@BeforeEach
	void seedADecision() {
		contactIs(settings, DecisionIntegrationTest.CONTACT);
		mira = user("mira", Role.MODERATOR);
		rita = user("rita", Role.MODERATOR);
		issue = issue("Stand B12", doc("Aufbau"));
		insult = issues.addComment(issue.getId(), richText.fromLexical(doc(DecisionIntegrationTest.INSULT)), null,
				author);
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.COMMENT, insult.getId(),
				issue.getId()), ReportCategory.HARASSMENT, null, null, false), reader);
		ModerationCase c = mongo.findOne(Query.query(Criteria.where("target.id").is(insult.getId())), ModerationCase.class);
		actingAs(mira);
		moderation.decide(c.getId(), DecisionIntegrationTest.action(0, RemovalScope.WHOLE));
		followUpsDone();
		decided = reload();
	}

	private ModerationCase reload() {
		return mongo.findOne(Query.query(Criteria.where("target.id").is(insult.getId())), ModerationCase.class);
	}

	private List<Notification> notices(User person, Notification.Type type) {
		return mongo.find(Query.query(Criteria.where("userId").is(person.getId()).and("type").is(type)),
				Notification.class);
	}

	private static String keyOf(Throwable refused) {
		return ((ApiException) refused).getMessageKey();
	}

	private String commentText() {
		return comments.findById(insult.getId()).orElseThrow().getText();
	}

	@Test
	void anAppealInTheAppGoesToSomebodyElseAndReversingPutsTheContentBack() {
		actingAs(author);
		Statement filed = member.appeal(decided.getId(), new AppealService.AppealRequest(APPEAL));

		assertThat(filed.appealState()).isEqualTo(Appeal.State.PENDING);
		assertThat(filed.appealable()).isFalse();
		assertThatThrownBy(() -> member.appeal(decided.getId(), new AppealService.AppealRequest(APPEAL)))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.appealExists"));
		assertThat(notices(rita, Notification.Type.MODERATION_APPEAL_FILED)).hasSize(1);
		assertThat(notices(mira, Notification.Type.MODERATION_APPEAL_FILED)).as("who decided does not review").isEmpty();

		long rev = reload().getRev();
		actingAs(mira);
		assertThatThrownBy(() -> moderation.decideAppeal(decided.getId(), new AppealService.AppealDecisionRequest(rev,
				AppealService.AppealDecisionRequest.Result.REVERSE, NOTE)))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.conflictOfInterest"));
		actingAs(rita);
		CaseViews.CaseSummary reversed = moderation.decideAppeal(decided.getId(), new AppealService.AppealDecisionRequest(
				rev, AppealService.AppealDecisionRequest.Result.REVERSE, NOTE));
		followUpsDone();

		assertThat(reversed.state()).isEqualTo(CaseViews.CaseState.NO_ACTION);
		assertThat(reversed.removed()).isFalse();
		assertThat(commentText()).contains(DecisionIntegrationTest.INSULT);
		ModerationCase after = reload();
		assertThat(after.getAppeal().state()).isEqualTo(Appeal.State.REVERSED);
		assertThat(after.getAppeal().decidedBy()).isEqualTo(rita.getId());
		assertThat(after.getAppeal().restored()).isEqualTo(RestoreOutcome.RESTORED);
		assertThat(after.getRestoration().outcome()).isEqualTo(RestoreOutcome.RESTORED);
		assertThat(notices(author, Notification.Type.MODERATION_NOTICE_UPDATED)).hasSize(1);
		assertThat(mongo.findAll(ContentReport.class)).allSatisfy(report -> assertThat(report.isRemoved()).isFalse());
		assertThat(mongo.count(Query.query(Criteria.where("action").is(AuditAction.MODERATION_APPEAL_DECIDED)),
				AuditLog.class)).isEqualTo(1);
		actingAs(author);
		assertThat(member.statement(decided.getId()).appeal()).isEqualTo("Your appeal was successful. The content is back.");
	}

	@Test
	void anAppealByReplyIsEnteredByAnybodyWhoSeesTheCaseAndDecidedTheSameWay() {
		AppealService.RecordedAppealRequest byReply = new AppealService.RecordedAppealRequest(APPEAL,
				Instant.now().minus(Duration.ofHours(2)));

		moderation.recordAppeal(decided.getId(), byReply);

		ModerationCase filed = reload();
		assertThat(filed.getAppeal().state()).isEqualTo(Appeal.State.PENDING);
		assertThat(filed.getAppeal().via()).isEqualTo(Appeal.Via.REPLY);
		assertThat(mongo.count(Query.query(Criteria.where("action").is(AuditAction.MODERATION_APPEAL_RECORDED)),
				AuditLog.class)).isEqualTo(1);
		actingAs(rita);
		moderation.decideAppeal(decided.getId(), new AppealService.AppealDecisionRequest(filed.getRev(),
				AppealService.AppealDecisionRequest.Result.UPHOLD, NOTE));
		followUpsDone();

		assertThat(reload().getAppeal().state()).isEqualTo(Appeal.State.UPHELD);
		assertThat(commentText()).doesNotContain(DecisionIntegrationTest.INSULT);
		assertThat(notices(author, Notification.Type.MODERATION_NOTICE_UPDATED)).hasSize(1);
		actingAs(author);
		assertThat(member.statement(decided.getId()).appeal()).contains("The decision stands");
	}

	@Test
	void anAppealIsOnlyForThePersonResponsibleAndOnlyForSixMonths() {
		actingAs(reader);
		assertThatThrownBy(() -> member.statement(decided.getId())).isInstanceOf(ApiException.class)
				.satisfies(refused -> assertThat(((ApiException) refused).getStatus().value()).isEqualTo(404));

		mongo.updateFirst(Query.query(Criteria.where("_id").is(decided.getId())), new Update()
				.set("decision.decidedAt", Instant.now().minus(Duration.ofDays(200))), ModerationCase.class);
		actingAs(author);
		assertThat(member.statement(decided.getId()).appealable()).isFalse();
		assertThat(member.statement(decided.getId()).appeal()).startsWith("The time for an appeal ended on");
		assertThatThrownBy(() -> member.appeal(decided.getId(), new AppealService.AppealRequest(APPEAL)))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.appealClosed"));
	}

	@Test
	void undoingARemovalOfTextWrittenAnewSinceKeepsTheNewText() {
		// A removed comment cannot be edited; an issue's title can, by anybody who edits the issue.
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.ISSUE, issue.getId(), null),
				ReportCategory.HARASSMENT, null, null, false), reader);
		ModerationCase onIssue = mongo.findOne(Query.query(Criteria.where("target.id").is(issue.getId())),
				ModerationCase.class);
		moderation.decide(onIssue.getId(), DecisionIntegrationTest.action(0, RemovalScope.TITLE));
		followUpsDone();
		issues.update(issue.getId(), i -> i.setTitle("Stand B12 aufbauen"), owner);

		moderation.restore(onIssue.getId(), new DecisionService.RestoreRequest(1L, NOTE));
		followUpsDone();

		ModerationCase after = mongo.findById(onIssue.getId(), ModerationCase.class);
		assertThat(after.getRestoration().outcome()).isEqualTo(RestoreOutcome.REWRITTEN_SINCE);
		assertThat(issues.get(issue.getId()).getTitle()).isEqualTo("Stand B12 aufbauen");
		assertThat(notices(owner, Notification.Type.MODERATION_NOTICE_UPDATED)).hasSize(1);
		assertThat(mongo.count(Query.query(Criteria.where("action").is(AuditAction.MODERATION_RESTORED)),
				AuditLog.class)).isEqualTo(1);
		actingAs(owner);
		assertThat(member.statement(onIssue.getId()).appeal()).contains("written anew");
		assertThatThrownBy(() -> member.appeal(onIssue.getId(), new AppealService.AppealRequest(APPEAL)))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.appealClosed"));
	}

	@Test
	void undoingTheRemovalOfDeletedContentSaysItIsGone() {
		issues.deleteComment(issue.getId(), insult.getId(), author);

		moderation.restore(decided.getId(), new DecisionService.RestoreRequest(decided.getRev(), NOTE));

		assertThat(reload().getRestoration().outcome()).isEqualTo(RestoreOutcome.GONE);
		actingAs(author);
		assertThat(member.statement(decided.getId()).appeal()).contains("no longer exists");
	}
}
