package com.ahmadre.hinata.admin;

import com.ahmadre.hinata.notification.MailService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Transactional mail for the admin user-management flows. Kept separate from the
 * self-service {@code AccountMailService} so the admin feature stays decoupled.
 * Rendered from the localed Thymeleaf templates under
 * {@code resources/templates/email/}.
 */
@Service
@RequiredArgsConstructor
public class AdminMailService {

	private static final String SUBJECT_PREFIX = "[Hinata] ";

	private final MailService mail;

	/** Invitation to join the workspace — carries the 7-day sign-up link. */
	public void sendInvite(User invitee, String inviteUrl, String message, String inviterName) {
		boolean de = "de".equalsIgnoreCase(invitee.getLocale());
		Map<String, Object> model = new HashMap<>();
		model.put("locale", de ? "de" : "en");
		model.put("inviteUrl", inviteUrl);
		model.put("inviterName", inviterName);
		model.put("message", message);
		model.put("expiresDays", 7);
		String subject = de ? "Du wurdest zu Hinata eingeladen" : "You've been invited to Hinata";
		mail.sendTemplate(invitee.getEmail(), SUBJECT_PREFIX + subject, "email/invite", model);
	}
}
