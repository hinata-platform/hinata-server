package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;

/**
 * Public invite-acceptance flow. An admin invites a local account (see
 * {@code AdminUserController}); the invitee follows the mailed link, lands on a
 * server-rendered "choose a password" form, and on submit the account becomes
 * active. Mirrors the password-reset confirm page in {@code MeController}.
 */
@Tag(name = "Auth · Invite")
@RestController
@RequiredArgsConstructor
public class InviteController {

	private final UserRepository users;
	private final UserService userService;
	private final PasswordEncoder passwordEncoder;

	@Operation(summary = "Render the invite-acceptance form from the mailed link")
	@SecurityRequirements
	@GetMapping(value = "/api/v1/auth/invite/confirm", produces = MediaType.TEXT_HTML_VALUE)
	public String inviteForm(@RequestParam String token) {
		resolve(token); // validate before showing the form
		return formPage(token);
	}

	@Operation(summary = "Accept an invitation and set the account password")
	@SecurityRequirements
	@PostMapping(value = "/api/v1/auth/invite/confirm", produces = MediaType.TEXT_HTML_VALUE)
	public String acceptInvite(@RequestParam String token, @RequestParam String password) {
		User user = resolve(token);
		userService.validatePassword(password);
		user.setPasswordHash(passwordEncoder.encode(password));
		user.setJoinedAt(Instant.now());
		user.setActive(true);
		user.setEmailVerified(true);
		user.setInviteTokenHash(null);
		user.setInviteExpiresAt(null);
		users.save(user);
		return resultPage("Welcome to Hinata",
				"Your account is ready. You can now sign in with your email and the password you chose.");
	}

	/** Resolves a {@code userId.secret} invite token to its pending user, or 400s. */
	private User resolve(String token) {
		int dot = token == null ? -1 : token.indexOf('.');
		if (dot <= 0) throw ApiException.badRequest("error.user.inviteInvalid");
		String id = token.substring(0, dot);
		String secret = token.substring(dot + 1);
		User user = users.findById(id).orElseThrow(
				() -> ApiException.badRequest("error.user.inviteInvalid"));
		if (!user.isInvitePending() || user.getInviteTokenHash() == null
				|| user.getInviteExpiresAt() == null
				|| user.getInviteExpiresAt().isBefore(Instant.now())
				|| !passwordEncoder.matches(secret, user.getInviteTokenHash())) {
			throw ApiException.badRequest("error.user.inviteInvalid");
		}
		return user;
	}

	private String formPage(String token) {
		return """
				<!doctype html><html><head><meta charset="utf-8"/>
				<meta name="viewport" content="width=device-width,initial-scale=1"/>
				<title>Set up your account · hinata</title></head>
				<body style="margin:0;font-family:-apple-system,'Segoe UI',Roboto,sans-serif;background:#F4F3EF">
				<div style="max-width:460px;margin:64px auto;background:#fff;border:1px solid #E7E5DE;border-radius:24px;overflow:hidden">
				<div style="height:4px;background:#D9A032"></div>
				<div style="padding:32px">
				<div style="font-weight:800;color:#2D2B55;margin-bottom:20px">hinata</div>
				<h1 style="color:#23223F;font-size:20px;margin:0 0 16px">Set up your account</h1>
				<form method="post" action="/api/v1/auth/invite/confirm">
				<input type="hidden" name="token" value="%s"/>
				<input type="password" name="password" minlength="10" required placeholder="Choose a password (min. 10 chars)"
				  style="width:100%%;box-sizing:border-box;padding:13px;border:1px solid #E7E5DE;border-radius:10px;font-size:15px;margin-bottom:14px"/>
				<button type="submit"
				  style="width:100%%;padding:13px;background:#2D2B55;color:#fff;border:0;border-radius:10px;font-size:15px;font-weight:600;cursor:pointer">
				  Create account</button>
				</form></div></div></body></html>
				""".formatted(HtmlUtils.htmlEscape(token));
	}

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
				""".formatted(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(title),
				HtmlUtils.htmlEscape(body));
	}
}
