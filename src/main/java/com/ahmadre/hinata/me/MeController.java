package com.ahmadre.hinata.me;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The self-service account surface ({@code /me}) — a user managing their own
 * account. Distinct from the admin user-management board. No admin role required.
 */
@Tag(name = "Account")
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

	private final MeService me;
	private final CurrentUser currentUser;
	private final UserEvents userEvents;
	private final DataExportPdfService dataExportPdf;
	private final com.ahmadre.hinata.user.UserService users;
	private final org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

	// --- DTOs -----------------------------------------------------------------

	public record MeResponse(String id, String displayName, String username, String email,
			boolean emailVerified, String pendingEmail, String title, String locale, String origin,
			List<String> roles, boolean active, String avatarUrl, Instant createdAt,
			Instant passwordChangedAt, TwoFactorDto twoFactor,
			NotificationPreferences notificationPreferences) {

		static MeResponse from(User u) {
			return new MeResponse(u.getId(), u.getDisplayName(), u.getUsername(), u.getEmail(),
					u.isEmailVerified(), u.getPendingEmail(), u.getTitle(), u.getLocale(),
					u.getOrigin().name(), u.getRoles().stream().map(Enum::name).sorted().toList(),
					u.isActive(), u.getAvatarUrl(), u.getCreatedAt(), u.getPasswordChangedAt(),
					new TwoFactorDto(u.isTotpEnabled(), "TOTP", u.recoveryCodesRemaining(),
							u.getTotpEnabledAt()),
					u.getNotificationPreferences());
		}
	}

	public record TwoFactorDto(boolean enabled, String method, int recoveryRemaining,
			Instant enabledAt) {
	}

	public record SessionDto(String id, boolean current, String kind, String os, String client,
			String app, String location, String ipMasked, Instant lastActive) {
	}

	public record AccessTeamDto(String id, String key, String name, int hue, String role, int members) {
	}

	public record AccessProjectDto(String id, String key, String name, String color, String role) {
	}

	// Only en/de are actually translated (templates, e-mails, message bundles), so
	// reject locales we can't serve rather than silently downgrading them to English.
	public record UpdateProfileRequest(@Size(max = 120) String displayName,
			@Size(max = 120) String title, @Pattern(regexp = "de|en") String locale) {
	}

	public record EmailChangeRequest(@NotBlank @Email String newEmail) {
	}

	public record CodeRequest(@NotBlank @Size(min = 6, max = 20) String code) {
	}

	public record DeleteAccountRequest(@NotBlank String confirm) {
	}

	public record RecoveryCodesResponse(List<String> recoveryCodes) {
	}

	// --- Profile --------------------------------------------------------------

	@Operation(summary = "Get my account")
	@GetMapping
	public MeResponse get() {
		return MeResponse.from(currentUser.require());
	}

	@Operation(summary = "Update my profile (display name, title, locale)")
	@PatchMapping
	public MeResponse updateProfile(@RequestBody @Valid UpdateProfileRequest request) {
		User saved = me.updateProfile(currentUser.require(), request.displayName(), request.title(),
				request.locale());
		return MeResponse.from(saved);
	}

	// --- Email & password -----------------------------------------------------

	@Operation(summary = "Start a double-opt-in email change")
	@PostMapping("/email-change")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Map<String, String> requestEmailChange(@RequestBody @Valid EmailChangeRequest request) {
		me.requestEmailChange(currentUser.require(), request.newEmail());
		return Map.of("status", "verification_sent");
	}

	@Operation(summary = "Confirm an email change from the mailed link")
	@SecurityRequirements
	@GetMapping(value = "/email-change/confirm", produces = MediaType.TEXT_HTML_VALUE)
	public String confirmEmailChange(@RequestParam String token) {
		me.confirmEmailChange(token);
		return resultPage("Email confirmed",
				"Your sign-in email address has been updated. You can close this window and sign in again.");
	}

	@Operation(summary = "Email myself a one-time password reset link")
	@PostMapping("/password-reset")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Map<String, String> sendPasswordReset() {
		me.sendPasswordReset(currentUser.require());
		return Map.of("status", "reset_sent");
	}

	@Operation(summary = "Render the password-reset form from the mailed link")
	@SecurityRequirements
	@GetMapping(value = "/password-reset/confirm", produces = MediaType.TEXT_HTML_VALUE)
	public String passwordResetForm(@RequestParam String token) {
		return passwordFormPage(token);
	}

	@Operation(summary = "Set a new password from the reset form")
	@SecurityRequirements
	@PostMapping(value = "/password-reset/confirm", produces = MediaType.TEXT_HTML_VALUE)
	public String submitPasswordReset(@RequestParam String token, @RequestParam String password) {
		me.confirmPasswordReset(token, password);
		return resultPage("Password updated",
				"Your password has been reset and other devices were signed out. You can close this window.");
	}

	// --- Sessions -------------------------------------------------------------

	@Operation(summary = "List my active device sessions")
	@GetMapping("/sessions")
	public List<SessionDto> sessions() {
		String userId = currentUser.requireId();
		String current = currentUser.currentSessionId();
		return me.sessions(userId).stream().map(s -> new SessionDto(s.getId(),
				s.getId().equals(current), s.getKind().name(), s.getOs(), s.getClient(), s.getApp(),
				s.getLocation(), s.getIpMasked(), s.getLastActiveAt())).toList();
	}

	/**
	 * Long-lived account event stream. The app keeps this open while signed in;
	 * the server pushes a {@code logout} frame when this device's session is
	 * revoked (by the user, an admin, a password reset, or deactivation), so the
	 * client signs out immediately instead of waiting for its next request to 401.
	 */
	@Operation(summary = "Live account event stream (real-time sign-out)")
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream() {
		return userEvents.subscribe(currentUser.requireId(), currentUser.currentSessionId());
	}

	@Operation(summary = "Revoke one device session")
	@DeleteMapping("/sessions/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revokeSession(@PathVariable String id) {
		me.revokeSession(currentUser.requireId(), id, currentUser.currentSessionId());
	}

	@Operation(summary = "Sign out all other devices")
	@PostMapping("/sessions/revoke-others")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revokeOtherSessions() {
		me.revokeOtherSessions(currentUser.requireId(), currentUser.currentSessionId());
	}

	// --- Notification preferences --------------------------------------------

	@Operation(summary = "Get my notification preferences")
	@GetMapping("/notification-preferences")
	public NotificationPreferences notificationPreferences() {
		return me.notificationPreferences(currentUser.require());
	}

	@Operation(summary = "Replace my notification preferences")
	@PutMapping("/notification-preferences")
	public NotificationPreferences saveNotificationPreferences(
			@RequestBody NotificationPreferences prefs) {
		return me.saveNotificationPreferences(currentUser.require(), prefs);
	}

	// --- Two-factor (TOTP) ----------------------------------------------------

	@Operation(summary = "Begin TOTP enrolment (returns secret + otpauth URI)")
	@PostMapping("/2fa/totp/setup")
	public MeService.TotpSetup beginTotpSetup() {
		return me.beginTotpSetup(currentUser.require());
	}

	@Operation(summary = "Verify the first code, enable 2FA, return recovery codes")
	@PostMapping("/2fa/totp/verify")
	public RecoveryCodesResponse verifyTotp(@RequestBody @Valid CodeRequest request) {
		return new RecoveryCodesResponse(me.verifyTotpSetup(currentUser.require(), request.code()));
	}

	@Operation(summary = "Regenerate recovery codes (requires a current code)")
	@PostMapping("/2fa/recovery-codes/regenerate")
	public RecoveryCodesResponse regenerateRecoveryCodes(@RequestBody @Valid CodeRequest request) {
		return new RecoveryCodesResponse(
				me.regenerateRecoveryCodes(currentUser.require(), request.code()));
	}

	@Operation(summary = "Disable 2FA (requires a current TOTP or recovery code)")
	@PostMapping("/2fa/disable")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void disableTotp(@RequestBody @Valid CodeRequest request) {
		me.disableTotp(currentUser.require(), request.code());
	}

	// --- Access overview ------------------------------------------------------

	@Operation(summary = "Teams I belong to (with my role)")
	@GetMapping("/teams")
	public List<AccessTeamDto> teams() {
		String userId = currentUser.requireId();
		return me.teamsOf(userId).stream().map(t -> {
			var membership = t.membership(userId);
			String role = membership != null && membership.isAdmin() ? "Admin" : "Member";
			return new AccessTeamDto(t.getId(), t.getKey(), t.getName(), t.getColorHue(), role,
					t.getMembers() == null ? 0 : t.getMembers().size());
		}).toList();
	}

	@Operation(summary = "Projects I can access (with my role)")
	@GetMapping("/projects")
	public List<AccessProjectDto> projects() {
		User user = currentUser.require();
		return me.projectsOf(user).stream().map(p -> new AccessProjectDto(p.getId(), p.getKey(),
				p.getName(), p.getColor(), me.projectRole(p, user.getId()))).toList();
	}

	// --- GDPR -----------------------------------------------------------------

	@Operation(summary = "Request my data report (Art. 15) — async PDF + email")
	@PostMapping("/data-report")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Map<String, String> requestDataReport() {
		me.requestDataReport(currentUser.require());
		return Map.of("status", "queued");
	}

	@Operation(summary = "Download my data export (machine-readable)")
	@GetMapping("/export")
	public Map<String, Object> export() {
		return me.exportData(currentUser.require());
	}

	/**
	 * Browser-facing download of the formatted PDF data report (Art. 15). The
	 * link is e-mailed to the user and authorises itself with a short-lived,
	 * signed {@code token} query param, so it works without an interactive
	 * session. Invalid or expired links render a friendly HTML page.
	 */
	@Operation(summary = "Download my data report as a PDF (signed link)")
	@SecurityRequirements
	@GetMapping("/export.pdf")
	public ResponseEntity<byte[]> exportPdf(@RequestParam(required = false) String token,
			@org.springframework.web.bind.annotation.RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {
		boolean de = acceptLanguage != null && acceptLanguage.toLowerCase().startsWith("de");
		User user = resolveExportToken(token);
		if (user == null) {
			String body = resultPage(
					de ? "Link ungültig oder abgelaufen" : "Link invalid or expired",
					de ? "Bitte fordere deinen Datenexport in den Kontoeinstellungen erneut an."
							: "Please request your data export again from your account settings.");
			return ResponseEntity.status(HttpStatus.GONE)
					.contentType(MediaType.TEXT_HTML)
					.body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		byte[] pdf = dataExportPdf.build(user);
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_PDF)
				.header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"" + dataExportPdf.fileName(user) + "\"")
				.cacheControl(org.springframework.http.CacheControl.noStore())
				.body(pdf);
	}

	/** Validates the signed export token and loads its subject, or null. */
	private User resolveExportToken(String token) {
		if (token == null || token.isBlank()) {
			return null;
		}
		try {
			org.springframework.security.oauth2.jwt.Jwt jwt = jwtDecoder.decode(token);
			if (!com.ahmadre.hinata.auth.TokenService.isDownloadToken(
					jwt, com.ahmadre.hinata.auth.TokenService.PURPOSE_DATA_EXPORT)) {
				return null;
			}
			return users.get(jwt.getSubject());
		} catch (Exception e) {
			return null;
		}
	}

	@Operation(summary = "Delete my account (Art. 17) — type DELETE to confirm")
	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteAccount(@RequestBody @Valid DeleteAccountRequest request) {
		if (!"DELETE".equals(request.confirm())) {
			throw ApiException.badRequest("error.me.deleteConfirmationMismatch");
		}
		me.deleteAccount(currentUser.require());
	}

	// --- minimal public pages -------------------------------------------------

	private String resultPage(String title, String body) {
		return """
				<!doctype html><html><head><meta charset="utf-8"/>
				<meta name="viewport" content="width=device-width,initial-scale=1"/>
				<title>%s · hinata</title></head>
				<body style="margin:0;font-family:-apple-system,'Segoe UI',Roboto,sans-serif;background:#F4F3EF">
				<div style="max-width:460px;margin:64px auto;background:#fff;border:1px solid #E7E5DE;border-radius:24px;overflow:hidden">
				<div style="height:4px;background:#D9A032"></div>
				<div style="padding:32px">
				<div style="font-weight:800;color:#2D2B55;margin-bottom:20px">hinata</div>
				<h1 style="color:#23223F;font-size:20px;margin:0 0 12px">%s</h1>
				<p style="color:#6B6A85;font-size:15px;line-height:1.6;margin:0">%s</p>
				</div></div></body></html>
				""".formatted(escape(title), escape(title), escape(body));
	}

	private String passwordFormPage(String token) {
		return """
				<!doctype html><html><head><meta charset="utf-8"/>
				<meta name="viewport" content="width=device-width,initial-scale=1"/>
				<title>Reset password · hinata</title></head>
				<body style="margin:0;font-family:-apple-system,'Segoe UI',Roboto,sans-serif;background:#F4F3EF">
				<div style="max-width:460px;margin:64px auto;background:#fff;border:1px solid #E7E5DE;border-radius:24px;overflow:hidden">
				<div style="height:4px;background:#D9A032"></div>
				<div style="padding:32px">
				<div style="font-weight:800;color:#2D2B55;margin-bottom:20px">hinata</div>
				<h1 style="color:#23223F;font-size:20px;margin:0 0 16px">Choose a new password</h1>
				<form method="post" action="/api/v1/me/password-reset/confirm">
				<input type="hidden" name="token" value="%s"/>
				<input type="password" name="password" minlength="10" required placeholder="New password (min. 10 chars)"
				  style="width:100%%;box-sizing:border-box;padding:13px;border:1px solid #E7E5DE;border-radius:10px;font-size:15px;margin-bottom:14px"/>
				<button type="submit"
				  style="width:100%%;padding:13px;background:#2D2B55;color:#fff;border:0;border-radius:10px;font-size:15px;font-weight:600;cursor:pointer">
				  Update password</button>
				</form></div></div></body></html>
				""".formatted(escape(token));
	}

	private String escape(String value) {
		return org.springframework.web.util.HtmlUtils.htmlEscape(value);
	}
}
