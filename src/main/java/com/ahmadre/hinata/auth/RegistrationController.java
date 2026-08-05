package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.config.ClientIpResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public self-registration API: sign up, verify the email via the one-time link,
 * and resend a verification email. All links are app deep-links; no backend
 * pages are rendered. Gated by the {@code localAuthEnabled}/{@code registrationEnabled}
 * flags (see {@link AuthPolicy}).
 */
@Tag(name = "Auth · Registration")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class RegistrationController {

	private final RegistrationService registration;
	private final ClientIpResolver clientIpResolver;

	/**
	 * Both length caps are load-bearing on an <em>unauthenticated</em> endpoint.
	 *
	 * <p>{@code displayName} is capped at 120 like every other endpoint that
	 * accepts one; without it, this route — the one anyone on the internet can
	 * reach — was the only way to store an unbounded name, and that name is
	 * interpolated into other users' push notification titles.
	 *
	 * <p>{@code password} is capped because it is bcrypt-hashed before anything
	 * about the request is known to be legitimate. Hashing cost grows with input
	 * length, so an uncapped field on an anonymous endpoint is a CPU-burn lever:
	 * a handful of megabyte passwords ties up request threads for free. 200 is far
	 * past any real passphrase and far below anything that costs measurable work.
	 */
	public record RegisterRequest(
			@NotBlank @Email String email,
			@NotBlank @Size(min = 3, max = 40) String username,
			@NotBlank @Size(max = 120) String displayName,
			@NotBlank @Size(max = 200) String password) {
	}

	public record VerifyEmailRequest(@NotBlank String token) {
	}

	public record ResendRequest(@NotBlank @Email String email) {
	}

	@Operation(summary = "Register a new local account and send a verification email")
	@SecurityRequirements
	@ResponseStatus(HttpStatus.ACCEPTED)
	@PostMapping("/register")
	public Map<String, String> register(@RequestBody @Valid RegisterRequest request) {
		registration.register(request.email(), request.username(), request.displayName(),
				request.password());
		return Map.of("status", "verification_sent");
	}

	@Operation(summary = "Confirm an email address from the verification link")
	@SecurityRequirements
	@PostMapping("/verify-email")
	public RegistrationService.VerifyResult verifyEmail(@RequestBody @Valid VerifyEmailRequest request,
			HttpServletRequest http) {
		return registration.verifyEmail(request.token(), clientIpResolver.resolve(http),
				http.getHeader("User-Agent"));
	}

	@Operation(summary = "Resend the email-verification link")
	@SecurityRequirements
	@ResponseStatus(HttpStatus.ACCEPTED)
	@PostMapping("/resend-verification")
	public Map<String, String> resend(@RequestBody @Valid ResendRequest request) {
		registration.resendVerification(request.email());
		return Map.of("status", "verification_sent");
	}
}
