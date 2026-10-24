package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.config.ClientIpResolver;
import com.ahmadre.hinata.user.HouseRules;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Public invite JSON API. The invitation email links straight to the Flutter
 * app's in-app acceptance page (see {@code HinataProperties#inviteLink}); that
 * page calls {@code GET /invite/info} to show who is joining and
 * {@code POST /invite/accept} to set the password and sign in. No backend pages
 * are rendered.
 *
 * <p>While local sign-in is off there is no password to set: the page then
 * starts an SSO sign-in with the token instead ({@code /sso/start/{id}?invite=}),
 * and that sign-in completes the invitation (HIN-179).
 */
@Tag(name = "Auth · Invite")
@RestController
@RequiredArgsConstructor
public class InviteController {

	/** Languages we ship email templates for; anything else keeps the default. */
	private static final java.util.Set<String> SUPPORTED_LOCALES =
			java.util.Set.of("en", "de", "zh", "hi", "es", "ja", "fr", "ru", "ar");

	private final UserRepository users;
	private final UserService userService;
	private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
	private final AuthService authService;
	private final AuthPolicy authPolicy;
	private final Invitations invitations;
	private final ClientIpResolver clientIpResolver;
	private final ObjectProvider<HouseRules> houseRules;

	public record InviteInfo(String email, String displayName) {
	}

	@Operation(summary = "Validate an invite token and return the invitee's email")
	@SecurityRequirements
	@GetMapping("/api/v1/auth/invite/info")
	public InviteInfo info(@RequestParam String token) {
		User user = invitations.resolve(token);
		return new InviteInfo(user.getEmail(), user.getDisplayName());
	}

	public record AcceptRequest(@NotBlank String token, @NotBlank String password) {
	}

	@Operation(summary = "Accept an invitation, set the password and sign in")
	@SecurityRequirements
	@PostMapping("/api/v1/auth/invite/accept")
	public AuthController.LoginResponse accept(@RequestBody @Valid AcceptRequest request,
			HttpServletRequest http) {
		// A password nobody can sign in with would only make a session without SSO, and leave
		// the account for whoever signs in by SSO with its address first.
		authPolicy.assertLocalAuthEnabled();
		User user = invitations.resolve(request.token());
		userService.validatePassword(request.password());
		user.setPasswordHash(passwordEncoder.encode(request.password()));
		// Adopt the invitee's own device/browser language (sent as Accept-Language
		// by the app on this first-open request) so account emails from now on are
		// localized to them, rather than the "en" the invite was created with.
		String lang = LocaleContextHolder.getLocale().getLanguage();
		if (SUPPORTED_LOCALES.contains(lang)) user.setLocale(lang);
		user.setJoinedAt(Instant.now());
		user.setActive(true);
		user.setEmailVerified(true);
		user.setInviteTokenHash(null);
		user.setInviteExpiresAt(null);
		User saved = users.save(user);
		TokenService.TokenPair pair = authService.issueWithSession(saved,
				clientIpResolver.resolve(http), http.getHeader("User-Agent"));
		return new AuthController.LoginResponse(false, null, pair.accessToken(), pair.refreshToken(),
				pair.expiresInSeconds(), AuthController.UserResponse.from(saved, houseRules.getIfAvailable()));
	}
}
