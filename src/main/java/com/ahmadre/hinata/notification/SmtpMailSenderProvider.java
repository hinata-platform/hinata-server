package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Builds a {@link JavaMailSender} from the runtime admin-area SMTP settings
 * ({@link ServerSettings.Smtp}) and rebuilds it whenever those settings change,
 * so "Outgoing SMTP" configured in the admin UI takes effect immediately — no
 * restart and no {@code spring.mail.*} env required. When SMTP is disabled or
 * incompletely configured, {@link #sender()} returns {@code null} and callers
 * fall back to the autoconfigured bean (if any) or skip sending.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmtpMailSenderProvider {

	private final SettingsService settings;

	private volatile JavaMailSender sender;
	private volatile ServerSettings.Smtp active;

	/**
	 * Cached alongside the sender rather than read per send: {@link #fromName()}
	 * is called on every outbound message, and settings.get() is a database read.
	 */
	private volatile String organizationName;

	@PostConstruct
	void init() {
		ServerSettings current = settings.get();
		organizationName = current.getOrganizationName();
		rebuild(current.getSmtp());
	}

	@EventListener
	void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		organizationName = event.settings().getOrganizationName();
		rebuild(event.settings().getSmtp());
	}

	private synchronized void rebuild(ServerSettings.Smtp smtp) {
		if (smtp == null || !smtp.isEnabled() || isBlank(smtp.getHost())) {
			sender = null;
			active = null;
			return;
		}
		JavaMailSenderImpl impl = new JavaMailSenderImpl();
		impl.setHost(smtp.getHost());
		impl.setPort(smtp.getPort());
		impl.setDefaultEncoding("UTF-8");
		boolean auth = !isBlank(smtp.getUsername());
		if (auth) {
			impl.setUsername(smtp.getUsername());
			impl.setPassword(smtp.getPassword());
		}
		Properties props = impl.getJavaMailProperties();
		props.put("mail.transport.protocol", "smtp");
		props.put("mail.smtp.auth", String.valueOf(auth));
		if (smtp.isSsl()) {
			props.put("mail.smtp.ssl.enable", "true");
		}
		else {
			props.put("mail.smtp.starttls.enable", String.valueOf(smtp.isStarttls()));
			if (smtp.isStarttls()) props.put("mail.smtp.starttls.required", "true");
		}
		props.put("mail.smtp.connectiontimeout", "10000");
		props.put("mail.smtp.timeout", "10000");
		props.put("mail.smtp.writetimeout", "10000");
		sender = impl;
		active = smtp;
		log.info("SMTP mail sender configured for host {}:{}", smtp.getHost(), smtp.getPort());
	}

	/** The current admin-configured sender, or {@code null} when SMTP is off. */
	public JavaMailSender sender() {
		return sender;
	}

	/** The configured From address, or {@code null} when SMTP is off. */
	public String fromAddress() {
		return active != null && !isBlank(active.getFromAddress()) ? active.getFromAddress() : null;
	}

	/**
	 * The From display name: what the admin configured, else the organization —
	 * the inbox row is the first thing a recipient reads, and on a self-hosted
	 * instance the sender is the organization, not the product.
	 */
	public String fromName() {
		if (active != null && !isBlank(active.getFromName())) {
			return active.getFromName();
		}
		String organization = organizationName;
		return isBlank(organization) ? null : organization.trim();
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
