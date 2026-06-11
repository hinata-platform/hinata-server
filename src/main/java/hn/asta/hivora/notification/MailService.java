package hn.asta.hivora.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/** Sends transactional HTML mails via the configured SMTP server (Mailpit in dev). */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

	private final ObjectProvider<JavaMailSender> mailSender;

	@Value("${hivora.mail.from:hivora@localhost}")
	private String from;

	@Async
	public void send(String to, String subject, String headline, String body, String link) {
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
			helper.setText(html(headline, body, link), true);
			sender.send(message);
		}
		catch (Exception ex) {
			log.warn("Sending mail to {} failed: {}", to, ex.getMessage());
		}
	}

	/** Minimal, accessible HTML template matching the Hivora design system. */
	private String html(String headline, String body, String link) {
		String safeHeadline = HtmlUtils.htmlEscape(headline);
		String safeBody = HtmlUtils.htmlEscape(body);
		String button = link != null
				? "<p style=\"margin-top:24px\"><a href=\"" + HtmlUtils.htmlEscape(link)
						+ "\" style=\"background:#2D2B55;color:#ffffff;padding:12px 24px;"
						+ "border-radius:24px;text-decoration:none\">Open in Hivora</a></p>"
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
