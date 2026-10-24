package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.admin.AdminUserService;
import com.ahmadre.hinata.admin.SuspensionService.LiftReason;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.me.RefreshSession;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.people.PersonProfileService;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Suspending through a case (HIN-172): only as the effect of a decision, with one statement for a
 * removal and a suspension together, a second person for anybody with a role, and every way of
 * ending it ending the same, once.
 */
class SuspensionIntegrationTest extends RemovalIntegrationSupport {

	private static final String NOTE = "Die Sperre war nach dem Gespraech nicht mehr noetig.";

	@Autowired
	private ModerationCaseController moderation;
	@Autowired
	private ContentReportService reports;
	@Autowired
	private RichTextService richText;
	@Autowired
	private AdminUserService adminUsers;
	@Autowired
	private ModerationSuspensionJob suspensionJob;
	@Autowired
	private PersonProfileService profiles;
	@Autowired
	private SettingsService settings;
	@Autowired
	private org.springframework.context.MessageSource messages;

	private User mira;
	private User rita;
	private User admin;
	private Issue issue;

	@BeforeEach
	void seed() {
		mongo.getCollection("sessions").deleteMany(new org.bson.Document());
		contactIs(settings, DecisionIntegrationTest.CONTACT);
		mira = user("mira", Role.MODERATOR);
		rita = user("rita", Role.MODERATOR);
		admin = user("ada", Role.ADMIN);
		issue = issue("Stand B12", doc("Aufbau"));
		actingAs(mira);
	}

	private ModerationCase reportedCommentOf(User writer, String text) {
		if (!project.getMemberIds().contains(writer.getId())) {
			project.getMemberIds().add(writer.getId());
			projects.save(project);
		}
		IssueComment comment = issues.addComment(issue.getId(), richText.fromLexical(doc(text)), null, writer);
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.COMMENT, comment.getId(),
				issue.getId()), ReportCategory.HARASSMENT, null, null, false), writer == reader ? owner : reader);
		return mongo.findOne(Query.query(Criteria.where("target.id").is(comment.getId())), ModerationCase.class);
	}

	private static DecisionService.DecisionRequest suspending(long rev, RemovalScope removal,
			Decision.SuspensionLength length) {
		return new DecisionService.DecisionRequest(rev, Decision.Outcome.ACTION,
				removal == null ? null : new DecisionService.RemovalChoice(removal), Decision.Ground.RULES,
				ReportCategory.HARASSMENT, DecisionIntegrationTest.FACTS, "Hausregel 2: Kein Herabsetzen anderer", null,
				null, false, null, null, false, new DecisionService.SuspensionChoice(length));
	}

	private User reload(User person) {
		return users.findById(person.getId()).orElseThrow();
	}

	private ModerationCase reload(ModerationCase c) {
		return mongo.findById(c.getId(), ModerationCase.class);
	}

	private long notices(User person, Notification.Type type, ModerationCase about) {
		return mongo.count(Query.query(Criteria.where("userId").is(person.getId()).and("type").is(type)
				.and("link").is(DecisionNotices.noticeLink(about))), Notification.class);
	}

	private List<Notification> notices(User person, Notification.Type type) {
		return mongo.find(Query.query(Criteria.where("userId").is(person.getId()).and("type").is(type)),
				Notification.class);
	}

	/** Never active and suspended at once, after any way in or out. */
	private void assertInvariant(User person) {
		User now = reload(person);
		assertThat(now.isActive() && now.isSuspended()).as("active and suspended at once").isFalse();
	}

	private static String keyOf(Throwable refused) {
		return ((ApiException) refused).getMessageKey();
	}

	@Test
	void aRemovalWithASuspensionIsOneStatementAndKeepsThePersonOut() {
		mongo.insert(RefreshSession.builder().userId(author.getId()).createdAt(Instant.now()).build());
		ModerationCase c = reportedCommentOf(author, DecisionIntegrationTest.INSULT);

		CaseViews.CaseSummary decided = moderation.decide(c.getId(), suspending(0, RemovalScope.WHOLE,
				Decision.SuspensionLength.DAYS_7));
		followUpsDone();

		assertThat(decided.suspended()).isTrue();
		User out = reload(author);
		assertThat(out.isActive()).isFalse();
		assertThat(out.getSuspension().caseId()).isEqualTo(c.getId());
		assertThat(out.getSuspension().until()).isBetween(Instant.now().plus(Duration.ofDays(6)),
				Instant.now().plus(Duration.ofDays(8)));
		assertThat(mongo.count(Query.query(Criteria.where("userId").is(author.getId())), RefreshSession.class)).isZero();
		assertThat(reload(c).getSuspension().running()).isTrue();
		assertThat(notices(author, Notification.Type.MODERATION_NOTICE, c)).isEqualTo(1);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> model = ArgumentCaptor.forClass(Map.class);
		verify(mail, timeout(3000)).sendTemplate(eq(author.getEmail()), anyString(), eq("email/moderation-notice"),
				model.capture(), eq(DecisionIntegrationTest.CONTACT));
		assertThat((String) model.getValue().get("measure")).contains("was removed", "Your account is suspended until");
		verify(push, never()).sendToUser(eq(author.getId()), any(), any(), any(), any());
		assertInvariant(author);
	}

	@Test
	void aSuspensionAloneLeavesTheContentAndEndsByItselfOnce() {
		ModerationCase c = reportedCommentOf(author, "Halt den Mund");
		moderation.decide(c.getId(), suspending(0, null, Decision.SuspensionLength.DAYS_30));
		followUpsDone();
		assertThat(reload(c).getRemovalState()).isNull();
		// The reporter learns that moderation acted, not that something was removed, nor what.
		assertThat(notices(reader, Notification.Type.MODERATION_DECISION)).singleElement()
				.satisfies(notice -> assertThat(notice.getBody()).startsWith(
						messages.getMessage("moderation.decision.ACTED", null, java.util.Locale.ENGLISH)));

		mongo.updateFirst(Query.query(Criteria.where("_id").is(author.getId())),
				new Update().set("suspension.until", Instant.now().minusSeconds(60)), User.class);
		suspensionJob.run();
		suspensionJob.run();
		followUpsDone();

		assertThat(reload(author).isActive()).isTrue();
		assertThat(reload(author).isSuspended()).isFalse();
		assertThat(reload(c).getSuspension().liftedHow()).isEqualTo(LiftReason.EXPIRED);
		assertThat(notices(author, Notification.Type.MODERATION_NOTICE_UPDATED, c)).isEqualTo(1);
		assertInvariant(author);
	}

	@Test
	void everyWayOfLiftingEndsTheSameAndTellsOnce() {
		Map<LiftReason, Consumer<ModerationCase>> ways = new java.util.LinkedHashMap<>();
		ways.put(LiftReason.MODERATION, c -> moderation.liftSuspension(c.getId(),
				new DecisionService.NoteRequest(reload(c).getRev(), NOTE)));
		ways.put(LiftReason.ADMIN, c -> {
			actingAs(admin);
			adminUsers.liftSuspension(author.getId(), "Klaerendes Gespraech gefuehrt");
			actingAs(mira);
		});
		ways.put(LiftReason.APPEAL, c -> {
			moderation.recordAppeal(c.getId(), new AppealService.RecordedAppealRequest("Das war Satire unter Kollegen.",
					Instant.now().minusSeconds(60)));
			actingAs(rita);
			moderation.decideAppeal(c.getId(), new AppealService.AppealDecisionRequest(reload(c).getRev(),
					AppealService.AppealDecisionRequest.Result.REVERSE, NOTE));
			actingAs(mira);
		});
		int round = 0;
		for (Map.Entry<LiftReason, Consumer<ModerationCase>> way : ways.entrySet()) {
			ModerationCase c = reportedCommentOf(author, "Beleidigung Nummer " + round++);
			moderation.decide(c.getId(), suspending(0, null, Decision.SuspensionLength.INDEFINITE));
			followUpsDone();
			assertThat(reload(author).isSuspended()).as(way.getKey().name()).isTrue();

			way.getValue().accept(c);
			suspensionJob.run();
			followUpsDone();

			assertThat(reload(author).isActive()).as(way.getKey().name()).isTrue();
			assertThat(reload(c).getSuspension().liftedHow()).isEqualTo(way.getKey());
			assertThat(notices(author, Notification.Type.MODERATION_NOTICE_UPDATED, c)).as(way.getKey().name())
					.isEqualTo(1);
			assertInvariant(author);
		}
		assertThat(notices(mira, Notification.Type.MODERATION_TEAM_NOTE)).as("an admin lifted one").hasSize(1);
	}

	@Test
	void activatingASuspendedAccountLiftsItAndDeactivatingEndsItKeepingTheAccountOut() {
		ModerationCase activated = reportedCommentOf(author, "Erste Beleidigung");
		moderation.decide(activated.getId(), suspending(0, null, Decision.SuspensionLength.INDEFINITE));
		actingAs(admin);
		adminUsers.setStatus(List.of(author.getId()), "ACTIVE");
		assertThat(reload(author).isActive()).isTrue();
		assertThat(reload(activated).getSuspension().liftedHow()).isEqualTo(LiftReason.ADMIN);
		assertInvariant(author);

		actingAs(mira);
		ModerationCase deactivated = reportedCommentOf(author, "Zweite Beleidigung");
		moderation.decide(deactivated.getId(), suspending(0, null, Decision.SuspensionLength.DAYS_7));
		actingAs(admin);
		adminUsers.setStatus(List.of(author.getId()), "DISABLED");
		followUpsDone();

		User out = reload(author);
		assertThat(out.isActive()).isFalse();
		assertThat(out.isSuspended()).isFalse();
		assertThat(reload(deactivated).getSuspension().liftedHow()).isEqualTo(LiftReason.DEACTIVATED);
		assertThat(notices(author, Notification.Type.MODERATION_NOTICE_UPDATED, deactivated)).isZero();
		assertThat(notices(author, Notification.Type.ACCOUNT_DEACTIVATED)).hasSize(1);
		suspensionJob.run();
		assertThat(reload(author).isActive()).as("the deactivation does not end by itself").isFalse();
	}

	@Test
	void aPersonWithARoleIsSuspendedOnlyWhenASecondPersonConfirms() {
		User karl = user("karl", Role.MODERATOR);
		ModerationCase confirmed = reportedCommentOf(karl, "Halt den Mund");

		CaseViews.CaseSummary pending = moderation.decide(confirmed.getId(), suspending(0, RemovalScope.WHOLE,
				Decision.SuspensionLength.DAYS_7));

		assertThat(pending.state()).isEqualTo(CaseViews.CaseState.PENDING_CONFIRMATION);
		assertThat(reload(karl).isActive()).isTrue();
		assertThat(notices(rita, Notification.Type.MODERATION_CONFIRM_REQUESTED)).hasSize(1);
		assertThat(notices(mira, Notification.Type.MODERATION_CONFIRM_REQUESTED)).isEmpty();
		assertThat(notices(admin, Notification.Type.MODERATION_ADMIN_ALERT)).hasSize(1);
		assertThatThrownBy(() -> moderation.confirm(confirmed.getId(), new DecisionService.StepRequest(pending.rev())))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.conflictOfInterest"));
		assertThatThrownBy(() -> moderation.decide(confirmed.getId(), suspending(pending.rev(), RemovalScope.WHOLE,
				Decision.SuspensionLength.DAYS_7)))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.confirmationPending"));
		actingAs(rita);
		moderation.confirm(confirmed.getId(), new DecisionService.StepRequest(pending.rev()));

		assertThat(reload(karl).isSuspended()).isTrue();
		assertThat(reload(confirmed).getDecision().confirmedBy()).isEqualTo(rita.getId());
		assertThat(reload(confirmed).getDecision().decidedBy()).isEqualTo(mira.getId());

		// Both who decided and who confirmed stay out of the appeal; a third person decides it.
		moderation.recordAppeal(confirmed.getId(), new AppealService.RecordedAppealRequest(
				"Das war ein Scherz unter Kollegen.", Instant.now()));
		long appealed = reload(confirmed).getRev();
		AppealService.AppealDecisionRequest uphold = new AppealService.AppealDecisionRequest(appealed,
				AppealService.AppealDecisionRequest.Result.UPHOLD, "Die Sperre bleibt, der Ton war verletzend.");
		assertThatThrownBy(() -> moderation.decideAppeal(confirmed.getId(), uphold))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.conflictOfInterest"));
		actingAs(user("nina", Role.MODERATOR));
		moderation.decideAppeal(confirmed.getId(), uphold);
		assertThat(reload(confirmed).getAppeal().state()).isEqualTo(Appeal.State.UPHELD);

		actingAs(mira);
		User olga = user("olga", Role.ORG_ADMIN);
		user("otto", Role.ORG_ADMIN);
		ModerationCase rejected = reportedCommentOf(olga, "Du nervst");
		long rev = moderation.decide(rejected.getId(), suspending(0, null, Decision.SuspensionLength.DAYS_7)).rev();
		actingAs(rita);
		CaseViews.CaseSummary reopened = moderation.reject(rejected.getId(), new DecisionService.NoteRequest(rev, NOTE));

		assertThat(reopened.state()).isNotEqualTo(CaseViews.CaseState.PENDING_CONFIRMATION);
		assertThat(reload(rejected).getStatus()).isEqualTo(CaseStatus.OPEN);
		assertThat(reload(rejected).getPendingDecision()).isNull();
		assertThat(reload(olga).isActive()).isTrue();
	}

	@Test
	void administratorsTheLastOrgAdminAndContentFromMailCannotBeSuspended() {
		ModerationCase byAdmin = reportedCommentOf(admin, "Halt den Mund");
		assertThatThrownBy(() -> moderation.decide(byAdmin.getId(), suspending(0, null, Decision.SuspensionLength.DAYS_7)))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.cannotSuspendAdmin"));

		User lastOrgAdmin = user("olga", Role.ORG_ADMIN);
		ModerationCase byOrgAdmin = reportedCommentOf(lastOrgAdmin, "Du nervst");
		assertThatThrownBy(() -> moderation.decide(byOrgAdmin.getId(), suspending(0, null,
				Decision.SuspensionLength.DAYS_7)))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.user.cannotRemoveLastOrgAdmin"));

		Issue mailed = issue("Rechnung offen", doc("Bitte zahlen Sie sofort"));
		mongo.updateFirst(Query.query(Criteria.where("_id").is(mailed.getId())), new Update()
				.set("inboundMessageId", "<m1@example.org>").set("reporterEmail", "extern@example.org"), Issue.class);
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.ISSUE, mailed.getId(), null),
				ReportCategory.SPAM, null, null, false), reader);
		ModerationCase fromMail = mongo.findOne(Query.query(Criteria.where("target.id").is(mailed.getId())),
				ModerationCase.class);
		assertThatThrownBy(() -> moderation.decide(fromMail.getId(), suspending(0, RemovalScope.WHOLE,
				Decision.SuspensionLength.DAYS_7)))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.suspensionNotAllowed"));
	}

	@Test
	void moderatorsListSuspendedAccountsWithoutTheCasesAboutTheirOwnContent() {
		ModerationCase aboutAuthor = reportedCommentOf(author, "Halt den Mund");
		moderation.decide(aboutAuthor.getId(), suspending(0, null, Decision.SuspensionLength.DAYS_7));
		ModerationCase aboutReader = reportedCommentOf(reader, "Du nervst");
		moderation.decide(aboutReader.getId(), suspending(0, null, Decision.SuspensionLength.DAYS_30));
		mongo.updateFirst(Query.query(Criteria.where("_id").is(aboutReader.getId())),
				new Update().addToSet("contributorIds", rita.getId()), ModerationCase.class);

		assertThat(moderation.suspensions(0, 1).getTotalElements()).isEqualTo(2);
		assertThat(moderation.suspensions(0, 1).getContent()).hasSize(1);
		actingAs(rita);
		assertThat(moderation.suspensions(0, 25).getContent()).extracting(SuspendedAccounts.SuspendedAccount::caseId)
				.containsExactly(aboutAuthor.getId());
		// Nor on the profile, nor in a note to the team: the case does not exist for its contributor.
		assertThat(profiles.profile(reload(rita), reader.getId()).suspension().caseId()).isNull();
		assertThat(profiles.profile(reload(rita), author.getId()).suspension().caseId()).isEqualTo(aboutAuthor.getId());
		actingAs(admin);
		adminUsers.liftSuspension(reader.getId(), "Klaerendes Gespraech gefuehrt");
		followUpsDone();
		assertThat(notices(rita, Notification.Type.MODERATION_TEAM_NOTE)).isEmpty();
		assertThat(notices(mira, Notification.Type.MODERATION_TEAM_NOTE)).hasSize(1);
	}

	@Test
	void moderatorsSeeTheProfileOfASuspendedAccountNotOfADeactivatedOne() {
		ModerationCase c = reportedCommentOf(author, "Halt den Mund");
		moderation.decide(c.getId(), suspending(0, null, Decision.SuspensionLength.DAYS_7));
		mongo.updateFirst(Query.query(Criteria.where("_id").is(reader.getId())), new Update().set("active", false),
				User.class);

		assertThat(profiles.profile(reload(mira), author.getId()).suspension().caseId()).isEqualTo(c.getId());
		assertThat(profiles.profile(reload(admin), author.getId()).suspension().caseId()).isNull();
		assertThatThrownBy(() -> profiles.profile(reload(mira), reader.getId()))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
		assertThat(profiles.profile(reload(admin), reader.getId()).suspension()).isNull();
	}
}
