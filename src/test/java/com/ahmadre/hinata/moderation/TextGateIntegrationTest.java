package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleService;
import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.migration.LinkTargetCleanup;
import com.ahmadre.hinata.migration.MigrationMarkers;
import com.ahmadre.hinata.moderation.rules.HouseRulesService;
import com.ahmadre.hinata.oauth.OAuthException;
import com.ahmadre.hinata.oauth.OAuthService;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.setup.AdminSettingsController;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.ProfileChange;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The text check on every way a text is stored (HIN-176): refused means nothing is saved and the
 * author sees the term; flagging is off until an administrator confirms it, and then only what is
 * new goes before moderation; own terms refuse and never flag.
 */
class TextGateIntegrationTest extends RemovalIntegrationSupport {

	private static final String THREAT = "ich bringe dich um";

	@Autowired
	SettingsService settings;
	@Autowired
	ArticleService articles;
	@Autowired
	UserService userService;
	@Autowired
	OAuthService oauth;
	@Autowired
	RichTextService richText;
	@Autowired
	AdminSettingsController adminSettings;
	@Autowired
	HouseRulesService houseRules;
	@Autowired
	LinkTargetCleanup linkCleanup;
	@Autowired
	MigrationMarkers markers;

	@BeforeEach
	void defaults() {
		for (String collection : List.of("server_settings", "articles", "article_versions", "oauth_clients")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		settings.save(new ServerSettings());
	}

	private void filter(Consumer<ServerSettings.Filter> change) {
		ServerSettings stored = settings.get();
		ServerSettings.Moderation moderation = stored.getModeration() == null ? new ServerSettings.Moderation()
				: stored.getModeration();
		ServerSettings.Filter filter = moderation.getFilter() == null ? new ServerSettings.Filter()
				: moderation.getFilter();
		change.accept(filter);
		moderation.setFilter(filter);
		stored.setModeration(moderation);
		settings.save(stored);
	}

	private static void refused(ThrowingCallable write, String terms) {
		assertThatThrownBy(write).isInstanceOfSatisfying(ApiException.class, ex -> {
			assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
			assertThat(ex.getDetails()).containsEntry("code", "error.moderation.refused").containsEntry("terms", terms);
		});
	}

	private RichText text(String markdown) {
		return richText.fromMarkdown(markdown);
	}

	private long count(Class<?> type) {
		return mongo.count(new Query(), type);
	}

	@Test
	void issuesRefuseOnCreateAndUpdateAndKeepWhatWasStored() {
		refused(() -> issues.create(Issue.builder().projectId(project.getId()).title(THREAT).description("")
				.type(Issue.Type.TASK).build(), author), THREAT);
		assertThat(count(Issue.class)).isZero();

		Issue issue = issue("Standaufbau", doc("Plan"));
		refused(() -> issues.update(issue.getId(), i -> i.setTitle("Plan: " + THREAT), author), THREAT);
		assertThat(issues.get(issue.getId()).getTitle()).isEqualTo("Standaufbau");
	}

	@Test
	void commentsRefuseOnPostAndEdit() {
		Issue issue = issue("Standaufbau", doc("Plan"));
		refused(() -> issues.addComment(issue.getId(), text(THREAT), author), THREAT);
		assertThat(count(IssueComment.class)).isZero();

		IssueComment comment = issues.addComment(issue.getId(), text("Passt so"), author);
		refused(() -> issues.editComment(issue.getId(), comment.getId(), text("Passt, " + THREAT), author), THREAT);
		assertThat(mongo.findById(comment.getId(), IssueComment.class).getText()).isEqualTo("Passt so");
	}

	@Test
	void pagesProfilesRegistrationsAndClientsRefuseToo() {
		refused(() -> articles.create(author, new ArticleService.Draft(THREAT, RichText.EMPTY, project.getId(), null,
				false, null, null, null, null, null)), THREAT);
		assertThat(count(Article.class)).isZero();

		refused(() -> userService.updateProfile(author, ProfileChange.of().displayName("kys")), "kys");
		assertThat(users.findById(author.getId()).orElseThrow().getDisplayName()).isEqualTo("jonas");

		refused(() -> userService.createSelfRegistered("kim@example.org", "kys", null, "Sicher!12345"), "kys");

		assertThatThrownBy(() -> oauth.register("kys", List.of("http://localhost/callback"), null, null))
				.isInstanceOf(OAuthException.class);
		assertThat(mongo.getCollection("oauth_clients").countDocuments()).isZero();
	}

	@Test
	void unclearTermsPassUntilFlaggingIsOnAndThenOnlyWhatIsNewOpensACase() {
		Issue issue = issue("Standaufbau", doc("Plan"));
		issues.addComment(issue.getId(), text("du arschloch"), author);
		assertThat(count(ModerationCase.class)).as("flagging is off by default").isZero();

		filter(f -> f.setFlagEnabled(true));
		IssueComment comment = issues.addComment(issue.getId(), text("du arschloch"), author);
		ContentReport report = mongo.findOne(Query.query(Criteria.where("targetId").is(comment.getId())),
				ContentReport.class);
		assertThat(report.getSource()).isEqualTo(ReportSource.AUTOMATED);
		assertThat(report.getReporterKey()).isEqualTo("auto:COMMENT");

		// A typo fixed: nothing new, no second report.
		issues.editComment(issue.getId(), comment.getId(), text("du arschloch!"), author);
		// Something new: the same report counts one more hit, never one more report.
		issues.editComment(issue.getId(), comment.getId(), text("du arschloch, du wichser"), author);
		List<ContentReport> reports = mongo.find(Query.query(Criteria.where("targetId").is(comment.getId())),
				ContentReport.class);
		assertThat(reports).singleElement().satisfies(only -> assertThat(only.getHits()).isEqualTo(2));
		assertThat(mongo.findById(report.getCaseId(), ModerationCase.class).getReportCount()).isEqualTo(1);
	}

	@Test
	void ownTermsRefuseAndNeverOpenACase() {
		filter(f -> {
			f.setFlagEnabled(true);
			f.setBlockTerms(List.of("Spast"));
		});
		Issue issue = issue("Standaufbau", doc("Plan"));

		refused(() -> issues.addComment(issue.getId(), text("so ein Spast"), author), "spast");
		assertThat(count(ModerationCase.class)).isZero();
		// And "How we moderate" names them, for everybody to see what holds.
		assertThat(houseRules.latest().renderedIn("de")).contains("Außerdem lehnt er diese Begriffe ab: Spast.");
	}

	@Test
	void flaggingStartsOnlyWithTheConfirmationAndIsRecorded() {
		User admin = user("admin", Role.ADMIN);
		actingAs(admin);
		ServerSettings stored = settings.get();
		ServerSettings.Moderation moderation = new ServerSettings.Moderation();
		ServerSettings.Filter filter = new ServerSettings.Filter();
		filter.setFlagEnabled(true);
		moderation.setFilter(filter);
		stored.setModeration(moderation);

		assertThatThrownBy(() -> adminSettings.update(stored)).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.getDetails()).containsEntry("code", "error.moderation.flagUnconfirmed"));
		stored.setFlagConfirmed(true);
		adminSettings.update(stored);

		assertThat(settings.get().getModeration().getFilter().flags()).isTrue();
		assertThat(mongo.find(Query.query(Criteria.where("action").is(AuditAction.MODERATION_FILTER_CHANGED)),
				AuditLog.class)).singleElement()
				.satisfies(entry -> assertThat(entry.getMetadata()).containsEntry("flag", "true"));
	}

	@Test
	void linksStoredBeforeTheCheckLoseWhereTheyLeadOnce() {
		Issue issue = issue("Links", """
				{"root":{"type":"root","children":[{"type":"paragraph","children":[{"type":"link",\
				"url":"javascript:alert(1)","children":[{"type":"text","text":"hier","format":0,"detail":0,\
				"mode":"normal","style":"","version":1}]}]}]}}""");
		mongo.getCollection(MigrationMarkers.COLLECTION).deleteMany(new Document("_id", "link-target-cleanup"));

		linkCleanup.run(null);

		String cleaned = issues.get(issue.getId()).getDescriptionDoc();
		assertThat(cleaned).doesNotContain("javascript").contains("hier");
		assertThat(markers.done("link-target-cleanup")).isTrue();
		mongo.getCollection(MigrationMarkers.COLLECTION).deleteMany(new Document("_id", "link-target-cleanup"));
		linkCleanup.run(null);
		assertThat(issues.get(issue.getId()).getDescriptionDoc()).isEqualTo(cleaned);
	}
}
