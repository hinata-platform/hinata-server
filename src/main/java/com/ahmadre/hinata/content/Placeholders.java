package com.ahmadre.hinata.content;

import org.springframework.context.MessageSource;

import java.text.MessageFormat;
import java.util.Locale;

/**
 * The words removed or deleted content is replaced with, in the language of the instance. Written
 * into the content itself, so every way it is read shows them without knowing anything about
 * moderation, the store app that cannot be updated included.
 */
public record Placeholders(String removedTitle, String removedText, String removedFile, String deletedText,
		String personPattern, String revertNote, Locale locale) {

	/** The texts in [locale], read once per removal or deletion. */
	public static Placeholders in(MessageSource messages, Locale locale) {
		return new Placeholders(
				messages.getMessage("moderation.placeholder.title", null, locale),
				messages.getMessage("moderation.placeholder.text", null, locale),
				messages.getMessage("moderation.placeholder.file", null, locale),
				messages.getMessage("moderation.placeholder.deleted", null, locale),
				messages.getMessage("moderation.placeholder.person", null, locale),
				messages.getMessage("moderation.placeholder.reverted", null, locale),
				locale);
	}

	/** The neutral name a person's removed profile shows, such as {@code Person 4f2a}. */
	public String personName(String code) {
		return new MessageFormat(personPattern, locale).format(new Object[] { code });
	}

	/** The text for content that went away for [reason]. */
	public String textFor(ScrubReason reason) {
		return reason == ScrubReason.REMOVED ? removedText : deletedText;
	}
}
