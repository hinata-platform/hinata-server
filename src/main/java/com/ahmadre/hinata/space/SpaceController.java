package com.ahmadre.hinata.space;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleRepository;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD for knowledge-base spaces. Spaces are organisation-wide (visible to every
 * authenticated user); their {@code name} is the key articles reference via
 * {@code Article.space}, so a rename cascades onto the articles in the space and
 * a non-empty space can't be deleted.
 */
@Tag(name = "Knowledge Base")
@RestController
@RequestMapping("/api/v1/spaces")
@RequiredArgsConstructor
public class SpaceController {

	private final SpaceRepository spaces;
	private final ArticleRepository articles;
	private final CurrentUser currentUser;

	public record SpaceRequest(
			@NotBlank @Size(max = 60) String name,
			@Size(max = 60) String icon,
			@Min(0) @Max(360) Integer hue,
			@Size(max = 200) String description,
			Integer sortOrder) {
	}

	/** Backstop ceiling on the KB-space list (spaces are a small org-wide
	 * taxonomy; this only guards against unbounded growth). */
	private static final int LIST_CAP = 500;

	@GetMapping
	public List<Space> list() {
		currentUser.require();
		return spaces.findAllByOrderBySortOrderAscNameAsc().stream().limit(LIST_CAP).toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Space create(@RequestBody @Valid SpaceRequest request) {
		String userId = currentUser.requireId();
		String name = request.name().trim();
		if (name.isEmpty()) {
			throw ApiException.badRequest("error.space.nameRequired");
		}
		if (spaces.existsByName(name)) {
			throw ApiException.conflict("error.space.exists");
		}
		return spaces.save(Space.builder()
				.name(name)
				.icon(request.icon() != null ? request.icon() : "file-text")
				.hue(request.hue() != null ? request.hue() : 250)
				.description(request.description() != null ? request.description() : "")
				.sortOrder(request.sortOrder() != null ? request.sortOrder() : nextSortOrder())
				.authorId(userId)
				.build());
	}

	@PatchMapping("/{id}")
	public Space update(@PathVariable String id, @RequestBody @Valid SpaceRequest request) {
		currentUser.require();
		Space space = spaces.findById(id).orElseThrow(() -> ApiException.notFound("space"));
		String newName = request.name().trim();
		if (newName.isEmpty()) {
			throw ApiException.badRequest("error.space.nameRequired");
		}
		if (!newName.equals(space.getName())) {
			if (spaces.existsByName(newName)) {
				throw ApiException.conflict("error.space.exists");
			}
			// Articles reference the space by name — cascade the rename onto them.
			List<Article> inSpace = articles.findBySpace(space.getName());
			for (Article a : inSpace) {
				a.setSpace(newName);
				articles.save(a);
			}
			space.setName(newName);
		}
		if (request.icon() != null) space.setIcon(request.icon());
		if (request.hue() != null) space.setHue(request.hue());
		if (request.description() != null) space.setDescription(request.description());
		if (request.sortOrder() != null) space.setSortOrder(request.sortOrder());
		return spaces.save(space);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		currentUser.require();
		Space space = spaces.findById(id).orElseThrow(() -> ApiException.notFound("space"));
		if (!articles.findBySpace(space.getName()).isEmpty()) {
			throw ApiException.conflict("error.space.notEmpty");
		}
		spaces.deleteById(id);
	}

	private int nextSortOrder() {
		return spaces.findAll().stream().mapToInt(Space::getSortOrder).max().orElse(-1) + 1;
	}
}
