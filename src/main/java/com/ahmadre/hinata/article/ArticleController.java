package com.ahmadre.hinata.article;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.moderation.ModerationSurface;
import com.ahmadre.hinata.richtext.LexicalJson;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "Knowledge Base")
@RestController
@RequestMapping("/api/v1/articles")
@RequiredArgsConstructor
public class ArticleController {

	private final ArticleRepository articles;
	private final RichTextService richText;
	private final CurrentUser currentUser;
	private final ProjectService projectService;
	private final TeamService teamService;
	// The body is gated inside RichTextService; the title has no converter to hide
	// behind and needs the gate called by name. See #gateTitle.
	private final ModerationService moderation;

	public record ArticleRequest(
			@NotBlank @Size(max = 300) String title,
			/**
			 * Markdown. Accepted and converted; {@code contentDoc} wins. Bounded by
			 * {@link RichTextService#MAX_MARKDOWN_CHARS} rather than by what a body
			 * can hold: markdown expands up to ~29× on conversion, and 100 000 chars
			 * of dense formatting produced a document three times over the size the
			 * read side accepts — content that loads and can never be saved again.
			 */
			@Size(max = RichTextService.MAX_MARKDOWN_CHARS) String content,
			/** Lexical document — what the app sends. */
			@Size(max = LexicalJson.MAX_JSON_CHARS) String contentDoc,
			String projectId,
			String teamId,
			String parentId,
			@Size(max = 60) String space,
			@Size(max = 60) String icon,
			List<String> tags,
			Integer sortOrder) {
	}

	/**
	 * Client-facing article shape. Decouples the HTTP contract from the
	 * {@code @Document} entity (layered-architecture rule) while remaining a
	 * byte-for-byte match of the entity's current JSON, so the client
	 * {@code Article.fromJson} is unchanged.
	 */
	public record ArticleResponse(String id, String projectId, String teamId, String parentId,
			String space, String icon, String title, String content, String contentDoc,
			List<String> tags, String authorId, int sortOrder, java.time.Instant createdAt,
			java.time.Instant updatedAt) {

		public static ArticleResponse from(Article a) {
			return new ArticleResponse(a.getId(), a.getProjectId(), a.getTeamId(), a.getParentId(),
					a.getSpace(), a.getIcon(), a.getTitle(), a.getContent(), a.getContentDoc(),
					a.getTags(), a.getAuthorId(), a.getSortOrder(), a.getCreatedAt(), a.getUpdatedAt());
		}

		static List<ArticleResponse> from(List<Article> articles) {
			return articles.stream().map(ArticleResponse::from).toList();
		}
	}

	/** Hard ceiling on the array-shaped corpus load so the KB can never stream an
	 * unbounded set of (potentially 100k-char) bodies to the client. */
	private static final int LIST_CAP = 1000;

	@GetMapping
	public List<ArticleResponse> list(@RequestParam(required = false) String projectId,
			@RequestParam(defaultValue = "false") boolean all,
			@RequestParam(required = false) String referencesIssue) {
		User user = currentUser.require();
		// Server-side issue⇄article backlink resolution: the references were
		// derived when the article was written, so this is an index lookup and
		// returns only the referencing articles the caller may see.
		if (referencesIssue != null && referencesIssue.matches("[A-Za-z]+-\\d+")) {
			String key = referencesIssue.toUpperCase(java.util.Locale.ROOT);
			return ArticleResponse.from(
					filterVisible(articles.findByReferencedIssueKeysContains(key), user)
							.stream().limit(LIST_CAP).toList());
		}
		final List<Article> base;
		if (all) {
			base = articles.findAllByOrderBySortOrderAsc();
		} else if (projectId != null) {
			base = articles.findByProjectIdOrderBySortOrderAsc(projectId);
		} else {
			base = articles.findByProjectIdIsNullOrderBySortOrderAsc();
		}
		return ArticleResponse.from(filterVisible(base, user).stream().limit(LIST_CAP).toList());
	}

	@GetMapping("/{id}")
	public ArticleResponse get(@PathVariable String id) {
		User user = currentUser.require();
		Article article = articles.findById(id).orElseThrow(() -> ApiException.notFound("article"));
		if (!canSee(article, user)) {
			// Don't leak existence of articles the user has no access to.
			throw ApiException.notFound("article");
		}
		return ArticleResponse.from(article);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ArticleResponse create(@RequestBody @Valid ArticleRequest request) {
		User user = currentUser.require();
		// Write-side ACL: the caller must be able to see the target project/team,
		// otherwise they could plant KB content into a space they can't access.
		assertCanTarget(request.projectId(), request.teamId(), user);
		gateTitle(request.title(), null);
		RichText body = richText.fromRequest(request.contentDoc(), request.content(),
				ModerationSurface.ARTICLE_CONTENT);
		if (body == null) body = RichText.EMPTY;
		return ArticleResponse.from(articles.save(Article.builder()
				.title(request.title())
				.content(body.text())
				.contentDoc(body.doc())
				.referencedIssueKeys(new java.util.ArrayList<>(body.issueKeys()))
				.projectId(request.projectId())
				.teamId(request.teamId())
				.parentId(request.parentId())
				.space(request.space())
				.icon(request.icon())
				.tags(request.tags() != null ? request.tags() : List.of())
				.sortOrder(request.sortOrder() != null ? request.sortOrder() : 0)
				.authorId(user.getId())
				.build()));
	}

	@PatchMapping("/{id}")
	public ArticleResponse update(@PathVariable String id, @RequestBody @Valid ArticleRequest request) {
		User user = currentUser.require();
		Article article = articles.findById(id).orElseThrow(() -> ApiException.notFound("article"));
		if (!canSee(article, user)) {
			throw ApiException.notFound("article");
		}
		// The caller must also be able to see the TARGET project/team — otherwise
		// an article could be relocated into a space the caller can't access.
		assertCanTarget(request.projectId(), request.teamId(), user);
		// Before the first setter, so a refused title cannot leave the article
		// half-updated: everything below mutates the loaded entity in place.
		gateTitle(request.title(), article.getTitle());
		article.setTitle(request.title());
		// Resolved against what is stored: a client old enough to send only the
		// legacy field is sending back the derived plain text it was given, and
		// converting that would flatten the document it came from.
		RichText body = richText.fromRequest(request.contentDoc(), request.content(),
				article.getContentDoc(), article.getContent(), ModerationSurface.ARTICLE_CONTENT);
		if (body != null) {
			article.setContent(body.text());
			article.setContentDoc(body.doc());
			article.setReferencedIssueKeys(new java.util.ArrayList<>(body.issueKeys()));
		}
		article.setProjectId(request.projectId());
		article.setTeamId(request.teamId());
		article.setParentId(request.parentId());
		if (request.space() != null) article.setSpace(request.space());
		if (request.icon() != null) article.setIcon(request.icon());
		if (request.tags() != null) article.setTags(request.tags());
		if (request.sortOrder() != null) article.setSortOrder(request.sortOrder());
		return ArticleResponse.from(articles.save(article));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		User user = currentUser.require();
		Article article = articles.findById(id).orElseThrow(() -> ApiException.notFound("article"));
		if (!canSee(article, user)) {
			throw ApiException.notFound("article");
		}
		if (!articles.findByParentId(id).isEmpty()) {
			throw ApiException.conflict("error.article.hasChildren");
		}
		articles.deleteById(id);
	}

	/**
	 * Puts an article title past the moderation gate.
	 *
	 * <p>The title was the only user-authored string in the knowledge base nothing
	 * judged, and it is the most-rendered one: 300 characters that appear in the
	 * sidebar tree, in every search result, in the breadcrumb of every child article
	 * and in the queue label of a report about it. A body at least has to be opened.
	 *
	 * <p>Judged as {@link ModerationSurface#ARTICLE_TITLE} rather than as content:
	 * both are technical surfaces, but the title is short and therefore always
	 * refusable, while a body is exempt from refusal on the "flag only" setting. That
	 * asymmetry is the point — retyping a heading costs a moment, losing a written
	 * page costs an afternoon.
	 *
	 * <p>A title equal to [stored] is not judged. {@code title} is {@code @NotBlank},
	 * so every PATCH carries it whether or not it changed — gating unconditionally
	 * would mean a heading written under an older ruleset makes every later save of
	 * that article fail, on a field the author is not even touching. Same rule
	 * {@link RichTextService#fromRequest} applies to the body for the same reason;
	 * pass {@code null} on create, where there is nothing to compare against.
	 *
	 * @throws com.ahmadre.hinata.moderation.ModerationException 422 when refused
	 */
	private void gateTitle(String title, String stored) {
		if (title != null && title.equals(stored)) {
			return;
		}
		moderation.checkText(title, ModerationSurface.ARTICLE_TITLE);
	}

	// ── visibility ──────────────────────────────────────────────────────────

	/**
	 * Articles (and therefore their spaces) are visible when:
	 * <ul>
	 *   <li>project-scoped → the user has access to that project (direct member
	 *       or via a team grant), or</li>
	 *   <li>team-scoped → the user belongs to that team, or</li>
	 *   <li>global (no project, no team) → visible to every authenticated user.</li>
	 * </ul>
	 * Platform admins see everything.
	 */
	private List<Article> filterVisible(List<Article> base, User user) {
		if (user.isAdmin()) {
			return base;
		}
		Set<String> projectIds = projectService.visibleTo(user).stream()
				.map(Project::getId).collect(Collectors.toSet());
		Set<String> teamIds = teamService.visibleTo(user).stream()
				.map(Team::getId).collect(Collectors.toSet());
		return base.stream()
				.filter(a -> canSee(a, projectIds, teamIds))
				.toList();
	}

	/**
	 * Guards the write-side target scope: a non-admin caller may only create/move
	 * an article into a project or team they can actually see. Global articles
	 * (null project + null team) stay creatable by any authenticated user.
	 */
	private void assertCanTarget(String projectId, String teamId, User user) {
		if (user.isAdmin()) {
			return;
		}
		if (projectId != null && projectService.visibleTo(user).stream()
				.noneMatch(p -> p.getId().equals(projectId))) {
			throw ApiException.forbidden("error.accessDenied");
		}
		if (teamId != null && teamService.visibleTo(user).stream()
				.noneMatch(t -> t.getId().equals(teamId))) {
			throw ApiException.forbidden("error.accessDenied");
		}
	}

	private boolean canSee(Article article, User user) {
		if (user.isAdmin()) {
			return true;
		}
		Set<String> projectIds = projectService.visibleTo(user).stream()
				.map(Project::getId).collect(Collectors.toSet());
		Set<String> teamIds = teamService.visibleTo(user).stream()
				.map(Team::getId).collect(Collectors.toSet());
		return canSee(article, projectIds, teamIds);
	}

	/**
	 * Delegates to {@link ArticleVisibility}, which is where this rule now lives so
	 * that global search enforces the same one. It used to be spelled out here and
	 * only here, and search — reading the same collection — never applied it.
	 */
	private boolean canSee(Article article, Set<String> projectIds, Set<String> teamIds) {
		return ArticleVisibility.canSee(article, projectIds, teamIds);
	}
}
