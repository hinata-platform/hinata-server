package com.ahmadre.hinata.admin;

import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Platform user administration — the admin "User management" board. Manages the
 * <em>global</em> account (role, auth origin, lifecycle status), distinct from
 * per-team membership. Every endpoint is admin-gated; the destructive paths
 * additionally enforce the last-active-admin and self-action invariants in
 * {@link AdminUserService}.
 */
@Tag(name = "Admin · Users")
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

	private final AdminUserService service;

	// --- Read ----------------------------------------------------------------

	@GetMapping
	public AdminUserListResponse list(
			// The client sends the search term as "q"; keep "query" as a fallback.
			@RequestParam(name = "q", required = false) String query,
			@RequestParam(required = false) String role,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String origin,
			@RequestParam(defaultValue = "lastActive") String sort,
			@RequestParam(defaultValue = "desc") String dir,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "25") int perPage) {
		return service.list(query, role, status, origin, sort, dir, page, perPage);
	}

	@GetMapping("/{id}")
	public AdminUserResponse getOne(@PathVariable String id) {
		return service.getOne(id);
	}

	// --- Create (local, with password) — kept for power/admin-settings use ----

	public record CreateUserRequest(
			@NotBlank @Email String email,
			@NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{3,40}") String username,
			@NotBlank @Size(max = 120) String displayName,
			// Min length is enforced dynamically by UserService#validatePassword
			// (SecurityPolicy, admin-configurable); only the hard upper bound is static.
			@NotBlank @Size(max = 128) String password,
			boolean admin) {
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public AdminUserResponse create(@RequestBody @Valid CreateUserRequest request) {
		Set<Role> roles = request.admin() ? Set.of(Role.ADMIN, Role.MEMBER) : Set.of(Role.MEMBER);
		User user = service.createLocal(request.email(), request.username(),
				request.displayName(), request.password(), roles);
		return service.toResponse(user);
	}

	// --- Invite / resend -----------------------------------------------------

	public record InviteRequest(@NotEmpty List<@Email String> emails, boolean admin,
			@Size(max = 1000) String message) {
	}

	@PostMapping("/invite")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Map<String, Integer> invite(@RequestBody @Valid InviteRequest request) {
		var r = service.invite(request.emails(), request.admin(), request.message());
		return Map.of("sent", r.sent(), "failed", r.failed(), "skipped", r.skipped());
	}

	public record IdsRequest(@NotEmpty List<String> ids) {
	}

	@PostMapping("/resend")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Map<String, Integer> resend(@RequestBody @Valid IdsRequest request) {
		var r = service.resend(request.ids());
		return Map.of("sent", r.sent(), "failed", r.failed(), "skipped", r.skipped());
	}

	// --- Status / role -------------------------------------------------------

	public record StatusRequest(@NotEmpty List<String> ids,
			@NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status) {
	}

	@PostMapping("/status")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void status(@RequestBody @Valid StatusRequest request) {
		service.setStatus(request.ids(), request.status());
	}

	public record RoleRequest(@NotEmpty List<String> ids,
			@NotBlank @Pattern(regexp = "ADMIN|USER") String role) {
	}

	@PostMapping("/role")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void role(@RequestBody @Valid RoleRequest request) {
		service.setRole(request.ids(), request.role());
	}

	// --- Approvals ------------------------------------------------------------

	@PostMapping("/approve")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void approve(@RequestBody @Valid IdsRequest request) {
		service.approve(request.ids());
	}

	// --- Credentials / sessions ----------------------------------------------

	@PostMapping("/password-reset")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public void passwordReset(@RequestBody @Valid IdsRequest request) {
		service.sendPasswordReset(request.ids());
	}

	@PostMapping("/revoke-sessions")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revokeSessions(@RequestBody @Valid IdsRequest request) {
		service.revokeSessions(request.ids());
	}

	// --- Edit / delete -------------------------------------------------------

	public record AdminUpdateUserRequest(Boolean active, Boolean admin,
			@Size(max = 120) String displayName, @Size(max = 120) String title,
			@Email String email) {
	}

	@PatchMapping("/{id}")
	public AdminUserResponse update(@PathVariable String id,
			@RequestBody @Valid AdminUpdateUserRequest request) {
		if (request.active() != null) {
			service.setStatus(List.of(id), request.active() ? "ACTIVE" : "DISABLED");
		}
		if (request.admin() != null) {
			service.setRole(List.of(id), request.admin() ? "ADMIN" : "USER");
		}
		return service.updateDetails(id, request.displayName(), request.title(), request.email());
	}

	@PostMapping("/delete")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteBulk(@RequestBody @Valid IdsRequest request) {
		service.delete(request.ids());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		service.delete(List.of(id));
	}
}
