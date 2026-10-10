package com.ahmadre.hinata.space;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleAccess;
import com.ahmadre.hinata.article.ArticleRepository;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CRUD for knowledge-base spaces. Their {@code name} is the key articles reference
 * via {@code Article.space}, so a rename cascades onto the articles in the space and
 * a non-empty space can't be deleted.
 *
 * <p><b>Who sees a space.</b> Its author, and whoever reads at least one page filed
 * in it ({@link ArticleAccess}). Nobody else, admins included: a space used to be
 * listed to every account, which named other people's topics on everybody's
 * knowledge page and offered them for deletion. A space somebody cannot see answers
 * 404, so its existence is not given away by id either.
 *
 * <p><b>Who changes a space.</b> Its author. A space from before spaces had an author
 * is managed by an admin who sees it. Renaming rewrites the space on every page in it,
 * readable or not, which is why reading one page there is not enough.
 */
@Tag(name = "Knowledge Base")
@RestController
@RequestMapping("/api/v1/spaces")
@RequiredArgsConstructor
public class SpaceController {

	private final SpaceRepository spaces;
	private final ArticleRepository articles;
	private final CurrentUser currentUser;
	private final MongoTemplate mongo;
	private final ArticleAccess access;

	/** A space as one reader sees it: whether they may rename or delete it rides along. */
	public record SpaceResponse(String id, String name, String icon, int hue, String description,
			int sortOrder, String authorId, boolean canManage, Instant createdAt, Instant updatedAt) {

		static SpaceResponse of(Space space, boolean canManage) {
			return new SpaceResponse(space.getId(), space.getName(), space.getIcon(), space.getHue(),
					space.getDescription(), space.getSortOrder(), space.getAuthorId(), canManage,
					space.getCreatedAt(), space.getUpdatedAt());
		}
	}

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
	public List<SpaceResponse> list() {
		User user = currentUser.require();
		Set<String> reachable = reachableSpaces(user);
		return spaces.findAllByOrderBySortOrderAscNameAsc().stream()
				.filter(space -> isAuthor(space, user) || reachable.contains(key(space.getName())))
				.limit(LIST_CAP)
				.map(space -> SpaceResponse.of(space, canManage(space, user)))
				.toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public SpaceResponse create(@RequestBody @Valid SpaceRequest request) {
		String userId = currentUser.requireId();
		String name = request.name().trim();
		if (name.isEmpty()) {
			throw ApiException.badRequest("error.space.nameRequired");
		}
		if (spaces.existsByName(name)) {
			throw ApiException.conflict("error.space.exists");
		}
		return SpaceResponse.of(spaces.save(Space.builder()
				.name(name)
				.icon(request.icon() != null ? request.icon() : "file-text")
				.hue(request.hue() != null ? request.hue() : 250)
				.description(request.description() != null ? request.description() : "")
				.sortOrder(request.sortOrder() != null ? request.sortOrder() : nextSortOrder())
				.authorId(userId)
				.build()), true);
	}

	@PatchMapping("/{id}")
	public SpaceResponse update(@PathVariable String id, @RequestBody @Valid SpaceRequest request) {
		Space space = managed(id, currentUser.require());
		String newName = request.name().trim();
		if (newName.isEmpty()) {
			throw ApiException.badRequest("error.space.nameRequired");
		}
		if (!newName.equals(space.getName())) {
			if (spaces.existsByName(newName)) {
				throw ApiException.conflict("error.space.exists");
			}
			// Articles reference the space by name, so the rename cascades onto them —
			// as one update of that field alone. Saving each whole page would write
			// pages the caller cannot read (private ones included) and could undo an
			// edit somebody made to one of them a moment earlier.
			mongo.updateMulti(Query.query(Criteria.where("space").is(space.getName())),
					new Update().set("space", newName), Article.class);
			space.setName(newName);
		}
		if (request.icon() != null) space.setIcon(request.icon());
		if (request.hue() != null) space.setHue(request.hue());
		if (request.description() != null) space.setDescription(request.description());
		if (request.sortOrder() != null) space.setSortOrder(request.sortOrder());
		return SpaceResponse.of(spaces.save(space), true);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		Space space = managed(id, currentUser.require());
		if (articles.existsBySpace(space.getName())) {
			throw ApiException.conflict("error.space.notEmpty");
		}
		spaces.deleteById(id);
	}

	/**
	 * The space behind [id] if [user] may change it: 404 when they cannot see it at
	 * all, 403 when they see it but it is not theirs to change.
	 */
	private Space managed(String id, User user) {
		Space space = spaces.findById(id).orElseThrow(() -> ApiException.notFound("space"));
		if (!isAuthor(space, user) && !reachableSpaces(user).contains(key(space.getName()))) {
			throw ApiException.notFound("space");
		}
		if (!canManage(space, user)) {
			throw ApiException.forbidden("error.accessDenied");
		}
		return space;
	}

	private static boolean isAuthor(Space space, User user) {
		return space.getAuthorId() != null && space.getAuthorId().equals(user.getId());
	}

	private static boolean canManage(Space space, User user) {
		if (space.getAuthorId() == null) return user.isAdmin() || user.isOrgAdmin();
		return isAuthor(space, user);
	}

	/** The spaces of the pages [user] reads, by {@link #key}: one distinct over the readable pages. */
	private Set<String> reachableSpaces(User user) {
		Query readable = Query.query(access.sightOf(user).criteria());
		return mongo.findDistinct(readable, "space", Article.class, String.class).stream()
				.filter(name -> name != null && !name.isBlank())
				.map(SpaceController::key)
				.collect(Collectors.toSet());
	}

	/** Pages name their space without regard to case, as the article filter matches it. */
	private static String key(String name) {
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
	}

	private int nextSortOrder() {
		return spaces.findAll().stream().mapToInt(Space::getSortOrder).max().orElse(-1) + 1;
	}
}
