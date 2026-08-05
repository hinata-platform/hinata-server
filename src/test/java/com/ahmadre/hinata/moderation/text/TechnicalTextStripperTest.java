package com.ahmadre.hinata.moderation.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The stripper is what makes moderating a bug tracker possible at all, so these
 * tests are mostly about what must NOT survive into the prose half: if a shell
 * command, a stack frame or an identifier reaches the classifier, the filter
 * starts refusing ordinary defect reports and the team switches it off.
 */
class TechnicalTextStripperTest {

	@Test
	void keepsOrdinaryProseIntact() {
		String prose = "The login button does not respond on the second attempt.";
		assertThat(TechnicalTextStripper.strip(prose).prose()).isEqualTo(prose);
	}

	@Test
	void movesFencedCodeOutOfTheProse() {
		String input = """
				This reproduces every time:

				```bash
				kill -9 $(pgrep -f hinata)
				```

				after the second restart.""";
		TechnicalTextStripper.Result result = TechnicalTextStripper.strip(input);

		assertThat(result.prose()).doesNotContain("kill").doesNotContain("pgrep");
		assertThat(result.prose()).contains("This reproduces every time");
		assertThat(result.prose()).contains("after the second restart");
		assertThat(result.technical()).contains("kill -9");
	}

	@Test
	void movesStackFramesOutOfTheProse() {
		String input = """
				Crashes on save:
				java.lang.IllegalStateException: killer session already closed
					at com.ahmadre.hinata.issue.IssueService.abort(IssueService.java:441)
					at com.ahmadre.hinata.issue.Killer.execute(Killer.java:12)
				Caused by: java.io.IOException: broken pipe
				Please advise.""";
		TechnicalTextStripper.Result result = TechnicalTextStripper.strip(input);

		assertThat(result.prose()).contains("Crashes on save").contains("Please advise");
		assertThat(result.prose()).doesNotContain("Killer").doesNotContain("IllegalStateException");
	}

	@Test
	void movesLogLinesOutOfTheProse() {
		String input = """
				Log excerpt:
				2026-08-05 11:02:31 ERROR Killed worker 7 after fatal abort
				[WARN] slave replica died during failover
				Any idea?""";
		TechnicalTextStripper.Result result = TechnicalTextStripper.strip(input);

		assertThat(result.prose()).contains("Log excerpt").contains("Any idea");
		assertThat(result.prose()).doesNotContain("Killed").doesNotContain("slave");
	}

	/**
	 * The single largest class of false positive after code fences: a field or class
	 * name that reads as a slur or a sexual term when the surrounding case
	 * information is thrown away.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"sexPreference", "userSexField", "KillProcessHandler", "master_slave_config",
			"SLAVE_NODE_COUNT", "com.example.killer.Service", "is-dead-letter-queue",
	})
	void movesIdentifiersOutOfTheProse(String identifier) {
		TechnicalTextStripper.Result result =
				TechnicalTextStripper.strip("Please rename " + identifier + " before the release.");

		assertThat(result.prose()).doesNotContain(identifier);
		assertThat(result.prose()).contains("Please rename").contains("before the release");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"https://example.com/anal-report.pdf",
			"/usr/local/bin/killall",
			"C:\\Users\\dev\\slave\\config.yml",
			"./src/main/java/Killer.java",
			"CVE-2021-44228",
			"HIN-26",
			"--force-kill",
			"3f8a9c2e1b4d5f6a7b8c9d0e1f2a3b4c5d6e7f80",
			"550e8400-e29b-41d4-a716-446655440000",
	})
	void movesMachineTokensOutOfTheProse(String token) {
		TechnicalTextStripper.Result result =
				TechnicalTextStripper.strip("See " + token + " for details.");

		assertThat(result.prose()).doesNotContain(token);
		assertThat(result.prose()).contains("See").contains("for details");
	}

	@Test
	void inlineCodeDoesNotSurviveAsProse() {
		TechnicalTextStripper.Result result =
				TechnicalTextStripper.strip("Run `kill -9 1234` and report back.");

		assertThat(result.prose()).doesNotContain("kill");
		assertThat(result.technical()).contains("kill -9 1234");
	}

	/**
	 * Nothing is discarded — the removed spans are scored separately at a ceiling
	 * below any block threshold, so hiding abuse in a code fence gets a human's
	 * attention instead of a free pass.
	 */
	@Test
	void keepsRemovedSpansForSeparateScoring() {
		TechnicalTextStripper.Result result = TechnicalTextStripper.strip("""
				Nothing to see:
				```
				you are a worthless idiot
				```
				""");

		assertThat(result.prose()).doesNotContain("worthless");
		assertThat(result.technical()).contains("worthless idiot");
	}

	/**
	 * Removing a span must not fuse its neighbours into a word nobody typed — that
	 * is how a stripper invents a slur out of two innocent fragments.
	 */
	@Test
	void doesNotFuseNeighbouringWords() {
		TechnicalTextStripper.Result result =
				TechnicalTextStripper.strip("cl`x`ass and ni`y`gger");

		assertThat(result.prose()).doesNotContain("nigger");
		assertThat(result.prose()).doesNotContain("class");
	}

	@Test
	void handlesAnAllCodeDocument() {
		TechnicalTextStripper.Result result = TechnicalTextStripper.strip("""
				```
				const a = 1;
				```
				""");

		assertThat(result.hasProse()).isFalse();
		assertThat(result.technical()).contains("const");
	}

	@Test
	void handlesNullAndBlank() {
		assertThat(TechnicalTextStripper.strip(null).prose()).isEmpty();
		assertThat(TechnicalTextStripper.strip("   ").prose()).isEmpty();
		assertThat(TechnicalTextStripper.strip(null).hasProse()).isFalse();
	}

	/**
	 * Every pattern runs against strings an attacker chooses, on the write path of
	 * every comment. A catastrophically backtracking pattern here would be a
	 * one-request denial of service, so the shapes most likely to trigger one are
	 * asserted to finish promptly rather than merely to finish.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "`", "```", "-", "/", "_", "a_", "0x", "@@", "$ " })
	void isLinearOnHostileRepetition(String unit) {
		String hostile = unit.repeat(20_000);

		assertThatCode(() -> TechnicalTextStripper.strip(hostile))
				.doesNotThrowAnyException();
		assertThat(Duration.ofNanos(timeOf(hostile))).isLessThan(Duration.ofSeconds(5));
	}

	private static long timeOf(String input) {
		long start = System.nanoTime();
		TechnicalTextStripper.strip(input);
		return System.nanoTime() - start;
	}
}
