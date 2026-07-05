package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleRepository;
import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * MCP write tools for the knowledge base. Mirrors {@code ArticleController.create}:
 * the article's author is the authenticated user and its scope is set by the
 * optional project / team ids (global when both are absent). Gates on the
 * {@code kb:write} scope, audits the write and returns a lean article view.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeWriteTools {

	private final ArticleRepository articles;
	private final CurrentUser currentUser;
	private final ScopeGuard scopeGuard;
	private final AuditService audit;

	/** Lean projection of a knowledge-base article for MCP callers. */
	public record ArticleView(String id, String title, String content, String projectId,
			String teamId, String parentId, String space, List<String> tags,
			String authorId, Instant createdAt, Instant updatedAt) {

		static ArticleView of(Article article) {
			return new ArticleView(article.getId(), article.getTitle(), article.getContent(),
					article.getProjectId(), article.getTeamId(), article.getParentId(),
					article.getSpace(), article.getTags(), article.getAuthorId(),
					article.getCreatedAt(), article.getUpdatedAt());
		}
	}

	@McpTool(name = "create_kb_article", title = "Create knowledge base article",
			description = "Create a knowledge base article authored by the current user. Scope it to a "
					+ "project or a team by passing that id (leave both empty for a global, organisation-wide "
					+ "article). Returns the created article.")
	public ArticleView create_kb_article(
			@McpToolParam(required = true, description = "Article title") String title,
			@McpToolParam(required = true, description = "Markdown body of the article") String content,
			@McpToolParam(required = false, description = "Project id to scope the article to (only project members see it)") String projectId,
			@McpToolParam(required = false, description = "Team id to scope the article to (only team members see it)") String teamId,
			@McpToolParam(required = false, description = "Parent article id for tree placement") String parentId,
			@McpToolParam(required = false, description = "Knowledge-base space the article lives in (e.g. Engineering)") String space,
			@McpToolParam(required = false, description = "Labels / tags") List<String> tags) {
		scopeGuard.require(Scopes.KB_WRITE);
		User me = currentUser.require();
		Article saved = articles.save(Article.builder()
				.title(title)
				.content(content)
				.projectId(projectId)
				.teamId(teamId)
				.parentId(parentId)
				.space(space)
				.tags(tags != null ? tags : List.of())
				.authorId(me.getId())
				.build());
		audit.event(AuditAction.MCP_KB_CREATED).actor(me)
				.meta("article", saved.getId()).log();
		return ArticleView.of(saved);
	}
}
