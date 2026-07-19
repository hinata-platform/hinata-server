package com.ahmadre.hinata.user;

import com.ahmadre.hinata.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Users")
@RestController
@RequiredArgsConstructor
public class UserController {

	private final UserRepository users;
	private final CurrentUser currentUser;

	public record DirectoryUser(String id, String username, String displayName, String avatarUrl,
			String title) {

		static DirectoryUser from(User user) {
			return new DirectoryUser(user.getId(), user.getUsername(), user.getDisplayName(),
					user.getAvatarUrl(), user.getTitle());
		}
	}

	/**
	 * Hard ceiling on the number of rows the (array-shaped) directory endpoints
	 * ever return, so a large org can never stream its whole user table over the
	 * wire on a screen open. Interactive pickers page through {@link #search}
	 * instead; this is only a bounded backstop for reference lookups.
	 */
	private static final int DIRECTORY_CAP = 500;

	/**
	 * Lightweight, capped directory for assignee pickers – visible to all members.
	 * Accepts an optional regex-escaped {@code q} name filter and never returns
	 * more than {@link #DIRECTORY_CAP} active users; use {@link #search} for
	 * paged type-ahead in large orgs.
	 */
	@GetMapping("/api/v1/users")
	public List<DirectoryUser> directory(
			@RequestParam(required = false, defaultValue = "") String q) {
		currentUser.require();
		String regex = java.util.regex.Pattern.quote(q.trim());
		Pageable pageable = PageRequest.of(0, DIRECTORY_CAP,
				Sort.by(Sort.Direction.ASC, "displayName"));
		return users.searchActive(regex, pageable).map(DirectoryUser::from).getContent();
	}

	/**
	 * Batch id→display resolution for rendering (issue cards, avatars) without
	 * draining the whole directory. Ids are de-duplicated and capped; inactive
	 * users are dropped.
	 */
	@GetMapping("/api/v1/users/by-ids")
	public List<DirectoryUser> byIds(@RequestParam(required = false) List<String> ids) {
		currentUser.require();
		if (ids == null || ids.isEmpty()) return List.of();
		List<String> capped = ids.stream().filter(java.util.Objects::nonNull).distinct()
				.limit(DIRECTORY_CAP).toList();
		return users.findAllById(capped).stream().filter(User::isActive)
				.map(DirectoryUser::from).toList();
	}

	/**
	 * Paginated type-ahead directory search for assignee/member pickers — so a
	 * picker need not load every user in a large org. Matches active users by
	 * name, username or title; an empty query returns the first page of all.
	 * The term is regex-escaped (NoSQL-injection safe).
	 */
	@GetMapping("/api/v1/users/search")
	public Page<DirectoryUser> search(
			@RequestParam(required = false, defaultValue = "") String q,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		currentUser.require();
		String regex = java.util.regex.Pattern.quote(q.trim());
		Pageable pageable = PageRequest.of(page, Math.min(size, 100),
				Sort.by(Sort.Direction.ASC, "displayName"));
		return users.searchActive(regex, pageable).map(DirectoryUser::from);
	}

	public record UpdateProfileRequest(@Size(max = 120) String displayName,
			@Size(max = 120) String title, @Pattern(regexp = "de|en") String locale) {
	}

	@PatchMapping("/api/v1/users/me")
	public User updateProfile(@RequestBody @Valid UpdateProfileRequest request) {
		User user = currentUser.require();
		if (request.displayName() != null) user.setDisplayName(request.displayName());
		if (request.title() != null) user.setTitle(request.title());
		if (request.locale() != null) user.setLocale(request.locale());
		return users.save(user);
	}

	// Admin user management lives in com.ahmadre.hinata.admin.AdminUserController.
}
