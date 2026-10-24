package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.storage.AttachmentStore;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What moderators do with a case before deciding it (HIN-170): take it and give it back at the
 * revision they saw, uncover a reporter with a reason, open withheld content with a second person,
 * look at a file, and open a case of their own. Every step that uncovers something is on record.
 */
class CaseStepsIntegrationTest extends RemovalIntegrationSupport {

	@Autowired
	private ModerationCaseController moderation;
	@Autowired
	private ContentReportService reports;
	@Autowired
	private RichTextService richText;
	@Autowired
	private AttachmentStore attachments;

	private User mira;
	private Issue issue;

	@BeforeEach
	void seedIssue() {
		mira = user("mira", Role.MODERATOR);
		issue = issue("Stand B12", doc("Aufbau"));
	}

	private IssueComment comment(String text) {
		return issues.addComment(issue.getId(), richText.fromLexical(doc(text)), null, author);
	}

	private ModerationCase reported(IssueComment comment, ReportCategory category, String explanation) {
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.COMMENT, comment.getId(),
				issue.getId()), category, explanation, null, false), reader);
		return mongo.findOne(Query.query(Criteria.where("target.id").is(comment.getId())), ModerationCase.class);
	}

	private ModerationCase reload(ModerationCase c) {
		return mongo.findById(c.getId(), ModerationCase.class);
	}

	private List<AuditLog> audits(AuditAction action) {
		return mongo.find(Query.query(Criteria.where("action").is(action)), AuditLog.class);
	}

	private static ModerationCaseController.ClaimRequest at(long rev, boolean takeOver) {
		return new ModerationCaseController.ClaimRequest(rev, takeOver);
	}

	private static String codeOf(Throwable refused) {
		return ((ApiException) refused).getDetails().get("code");
	}

	@Test
	void aCaseIsTakenAtTheRevisionSeenAndTakenOverOnlyWhenStale() {
		User rita = user("rita", Role.MODERATOR);
		ModerationCase c = reported(comment("Halt den Mund"), ReportCategory.HARASSMENT, null);

		actingAs(mira);
		assertThat(moderation.claim(c.getId(), at(0, false)).assignee().id()).isEqualTo(mira.getId());
		assertThat(audits(AuditAction.MODERATION_CASE_CLAIMED)).singleElement()
				.satisfies(entry -> assertThat(entry.getMetadata()).containsOnlyKeys("case"));

		actingAs(rita);
		assertThatThrownBy(() -> moderation.claim(c.getId(), at(0, false)))
				.satisfies(refused -> assertThat(codeOf(refused)).isEqualTo("error.moderation.caseChanged"));
		assertThatThrownBy(() -> moderation.claim(c.getId(), at(1, true)))
				.satisfies(refused -> assertThat(codeOf(refused)).isEqualTo("error.moderation.caseChanged"));
		assertThatThrownBy(() -> moderation.release(c.getId(), at(1, false))).isInstanceOf(ApiException.class);

		mongo.updateFirst(Query.query(Criteria.where("_id").is(c.getId())),
				new Update().set("claimedAt", Instant.now().minusSeconds(3 * 3600)), ModerationCase.class);
		assertThat(moderation.claim(c.getId(), at(1, true)).assignee().id()).isEqualTo(rita.getId());
		assertThat(moderation.release(c.getId(), at(2, false)).assignee()).isNull();
		assertThat(reload(c).getHistory()).extracting(CaseEvent::kind).containsExactly(CaseEvent.Kind.REPORTED,
				CaseEvent.Kind.CLAIMED, CaseEvent.Kind.CLAIMED, CaseEvent.Kind.RELEASED);

		mongo.updateFirst(Query.query(Criteria.where("_id").is(c.getId())),
				new Update().set("status", CaseStatus.DECIDED), ModerationCase.class);
		assertThatThrownBy(() -> moderation.claim(c.getId(), at(3, false)))
				.satisfies(refused -> assertThat(codeOf(refused)).isEqualTo("error.moderation.caseDecided"));
	}

	@Test
	void aReporterStaysCoveredUntilUncoveredWithAReasonAndNeverForChildSafety() {
		ModerationCase c = reported(comment("Halt den Mund"), ReportCategory.HARASSMENT, null);
		actingAs(mira);

		CaseViews.ReportView covered = moderation.detail(c.getId()).reports().getFirst();
		assertThat(covered.reporter()).isNull();
		CaseViews.ReportView uncovered = moderation.revealReporter(c.getId(), covered.id(),
				new CaseService.RevealRequest("Mehrere Meldungen derselben Person"));
		assertThat(uncovered.reporter().id()).isEqualTo(reader.getId());
		assertThat(moderation.detail(c.getId()).reports().getFirst().reporter().id()).isEqualTo(reader.getId());
		assertThat(reload(c).getHistory()).last().satisfies(event -> {
			assertThat(event.kind()).isEqualTo(CaseEvent.Kind.REPORTER_REVEALED);
			assertThat(event.detail()).isEqualTo("Mehrere Meldungen derselben Person");
		});
		assertThat(audits(AuditAction.MODERATION_REPORTER_REVEALED)).singleElement()
				.satisfies(entry -> assertThat(entry.getMetadata()).containsOnlyKeys("case"));
		// Filed again about the same content, now about a child: covered again, for good.
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.COMMENT, c.getTarget().id(),
				issue.getId()), ReportCategory.CHILD_SAFETY, "Es geht um ein Kind", null, false), reader);
		assertThat(moderation.detail(c.getId()).reports().getFirst().reporter()).isNull();

		ModerationCase childSafety = reported(comment("Schick mir Bilder von dir"), ReportCategory.CHILD_SAFETY,
				"Erwachsener schreibt einem Kind");
		String reportId = moderation.detail(childSafety.getId()).reports().getFirst().id();
		assertThatThrownBy(() -> moderation.revealReporter(childSafety.getId(), reportId,
				new CaseService.RevealRequest("Rueckfrage")))
				.isInstanceOfSatisfying(ApiException.class, refused -> {
					assertThat(refused.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
					assertThat(refused.getMessageKey()).isEqualTo("error.moderation.childSafetyCovered");
				});
	}

	@Test
	void withheldContentOpensWithASecondPerson() {
		User rita = user("rita", Role.MODERATOR);
		ModerationCase c = withheld(reported(comment("Interna aus dem Vorstand"), ReportCategory.PRIVACY, null));

		actingAs(mira);
		CaseViews.CaseDetail closed = moderation.detail(c.getId());
		assertThat(closed.evidenceWithheld()).isTrue();
		assertThat(closed.evidence()).isNull();
		assertThat(moderation.revealEvidence(c.getId(), new CaseService.RevealRequest("Meldung pruefen"))
				.evidence()).isNull();
		assertThat(notices(rita, Notification.Type.MODERATION_CONFIRM_REQUESTED)).hasSize(1);
		assertThat(notices(mira, Notification.Type.MODERATION_CONFIRM_REQUESTED)).isEmpty();
		assertThatThrownBy(() -> moderation.revealEvidence(c.getId(), new CaseService.RevealRequest("Eilt")))
				.isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.getMessageKey())
						.isEqualTo("error.moderation.secondPersonNeeded"));

		actingAs(rita);
		assertThat(moderation.revealEvidence(c.getId(), new CaseService.RevealRequest("Bestaetigt")).evidence())
				.isNotNull();
		actingAs(mira);
		assertThat(moderation.detail(c.getId()).evidence().excerpt()).contains("Interna aus dem Vorstand");
		assertThat(audits(AuditAction.MODERATION_EVIDENCE_REVEALED)).singleElement()
				.satisfies(entry -> assertThat(entry.getActorId()).isEqualTo(rita.getId()));
		assertThat(notices(mira, Notification.Type.MODERATION_TEAM_NOTE)).hasSize(1);
	}

	@Test
	void withNobodyElseOneOpensAloneAtMostFiveTimesADayAndTheAdminsHear() {
		User admin = user("ada", Role.ADMIN);
		actingAs(mira);
		for (int i = 0; i < CaseService.SOLO_REVEALS_PER_DAY; i++) {
			ModerationCase c = withheld(reported(comment("Interna " + i), ReportCategory.PRIVACY, null));
			assertThat(moderation.revealEvidence(c.getId(), new CaseService.RevealRequest("Niemand sonst da"))
					.evidence()).isNotNull();
		}
		ModerationCase sixth = withheld(reported(comment("Interna 6"), ReportCategory.PRIVACY, null));

		assertThatThrownBy(() -> moderation.revealEvidence(sixth.getId(), new CaseService.RevealRequest("Noch einer")))
				.isInstanceOfSatisfying(ApiException.class,
						refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
		assertThat(notices(admin, Notification.Type.MODERATION_ADMIN_ALERT))
				.hasSize(CaseService.SOLO_REVEALS_PER_DAY);
	}

	@Test
	void aFileIsStreamedNeverCachedAndEveryLookIsOnRecord() {
		store.put("object-plan", "%PDF-1.7");
		attachments.add(issue.getId(), Issue.Attachment.builder().id("plan").fileName("Hallenplan.pdf")
				.contentType("application/pdf").size(8).objectKey("object-plan").uploadedAt(Instant.now())
				.uploaderId(author.getId()).build());
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.ATTACHMENT, "plan",
				issue.getId()), ReportCategory.PRIVACY, null, null, false), reader);
		ModerationCase c = mongo.findOne(Query.query(Criteria.where("target.id").is("plan")), ModerationCase.class);
		actingAs(mira);

		ResponseEntity<byte[]> file = moderation.evidence(c.getId(), 0);
		moderation.evidence(c.getId(), 0);

		assertThat(new String(file.getBody())).isEqualTo("%PDF-1.7");
		assertThat(file.getHeaders().getCacheControl()).isEqualTo("no-store");
		assertThat(file.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(file.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline")
				.contains("Hallenplan.pdf");
		assertThat(audits(AuditAction.MODERATION_EVIDENCE_VIEWED)).hasSize(2)
				.allSatisfy(entry -> assertThat(entry.getMetadata()).containsOnlyKeys("case"));
		assertThatThrownBy(() -> moderation.evidence(c.getId(), 1)).isInstanceOfSatisfying(ApiException.class,
				refused -> assertThat(refused.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

		mongo.updateFirst(Query.query(Criteria.where("_id").is(c.getId())),
				new Update().addToSet("categories", ReportCategory.CHILD_SAFETY), ModerationCase.class);
		assertThatThrownBy(() -> moderation.evidence(c.getId(), 0)).isInstanceOfSatisfying(ApiException.class,
				refused -> assertThat(refused.getMessageKey()).isEqualTo("error.moderation.childSafetyFilesClosed"));
	}

	@Test
	void aModeratorOpensACaseOfTheirOwnAndMayDecideIt() {
		project.getMemberIds().add(mira.getId());
		projects.save(project);
		IssueComment insult = comment("Halt den Mund");
		actingAs(mira);

		String id = moderation.open(new CaseService.OwnCaseRequest(new TargetRef(ContentKind.COMMENT,
				insult.getId(), issue.getId()), ReportCategory.HARASSMENT, "Im Vorstandskanal gesehen")).get("id");

		ModerationCase c = mongo.findById(id, ModerationCase.class);
		assertThat(c.getSources()).containsExactly(ReportSource.OWN_INITIATIVE);
		assertThat(mongo.findAll(ContentReport.class)).singleElement().satisfies(report -> {
			assertThat(report.getReporterKey()).isEqualTo("own:" + mira.getId());
			assertThat(report.getReporterId()).isNull();
			assertThat(report.getExplanation()).isEqualTo("Im Vorstandskanal gesehen");
		});
		assertThat(audits(AuditAction.MODERATION_CASE_CREATED)).hasSize(1);
		assertThat(moderation.claim(id, at(0, false)).state()).isEqualTo(CaseViews.CaseState.IN_REVIEW);
		assertThatThrownBy(() -> moderation.open(new CaseService.OwnCaseRequest(new TargetRef(ContentKind.PERSON,
				mira.getId(), null), ReportCategory.HARASSMENT, "Ich selbst")))
				.isInstanceOfSatisfying(ApiException.class, refused -> assertThat(refused.getMessageKey())
						.isEqualTo("error.moderation.ownContent"));
		// Content the moderator cannot see is named as such, not as a case that is missing.
		assertThatThrownBy(() -> moderation.open(new CaseService.OwnCaseRequest(new TargetRef(ContentKind.COMMENT,
				"no-such-comment", issue.getId()), ReportCategory.HARASSMENT, "Im Vorstandskanal gesehen")))
				.isInstanceOfSatisfying(ApiException.class, missing -> {
					assertThat(missing.getStatus().value()).isEqualTo(404);
					assertThat(((org.springframework.context.MessageSourceResolvable) missing.getArgs()[0]).getCodes())
							.containsExactly("entity.comment");
				});
	}

	/** As a report without an account on internal content leaves a case (HIN-175). */
	private ModerationCase withheld(ModerationCase c) {
		mongo.updateFirst(Query.query(Criteria.where("_id").is(c.getId())), new Update().set("evidenceWithheld", true),
				ModerationCase.class);
		return reload(c);
	}

	private List<Notification> notices(User person, Notification.Type type) {
		return mongo.find(Query.query(Criteria.where("userId").is(person.getId()).and("type").is(type)),
				Notification.class);
	}
}
