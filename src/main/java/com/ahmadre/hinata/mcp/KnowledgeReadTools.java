package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.richtext.LexicalToMarkdown;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Read-only MCP tool over the knowledge base. Gates on {@code kb:read} and
 * applies the exact same visibility rule the {@code ArticleController} enforces,
 * through {@link com.ahmadre.hinata.article.ArticleAccess}: a project page for the
 * project's people, a team page for whom the team opened it to, a private page for
 * its author. Access denial is reported as "not found"
 * so the tool never leaks the existence of an article the caller cannot see.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeReadTools {

	private final ScopeGuard scopeGuard;
	private final CurrentUser currentUser;
	private final ArticleService articleService;

	@McpTool(name = "read_kb_article", title = "Read a knowledge base article",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "Fetch a knowledge base article's full markdown content by id, in the "
					+ "same dialect update_kb_article accepts (:::info callouts, {{issue:KEY}} / "
					+ "{{doc:ID}} / {{user:ID}} links), so it can be edited and written back "
					+ "without losing formatting. Fails with not-found if the caller has no "
					+ "access to the article's project or team.")
	public ArticleView readKbArticle(
			@McpToolParam(description = "Article id") String id) {
		scopeGuard.require(Scopes.KB_READ);
		User user = currentUser.require();
		return ArticleView.of(requireVisible(id, user));
	}

	@McpTool(name = "list_kb_articles", title = "List knowledge base articles",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "List the knowledge base articles visible to the caller (metadata only, "
					+ "no content — use read_kb_article for the body). Optionally restrict to one "
					+ "project or one space.")
	public List<ArticleListItem> listKbArticles(
			@McpToolParam(required = false, description = "Only articles of this project") String projectId,
			@McpToolParam(required = false, description = "Only articles in this space (e.g. Engineering)") String space) {
		scopeGuard.require(Scopes.KB_READ);
		User user = currentUser.require();
		List<Article> candidates = articleService.list(user, projectId != null, projectId);
		return candidates.stream()
				.filter(a -> space == null || space.equalsIgnoreCase(a.getSpace()))
				.map(ArticleListItem::of)
				.toList();
	}

	/** Article metadata without the (potentially large) markdown body. */
	public record ArticleListItem(String id, String title, String space, String icon,
			String projectId, String teamId, String parentId, List<String> tags,
			String authorId, Instant updatedAt) {

		static ArticleListItem of(Article a) {
			return new ArticleListItem(a.getId(), a.getTitle(), a.getSpace(), a.getIcon(),
					a.getProjectId(), a.getTeamId(), a.getParentId(), a.getTags(),
					a.getAuthorId(), a.getUpdatedAt());
		}
	}

	/**
	 * Loads an article and enforces the caller's visibility, mirroring the
	 * knowledge-base controller. Shared with {@link HinataResources}. Throws
	 * 404 both when the article is missing and when it is hidden from the caller.
	 */
	Article requireVisible(String id, User user) {
		return articleService.readable(id, user);
	}

	/**
	 * Lean article projection — the markdown content plus placement metadata.
	 *
	 * <p>{@code content} is rendered back to markdown from the stored document, not
	 * read out of the derived plain-text field. {@code update_kb_article} takes
	 * markdown, so the read has to return markdown or the standard read → edit →
	 * write loop replaces a formatted article with a flattening of itself.
	 */
	public record ArticleView(
			String id, String title, String content, String space, String icon,
			String projectId, String teamId, String parentId, List<String> tags,
			String authorId, Instant createdAt, Instant updatedAt) {

		static ArticleView of(Article a) {
			return new ArticleView(
					a.getId(), a.getTitle(),
					LexicalToMarkdown.fromStored(a.getContentDoc(), a.getContent()),
					a.getSpace(), a.getIcon(),
					a.getProjectId(), a.getTeamId(), a.getParentId(), a.getTags(),
					a.getAuthorId(), a.getCreatedAt(), a.getUpdatedAt());
		}
	}
}
