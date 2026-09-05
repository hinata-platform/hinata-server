package com.ahmadre.hinata.common;

import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * A {@link UserWords} backed by the real bundles, for tests that assert what a
 * reader actually sees.
 *
 * <p>Mocking the {@code MessageSource} instead would let a test pass while the
 * bundle it depends on is missing the key — which is precisely the failure the
 * translations are supposed to be checked for.
 */
public final class UserWordsFixture {

	private UserWordsFixture() {
	}

	public static UserWords real() {
		ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
		messages.setBasenames("messages", "email-messages");
		messages.setDefaultEncoding("UTF-8");
		messages.setFallbackToSystemLocale(false);
		return new UserWords(messages);
	}
}
