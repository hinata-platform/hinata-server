package com.ahmadre.hinata.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.richtext.RichTextService;
import org.junit.jupiter.api.Test;

/**
 * The agent loop is read → change one paragraph → write the whole body back, and
 * the write tools take markdown. So what the read tools return has to <em>be</em>
 * markdown: handing the model the derived plain text while calling it markdown
 * turned every agent edit into a flattening of the article — headings, lists,
 * tables, code blocks and links replaced by a paragraph per line.
 */
class McpMarkdownRoundTripTest {

	private final RichTextService richText = new RichTextService();

	private static final String SOURCE = """
			# Runbook

			Vor jedem Deploy, siehe {{issue:HIN-1}}.

			:::info
			Jede Änderung wird geprüft.
			:::

			- [x] Tests grün
			- [ ] Changelog

			| Umgebung | Ziel |
			|---|---|
			| prod | `track.asta.hn` |

			> Im Zweifel: nicht deployen.
			""";

	@Test
	void anIssueViewHandsTheModelMarkdownRatherThanAFlattening() {
		RichText body = richText.fromMarkdown(SOURCE);
		Issue issue = Issue.builder().id("i1").readableId("HIN-1").title("Deploy")
				.description(body.text()).descriptionDoc(body.doc())
				.build();

		String seen = McpViews.IssueView.of(issue).description();

		assertThat(seen).contains("# Runbook").contains(":::info").contains("- [x] Tests grün")
				.contains("| Umgebung | Ziel |").contains("> Im Zweifel").contains("{{issue:HIN-1}}");
		assertThat(seen).as("the derived plain text is not markdown")
				.isNotEqualTo(issue.getDescription());
	}

	@Test
	void anArticleViewHandsTheModelMarkdownRatherThanAFlattening() {
		RichText body = richText.fromMarkdown(SOURCE);
		Article article = Article.builder().id("a1").title("Runbook")
				.content(body.text()).contentDoc(body.doc())
				.build();

		assertThat(KnowledgeReadTools.ArticleView.of(article).content())
				.contains("# Runbook").contains(":::info").contains("`track.asta.hn`");
		assertThat(KnowledgeWriteTools.ArticleView.of(article).content())
				.as("the write tool's return view must agree with the read tool's")
				.isEqualTo(KnowledgeReadTools.ArticleView.of(article).content());
	}

	/**
	 * The whole point: what the model reads, written straight back, has to produce
	 * the document it read. Anything less and an unrelated edit destroys formatting
	 * the agent never touched.
	 */
	@Test
	void whatTheModelReadsWrittenBackUnchangedStoresTheSameDocument() {
		RichText stored = richText.fromMarkdown(SOURCE);
		Article article = Article.builder().id("a1").title("Runbook")
				.content(stored.text()).contentDoc(stored.doc())
				.build();

		String read = KnowledgeReadTools.ArticleView.of(article).content();
		RichText writtenBack = richText.fromMarkdown(read);

		assertThat(writtenBack.doc()).isEqualTo(stored.doc());
	}

	@Test
	void aCommentViewIsMarkdownToo() {
		RichText body = richText.fromMarkdown("sieht **gut** aus, siehe {{issue:HIN-2}}");
		IssueComment comment = IssueComment.builder().id("c1").issueId("i1").authorId("u1")
				.text(body.text()).textDoc(body.doc())
				.build();

		assertThat(McpViews.CommentView.of(comment).text())
				.isEqualTo("sieht **gut** aus, siehe {{issue:HIN-2}}");
	}

	@Test
	void aRowWithoutADocumentStillReadsAsItsStoredText() {
		// A voice comment has no document; a row that predates the migration has
		// none either. Falling back to the field is honest — it is what is stored.
		IssueComment voice = IssueComment.builder().id("c1").issueId("i1").authorId("u1")
				.type(IssueComment.Type.VOICE).build();
		Issue legacy = Issue.builder().id("i1").description("nur Text, kein Dokument").build();

		assertThat(McpViews.CommentView.of(voice).text()).isNull();
		assertThat(McpViews.IssueView.of(legacy).description()).isEqualTo("nur Text, kein Dokument");
	}
}
