package com.ahmadre.hinata.me;

import com.ahmadre.hinata.notification.MailService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Transactional mails for the self-service account flows (email verification,
 * password reset, data export, security alerts). Rendered from the localed
 * Thymeleaf templates under {@code resources/templates/email/}.
 */
@Service
@RequiredArgsConstructor
public class AccountMailService {


	private final MailService mail;

	public void sendEmailChangeVerification(User user, String newEmail, String confirmUrl) {
		Map<String, Object> model = base(user);
		model.put("newEmail", newEmail);
		model.put("confirmUrl", confirmUrl);
		model.put("expiresHours", 24);
		String subject = de(user) ? "Bestätige deine neue E-Mail-Adresse" : "Confirm your new email address";
		mail.sendTemplate(newEmail, mail.subjectPrefix() + subject, "email/email-change-verify", model);
	}

	public void sendPasswordReset(User user, String resetUrl) {
		Map<String, Object> model = base(user);
		model.put("resetUrl", resetUrl);
		model.put("expiresMinutes", 30);
		String subject = de(user) ? "Passwort zurücksetzen" : "Reset your password";
		mail.sendTemplate(user.getEmail(), mail.subjectPrefix() + subject, "email/password-reset", model);
	}

	public void sendDataReportReady(User user, String downloadUrl) {
		Map<String, Object> model = base(user);
		model.put("downloadUrl", downloadUrl);
		model.put("expiresHours", 72);
		String subject = de(user) ? "Dein Datenexport ist bereit" : "Your data export is ready";
		mail.sendTemplate(user.getEmail(), mail.subjectPrefix() + subject, "email/data-report-ready", model);
	}

	/** Security alert: 2FA enabled/disabled, password or email changed. */
	public void sendSecurityAlert(User user, String headline, String body) {
		mail.sendNotification(user.getEmail(), mail.subjectPrefix() + headline, headline, body, null, null,
				de(user) ? "de" : "en", "email.eyebrow.SECURITY_ALERT");
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
