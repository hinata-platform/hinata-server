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


	private final MailService mail;
	private final com.ahmadre.hinata.common.UserWords words;

	/**
	 * Invitation to join the workspace — carries the 7-day sign-up link. Sent
	 * synchronously so the caller learns whether delivery to SMTP succeeded;
	 * returns {@code true} on success.
	 */
	public boolean sendInvite(User invitee, String inviteUrl, String message, String inviterName) {
		Map<String, Object> model = new HashMap<>();
		model.put("locale", words.localeOf(invitee).getLanguage());
		model.put("inviteUrl", inviteUrl);
		model.put("inviterName", inviterName);
		model.put("message", message);
		model.put("expiresDays", 7);
		// The invitation is to the organization, not to the software it runs on —
		// and the subject line is the first (often only) thing that is read.
		String org = mail.organizationName();
		String subject = words.of(invitee, "email.subject.invite", org);
		return mail.sendTemplateSync(invitee.getEmail(), mail.subjectPrefix() + subject, "email/invite", model);
	}
}
