package com.ahmadre.hinata.richtext;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.oauth.OAuthClient;
import com.ahmadre.hinata.user.User;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every way a checked text is written goes past the text check (HIN-176), read from the code
 * instead of a table kept by hand: the setters and builder methods of the checked fields, and the
 * conversions that make a stored text, may be called only by the classes named here, each one
 * either checking or written down as not checked, with the reason. A new way to write one of them
 * fails this test until somebody decided about it.
 */
class TextWritersTest {

	private static final String ROOT = "com.ahmadre.hinata";

	/** The checked fields, by the class that holds them and the methods that write them. */
	private static final Map<String, Set<String>> CHECKED_WRITES = Map.of(
			Issue.class.getName(), Set.of("setTitle", "setDescription", "setDescriptionDoc"),
			Issue.IssueBuilder.class.getName(), Set.of("title", "description", "descriptionDoc"),
			IssueComment.class.getName(), Set.of("setText", "setTextDoc"),
			IssueComment.IssueCommentBuilder.class.getName(), Set.of("text", "textDoc"),
			Article.class.getName(), Set.of("setTitle", "setContent", "setContentDoc"),
			Article.ArticleBuilder.class.getName(), Set.of("title", "content", "contentDoc"),
			User.class.getName(), Set.of("setDisplayName", "setTitle", "setPronouns", "setUsername"),
			User.UserBuilder.class.getName(), Set.of("displayName", "title", "pronouns", "username"),
			OAuthClient.class.getName(), Set.of("setClientName"),
			RichTextService.class.getName(), Set.of("fromLexical", "fromMarkdown", "fromStoredMarkdown", "fromRequest"));

	/**
	 * Who may write a checked text. The first group checks, or hands the text to a service that
	 * checks before anything is stored; the second is not checked, on purpose.
	 */
	private static final Set<String> WRITERS = Set.of(
			// Checked here.
			ROOT + ".issue.IssueService", ROOT + ".article.ArticleService", ROOT + ".user.UserService",
			ROOT + ".user.SsoAccounts", ROOT + ".admin.AdminUserService", ROOT + ".oauth.OAuthService",
			ROOT + ".mailingest.EmailIngestService",
			// Set a request's words on the entity and save it through a service above.
			ROOT + ".issue.IssueController", ROOT + ".article.ArticleController", ROOT + ".mcp.IssueWriteTools",
			ROOT + ".mcp.KnowledgeWriteTools", ROOT + ".git.GitService",
			// Not checked: copies of what was checked when it was written.
			ROOT + ".issue.IssueCloneService", ROOT + ".template.ProjectCopyService",
			ROOT + ".moderation.quarantine.MongoQuarantine", ROOT + ".article.ArticleHistory",
			// Not checked: moderation's own placeholders, and putting back what it removed.
			ROOT + ".issue.IssueContentSurface", ROOT + ".issue.CommentContentSurface",
			ROOT + ".article.ArticleContentSurface", ROOT + ".user.PersonContentSurface",
			// Not checked: the demo dataset and the migrations of stored texts.
			ROOT + ".demo", ROOT + ".migration", ROOT + ".mailingest.IngestedHtmlDescriptionBackfill",
			// The entities and their builders themselves, and the converter calling its own forms.
			Issue.class.getName(), IssueComment.class.getName(), Article.class.getName(), User.class.getName(),
			OAuthClient.class.getName(), RichTextService.class.getName(),
			// A reader's name copied from a projection into a user that is never saved.
			ROOT + ".user.UserRepository");

	private static final JavaClasses PRODUCTION = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages(ROOT);

	private static final DescribedPredicate<JavaClass> A_WRITER = DescribedPredicate.describe(
			"a class that checks a text or is written down as not checking it", javaClass -> {
				String name = javaClass.getFullName();
				return WRITERS.stream().anyMatch(writer -> name.equals(writer) || name.startsWith(writer + "$")
						|| name.startsWith(writer + "."));
			});

	private static final DescribedPredicate<JavaMethodCall> WRITES_A_CHECKED_TEXT = DescribedPredicate.describe(
			"writes a checked text", call -> {
				Set<String> methods = CHECKED_WRITES.get(call.getTargetOwner().getFullName());
				return methods != null && methods.contains(call.getName());
			});

	static final ArchRule TEXT_GATE_GUARDS_EVERY_WRITER = noClasses()
			.that(not(A_WRITER))
			.should().callMethodWhere(WRITES_A_CHECKED_TEXT)
			.because("a text that reaches the database past the text check is a way around it; check it, "
					+ "or write down here why it is not checked");

	@Test
	void textGateGuardsEveryWriter() {
		TEXT_GATE_GUARDS_EVERY_WRITER.check(PRODUCTION);
	}

	/** The rule against a class written to break it. */
	@Test
	void theRuleCatchesANewWayToWrite() {
		assertThatThrownBy(() -> TEXT_GATE_GUARDS_EVERY_WRITER.check(new ClassFileImporter()
				.importClasses(TextWriterProbe.class)))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("TextWriterProbe");
	}

	/** Writes a comment's words without asking anybody: what the rule is there to refuse. */
	static class TextWriterProbe {
		void write(IssueComment comment) {
			comment.setText("unchecked");
		}
	}
}
