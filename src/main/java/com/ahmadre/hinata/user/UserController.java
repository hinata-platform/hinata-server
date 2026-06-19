package com.ahmadre.hinata.user;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.NotificationService;
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
	private final NotificationService notifications;

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
		boolean wasActive = user.isActive();
		boolean wasAdmin = user.isAdmin();

		if (request.active() != null) applyActive(user, request.active(), wasAdmin);
		if (request.admin() != null) applyAdmin(user, request.admin(), wasAdmin);
		if (request.displayName() != null) user.setDisplayName(request.displayName());
		if (request.title() != null) user.setTitle(request.title());

		User saved = users.save(user);
		notifyAccountChanges(saved, wasActive, wasAdmin);
		return saved;
	}

	private void applyActive(User user, boolean active, boolean wasAdmin) {
		if (!active) {
			if (user.getId().equals(currentUser.requireId())) {
				throw ApiException.badRequest("error.user.cannotDeactivateSelf");
			}
			if (wasAdmin) requireAnotherActiveAdmin(user, "error.user.cannotDeactivateLastAdmin");
		}
		user.setActive(active);
	}

	private void applyAdmin(User user, boolean admin, boolean wasAdmin) {
		if (!admin && wasAdmin) {
			requireAnotherActiveAdmin(user, "error.user.cannotRemoveLastAdmin");
		}
		user.setRoles(admin ? Set.of(Role.ADMIN, Role.MEMBER) : Set.of(Role.MEMBER));
	}

	/** Notify the affected user of meaningful account changes (status / roles). */
	private void notifyAccountChanges(User saved, boolean wasActive, boolean wasAdmin) {
		if (saved.isActive() != wasActive) {
			if (saved.isActive()) notifications.notifyAccountActivated(saved);
			else notifications.notifyAccountDeactivated(saved);
		}
		if (saved.isAdmin() != wasAdmin) {
			notifications.notifyRolesChanged(saved);
		}
	}

	@DeleteMapping("/api/v1/admin/users/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		User user = userService.get(id);
		if (user.getId().equals(currentUser.requireId())) {
			throw ApiException.badRequest("error.user.cannotDeleteSelf");
		}
		if (user.isAdmin()) {
			requireAnotherActiveAdmin(user, "error.user.cannotDeleteLastAdmin");
		}
		// E-mail the user before the account (and its data) are removed.
		notifications.notifyAccountDeleted(user);
		userService.delete(user);
	}

	/** Guards against locking the organization out by removing its last active admin. */
	private void requireAnotherActiveAdmin(User user, String messageKey) {
		if (users.countByRolesContainingAndActiveIsTrueAndIdNot(Role.ADMIN, user.getId()) == 0) {
			throw ApiException.conflict(messageKey);
		}
	}
}
