package com.ahmadre.hinata.moderation.filter;

import com.ahmadre.hinata.moderation.ContentReportService;
import com.ahmadre.hinata.moderation.ModerationStats;
import com.ahmadre.hinata.richtext.TextField;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The text check runs on every save of every comment (HIN-176), so it has to stay out of the way:
 * 10 000 characters of prose, code and log in under 2 ms, the median of 50 runs after warming up.
 */
class TextCheckSpeedTest {

	private static final int RUNS = 50;

	@Test
	void tenThousandCharactersInUnderTwoMilliseconds() {
		SettingsService settings = mock(SettingsService.class);
		when(settings.get()).thenReturn(new ServerSettings());
		LexiconTextGate gate = new LexiconTextGate(settings, mock(ContentReportService.class),
				mock(ModerationStats.class), Clock.systemUTC());
		String text = text(10_000);

		for (int i = 0; i < 200; i++) {
			gate.check(text, TextField.COMMENT, null, null);
		}
		long[] nanos = new long[RUNS];
		for (int i = 0; i < RUNS; i++) {
			long start = System.nanoTime();
			gate.check(text, TextField.COMMENT, null, null);
			nanos[i] = System.nanoTime() - start;
		}
		Arrays.sort(nanos);
		double medianMillis = nanos[RUNS / 2] / 1_000_000.0;
		assertThat(medianMillis).as("median of %d runs, ms", RUNS).isLessThan(2.0);
	}

	/** Prose, a stack trace, a log and a code block, repeated to [length]. */
	private static String text(int length) {
		String chunk = """
				Der Export bricht beim zweiten Durchlauf ab, sobald mehr als 500 Zeilen drin sind. Ich habe es mit
				kill -9 auf dem Worker versucht, danach hängt die Queue. Bitte schaut euch den Log an:
				2026-10-24 09:12:01 ERROR [export] java.lang.IllegalStateException: fatal at com.acme.Exporter.run(Exporter.java:31)
				    at com.acme.Queue.drain(Queue.java:88)
				```java
				if (masterNode.isDown()) { slaveNode.promote(); }
				```
				Siehe auch https://example.org/issues/ACME-42 und /knowledge/abc. Danke euch!
				""";
		StringBuilder out = new StringBuilder(length + chunk.length());
		while (out.length() < length) {
			out.append(chunk);
		}
		return out.substring(0, length);
	}
}
