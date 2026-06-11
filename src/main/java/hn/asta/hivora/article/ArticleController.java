package hn.asta.hivora.article;

import hn.asta.hivora.auth.CurrentUser;
import hn.asta.hivora.common.ApiException;
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
	private final CurrentUser currentUser;

	public record ArticleRequest(
			@NotBlank @Size(max = 300) String title,
			@Size(max = 100000) String content,
			String projectId,
			String parentId,
			List<String> tags,
			Integer sortOrder) {
	}

	@GetMapping
	public List<Article> list(@RequestParam(required = false) String projectId) {
		currentUser.require();
		return projectId != null
				? articles.findByProjectIdOrderBySortOrderAsc(projectId)
				: articles.findByProjectIdIsNullOrderBySortOrderAsc();
	}

	@GetMapping("/{id}")
	public Article get(@PathVariable String id) {
		currentUser.require();
		return articles.findById(id).orElseThrow(() -> ApiException.notFound("Article"));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Article create(@RequestBody @Valid ArticleRequest request) {
		String userId = currentUser.requireId();
		return articles.save(Article.builder()
				.title(request.title())
				.content(request.content())
				.projectId(request.projectId())
				.parentId(request.parentId())
				.tags(request.tags() != null ? request.tags() : List.of())
				.sortOrder(request.sortOrder() != null ? request.sortOrder() : 0)
				.authorId(userId)
				.build());
	}

	@PatchMapping("/{id}")
	public Article update(@PathVariable String id, @RequestBody @Valid ArticleRequest request) {
		currentUser.require();
		Article article = articles.findById(id).orElseThrow(() -> ApiException.notFound("Article"));
		article.setTitle(request.title());
		if (request.content() != null) article.setContent(request.content());
		article.setParentId(request.parentId());
		if (request.tags() != null) article.setTags(request.tags());
		if (request.sortOrder() != null) article.setSortOrder(request.sortOrder());
		return articles.save(article);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		currentUser.require();
		if (!articles.findByParentId(id).isEmpty()) {
			throw ApiException.conflict("Delete or move child articles first");
		}
		articles.deleteById(id);
	}
}
