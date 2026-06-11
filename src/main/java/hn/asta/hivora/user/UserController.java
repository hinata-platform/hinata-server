package hn.asta.hivora.user;

import hn.asta.hivora.auth.CurrentUser;
import hn.asta.hivora.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@Tag(name = "Users")
@RestController
@RequiredArgsConstructor
public class UserController {

	private final UserRepository users;
	private final UserService userService;
	private final CurrentUser currentUser;

	public record DirectoryUser(String id, String username, String displayName, String avatarUrl,
			String title) {

		static DirectoryUser from(User user) {
			return new DirectoryUser(user.getId(), user.getUsername(), user.getDisplayName(),
					user.getAvatarUrl(), user.getTitle());
		}
	}

	/** Lightweight directory for assignee pickers – visible to all members. */
	@GetMapping("/api/v1/users")
	public List<DirectoryUser> directory() {
		currentUser.require();
		return users.findAll().stream().filter(User::isActive).map(DirectoryUser::from).toList();
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

	// --- Admin user management -------------------------------------------------

	public record CreateUserRequest(
			@NotBlank @Email String email,
			@NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{3,40}") String username,
			@NotBlank @Size(max = 120) String displayName,
			@NotBlank @Size(min = 10, max = 128) String password,
			boolean admin) {
	}

	public record AdminUpdateUserRequest(Boolean active, Boolean admin,
			@Size(max = 120) String displayName, @Size(max = 120) String title) {
	}

	@GetMapping("/api/v1/admin/users")
	@PreAuthorize("hasRole('ADMIN')")
	public List<User> all() {
		return users.findAll();
	}

	@PostMapping("/api/v1/admin/users")
	@PreAuthorize("hasRole('ADMIN')")
	@ResponseStatus(HttpStatus.CREATED)
	public User create(@RequestBody @Valid CreateUserRequest request) {
		Set<Role> roles = request.admin() ? Set.of(Role.ADMIN, Role.MEMBER) : Set.of(Role.MEMBER);
		return userService.createLocal(request.email(), request.username(), request.displayName(),
				request.password(), roles);
	}

	@PatchMapping("/api/v1/admin/users/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public User adminUpdate(@PathVariable String id, @RequestBody @Valid AdminUpdateUserRequest request) {
		User user = userService.get(id);
		if (request.active() != null) {
			if (!request.active() && user.getId().equals(currentUser.requireId())) {
				throw ApiException.badRequest("You cannot deactivate yourself");
			}
			user.setActive(request.active());
		}
		if (request.admin() != null) {
			user.setRoles(request.admin() ? Set.of(Role.ADMIN, Role.MEMBER) : Set.of(Role.MEMBER));
		}
		if (request.displayName() != null) user.setDisplayName(request.displayName());
		if (request.title() != null) user.setTitle(request.title());
		return users.save(user);
	}
}
