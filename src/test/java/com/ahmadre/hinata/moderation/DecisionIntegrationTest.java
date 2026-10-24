package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.ScrubReason;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.moderation.quarantine.QuarantinedContent;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.storage.AttachmentStore;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Deciding a case (HIN-171): saved first at the revision seen, carried out after, and everybody
 * who is owed a word gets it; the person responsible on a path no setting and no inactive account
 * closes.
 */
class DecisionIntegrationTest extends RemovalIntegrationSupport {

	static final String INSULT = "Jonas ist zu bloed fuer den Job";
	static final String FACTS = "Der Kommentar bezeichnet einen Kollegen als zu bloed fuer den Job.";
	static final String CONTACT = "moderation@example.org";

	@Autowired
	private ModerationCaseController moderation;
	@Autowired
	private ContentReportService reports;
	@Autowired
	private RichTextService richText;
	@Autowired
	private IssueCommentRepository comments;
	@Autowired
	private AttachmentStore attachments;
	@Autowired
	private SettingsService settings;
	@Autowired
	private com.ahmadre.hinata.moderation.rules.HouseRulesService houseRules;

	private User mira;
	private Issue issue;

	@BeforeEach
	void seedCaseWorld() {
		contactIs(settings, CONTACT);
		mira = user("mira", Role.MODERATOR);
		issue = issue("Stand B12", doc("Aufbau"));
		actingAs(mira);
	}

	private IssueComment comment(String text) {
		return issues.addComment(issue.getId(), richText.fromLexical(doc(text)), null, author);
	}

	private ModerationCase reported(IssueComment comment) {
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.COMMENT, comment.getId(),
				issue.getId()), ReportCategory.HARASSMENT, null, null, false), reader);
		return mongo.findOne(Query.query(Criteria.where("target.id").is(comment.getId())), ModerationCase.class);
	}

	static DecisionService.DecisionRequest action(long rev, RemovalScope scope) {
		return new DecisionService.DecisionRequest(rev, Decision.Outcome.ACTION, new DecisionService.RemovalChoice(scope),
				Decision.Ground.RULES, ReportCategory.HARASSMENT, FACTS, "Hausregel 2: Kein Herabsetzen anderer", null,
				null, false, "Bitte bleib sachlich.", "Zweiter Vorfall im Projekt", false);
	}

	static DecisionService.DecisionRequest outcome(long rev, Decision.Outcome outcome, String facts) {
		return new DecisionService.DecisionRequest(rev, outcome, null, null, null, facts, null, null, null, false, null,
				null, false);
	}

	private ModerationCase reload(ModerationCase c) {
		return mongo.findById(c.getId(), ModerationCase.class);
	}

	private List<Notification> notices(User person, Notification.Type type) {
		return mongo.find(Query.query(Criteria.where("userId").is(person.getId()).and("type").is(type)),
				Notification.class);
	}

	private static String keyOf(Throwable refused) {
		return ((ApiException) refused).getMessageKey();
	}

	@Test
	void anActionRewritesTheContentAndEverybodyOwedAWordGetsIt() {
		IssueComment insult = comment(INSULT);
		ModerationCase c = reported(insult);

		CaseViews.CaseSummary decided = moderation.decide(c.getId(), action(0, RemovalScope.WHOLE));
		followUpsDone();

		assertThat(decided.state()).isEqualTo(CaseViews.CaseState.ACTED);
		assertThat(decided.removed()).isTrue();
		assertThat(comments.findById(insult.getId()).orElseThrow().getText()).doesNotContain(INSULT);
		assertThat(mongo.count(Query.query(Criteria.where("caseId").is(c.getId())), QuarantinedContent.class))
				.isEqualTo(1);
		ModerationCase after = reload(c);
		assertThat(after.getDecision().decidedBy()).isEqualTo(mira.getId());
		assertThat(after.getDecision().responsibleId()).isEqualTo(author.getId());
		assertThat(after.getDecision().basis()).isEqualTo(Decision.Basis.NOTICE);
		assertThat(after.getDecision().quarantineId()).isNotNull();
		// The version of the house rules in force, which the statement links to (HIN-175).
		assertThat(after.getDecision().rulesVersion()).isEqualTo(houseRules.currentVersion()).isNotNull();
		assertThat(after.getRemovalState()).isEqualTo(RemovalState.DONE);
		assertThat(after.getCopiesScrubbedAt()).isNotNull();
		assertThat(after.getStatementSentAt()).isNotNull();
		assertThat(after.getReportersNotifiedAt()).isNotNull();
		assertThat(after.getFollowUpDue()).isNull();
		assertThat(after.getPendingCopies()).isNull();
		assertThat(after.getRetainUntil()).isAfter(Instant.now().plus(Duration.ofDays(180)));
		verify(copiesScrub).scrub(anyList(), eq(ScrubReason.REMOVED), any());

		assertThat(notices(author, Notification.Type.MODERATION_NOTICE)).singleElement()
				.satisfies(notice -> assertThat(notice.getLink()).isEqualTo("/notices/" + c.getId()));
		verify(mail, timeout(3000)).sendTemplate(eq(author.getEmail()), anyString(), eq("email/moderation-notice"),
				anyMap(), isNull());
		assertThat(notices(reader, Notification.Type.MODERATION_DECISION)).singleElement()
				.satisfies(notice -> assertThat(notice.getBody()).doesNotContain("jonas", INSULT));
		assertThat(mongo.findAll(ContentReport.class)).singleElement().satisfies(report -> {
			assertThat(report.getDecisionNoticeSentAt()).isNotNull();
			assertThat(report.isRemoved()).isTrue();
		});
		assertThat(mongo.find(Query.query(Criteria.where("action").is(AuditAction.MODERATION_DECIDED)), AuditLog.class))
				.singleElement().satisfies(entry -> assertThat(entry.getMetadata()).containsOnlyKeys("case"));
	}

	@Test
	void noViolationAndUnfoundedTellOnlyTheReportersAndKeepNoCopy() {
		ModerationCase fine = reported(comment("Bitte bis Freitag liefern"));
		ModerationCase unfounded = reported(comment("Der Stand ist fertig"));

		assertThatThrownBy(() -> moderation.decide(unfounded.getId(), outcome(0, Decision.Outcome.UNFOUNDED, "kurz")))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.factsRequired"));
		moderation.decide(fine.getId(), outcome(0, Decision.Outcome.NO_VIOLATION, null));
		moderation.decide(unfounded.getId(), outcome(0, Decision.Outcome.UNFOUNDED,
				"Die Meldung nennt einen Kommentar, der nur einen Termin bestaetigt."));
		followUpsDone();

		for (ModerationCase c : List.of(fine, unfounded)) {
			ModerationCase after = reload(c);
			assertThat(after.getEvidence()).isNull();
			assertThat(after.getListExcerpt()).isNull();
			assertThat(after.getRetainUntil()).isBetween(Instant.now().plus(Duration.ofDays(29)),
					Instant.now().plus(Duration.ofDays(31)));
			assertThat(after.getFollowUpDue()).isNull();
		}
		assertThat(notices(reader, Notification.Type.MODERATION_DECISION)).hasSize(2);
		assertThat(notices(author, Notification.Type.MODERATION_NOTICE)).isEmpty();
	}

	@Test
	void handledFitsOnlySomethingElse() {
		ModerationCase onComment = reported(comment("Halt den Mund"));
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.OTHER, null, null),
				ReportCategory.OTHER, "Der Teamname beleidigt Mitglieder", "Team Aufbau", false), reader);
		ModerationCase other = mongo.findOne(Query.query(Criteria.where("target.kind").is(ContentKind.OTHER)),
				ModerationCase.class);
		DecisionService.DecisionRequest handled = new DecisionService.DecisionRequest(0L, Decision.Outcome.HANDLED, null,
				Decision.Ground.RULES, ReportCategory.HARASSMENT, "Die Projektleitung hat den Teamnamen geaendert.", null,
				null, null, false, null, null, false);

		assertThatThrownBy(() -> moderation.decide(onComment.getId(), handled))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.outcomeNotAllowed"));
		assertThat(moderation.decide(other.getId(), handled).state()).isEqualTo(CaseViews.CaseState.ACTED);
		assertThatThrownBy(() -> moderation.decide(onComment.getId(), action(0, RemovalScope.TITLE)))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.scopeNotAllowed"));
	}

	@Test
	void aSensitiveImageTakenFromEveryIssueNamesItsOtherPlacesInTheCase() {
		String shared = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
		store.put("media/" + shared, "jpeg");
		Issue photo = issue("Foto", docWithImage("Vom Fest", shared));
		Issue copy = issue("Foto, Kopie", docWithImage("Noch einmal", shared));
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.ISSUE, photo.getId(), null),
				ReportCategory.SEXUAL, null, null, false), reader);
		ModerationCase c = mongo.findOne(Query.query(Criteria.where("target.id").is(photo.getId())),
				ModerationCase.class);

		moderation.decide(c.getId(), new DecisionService.DecisionRequest(0L, Decision.Outcome.ACTION,
				new DecisionService.RemovalChoice(RemovalScope.DESCRIPTION), Decision.Ground.RULES, ReportCategory.SEXUAL,
				FACTS, "Hausregel 4: Keine sexuellen Inhalte", null, null, false, null, null, false));
		followUpsDone();

		assertThat(moderation.detail(c.getId()).otherUses()).singleElement().satisfies(use -> {
			assertThat(use.kind()).isEqualTo(ContentKind.ISSUE);
			assertThat(use.id()).isEqualTo(copy.getId());
			assertThat(use.location()).isEqualTo(issues.get(copy.getId()).getReadableId());
		});
	}

	@Test
	void anActionNeedsFactsAndTheReferenceOfItsGround() {
		ModerationCase c = reported(comment("Halt den Mund"));
		DecisionService.DecisionRequest full = action(0, RemovalScope.WHOLE);

		assertThatThrownBy(() -> moderation.decide(c.getId(), new DecisionService.DecisionRequest(0L, full.outcome(),
				full.removal(), full.ground(), full.category(), "zu kurz", full.ruleReference(), null, null, false, null,
				null, false))).satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.factsRequired"));
		assertThatThrownBy(() -> moderation.decide(c.getId(), new DecisionService.DecisionRequest(0L, full.outcome(),
				full.removal(), Decision.Ground.RULES, full.category(), FACTS, " ", null, null, false, null, null, false)))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.referenceRequired"));
		assertThatThrownBy(() -> moderation.decide(c.getId(), new DecisionService.DecisionRequest(0L, full.outcome(),
				full.removal(), Decision.Ground.LAW, full.category(), FACTS, full.ruleReference(), null, null, false,
				null, null, false)))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.referenceRequired"));
		assertThatThrownBy(() -> moderation.decide(c.getId(), new DecisionService.DecisionRequest(0L, full.outcome(),
				full.removal(), full.ground(), ReportCategory.HARASSMENT, FACTS, full.ruleReference(), null, null, true,
				null, null, false)))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.outcomeNotAllowed"));
		assertThat(reload(c).getStatus()).isEqualTo(CaseStatus.OPEN);
	}

	@Test
	void aRemovalTheStoreRefusesLeavesTheDecisionStandingUntilTheFollowUpCarriesItOut() {
		store.put("object-plan", "%PDF-1.7");
		attachments.add(issue.getId(), Issue.Attachment.builder().id("plan").fileName("Hallenplan.pdf")
				.contentType("application/pdf").size(8).objectKey("object-plan").uploaderId(author.getId())
				.uploadedAt(Instant.now()).build());
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.ATTACHMENT, "plan",
				issue.getId()), ReportCategory.PRIVACY, null, null, false), reader);
		ModerationCase c = mongo.findOne(Query.query(Criteria.where("target.id").is("plan")), ModerationCase.class);
		store.refuseCopies.add("object-plan");

		moderation.decide(c.getId(), action(0, RemovalScope.WHOLE));
		followUpsDone();

		ModerationCase stalled = reload(c);
		assertThat(stalled.getStatus()).isEqualTo(CaseStatus.DECIDED);
		assertThat(stalled.getRemovalState()).isEqualTo(RemovalState.INCOMPLETE);
		assertThat(stalled.getStatementSentAt()).as("no statement of a removal that did not happen").isNull();
		assertThat(stalled.getFollowUpDue()).isNotNull();

		store.refuseCopies.clear();
		followUps.complete(c.getId());

		ModerationCase done = reload(c);
		assertThat(done.getRemovalState()).isEqualTo(RemovalState.DONE);
		assertThat(done.getStatementSentAt()).isNotNull();
		assertThat(done.getFollowUpDue()).isNull();
		assertThat(store.has("object-plan")).isFalse();
		assertThat(notices(author, Notification.Type.MODERATION_NOTICE)).hasSize(1);
	}

	@Test
	void ofTwoModeratorsDecidingAtOnceOneWinsAndTheOtherHearsItWasDecided() throws Exception {
		User rita = user("rita", Role.MODERATOR);
		ModerationCase c = reported(comment("Halt den Mund"));
		List<Callable<String>> clicks = new ArrayList<>();
		for (User moderator : List.of(mira, rita)) {
			clicks.add(() -> {
				actingAs(moderator);
				try {
					moderation.decide(c.getId(), outcome(0, Decision.Outcome.NO_VIOLATION, null));
					return "won";
				}
				catch (ApiException refused) {
					return refused.getDetails().get("code");
				}
			});
		}
		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<String> results = new ArrayList<>();
		try {
			for (Future<String> click : pool.invokeAll(clicks)) {
				results.add(click.get());
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(results).containsExactlyInAnyOrder("won", "decided");
		assertThat(reload(c).getRev()).isEqualTo(1);
	}

	@Test
	void whoWroteOrReportedItDoesNotDecideIt() {
		User karl = user("karl", Role.MODERATOR);
		User rita = user("rita", Role.MODERATOR);
		project.getMemberIds().addAll(List.of(karl.getId(), rita.getId()));
		projects.save(project);
		IssueComment byKarl = issues.addComment(issue.getId(), richText.fromLexical(doc("Halt den Mund")), null, karl);
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.COMMENT, byKarl.getId(),
				issue.getId()), ReportCategory.HARASSMENT, null, null, false), rita);
		ModerationCase c = mongo.findOne(Query.query(Criteria.where("target.id").is(byKarl.getId())), ModerationCase.class);

		actingAs(karl);
		assertThatThrownBy(() -> moderation.decide(c.getId(), action(0, RemovalScope.WHOLE)))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
		actingAs(rita);
		assertThatThrownBy(() -> moderation.decide(c.getId(), action(0, RemovalScope.WHOLE)))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.conflictOfInterest"));
	}

	@Test
	void aPersonWhoCannotSignInGetsTheStatementByMailAndAppealsByReplying() {
		IssueComment insult = comment(INSULT);
		ModerationCase c = reported(insult);
		mongo.updateFirst(Query.query(Criteria.where("_id").is(author.getId())), new Update().set("active", false),
				User.class);

		moderation.decide(c.getId(), action(0, RemovalScope.WHOLE));
		followUpsDone();

		assertThat(notices(author, Notification.Type.MODERATION_NOTICE)).hasSize(1);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> model = ArgumentCaptor.forClass(Map.class);
		verify(mail, timeout(3000)).sendTemplate(eq(author.getEmail()), anyString(), eq("email/moderation-notice"),
				model.capture(), eq(CONTACT));
		assertThat(model.getValue()).containsEntry("ctaLink", null);
		assertThat((String) model.getValue().get("appeal")).contains("replying to this mail");
		verify(push, never()).sendToUser(eq(author.getId()), any(), any(), any(), any());
	}

	@Test
	void moreThanTenRemovalsInAnHourTellTheAdmins() {
		User admin = user("ada", Role.ADMIN);
		for (int i = 0; i < DecisionService.REMOVALS_PER_HOUR + 1; i++) {
			ModerationCase c = reported(comment("Beleidigung Nummer " + i));
			moderation.decide(c.getId(), action(0, RemovalScope.WHOLE));
			if (i == DecisionService.REMOVALS_PER_HOUR - 1) {
				assertThat(notices(admin, Notification.Type.MODERATION_ADMIN_ALERT)).isEmpty();
			}
		}
		followUpsDone();

		assertThat(notices(admin, Notification.Type.MODERATION_ADMIN_ALERT)).hasSize(1);
	}

	@Test
	void aNoteToLawEnforcementHoldsTheCaseAndTheHoldOnlyGrows() {
		// A threat the shipped lists do not refuse, so that it gets saved and reported (HIN-176).
		ModerationCase c = reported(comment("Ich weiss, wo dein Auto steht"));
		DecisionService.DecisionRequest full = action(0, RemovalScope.WHOLE);
		moderation.decide(c.getId(), new DecisionService.DecisionRequest(0L, full.outcome(), full.removal(),
				Decision.Ground.LAW, ReportCategory.THREAT, FACTS, null, "§ 241 StGB", null, false, null, null, true));

		Instant held = reload(c).getLegalHoldUntil();
		assertThat(held).isAfter(Instant.now().plus(Duration.ofDays(360)));
		assertThatThrownBy(() -> moderation.legalHold(c.getId(), new DecisionService.LegalHoldRequest(
				Instant.now().plus(Duration.ofDays(30)), false, "Akte der Polizei")))
				.satisfies(refused -> assertThat(keyOf(refused)).isEqualTo("error.moderation.legalHoldUntil"));
		moderation.legalHold(c.getId(), new DecisionService.LegalHoldRequest(null, true, "Verfahren laeuft"));

		assertThat(reload(c).getLegalHoldUntil()).isEqualTo(DecisionService.INDEFINITELY);
		assertThat(mongo.count(Query.query(Criteria.where("action").is(AuditAction.MODERATION_LEGAL_HOLD_CHANGED)),
				AuditLog.class)).isEqualTo(2);
	}

	@Test
	void theRulesToRestADecisionOnAreNumbered() {
		ModerationRules.RuleSet rules = moderation.rules();

		assertThat(rules.builtIn()).isTrue();
		assertThat(rules.rules()).hasSize(ModerationRules.BUILT_IN)
				.extracting(ModerationRules.Rule::number).containsExactly(1, 2, 3, 4, 5, 6, 7);
		assertThat(rules.rules().get(1).title()).isEqualTo("No belittling others");
	}
}
