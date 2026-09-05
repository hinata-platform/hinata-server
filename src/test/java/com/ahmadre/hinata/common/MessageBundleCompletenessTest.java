package com.ahmadre.hinata.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Guards against returning a raw {@code error.*} key to an API client. */
class MessageBundleCompletenessTest {

	private static final Pattern ERROR_KEY = Pattern.compile("\\\"(error\\.[A-Za-z0-9.]+)\\\"");

	/**
	 * Every language {@code LocaleConfig} advertises. Kept here rather than read
	 * from it so that adding a locale to the resolver without adding its bundle
	 * fails as a missing translation, which is what it is.
	 */
	private static final Locale[] SUPPORTED = {Locale.ENGLISH, Locale.GERMAN, Locale.CHINESE,
			Locale.forLanguageTag("hi"), Locale.forLanguageTag("es"), Locale.JAPANESE,
			Locale.FRENCH, Locale.forLanguageTag("ru"), Locale.forLanguageTag("ar")};

	@Test
	void everyLiteralServerErrorKeyExistsInEverySupportedBundle() throws IOException {
		Set<String> keys = new TreeSet<>();
		try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
			files.filter(path -> path.toString().endsWith(".java"))
					.map(this::read)
					.forEach(source -> addErrorKeys(source, keys));
		}

		for (Locale locale : SUPPORTED) {
			ResourceBundle bundle = ResourceBundle.getBundle("messages", locale);
			for (String key : keys) {
				assertThat(bundle.containsKey(key))
						.as("%s must contain %s", locale, key)
						.isTrue();
			}
		}
	}

	private String read(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not read " + path, ex);
		}
	}

	private static void addErrorKeys(String source, Set<String> keys) {
		Matcher matcher = ERROR_KEY.matcher(source);
		while (matcher.find()) {
			keys.add(matcher.group(1));
		}
	}
}
