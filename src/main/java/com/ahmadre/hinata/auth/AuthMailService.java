package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.notification.MailService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Transactional mails for the public authentication flows: the sign-up email
 * verification link and — when the admin-approval flag is on — the notice to
 * admins that a new registration is waiting. Rendered from the localed Thymeleaf
 * templates under {@code resources/templates/email/}. The links are app
 * deep-links (built by the caller via {@code GatewayService.relayLink}); no
 * backend HTML pages are rendered.
 */
@Service
@RequiredArgsConstructor
public class AuthMailService {

	private static final String SUBJECT_PREFIX = "[Hinata] ";

	private final MailService mail;

	/** Sign-up: confirm the email address to activate the account. */
	public void sendVerification(User user, String verifyUrl) {
		Map<String, Object> model = base(user);
		model.put("verifyUrl", verifyUrl);
		model.put("expiresHours", 24);
		String subject = de(user) ? "Bestätige deine E-Mail-Adresse" : "Confirm your email address";
		mail.sendTemplate(user.getEmail(), SUBJECT_PREFIX + subject, "email/verify-email", model);
	}

	/** Tells an admin a verified self-registration is awaiting their approval. */
	public void sendApprovalRequest(User admin, User newUser, String reviewUrl) {
		Map<String, Object> model = base(admin);
		model.put("newUserName", newUser.getDisplayName());
		model.put("newUserEmail", newUser.getEmail());
		model.put("reviewUrl", reviewUrl);
		String subject = de(admin)
				? "Neue Registrierung wartet auf Freigabe"
				: "A new registration awaits approval";
		mail.sendTemplate(admin.getEmail(), SUBJECT_PREFIX + subject, "email/approval-request", model);
	}

	private Map<String, Object> base(User user) {
		Map<String, Object> model = new HashMap<>();
		model.put("displayName", user.getDisplayName());
		model.put("locale", de(user) ? "de" : "en");
		return model;
	}

	private boolean de(User user) {
		return "de".equalsIgnoreCase(user.getLocale());
	}
}
