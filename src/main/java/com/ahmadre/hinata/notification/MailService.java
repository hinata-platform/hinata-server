package com.ahmadre.hinata.notification;

import jakarta.mail.internet.InternetAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Map;

/** Sends transactional HTML mails via the configured SMTP server (Mailpit in dev). */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

	private final ObjectProvider<JavaMailSender> mailSender;
	private final ObjectProvider<SpringTemplateEngine> templateEngine;
	private final SmtpMailSenderProvider smtp;

	@Value("${hinata.mail.from:hinata@localhost}")
	private String from;

	@Async
	public void send(String to, String subject, String headline, String body, String link) {
		dispatch(to, subject, html(headline, body, link, "Open in Hinata"));
	}

	/** As {@link #send}, with a localized call-to-action button label. */
	@Async
	public void send(String to, String subject, String headline, String body, String link,
			String buttonLabel) {
		dispatch(to, subject, html(headline, body, link, buttonLabel));
	}

	/**
	 * Renders a Thymeleaf template from {@code resources/templates/} and mails it.
	 * Used for account-lifecycle mails (see {@code templates/email/account-*.html}).
	 */
	@Async
	public void sendTemplate(String to, String subject, String template, Map<String, Object> model) {
		sendTemplateSync(to, subject, template, model);
	}

	/**
	 * Synchronous templated send for admin flows (invite / resend) that must
	 * report a real per-recipient outcome instead of fire-and-forget. Returns
	 * {@code true} only if the message was handed to the SMTP server.
	 */
	public boolean sendTemplateSync(String to, String subject, String template, Map<String, Object> model) {
		SpringTemplateEngine engine = templateEngine.getIfAvailable();
		if (engine == null) {
			log.warn("No template engine available; cannot send mail to {}", to);
			return false;
		}
		Context context = new Context();
		context.setVariables(model);
		return dispatch(to, subject, engine.process(template, context));
	}

	private boolean dispatch(String to, String subject, String html) {
		// Prefer the admin-area SMTP (configured at runtime), falling back to a
		// Spring-autoconfigured sender if present.
		JavaMailSender sender = smtp.sender();
		if (sender == null) sender = mailSender.getIfAvailable();
		if (sender == null) {
			log.warn("No SMTP server configured; cannot send mail to {}", to);
			return false;
		}
		try {
			var message = sender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
			String fromAddress = smtp.fromAddress() != null ? smtp.fromAddress() : from;
			String fromName = smtp.fromName();
			helper.setFrom(fromName != null
					? new InternetAddress(fromAddress, fromName)
					: new InternetAddress(fromAddress));
			helper.setTo(to);
			helper.setSubject(subject);
			helper.setText(html, true);
			sender.send(message);
			log.info("Mail sent to {} (subject: {})", to, subject);
			return true;
		}
		catch (Exception ex) {
			log.warn("Sending mail to {} failed: {}", to, ex.getMessage());
			return false;
		}
	}

	/** Minimal, accessible HTML template matching the Hinata design system. */
	private String html(String headline, String body, String link, String buttonLabel) {
		String safeHeadline = HtmlUtils.htmlEscape(headline);
		String safeBody = HtmlUtils.htmlEscape(body);
		String button = link != null
				? "<p style=\"margin-top:24px\"><a href=\"" + HtmlUtils.htmlEscape(link)
						+ "\" style=\"background:#2D2B55;color:#ffffff;padding:12px 24px;"
						+ "border-radius:24px;text-decoration:none\">"
						+ HtmlUtils.htmlEscape(buttonLabel) + "</a></p>"
				: "";
		return """
				<div style="font-family:-apple-system,'Segoe UI',Roboto,sans-serif;background:#F2F1F8;padding:32px">
				  <div style="max-width:560px;margin:0 auto;background:#ffffff;border-radius:24px;padding:32px">
				    <h1 style="color:#2D2B55;font-size:20px;margin:0 0 16px">%s</h1>
				    <p style="color:#4A4866;font-size:15px;line-height:1.6;margin:0">%s</p>
				    %s
				  </div>
				</div>
				""".formatted(safeHeadline, safeBody, button);
	}
}
