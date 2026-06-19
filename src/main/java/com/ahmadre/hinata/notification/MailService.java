package com.ahmadre.hinata.notification;

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

	@Value("${hinata.mail.from:hinata@localhost}")
	private String from;

	@Async
	public void send(String to, String subject, String headline, String body, String link) {
		dispatch(to, subject, html(headline, body, link));
	}

	/**
	 * Renders a Thymeleaf template from {@code resources/templates/} and mails it.
	 * Used for account-lifecycle mails (see {@code templates/email/account-*.html}).
	 */
	@Async
	public void sendTemplate(String to, String subject, String template, Map<String, Object> model) {
		SpringTemplateEngine engine = templateEngine.getIfAvailable();
		if (engine == null) {
			log.debug("No template engine available; skipping templated mail to {}", to);
			return;
		}
		Context context = new Context();
		context.setVariables(model);
		dispatch(to, subject, engine.process(template, context));
	}

	private void dispatch(String to, String subject, String html) {
		JavaMailSender sender = mailSender.getIfAvailable();
		if (sender == null) {
			log.debug("No SMTP server configured; skipping mail to {}", to);
			return;
		}
		try {
			var message = sender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
			helper.setFrom(from);
			helper.setTo(to);
			helper.setSubject(subject);
			helper.setText(html, true);
			sender.send(message);
		}
		catch (Exception ex) {
			log.warn("Sending mail to {} failed: {}", to, ex.getMessage());
		}
	}

	/** Minimal, accessible HTML template matching the Hinata design system. */
	private String html(String headline, String body, String link) {
		String safeHeadline = HtmlUtils.htmlEscape(headline);
		String safeBody = HtmlUtils.htmlEscape(body);
		String button = link != null
				? "<p style=\"margin-top:24px\"><a href=\"" + HtmlUtils.htmlEscape(link)
						+ "\" style=\"background:#2D2B55;color:#ffffff;padding:12px 24px;"
						+ "border-radius:24px;text-decoration:none\">Open in Hinata</a></p>"
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
