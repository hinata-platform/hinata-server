package com.ahmadre.hinata.config;

import java.util.regex.Pattern;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Replaces the logback message converter so a secret that travels in a URL path never reaches the log.
 * Spring Security names the request path at DEBUG ("Securing GET /api/v1/shifts/feed/{token}.ics"), and
 * the personal shift feed carries its bearer token in exactly that path. Registered for {@code %m},
 * {@code %msg} and {@code %message} in {@code logback-spring.xml}, so every appender of the default
 * Spring Boot layout is covered.
 */
public class LogSecretMasking extends MessageConverter {

	/** The token segment of the shift feed path, whatever follows it. */
	static final Pattern FEED_TOKEN = Pattern.compile("(/shifts/feed/)[^/?#\\s.]+");

	@Override
	public String convert(ILoggingEvent event) {
		return mask(super.convert(event));
	}

	static String mask(String message) {
		if (message == null || !message.contains("/shifts/feed/")) {
			return message;
		}
		return FEED_TOKEN.matcher(message).replaceAll("$1***");
	}

}
