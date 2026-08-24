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

import com.ahmadre.hinata.setup.MailBandComposer;

import org.springframework.core.io.ByteArrayResource;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Sends transactional HTML mails via the configured SMTP server (Mailpit in dev). */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

	/**
	 * Content-ID of the band that opens every templated mail. The artwork is
	 * inlined into the message rather than linked: Hinata is self-hosted, so an
	 * instance is regularly unreachable from wherever the recipient reads their
	 * mail, and a linked band would render as a broken image on exactly those
	 * installs. Hotlinking would additionally leak every recipient's IP and open
	 * time to whoever hosts the picture.
	 */
	static final String MASTHEAD_CID = "hinata-masthead";

	/** Longest organization name that still leaves room for a subject on a phone. */
	private static final int MAX_SUBJECT_TAG = 22;

	/** One image travelling with the message body as a related part. */
	private record InlinePart(String cid, byte[] data, String contentType) {}

	private final ObjectProvider<JavaMailSender> mailSender;
	private final ObjectProvider<SpringTemplateEngine> templateEngine;
	private final SmtpMailSenderProvider smtp;
	private final com.ahmadre.hinata.setup.SettingsService settings;
	private final com.ahmadre.hinata.setup.BrandLogoService brandLogo;

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
		// Each mail gets the illustration that belongs to it, carrying whichever
		// lockup this instance has. Resolved before the render so the body and the
		// attached part can never disagree about which band this mail shows.
		String backdrop = backdropFor(template);
		byte[] band = brandLogo.mailBand(backdrop)
				.or(() -> MailBandComposer.composeHinata(backdrop))
				.orElse(null);
		String html = render(engine, template, model);
		return dispatch(to, subject, html,
				List.of(new InlinePart(MASTHEAD_CID, band, MailBandComposer.CONTENT_TYPE)));
	}

	/**
	 * The artwork a template opens with, named after the template itself — an
	 * invitation gets a door standing open, a password reset gets a key. A
	 * template with no artwork of its own falls through to the neutral band inside
	 * the composer, so adding a template never breaks a send.
	 */
	static String backdropFor(String template) {
		if (template == null || template.isBlank()) {
			return MailBandComposer.DEFAULT_BACKDROP;
		}
		return template.substring(template.lastIndexOf('/') + 1);
	}

	/**
	 * Renders {@code template} against {@code model}. The Thymeleaf {@link Context}
	 * is built with an explicit {@link Locale} taken from the model's {@code locale}
	 * key, because the copy lives in {@code email-messages[_de].properties} and is
	 * resolved with {@code #{...}} — a default-locale context would silently mail
	 * everyone English (or, with fallback-to-system-locale on, the host's language).
	 */
	/**
	 * Renders {@code template} against {@code model}.
	 *
	 * <p>Every variable is set with {@code putIfAbsent}: that is the seam the
	 * e-mail preview task renders through, substituting on-disk filenames for the
	 * {@code cid:} references a browser cannot resolve.
	 */
	public String render(SpringTemplateEngine engine, String template, Map<String, Object> model) {
		Object locale = model.get("locale");
		Locale resolved = (locale instanceof String tag && !tag.isBlank())
				? Locale.forLanguageTag(tag)
				: Locale.ENGLISH;
		Map<String, Object> vars = new HashMap<>(model);
		// The band rides along as an inlined part (see dispatch), so the template
		// addresses it by Content-ID rather than by URL.
		vars.putIfAbsent("mastheadSrc", "cid:" + MASTHEAD_CID);
		vars.putIfAbsent("mastheadHeight", MailBandComposer.DISPLAY_HEIGHT);
		// Thymeleaf treats an absent variable and a null one the same in `${x != null}`,
		// but the footer also reads it as text — put it in explicitly.
		vars.putIfAbsent("organizationName", organizationName());
		return engine.process(template, new Context(resolved, vars));
	}

	/**
	 * The bracketed tag every transactional subject opens with — "[AStA] ", not
	 * "[Hinata] ". The inbox list is where identity actually lands, and on a
	 * self-hosted instance the sender is the organization.
	 *
	 * <p>Truncated hard: a subject line is read at a glance on a phone, where a
	 * long organization name would push the actual subject off the row entirely.
	 */
	public String subjectPrefix() {
		String name = organizationName();
		return "[" + (name.length() > MAX_SUBJECT_TAG
				? name.substring(0, MAX_SUBJECT_TAG - 1).trim() + "…"
				: name) + "] ";
	}

	/**
	 * The instance's organization, or the product name on one that has none.
	 *
	 * <p>Held rather than read per call: this is on the path of every subject line
	 * and every render, so a fan-out to fifty watchers would otherwise cost a
	 * hundred settings lookups for a value that changes when an admin saves a
	 * form. Filled lazily instead of at startup so a directly-constructed
	 * instance (the template render tests) needs no lifecycle.
	 */
	public String organizationName() {
		String cached = organization;
		if (cached != null) {
			return cached;
		}
		String resolved;
		try {
			String name = settings.get().getOrganizationName();
			resolved = name == null || name.isBlank() ? "Hinata" : name.trim();
		}
		catch (Exception ex) {
			// Branding must never be the reason a mail fails to go out — and a
			// failure is not cached, so the next send tries again.
			return "Hinata";
		}
		organization = resolved;
		return resolved;
	}

	private volatile String organization;

	@org.springframework.context.event.EventListener
	void onSettingsChanged(com.ahmadre.hinata.setup.SettingsService.SettingsChangedEvent event) {
		organization = null;
	}

	private boolean dispatch(String to, String subject, String html, List<InlinePart> parts) {
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
			// the body that is already there. Only parts the body actually references
			// are attached — and only non-empty ones: an empty part renders as a
			// broken-image icon, which is strictly worse than the alt text a missing
			// one falls back to.
			for (InlinePart part : parts) {
				if (part.data() != null && part.data().length > 0
						&& html.contains("cid:" + part.cid())) {
					helper.addInline(part.cid(), new ByteArrayResource(part.data()),
							part.contentType());
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

}
