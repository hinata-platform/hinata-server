package com.ahmadre.hinata.moderation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code MessageBundleCompletenessTest} finds keys by scanning source for string
 * literals matching {@code "error.*"}, which cannot see either of the two kinds of
 * key this feature uses: {@link ModerationCategory#reasonKey()} builds its key by
 * concatenation, and the reason keys are not under the {@code error.} prefix at all.
 *
 * <p>Without this test a category added later would ship a refusal dialog reading
 * "Your content was not saved because moderation.reason.extremism" — a raw key in
 * front of the user, at the single worst moment to show one.
 */
class ModerationMessagesTest {

	private static final Locale[] SUPPORTED = { Locale.ENGLISH, Locale.GERMAN };

	/**
	 * Loads a bundle without {@link ResourceBundle}'s default-locale fallback.
	 *
	 * <p>The plain {@code getBundle("messages", ENGLISH)} does not do what it looks
	 * like it does. There is no {@code messages_en.properties} — English lives in the
	 * base bundle — so the lookup misses, and before falling back to the base bundle
	 * {@code ResourceBundle} first tries the <em>JVM default locale</em>. On a German
	 * machine that resolves English to {@code messages_de.properties}, and a test
	 * written the obvious way asserts German twice while appearing to cover both.
	 *
	 * <p>Spring is not affected — {@code spring.messages.fallback-to-system-locale}
	 * is already false in {@code application.yml} — so this is a hazard of the test,
	 * not of the product. Which is exactly why it has to be handled here rather than
	 * ignored.
	 */
	private static ResourceBundle bundle(Locale locale) {
		return ResourceBundle.getBundle("messages", locale,
				ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
	}

	@ParameterizedTest
	@EnumSource(ModerationCategory.class)
	void everyCategoryHasAReasonInEveryBundle(ModerationCategory category) {
		for (Locale locale : SUPPORTED) {
			ResourceBundle bundle = bundle(locale);

			assertThat(bundle.containsKey(category.reasonKey()))
					.as("%s must define %s", locale, category.reasonKey())
					.isTrue();
			assertThat(bundle.getString(category.reasonKey()))
					.as("%s for %s must not be blank", category.reasonKey(), locale)
					.isNotBlank();
		}
	}

	@Test
	void theGenericFallbackReasonExists() {
		for (Locale locale : SUPPORTED) {
			assertThat(bundle(locale))
					.matches(bundle -> bundle.containsKey("moderation.reason.generic"));
		}
	}

	/**
	 * The refusal templates are run through {@link MessageFormat}, so an unescaped
	 * apostrophe silently swallows the placeholder and the user is told their
	 * content was refused "because" — with no reason at all.
	 */
	@ParameterizedTest
	@EnumSource(ModerationCategory.class)
	void refusalTemplatesActuallyInterpolateTheReason(ModerationCategory category) {
		for (Locale locale : SUPPORTED) {
			ResourceBundle bundle = bundle(locale);
			String reason = bundle.getString(category.reasonKey());

			String rendered = new MessageFormat(
					bundle.getString("error.moderation.blockedReason"), locale)
					.format(new Object[] { reason });

			assertThat(rendered).contains(reason).doesNotContain("{0}");
		}
	}

	@Test
	void fileRefusalTemplateInterpolatesBothFileNameAndReason() {
		for (Locale locale : SUPPORTED) {
			ResourceBundle bundle = bundle(locale);
			String reason = bundle.getString(ModerationCategory.SEXUAL.reasonKey());

			String rendered = new MessageFormat(
					bundle.getString("error.moderation.blockedFile"), locale)
					.format(new Object[] { "holiday.png", reason });

			assertThat(rendered).contains("holiday.png").contains(reason)
					.doesNotContain("{0}").doesNotContain("{1}");
		}
	}

	/**
	 * The whole point of the message is that the person knows what happened and what
	 * to do next, so a refusal that does not mention the automated check or a route
	 * to a human is a regression in both product and compliance terms.
	 */
	@Test
	void refusalMessagesMentionAutomationAndARouteToAHuman() {
		ResourceBundle english = bundle(Locale.ENGLISH);

		assertThat(english.getString("error.moderation.blockedReason"))
				.containsIgnoringCase("automated")
				.containsIgnoringCase("administrator");
		assertThat(english.getString("error.moderation.blockedFile"))
				.containsIgnoringCase("automated")
				.containsIgnoringCase("administrator");
	}
}
