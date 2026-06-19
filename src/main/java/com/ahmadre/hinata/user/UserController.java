package com.ahmadre.hinata.user;

import com.ahmadre.hinata.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.tags.Tag;
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

	// Admin user management lives in com.ahmadre.hinata.admin.AdminUserController.
}
