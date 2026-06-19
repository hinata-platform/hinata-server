package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "Auth")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private final UserService userService;
	private final CurrentUser currentUser;
	private final JwtDecoder jwtDecoder;
	private final com.ahmadre.hinata.user.UserRepository users;
	private final com.ahmadre.hinata.config.ClientIpResolver clientIpResolver;

	public record LoginRequest(@NotBlank String identifier, @NotBlank String password) {
	}

	public record RefreshRequest(@NotBlank String refreshToken) {
	}

	public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
	}

	public record TokenResponse(String accessToken, String refreshToken, long expiresIn,
			UserResponse user) {
	}

	public record UserResponse(String id, String email, String username, String displayName,
			Set<String> roles, String avatarUrl, String title, String locale, String origin) {

		static UserResponse from(User user) {
			return new UserResponse(user.getId(), user.getEmail(), user.getUsername(),
					user.getDisplayName(),
					user.getRoles().stream().map(Enum::name).collect(Collectors.toSet()),
					user.getAvatarUrl(), user.getTitle(), user.getLocale(), user.getOrigin().name());
		}
	}

	@Operation(summary = "Login with email/username + password", description = "Returns a JWT access token and refresh token pair.")
	@ApiResponse(responseCode = "200", description = "Authenticated")
	@ApiResponse(responseCode = "401", description = "Bad credentials or account locked")
	@SecurityRequirements
	@PostMapping("/login")
	public TokenResponse login(@RequestBody @jakarta.validation.Valid LoginRequest request,
			HttpServletRequest http) {
		AuthService.LoginResult result = authService.login(
				request.identifier().trim(), request.password(), clientIpResolver.resolve(http));
		return toResponse(result.user(), result.tokens());
	}

	@Operation(summary = "Exchange a refresh token for a new token pair")
	@ApiResponse(responseCode = "200", description = "New token pair issued")
	@ApiResponse(responseCode = "401", description = "Invalid or expired refresh token")
	@SecurityRequirements
	@PostMapping("/refresh")
	public TokenResponse refresh(@RequestBody @jakarta.validation.Valid RefreshRequest request) {
		Jwt jwt;
		try {
			jwt = jwtDecoder.decode(request.refreshToken());
		}
		catch (JwtException ex) {
			throw ApiException.unauthorized("error.auth.invalidRefreshToken");
		}
		if (!TokenService.isRefreshToken(jwt)) {
			throw ApiException.unauthorized("error.auth.notRefreshToken");
		}
		User user = users.findById(jwt.getSubject())
				.orElseThrow(() -> ApiException.unauthorized("error.auth.unknownUser"));
		return toResponse(user, authService.refresh(user));
	}

	@Operation(summary = "Return the currently authenticated user")
	@GetMapping("/me")
	public UserResponse me() {
		return UserResponse.from(currentUser.require());
	}

	@PostMapping("/password")
	public Map<String, String> changePassword(
			@RequestBody @jakarta.validation.Valid ChangePasswordRequest request) {
		userService.changePassword(currentUser.require(), request.currentPassword(),
				request.newPassword());
		return Map.of("status", "ok");
	}

	private TokenResponse toResponse(User user, TokenService.TokenPair pair) {
		return new TokenResponse(pair.accessToken(), pair.refreshToken(), pair.expiresInSeconds(),
				UserResponse.from(user));
	}
}
