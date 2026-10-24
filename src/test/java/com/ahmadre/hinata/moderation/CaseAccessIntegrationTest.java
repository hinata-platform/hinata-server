package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
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
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who reaches a case (HIN-170), on every route under {@code /api/v1/moderation}: nobody who does
 * not moderate, and nobody who wrote what it is about, who finds it on no way at all. Whoever
 * reported a case sees it and does not decide it.
 */
class CaseAccessIntegrationTest extends RemovalIntegrationSupport {

	@Autowired
	private ModerationCaseController moderation;
	@Autowired
	private ContentReportService reports;
	@Autowired
	private CaseAccess access;
	@Autowired
	private RichTextService richText;

	private User mira;
	private User rita;
	private User karl;
	private ModerationCase aboutKarl;
	private ModerationCase aboutTheIssue;

	@BeforeEach
	void seedTwoCases() {
		mira = user("mira", Role.MODERATOR);
		rita = user("rita", Role.MODERATOR);
		karl = user("karl", Role.MODERATOR);
		project.getMemberIds().addAll(List.of(mira.getId(), rita.getId(), karl.getId()));
		projects.save(project);
		Issue issue = issue("Stand B12", doc("Aufbau"));
		IssueComment byKarl = issues.addComment(issue.getId(), richText.fromLexical(doc("Halt den Mund")), null, karl);

		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.COMMENT, byKarl.getId(),
				issue.getId()), ReportCategory.HARASSMENT, null, null, false), reader);
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.ISSUE, issue.getId(), null),
				ReportCategory.SPAM, null, null, false), rita);
		aboutKarl = byTarget(ContentKind.COMMENT);
		aboutTheIssue = byTarget(ContentKind.ISSUE);
	}

	private ModerationCase byTarget(ContentKind kind) {
		return mongo.findOne(Query.query(Criteria.where("target.kind").is(kind)), ModerationCase.class);
	}

	/** Every route of the moderation page on [c], by name. */
	private Map<String, Executable> routesOn(ModerationCase c) {
		ContentReport first = mongo.findOne(Query.query(Criteria.where("caseId").is(c.getId())), ContentReport.class);
		ModerationCaseController.ClaimRequest atRev = new ModerationCaseController.ClaimRequest(c.getRev(), false);
		CaseService.RevealRequest because = new CaseService.RevealRequest("Rueckfrage noetig");
		Map<String, Executable> routes = new LinkedHashMap<>();
		routes.put("detail", () -> moderation.detail(c.getId()));
		routes.put("reports", () -> moderation.reports(c.getId(), 0, 25));
		routes.put("claim", () -> moderation.claim(c.getId(), atRev));
		routes.put("release", () -> moderation.release(c.getId(), atRev));
		routes.put("revealReporter", () -> moderation.revealReporter(c.getId(), first.getId(), because));
		routes.put("revealEvidence", () -> moderation.revealEvidence(c.getId(), because));
		routes.put("evidence", () -> moderation.evidence(c.getId(), 0));
		return routes;
	}

	private Map<String, Executable> everyRoute() {
		Map<String, Executable> routes = new LinkedHashMap<>();
		for (CaseViews.View view : CaseViews.View.values()) {
			routes.put("list " + view, () -> moderation.list(view, 0, 25));
		}
		routes.put("summary", () -> moderation.summary());
		routes.put("open", () -> moderation.open(new CaseService.OwnCaseRequest(new TargetRef(ContentKind.PERSON,
				author.getId(), null), ReportCategory.HARASSMENT, "Beleidigt in jedem Kommentar")));
		routes.putAll(routesOn(aboutKarl));
		return routes;
	}

	private static void assertRefused(Map<String, Executable> routes, HttpStatus status) {
		routes.forEach((name, route) -> assertThatThrownBy(route::execute).as(name)
				.isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.getStatus()).isEqualTo(status)));
	}

	@Test
	void whoeverDoesNotModerateIsRefusedOnEveryRoute() {
		actingAs(reader);
		assertRefused(everyRoute(), HttpStatus.FORBIDDEN);

		actingAs(user("ada", Role.ADMIN));
		assertRefused(everyRoute(), HttpStatus.FORBIDDEN);
	}

	@Test
	void whoeverWroteTheContentFindsTheCaseOnNoWay() {
		actingAs(karl);

		assertRefused(routesOn(aboutKarl), HttpStatus.NOT_FOUND);
		// Not in a list, a number or a notice either.
		assertThat(moderation.list(CaseViews.View.OPEN, 0, 25).getContent()).extracting(CaseViews.CaseSummary::id)
				.containsExactly(aboutTheIssue.getId());
		assertThat(moderation.list(CaseViews.View.OPEN, 0, 25).getTotalElements()).isEqualTo(1);
		assertThat(moderation.summary().open()).isEqualTo(1);
		assertThat(mongo.find(Query.query(Criteria.where("userId").is(karl.getId())
				.and("type").is(Notification.Type.MODERATION_CASE_OPENED)), Notification.class))
				.extracting(Notification::getLink)
				.containsExactly(ModerationNotices.caseLink(aboutTheIssue));
		// The other case stays theirs to work on.
		assertThat(moderation.detail(aboutTheIssue.getId()).summary().id()).isEqualTo(aboutTheIssue.getId());

		actingAs(mira);
		assertThat(moderation.summary().open()).isEqualTo(2);
	}

	@Test
	void whoeverReportedTheCaseSeesItAndDoesNotTakeIt() {
		actingAs(rita);

		CaseViews.CaseDetail seen = moderation.detail(aboutTheIssue.getId());
		assertThat(seen.summary().reportedByMe()).isTrue();
		assertThatThrownBy(() -> moderation.claim(aboutTheIssue.getId(),
				new ModerationCaseController.ClaimRequest(aboutTheIssue.getRev(), false)))
				.isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.getMessageKey())
						.isEqualTo("error.moderation.conflictOfInterest"));

		actingAs(mira);
		assertThat(moderation.claim(aboutTheIssue.getId(),
				new ModerationCaseController.ClaimRequest(aboutTheIssue.getRev(), false)).state())
				.isEqualTo(CaseViews.CaseState.IN_REVIEW);
	}

	@Test
	void eachPurposeLeavesOutWhoItMust() {
		mongo.updateFirst(Query.query(Criteria.where("_id").is(aboutTheIssue.getId())), new Update()
				.set("decision", decision(Decision.Outcome.ACTION, Decision.Ground.RULES, mira.getId()))
				.set("evidenceReveal", new EvidenceReveal(karl.getId(), java.time.Instant.now(), "Bitte ansehen", null,
						null)), ModerationCase.class);
		ModerationCase c = mongo.findById(aboutTheIssue.getId(), ModerationCase.class);

		// The issue is the owner's, rita reported it, mira decided it, karl asked for a second person.
		assertThat(names(access.eligibleModerators(c, CaseAccess.Purpose.SEE))).containsExactlyInAnyOrder("mira",
				"rita", "karl");
		assertThat(names(access.eligibleModerators(c, CaseAccess.Purpose.DECIDE))).containsExactlyInAnyOrder("mira",
				"karl");
		assertThat(names(access.eligibleModerators(c, CaseAccess.Purpose.REVIEW_APPEAL)))
				.containsExactlyInAnyOrder("karl");
		assertThat(names(access.eligibleModerators(c, CaseAccess.Purpose.SECOND_PERSON)))
				.containsExactlyInAnyOrder("mira");
		assertThat(names(access.eligibleModerators(aboutKarl, CaseAccess.Purpose.SEE)))
				.containsExactlyInAnyOrder("mira", "rita");
	}

	private static List<String> names(List<User> people) {
		return people.stream().map(User::getUsername).toList();
	}
}
