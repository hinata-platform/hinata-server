package com.ahmadre.hinata.moderation.rules;

import com.ahmadre.hinata.common.Languages;
import com.ahmadre.hinata.setup.ServerSettings.RulesMode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the house rules out in every language (HIN-175): the instance's rules, built in or its
 * own, followed by the section on how moderation works, which the server always writes itself
 * from the settings as they are, so that it can neither go missing nor say something that is no
 * longer true (DSA Art. 14).
 */
@Component
@RequiredArgsConstructor
class RulesRenderer {

	private static final String TEMPLATE_DIR = "moderation-rules/";

	/** A numbered rule's heading in the built-in rules: {@code ## 2. Don't put anyone down}. */
	private static final Pattern NUMBERED = Pattern.compile("(?m)^## (\\d+)\\. (.+)$");

	private final MessageSource messages;
	private final Map<String, String> templates = new ConcurrentHashMap<>();

	/**
	 * Everything the rules depend on. [operator] is never null: without a name of its own the
	 * instance is called by its organisation's.
	 */
	record Inputs(RulesMode mode, String text, String url, String operator, String contactEmail,
			String imprintUrl, int retentionMonths, String reportUrl, TextCheck textCheck) {
	}

	/**
	 * What the text check does (HIN-176), as "How we moderate" has to say: whether the shipped
	 * lists refuse, whether unclear finds go before moderation, and the own terms that are refused.
	 */
	record TextCheck(boolean refuses, boolean flags, List<String> ownTerms) {

		static final TextCheck DEFAULT = new TextCheck(true, false, List.of());

		TextCheck {
			ownTerms = ownTerms == null ? List.of() : List.copyOf(ownTerms);
		}
	}

	/** The rules in each language, by language tag, in {@link Languages#SUPPORTED} order. */
	Map<String, String> render(Inputs inputs) {
		Map<String, String> rendered = new LinkedHashMap<>();
		for (String language : Languages.SUPPORTED) {
			rendered.put(language, render(inputs, Locale.forLanguageTag(language)));
		}
		return rendered;
	}

	private String render(Inputs in, Locale locale) {
		String rules = switch (in.mode()) {
			case BUILT_IN -> template(locale.getLanguage()).strip()
					.replace("{operator}", in.operator())
					.replace("{contact}", in.contactEmail() != null ? mailto(in.contactEmail()) : link(in.reportUrl()));
			case TEXT -> in.text().strip();
			case URL -> t("moderation.rules.atUrl", locale, in.operator(), link(in.url()));
		};
		return rules + "\n\n" + howWeModerate(in, locale) + "\n";
	}

	private String howWeModerate(Inputs in, Locale locale) {
		List<String> parts = new ArrayList<>();
		parts.add("## " + t("moderation.how.title", locale));
		parts.add(t("moderation.how.report", locale, link(in.reportUrl())));
		parts.add(t("moderation.how.review", locale, in.operator()));
		parts.add(textCheck(in.textCheck() == null ? TextCheck.DEFAULT : in.textCheck(), locale));
		parts.add(t("moderation.how.images", locale));
		parts.add(t("moderation.how.consequences", locale));
		parts.add(t("moderation.how.retention", locale, in.retentionMonths()));
		if (in.contactEmail() != null) {
			parts.add(t("moderation.how.contact", locale, mailto(in.contactEmail())));
		}
		if (in.imprintUrl() != null) {
			parts.add(t("moderation.how.imprint", locale, link(in.imprintUrl())));
		}
		return String.join("\n\n", parts);
	}

	/** The line on the text check, as the settings stand: one sentence per thing it does. */
	private String textCheck(TextCheck check, Locale locale) {
		String own = check.ownTerms().isEmpty() ? null
				: String.join(", ", check.ownTerms().stream().map(RulesRenderer::literal).toList());
		if (!check.refuses() && !check.flags()) {
			return own == null ? t("moderation.how.text", locale) : t("moderation.how.textOwnOnly", locale, own);
		}
		List<String> sentences = new ArrayList<>();
		sentences.add(t(check.refuses() ? "moderation.how.textRefuses" : "moderation.how.textFlagsOnly", locale));
		if (check.refuses() && check.flags()) {
			sentences.add(t("moderation.how.textFlags", locale));
		}
		if (own != null) {
			sentences.add(t("moderation.how.textOwn", locale, own));
		}
		return String.join(" ", sentences);
	}

	/** [text] as plain words in Markdown: nothing in it becomes a link, a heading or emphasis. */
	static String literal(String text) {
		StringBuilder out = new StringBuilder(text.length() + 8);
		for (char c : text.toCharArray()) {
			if ("\\`*_{}[]()<>#+-.!|~".indexOf(c) >= 0) {
				out.append('\\');
			}
			out.append(c == '\n' || c == '\r' ? ' ' : c);
		}
		return out.toString();
	}

	private String t(String key, Locale locale, Object... args) {
		return messages.getMessage(key, args.length == 0 ? null : args, locale);
	}

	/** [url] as a Markdown link to itself; brackets and spaces would end it early. */
	static String link(String url) {
		String safe = url.replace(" ", "%20").replace("(", "%28").replace(")", "%29");
		return "[" + safe.replace("[", "%5B").replace("]", "%5D") + "](" + safe + ")";
	}

	private static String mailto(String email) {
		return "[" + email + "](mailto:" + email.replace(" ", "%20").replace(")", "%29") + ")";
	}

	/**
	 * The titles of the built-in rules in [language], by number, as their headings name them: the
	 * one source for the published rules and for the rule a decision cites (HIN-171).
	 */
	Map<Integer, String> numberedRules(String language) {
		Map<Integer, String> rules = new LinkedHashMap<>();
		Matcher heading = NUMBERED.matcher(template(language));
		while (heading.find()) {
			rules.put(Integer.parseInt(heading.group(1)), heading.group(2).strip());
		}
		return rules;
	}

	/** The built-in rules in [language], or the English ones for a language without them. */
	private String template(String language) {
		return templates.computeIfAbsent(language, lang -> {
			String text = read(TEMPLATE_DIR + "rules_" + lang + ".md");
			return text != null ? text : read(TEMPLATE_DIR + "rules_en.md");
		});
	}

	private static String read(String path) {
		ClassPathResource resource = new ClassPathResource(path);
		if (!resource.exists()) {
			return null;
		}
		try (InputStream in = resource.getInputStream()) {
			return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
