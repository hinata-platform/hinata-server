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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
	private final com.ahmadre.hinata.issue.IssueWatchService watchedIssues;
	private final com.ahmadre.hinata.auth.SecurityPolicy securityPolicy;
	private final org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;
	private final com.ahmadre.hinata.setup.SettingsService settings;
	private final com.ahmadre.hinata.setup.BrandLogoService brandLogo;
	private final org.springframework.context.MessageSource messages;

	// --- DTOs -----------------------------------------------------------------

	public record MeResponse(String id, String displayName, String username, String email,
			boolean emailVerified, String pendingEmail, String title, String pronouns, String locale,
			String origin, List<String> roles, boolean active, String avatarUrl, Instant createdAt,
			Instant passwordChangedAt, TwoFactorDto twoFactor,
			NotificationPreferences notificationPreferences) {

		static MeResponse from(User u) {
			NotificationPreferences prefs = u.getNotificationPreferences();
			// Fill in any events added since the account's prefs were last saved (e.g.
			// "ingest"), so the client always sees each event with its proper default.
			prefs = (prefs == null ? NotificationPreferences.defaults() : prefs).sanitized();
			return new MeResponse(u.getId(), u.getDisplayName(), u.getUsername(), u.getEmail(),
					u.isEmailVerified(), u.getPendingEmail(), u.getTitle(), u.getPronouns(),
					u.getLocale(), u.getOrigin().name(),
					u.getRoles().stream().map(Enum::name).sorted().toList(), u.isActive(),
					u.getAvatarUrl(), u.getCreatedAt(), u.getPasswordChangedAt(),
					new TwoFactorDto(u.isTotpEnabled(), "TOTP", u.recoveryCodesRemaining(),
							u.getTotpEnabledAt()),
					prefs);
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

	// Only the languages we ship translations for (templates, e-mails, message
	// bundles) are accepted — a locale we cannot serve is rejected rather than
	// silently downgraded to English.
	public record UpdateProfileRequest(@Size(max = 120) String displayName,
			@Size(max = 120) String title, @Size(max = 120) String pronouns,
			@Pattern(regexp = "de|en|zh|hi|es") String locale) {
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
		// Keep the stored locale in step with the client's Accept-Language so
		// async e-mails/push (which have no request context) localize to the
		// language the user actually runs the app in. The app re-fetches /me on
		// every start-up, so this self-heals accounts left on the sign-up default.
		User user = me.syncLocale(currentUser.require(),
				org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage());
		return MeResponse.from(user);
	}

	@Operation(summary = "Update my profile (display name, title, pronouns, locale)")
	@PatchMapping
	public MeResponse updateProfile(@RequestBody @Valid UpdateProfileRequest request) {
		User saved = me.updateProfile(currentUser.require(), request.displayName(), request.title(),
				request.pronouns(), request.locale());
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
	public ResponseEntity<String> confirmEmailChange(@RequestParam String token,
			@RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {
		me.confirmEmailChange(token);
		return page(resultPage(localeOf(acceptLanguage), "web.emailChange.doneTitle",
				"web.emailChange.doneBody"));
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
	public ResponseEntity<String> passwordResetForm(@RequestParam String token,
			@RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {
		return page(passwordFormPage(token, localeOf(acceptLanguage)));
	}

	@Operation(summary = "Set a new password from the reset form")
	@SecurityRequirements
	@PostMapping(value = "/password-reset/confirm", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> submitPasswordReset(@RequestParam String token,
			@RequestParam String password,
			@RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {
		me.confirmPasswordReset(token, password);
		return page(resultPage(localeOf(acceptLanguage), "web.passwordReset.doneTitle",
				"web.passwordReset.doneBody"));
	}

	// --- Sessions -------------------------------------------------------------

	/**
	 * The caller's device sessions, most recently active first.
	 *
	 * <p>
	 * Paginated because this list only ever grows: every browser, every phone
	 * and every reinstall adds a row, and an account a few years old answers
	 * with hundreds. The first page is what the settings screen shows; the rest
	 * is fetched only if the reader asks to see it.
	 */
	@Operation(summary = "List my active device sessions")
	@GetMapping("/sessions")
	public Page<SessionDto> sessions(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		// Both bounds, not just the upper one: PageRequest.of throws on a
		// negative page or a zero size, and an unhandled IllegalArgumentException
		// is a 500 with a stack trace in the log — one authenticated caller could
		// fill the error log from a query string.
		return sessionPage(PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)));
	}

	/**
	 * The same sessions as a plain array, for a client that predates the page.
	 *
	 * <p>Paginating this list changed its response from an array to a page
	 * envelope, and a published app cannot be recompiled: 10.2.1 reads the answer
	 * as a list, a cast that throws on an object. Its settings screen loads the
	 * account, the sessions, the teams and the projects together, so that one
	 * throw took the whole page down — the account did not fail to load, it was
	 * never asked for.
	 *
	 * <p>Routed by the absence of both parameters, which is exactly what the old
	 * client sends and never what the new one does (it always sends both). It can
	 * go when 10.2.1 is no longer in the field.
	 *
	 * <p>Bounded at the same 100 the page clamps to, because an unbounded read of
	 * this table is what pagination was introduced to stop. An account with more
	 * sessions than that sees its hundred most recent ones — which is the whole
	 * list for anyone who has not been signing in for years, and a bounded answer
	 * for everyone else.
	 */
	@Operation(summary = "List my active device sessions (legacy array; use the paged form)")
	@GetMapping(value = "/sessions", params = { "!page", "!size" })
	public List<SessionDto> sessionsLegacy() {
		return sessionPage(PageRequest.of(0, LEGACY_SESSION_LIMIT)).getContent();
	}

	/** Most sessions the pre-pagination response carries — see {@link #sessionsLegacy}. */
	private static final int LEGACY_SESSION_LIMIT = 100;

	private Page<SessionDto> sessionPage(PageRequest request) {
		String userId = currentUser.requireId();
		String current = currentUser.currentSessionId();
		return me.sessions(userId, request)
				.map(s -> new SessionDto(s.getId(), s.getId().equals(current), s.getKind().name(),
						s.getOs(), s.getClient(), s.getApp(), s.getLocation(), s.getIpMasked(),
						s.getLastActiveAt()));
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

	/**
	 * The issues the caller subscribed to, newest-touched first.
	 *
	 * <p>Only from projects they can still see: a subscription outlives the access
	 * that allowed it, and this list must never be the place where a former member
	 * still reads an issue's title.
	 */
	@Operation(summary = "Issues I watch")
	@GetMapping("/watched")
	public org.springframework.data.domain.Page<com.ahmadre.hinata.issue.Issue> watched(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return watchedIssues.watchedBy(currentUser.require(), page, size);
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
			@RequestHeader(name = "Accept-Language", required = false) String acceptLanguage) {
		User user = resolveExportToken(token);
		if (user == null) {
			String body = resultPage(localeOf(acceptLanguage), "web.dataExport.linkInvalidTitle",
					"web.dataExport.linkInvalidBody");
			return ResponseEntity.status(HttpStatus.GONE)
					.contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
					.header("Content-Security-Policy", PAGE_CSP)
					.body(body.getBytes(StandardCharsets.UTF_8));
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

	/** The languages the mailed-link pages are translated into. */
	private static final java.util.Set<String> PAGE_LOCALES =
			java.util.Set.of("en", "de", "zh", "hi", "es");

	/** The wordmark these pages carried before organizations could brand them. */
	private static final String PRODUCT_NAME = "hinata";

	/**
	 * The policy these two pages need, and the reason they are wrapped in a
	 * {@link ResponseEntity} at all. The chain-wide header is
	 * {@code default-src 'none'} — exactly right for a JSON API, but it also
	 * blocks the inline styling these pages are made of and the organization
	 * logo they now carry. Spring Security's header writers leave a header the
	 * handler already set alone (the mechanism {@code /api/v1/meta/logo} relies
	 * on), so this narrower policy replaces it for these responses only: still
	 * no script, no framing, no base-URI games, and a form that can post
	 * nowhere but back to us.
	 */
	private static final String PAGE_CSP = "default-src 'none'; img-src 'self'; "
			+ "style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; "
			+ "frame-ancestors 'none'";

	/** Serves a rendered page as UTF-8 HTML under {@link #PAGE_CSP}. */
	private static ResponseEntity<String> page(String html) {
		return ResponseEntity.ok()
				.contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
				.header("Content-Security-Policy", PAGE_CSP)
				.body(html);
	}

	/**
	 * The language of a page nobody opened from the app: these are landed on
	 * from a link in an e-mail, so the browser's {@code Accept-Language} is the
	 * only signal there is — the same rule {@code export.pdf} follows. Only
	 * Only the languages we ship bundles for are honoured; the rest land on
	 * English.
	 */
	private static Locale localeOf(String acceptLanguage) {
		if (acceptLanguage == null) {
			return Locale.ENGLISH;
		}
		// Just the primary tag of the first entry: "de-AT,de;q=0.9" is German to a
		// bundle that only has a language, and anything we do not speak is English.
		String tag = acceptLanguage.split(",")[0].trim().split("-")[0].toLowerCase(Locale.ROOT);
		return PAGE_LOCALES.contains(tag) ? Locale.forLanguageTag(tag) : Locale.ENGLISH;
	}

	/** Resolves page copy from {@code messages*.properties}, key as fallback. */
	private String t(Locale locale, String key, Object... args) {
		return messages.getMessage(key, args, key, locale);
	}

	/** What goes where the product wordmark used to be. */
	private record Brand(String name, boolean logo) {
	}

	/**
	 * Resolved once per render. Both reads are served from caches, so a warm
	 * instance pays no I/O for it — and the whole thing is guarded because
	 * branding must never be the reason a password cannot be reset: an
	 * unreachable settings store falls back to the product wordmark and the
	 * page still does its job.
	 */
	private Brand brand() {
		try {
			String name = settings.get().getOrganizationName();
			return new Brand(name == null || name.isBlank() ? PRODUCT_NAME : name.trim(),
					brandLogo.display().isPresent());
		} catch (Exception e) {
			return new Brand(PRODUCT_NAME, false);
		}
	}

	/**
	 * The browser counterpart of the mail masthead: on an instance that has a
	 * logo, a page reached from a transactional e-mail belongs to the
	 * organization rather than to the product.
	 *
	 * <p>The logo is referenced through our own {@code /api/v1/meta/logo} proxy,
	 * which is same-origin by construction — no CORS question, no bytes to
	 * inline — and only offered when {@link com.ahmadre.hinata.setup.BrandLogoService}
	 * actually holds usable bytes, so a logo we cannot fetch degrades to the
	 * organization's name instead of a broken-image icon. The image is capped
	 * but deliberately not boxed, and carries no {@code onerror}: inline script
	 * is not on this page's CSP, and an alt text in a text-sized row is a far
	 * better failure than an empty 200×32 hole.
	 */
	private String masthead(Brand brand) {
		String mark = brand.logo()
				? "<img src=\"/api/v1/meta/logo\" alt=\"" + escape(brand.name())
						+ "\" style=\"max-height:32px;max-width:200px\"/>"
				: escape(brand.name());
		return "<div style=\"font-weight:800;color:#2D2B55;margin-bottom:20px\">" + mark
				+ "</div>";
	}

	/**
	 * The page a mailed link lands on when there is nothing left to do: an
	 * e-mail change confirmed, a password reset, or an export link that has
	 * expired. Only the two message keys differ between those cases.
	 */
	private String resultPage(Locale locale, String titleKey, String bodyKey) {
		Brand brand = brand();
		String title = t(locale, titleKey);
		return """
				<!doctype html><html lang="%s"><head><meta charset="utf-8"/>
				<meta name="viewport" content="width=device-width,initial-scale=1"/>
				<title>%s · %s</title></head>
				<body style="margin:0;font-family:-apple-system,'Segoe UI',Roboto,sans-serif;background:#F4F3EF">
				<div style="max-width:460px;margin:64px auto;background:#fff;border:1px solid #E7E5DE;border-radius:24px;overflow:hidden">
				<div style="height:4px;background:#D9A032"></div>
				<div style="padding:32px">
				%s
				<h1 style="color:#23223F;font-size:20px;margin:0 0 12px">%s</h1>
				<p style="color:#6B6A85;font-size:15px;line-height:1.6;margin:0">%s</p>
				</div></div></body></html>
				""".formatted(locale.getLanguage(), escape(title), escape(brand.name()),
				masthead(brand), escape(title), escape(t(locale, bodyKey)));
	}

	private String passwordFormPage(String token, Locale locale) {
		int min = securityPolicy.passwordMinLength();
		Brand brand = brand();
		return """
				<!doctype html><html lang="%s"><head><meta charset="utf-8"/>
				<meta name="viewport" content="width=device-width,initial-scale=1"/>
				<title>%s · %s</title></head>
				<body style="margin:0;font-family:-apple-system,'Segoe UI',Roboto,sans-serif;background:#F4F3EF">
				<div style="max-width:460px;margin:64px auto;background:#fff;border:1px solid #E7E5DE;border-radius:24px;overflow:hidden">
				<div style="height:4px;background:#D9A032"></div>
				<div style="padding:32px">
				%s
				<h1 style="color:#23223F;font-size:20px;margin:0 0 16px">%s</h1>
				<form method="post" action="/api/v1/me/password-reset/confirm">
				<input type="hidden" name="token" value="%s"/>
				<input type="password" name="password" minlength="%d" required placeholder="%s"
				  style="width:100%%;box-sizing:border-box;padding:13px;border:1px solid #E7E5DE;border-radius:10px;font-size:15px;margin-bottom:14px"/>
				<button type="submit"
				  style="width:100%%;padding:13px;background:#2D2B55;color:#fff;border:0;border-radius:10px;font-size:15px;font-weight:600;cursor:pointer">
				  %s</button>
				</form></div></div></body></html>
				""".formatted(locale.getLanguage(),
				escape(t(locale, "web.passwordReset.pageTitle")), escape(brand.name()),
				masthead(brand), escape(t(locale, "web.passwordReset.heading")), escape(token), min,
				escape(t(locale, "web.passwordReset.placeholder", min)),
				escape(t(locale, "web.passwordReset.submit")));
	}

	/**
	 * Everything interpolated into these pages goes through here — copy, the
	 * organization name, the reset token.
	 *
	 * <p>Escaped for a UTF-8 document (which is what both pages declare and now
	 * also send in their Content-Type): identical protection — the same five
	 * characters that can break out of text or a quoted attribute — while German
	 * copy stays readable text instead of turning into entity soup.
	 */
	private String escape(String value) {
		return org.springframework.web.util.HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
	}
}
