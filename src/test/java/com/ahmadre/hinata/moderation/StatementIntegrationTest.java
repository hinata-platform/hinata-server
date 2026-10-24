package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.notification.Notification;
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

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The statement of reasons (DSA Art. 17, HIN-171): every part the article asks for, in every
 * language, never a reporter, and the same words in the preview as in the notice.
 */
class StatementIntegrationTest extends RemovalIntegrationSupport {

	private static final List<String> LANGUAGES = List.of("de", "fr", "es", "ru", "ar", "zh", "ja", "hi");

	@Autowired
	private ModerationCaseController moderation;
	@Autowired
	private MemberModerationController member;
	@Autowired
	private ContentReportService reports;
	@Autowired
	private RichTextService richText;
	@Autowired
	private Statements statements;
	@Autowired
	private SettingsService settings;

	private User mira;
	private Issue issue;

	@BeforeEach
	void seed() {
		contactIs(settings, DecisionIntegrationTest.CONTACT);
		mira = user("mira", Role.MODERATOR);
		issue = issue("Stand B12", doc("Aufbau"));
		actingAs(mira);
	}

	private ModerationCase reportedComment() {
		IssueComment insult = issues.addComment(issue.getId(), richText.fromLexical(doc(DecisionIntegrationTest.INSULT)),
				null, author);
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.COMMENT, insult.getId(),
				issue.getId()), ReportCategory.HARASSMENT, null, null, false), reader);
		return mongo.findOne(Query.query(Criteria.where("target.id").is(insult.getId())), ModerationCase.class);
	}

	@Test
	void everyLanguageWordsEveryPartOfTheArticle() {
		ModerationCase c = reportedComment();
		moderation.decide(c.getId(), DecisionIntegrationTest.action(0, RemovalScope.WHOLE));
		ModerationCase decided = mongo.findById(c.getId(), ModerationCase.class);
		Function<String, Statement> in = tag -> statements.of(decided, decided.getDecision(), Locale.forLanguageTag(tag),
				ZoneOffset.UTC, true, Instant.now());
		Statement english = in.apply("en");

		assertThat(english.measure()).startsWith("Your comment of").contains("ACME-1").doesNotContain("Stand B12");
		assertThat(english.facts()).isEqualTo(DecisionIntegrationTest.FACTS);
		assertThat(english.automatedDecision()).isFalse();
		// The version the decision applied, as it read then (HIN-175).
		assertThat(decided.getDecision().rulesVersion()).isNotNull();
		assertThat(english.rulesUrl()).isEqualTo("/api/v1/public/rules?version=" + decided.getDecision().rulesVersion());
		assertThat(english.appealable()).isTrue();
		assertThat(english.toString()).doesNotContain(reader.getId(), "lena");
		for (String tag : LANGUAGES) {
			Statement statement = in.apply(tag);
			for (Function<Statement, String> part : List.<Function<Statement, String>>of(Statement::headline,
					Statement::measure, Statement::basis, Statement::automation, Statement::groundText,
					Statement::appeal, Statement::courtsNote)) {
				assertThat(part.apply(statement)).as(tag).isNotBlank().doesNotContain("{", "moderation.")
						.isNotEqualTo(part.apply(english));
			}
			assertThat(statement.groundText()).as(tag).contains("Hausregel 2");
		}
	}

	@Test
	void thePreviewSaysWhatTheNoticeSays() {
		ModerationCase c = reportedComment();

		Statement preview = moderation.preview(c.getId(), DecisionIntegrationTest.action(0, RemovalScope.WHOLE));
		moderation.decide(c.getId(), DecisionIntegrationTest.action(0, RemovalScope.WHOLE));
		followUpsDone();
		actingAs(author);
		Statement notice = member.statement(c.getId());

		assertThat(notice).usingRecursiveComparison().ignoringFields("appealUntil").isEqualTo(preview);
	}

	@Test
	void contentFromAnAddressGetsANeutralMailAndNoMemberIsTakenForItsAuthor() {
		User sameAddress = user("extern");
		Issue mailed = issue("Rechnung offen", doc("Bitte zahlen Sie sofort"));
		mongo.updateFirst(Query.query(Criteria.where("_id").is(mailed.getId())), new Update()
				.set("inboundMessageId", "<m1@example.org>").set("reporterEmail", sameAddress.getEmail())
				.set("reporterId", sameAddress.getId()), Issue.class);
		reports.submit(new ContentReportService.ReportRequest(new TargetRef(ContentKind.ISSUE, mailed.getId(), null),
				ReportCategory.SPAM, null, null, false), reader);
		ModerationCase c = mongo.findOne(Query.query(Criteria.where("target.id").is(mailed.getId())), ModerationCase.class);

		moderation.decide(c.getId(), DecisionIntegrationTest.action(0, RemovalScope.WHOLE));
		followUpsDone();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> model = ArgumentCaptor.forClass(Map.class);
		verify(mail, timeout(3000)).sendTemplate(eq(sameAddress.getEmail()), anyString(), eq("email/moderation-notice"),
				model.capture(), eq(DecisionIntegrationTest.CONTACT));
		assertThat(model.getValue()).doesNotContainKey("facts").doesNotContainKey("message");
		assertThat(String.valueOf(model.getValue())).doesNotContain("ACME", "Rechnung offen", "Bitte zahlen");
		// In the instance's language: an address has no language of its own.
		assertThat((String) model.getValue().get("appeal"))
				.contains("de".equals(settings.instanceLocale().getLanguage()) ? "auf diese Mail antwortest"
						: "replying to this mail");
		assertThat(mongo.find(Query.query(Criteria.where("userId").is(sameAddress.getId())
				.and("type").is(Notification.Type.MODERATION_NOTICE)), Notification.class)).isEmpty();
		assertThat(mongo.findById(c.getId(), ModerationCase.class).getDecision().responsibleId()).isNull();
	}
}
