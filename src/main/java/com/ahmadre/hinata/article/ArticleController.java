package com.ahmadre.hinata.article;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.richtext.LexicalJson;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Knowledge Base")
@RestController
@RequestMapping("/api/v1/articles")
@RequiredArgsConstructor
public class ArticleController {

	private final ArticleRepository articles;
	private final RichTextService richText;
	private final CurrentUser currentUser;
	private final ArticleAccess access;
	private final ArticleService service;

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

	@GetMapping
	public List<ArticleResponse> list(@RequestParam(required = false) String projectId,
			/** Only the pages of one team, for the Team-Admin picking pages to open. */
			@RequestParam(required = false) String teamId,
			@RequestParam(defaultValue = "false") boolean all,
			@RequestParam(required = false) String referencesIssue) {
		User user = currentUser.require();
		// Server-side issue⇄article backlink resolution: the references were
		// derived when the article was written, so this is an index lookup and
		// returns only the referencing articles the caller may see.
		if (referencesIssue != null) {
			// A backlink question never becomes a listing. A value that is not a key
			// has no referencing articles; falling through to the ordinary list put
			// every global article under "documented in" on the issue.
			if (!RichTextService.isIssueKey(referencesIssue)) return List.of();
			String key = referencesIssue.toUpperCase(java.util.Locale.ROOT);
			return ArticleResponse.from(access.sightOf(user)
					.filter(articles.findByReferencedIssueKeysContains(key))
					.stream().limit(ArticleService.LIST_CAP).toList());
		}
		if (teamId != null) {
			return ArticleResponse.from(service.listOfTeam(user, teamId));
		}
		return ArticleResponse.from(service.list(user, !all, projectId));
	}

	@GetMapping("/{id}")
	public ArticleResponse get(@PathVariable String id) {
		return ArticleResponse.from(service.readable(id, currentUser.require()));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ArticleResponse create(@RequestBody @Valid ArticleRequest request) {
		User user = currentUser.require();
		RichText body = richText.fromRequest(request.contentDoc(), request.content());
		return ArticleResponse.from(service.create(user, new ArticleService.Draft(request.title(), body,
				request.projectId(), request.teamId(), request.parentId(), request.space(), request.icon(),
				request.tags(), request.sortOrder())));
	}

	/**
	 * Edits a page. Its place (project, team, private) is not changed here: a
	 * client that never knew about places sends them back empty, and reading that
	 * as "make it private" would hide a page from everybody on every save. Places
	 * change through {@link #place}. A new parent does move the page, into the
	 * parent's place.
	 */
	@PatchMapping("/{id}")
	public ArticleResponse update(@PathVariable String id, @RequestBody @Valid ArticleRequest request) {
		User user = currentUser.require();
		Article article = service.readable(id, user);
		article.setTitle(request.title());
		// Resolved against what is stored: a client old enough to send only the
		// legacy field is sending back the derived plain text it was given, and
		// converting that would flatten the document it came from.
		RichText body = richText.fromRequest(request.contentDoc(), request.content(),
				article.getContentDoc(), article.getContent());
		if (body != null) {
			article.setContent(body.text());
			article.setContentDoc(body.doc());
			article.setReferencedIssueKeys(new java.util.ArrayList<>(body.issueKeys()));
		}
		if (request.space() != null) article.setSpace(request.space());
		if (request.icon() != null) article.setIcon(request.icon());
		if (request.tags() != null) article.setTags(request.tags());
		if (request.sortOrder() != null) article.setSortOrder(request.sortOrder());
		return ArticleResponse.from(service.save(article, request.parentId(), user));
	}

	/** Where a page lives. Both null makes it private to its author. */
	public record PlaceRequest(String projectId, String teamId) {
	}

	/**
	 * Moves a top-level page, with everything filed under it, into a project, a
	 * team or the author's private pages. Who reads it changes with it, so the
	 * caller must reach the new place.
	 */
	@PutMapping("/{id}/place")
	public ArticleResponse place(@PathVariable String id, @RequestBody PlaceRequest request) {
		User user = currentUser.require();
		return ArticleResponse.from(service.place(id, request.projectId(), request.teamId(), user));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		service.delete(service.readable(id, currentUser.require()));
	}
}
