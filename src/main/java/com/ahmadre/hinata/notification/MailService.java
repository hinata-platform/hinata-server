package com.ahmadre.hinata.notification;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sends transactional HTML mails via the configured SMTP server (Mailpit in dev). */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

	/**
	 * Content-ID of the masthead that opens every templated mail. The artwork is
	 * inlined into the message rather than linked: Hinata is self-hosted, so an
	 * instance is regularly unreachable from wherever the recipient reads their
	 * mail, and a linked masthead would render as a broken image on exactly those
	 * installs.
	 */
	static final String MASTHEAD_CID = "hinata-masthead";

	/** A masthead variant: the classpath artwork and the height it displays at. */
	private record Masthead(String resource, int height) {}

	/**
	 * Which band heads which mail. Nearly everything gets the plain aurora — that
	 * repetition is what a masthead is for. Only the two mails that mark a
	 * beginning carry an illustration; a picture on a password reset is noise, and
	 * the band is inlined into every message it heads. Selected by the model's
	 * {@code masthead} key (see AuthMailService / AdminMailService).
	 */
	private static final Map<String, Masthead> MASTHEADS = Map.of(
			"default", new Masthead("email/masthead.jpg", 118),
			"welcome", new Masthead("email/masthead-welcome.jpg", 200),
			"invite", new Masthead("email/masthead-invite.jpg", 200));

	private final ObjectProvider<JavaMailSender> mailSender;
	private final ObjectProvider<SpringTemplateEngine> templateEngine;
	private final SmtpMailSenderProvider smtp;

	/** Artwork read from the classpath on first use and kept; 28-50 KB each. */
	private final Map<String, byte[]> artCache = new ConcurrentHashMap<>();

	@Value("${hinata.mail.from:hinata@localhost}")
	private String from;

	/** A file to attach to an outbound reply. */
	public record Attachment(String fileName, String contentType, byte[] data) {}

	/** Outcome of an outbound reply so callers can surface an accurate error. */
	public enum SendResult { SENT, NO_SMTP, SEND_FAILED }

	/**
	 * Sends a user-authored reply to the original sender of an ingested e-mail.
	 * {@code replyTo} (the project's ingest mailbox) and threading headers are set
	 * so a customer reply loops back into ingest and threads in their client.
	 */
	public SendResult sendReply(String to, String replyTo, String subject, String htmlBody,
			String inReplyToMessageId, List<Attachment> attachments) {
		JavaMailSender sender = smtp.sender();
		if (sender == null) sender = mailSender.getIfAvailable();
		if (sender == null) {
			log.warn("No SMTP server configured; cannot send e-mail reply to {}", to);
			return SendResult.NO_SMTP;
		}
		try {
			boolean multipart = attachments != null && !attachments.isEmpty();
			MimeMessage message = sender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, multipart, "UTF-8");
			String fromAddress = smtp.fromAddress() != null ? smtp.fromAddress() : from;
			String fromName = smtp.fromName();
			helper.setFrom(fromName != null
					? new InternetAddress(fromAddress, fromName)
					: new InternetAddress(fromAddress));
			helper.setTo(to);
			if (replyTo != null && !replyTo.isBlank()) helper.setReplyTo(replyTo);
			helper.setSubject(subject);
			helper.setText(htmlBody, true);
			if (inReplyToMessageId != null && !inReplyToMessageId.isBlank()) {
				message.setHeader("In-Reply-To", inReplyToMessageId);
				message.setHeader("References", inReplyToMessageId);
			}
			if (multipart) {
				for (Attachment a : attachments) {
					helper.addAttachment(a.fileName(), new ByteArrayResource(a.data()),
							a.contentType());
				}
			}
			sender.send(message);
			log.info("E-mail reply sent to {} (subject: {})", to, subject);
			return SendResult.SENT;
		}
		catch (Exception ex) {
			log.warn("Sending e-mail reply to {} failed: {}", to, ex.getMessage());
			return SendResult.SEND_FAILED;
		}
	}

	/**
	 * Sends a bell notification by mail. {@code eyebrowKey} names an
	 * {@code email.eyebrow.<TYPE>} message so the recipient can tell an
	 * assignment from a mention before reading a word; pass {@code null} for the
	 * neutral label. Title and body arrive already localized from the caller.
	 */
	@Async
	public void sendNotification(String to, String subject, String headline, String body, String link,
			String buttonLabel, String locale, String eyebrowKey) {
		Map<String, Object> model = new HashMap<>();
		model.put("locale", locale);
		model.put("headline", headline);
		model.put("body", body);
		model.put("ctaLink", link);
		model.put("ctaLabel", buttonLabel);
		model.put("eyebrowKey", eyebrowKey);
		sendTemplateSync(to, subject, "email/notification", model);
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
		Masthead art = mastheadFor(model);
		return dispatch(to, subject, render(engine, template, model), art);
	}

	private static Masthead mastheadFor(Map<String, Object> model) {
		Object variant = model.get("masthead");
		return MASTHEADS.getOrDefault(variant instanceof String s ? s : "default",
				MASTHEADS.get("default"));
	}

	/**
	 * Renders {@code template} against {@code model}. The Thymeleaf {@link Context}
	 * is built with an explicit {@link Locale} taken from the model's {@code locale}
	 * key, because the copy lives in {@code email-messages[_de].properties} and is
	 * resolved with {@code #{...}} — a default-locale context would silently mail
	 * everyone English (or, with fallback-to-system-locale on, the host's language).
	 */
	public String render(SpringTemplateEngine engine, String template, Map<String, Object> model) {
		Object locale = model.get("locale");
		Locale resolved = (locale instanceof String tag && !tag.isBlank())
				? Locale.forLanguageTag(tag)
				: Locale.ENGLISH;
		Map<String, Object> vars = new HashMap<>(model);
		// The masthead rides along as an inlined part (see dispatch), so the
		// template addresses it by Content-ID rather than by URL.
		vars.putIfAbsent("mastheadSrc", "cid:" + MASTHEAD_CID);
		vars.putIfAbsent("mastheadHeight", mastheadFor(model).height());
		return engine.process(template, new Context(resolved, vars));
	}

	private boolean dispatch(String to, String subject, String html, Masthead art) {
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
			// multipart/related, so the masthead can travel with the body as an
			// inlined part instead of being fetched from the instance.
			MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
			String fromAddress = smtp.fromAddress() != null ? smtp.fromAddress() : from;
			String fromName = smtp.fromName();
			helper.setFrom(fromName != null
					? new InternetAddress(fromAddress, fromName)
					: new InternetAddress(fromAddress));
			helper.setTo(to);
			helper.setSubject(subject);
			helper.setText(html, true);
			// Must follow setText: MimeMessageHelper builds the related part around
			// the body that is already there.
			if (html.contains("cid:" + MASTHEAD_CID)) {
				byte[] bytes = artwork(art.resource());
				if (bytes != null) {
					helper.addInline(MASTHEAD_CID, new ByteArrayResource(bytes), "image/jpeg");
				}
			}
			sender.send(message);
			log.info("Mail sent to {} (subject: {})", to, subject);
			return true;
		}
		catch (Exception ex) {
			log.warn("Sending mail to {} failed: {}", to, ex.getMessage());
			return false;
		}
	}

	/**
	 * Artwork bytes, read from the classpath on first use. A missing or unreadable
	 * asset degrades to a mail without the band (the template's bgcolor and alt
	 * text still carry the brand) rather than to a failed send.
	 */
	private byte[] artwork(String resource) {
		return artCache.computeIfAbsent(resource, path -> {
			try (var in = new ClassPathResource(path).getInputStream()) {
				return StreamUtils.copyToByteArray(in);
			}
			catch (IOException ex) {
				log.warn("E-mail artwork {} unreadable: {}", path, ex.getMessage());
				return new byte[0];
			}
		});
	}
}
