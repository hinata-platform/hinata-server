package com.ahmadre.hinata.article;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * The history of a page: its versions, one of them in full, bringing one back,
 * and who worked on it. Whoever reads the page reads its history, as they may
 * edit it.
 */
@Tag(name = "Knowledge Base")
@RestController
@RequestMapping("/api/v1/articles/{id}")
@RequiredArgsConstructor
public class ArticleHistoryController {

	private final ArticleService service;
	private final ArticleHistory history;
	private final CurrentUser currentUser;

	/** One version with its document. */
	public record VersionResponse(int number, String title, String contentDoc, String editorId, Instant createdAt) {

		static VersionResponse from(ArticleVersion v) {
			return new VersionResponse(v.getNumber(), v.getTitle(), v.getContentDoc(), v.getEditorId(),
					v.getCreatedAt());
		}
	}

	@GetMapping("/versions")
	public Page<ArticleHistory.Summary> versions(@PathVariable String id,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		Article article = service.readable(id, currentUser.require());
		return history.list(article.getId(), page, size);
	}

	@GetMapping("/versions/{number}")
	public VersionResponse version(@PathVariable String id, @PathVariable int number) {
		Article article = service.readable(id, currentUser.require());
		return VersionResponse.from(history.get(article.getId(), number));
	}

	@PostMapping("/versions/{number}/restore")
	public ArticleController.ArticleResponse restore(@PathVariable String id, @PathVariable int number) {
		User user = currentUser.require();
		return ArticleController.ArticleResponse.from(service.restore(service.readable(id, user), number, user));
	}

	/** Everybody who saved a version, most recent first; the author when the page has no history yet. */
	@GetMapping("/contributors")
	public List<String> contributors(@PathVariable String id) {
		Article article = service.readable(id, currentUser.require());
		List<String> editors = history.editors(article.getId());
		if (!editors.isEmpty() || article.getAuthorId() == null) return editors;
		return List.of(article.getAuthorId());
	}
}
