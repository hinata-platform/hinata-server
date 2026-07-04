package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.ClientIpResolver;
import com.ahmadre.hinata.me.SessionService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Public password-reset JSON API. The reset email links straight to the Flutter
 * app's in-app reset page (see {@code HinataProperties#resetLink}); that page
 * posts to {@code /reset/accept}, which sets the new password, revokes existing
 * sessions and signs the user in. No backend pages are rendered. Reuses the
 * one-time token on {@link User#getPasswordResetTokenHash()}.
 */
@Tag(name = "Auth · Password reset")
@RestController
@RequiredArgsConstructor
public class PasswordResetController {

	private final UserRepository users;
	private final UserService userService;
	private final PasswordEncoder passwordEncoder;
	private final AuthService authService;
	private final SessionService sessions;
	private final ClientIpResolver clientIpResolver;
	private final PasswordResetService passwordResetService;

	public record RequestReset(@NotBlank @Email String email) {
	}

	public record AcceptRequest(@NotBlank String token, @NotBlank String password) {
	}

	@Operation(summary = "Request a password-reset email (forgot password)")
	@SecurityRequirements
	@ResponseStatus(HttpStatus.ACCEPTED)
	@PostMapping("/api/v1/auth/reset/request")
	public Map<String, String> request(@RequestBody @Valid RequestReset body) {
		// Always 202, whether or not the address maps to a usable local account,
		// so the endpoint can't be used to probe which emails are registered.
		passwordResetService.requestByEmail(body.email());
		return Map.of("status", "reset_requested");
	}

	@Operation(summary = "Set a new password from a reset link and sign in")
	@SecurityRequirements
	@PostMapping("/api/v1/auth/reset/accept")
	public AuthController.LoginResponse accept(@RequestBody @Valid AcceptRequest request,
			HttpServletRequest http) {
		User user = resolve(request.token());
		userService.validatePassword(request.password());
		user.setPasswordHash(passwordEncoder.encode(request.password()));
		user.setPasswordChangedAt(Instant.now());
		user.setPasswordResetTokenHash(null);
		user.setPasswordResetExpiresAt(null);
		User saved = users.save(user);
		sessions.revokeAll(saved.getId()); // sign out every existing device
		TokenService.TokenPair pair = authService.issueWithSession(saved,
				clientIpResolver.resolve(http), http.getHeader("User-Agent"));
		return new AuthController.LoginResponse(false, null, pair.accessToken(), pair.refreshToken(),
				pair.expiresInSeconds(), AuthController.UserResponse.from(saved));
	}

	/** Resolves a {@code userId.secret} reset token to its user, or 400s. */
	private User resolve(String token) {
		int dot = token == null ? -1 : token.indexOf('.');
		if (dot <= 0) throw ApiException.badRequest("error.me.passwordResetInvalid");
		String id = token.substring(0, dot);
		String secret = token.substring(dot + 1);
		User user = users.findById(id)
				.orElseThrow(() -> ApiException.badRequest("error.me.passwordResetInvalid"));
		if (user.getPasswordResetTokenHash() == null || user.getPasswordResetExpiresAt() == null
				|| user.getPasswordResetExpiresAt().isBefore(Instant.now())
				|| !passwordEncoder.matches(secret, user.getPasswordResetTokenHash())) {
			throw ApiException.badRequest("error.me.passwordResetInvalid");
		}
		return user;
	}
}
