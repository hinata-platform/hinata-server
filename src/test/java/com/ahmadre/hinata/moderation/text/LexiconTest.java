package com.ahmadre.hinata.moderation.text;

import com.ahmadre.hinata.moderation.ModerationCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lexicon's whole job is to be precise rather than eager. Most of these tests
 * therefore assert that something does <em>not</em> match: a filter that refuses
 * "Analyse" or "assessment" is worse than no filter, because it gets switched off
 * and then nothing is checked at all.
 */
class LexiconTest {

	private static final Lexicon BUNDLED = Lexicon.bundled();

	// --- the German compounding problem -----------------------------------

	/**
	 * Every one of these contains a listed term as a substring and is an ordinary
	 * German word. A {@code contains()} filter refuses all of them.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"Analyse", "analysieren", "Analytik", "Kanal", "Kanalisation",
			"Klassenzimmer", "Zimmermann", "Sexualität", "Sexualkunde",
			"Homosexualität", "Dickicht", "Dickmilch", "Schwanzmeise", "Fickmühle",
	})
	void doesNotFireOnGermanCompounds(String word) {
		assertThat(BUNDLED.scan("Bitte " + word + " prüfen")).isEmpty();
	}

	/** The English half of the same problem — the Scunthorpe class. */
	@ParameterizedTest
	@ValueSource(strings = {
			"assessment", "assassin", "assignment", "classic", "password", "compass",
			"Scunthorpe", "Essex", "Middlesex", "cocktail", "peacock", "dictionary",
			"analysis", "therapist", "sixteen",
	})
	void doesNotFireOnInnocentEnglishWords(String word) {
		assertThat(BUNDLED.scan("The " + word + " is fine")).isEmpty();
	}

	/** Engineering vocabulary must survive untouched — the whole product depends on it. */
	@ParameterizedTest
	@ValueSource(strings = {
			"kill the process", "abort the transaction", "master slave replication",
			"attack vector", "penetration test", "exploit chain", "the daemon died",
			"zombie process", "kill -9", "nuke the cache", "blacklist the host",
			"Angriffsvektor prüfen", "Prozess töten", "Schwachstelle ausnutzen",
	})
	void doesNotFireOnEngineeringLanguage(String phrase) {
		assertThat(BUNDLED.scan(phrase)).isEmpty();
	}

	// --- what must match ---------------------------------------------------

	@Test
	void matchesAnUnambiguousSlur() {
		List<Lexicon.Hit> hits = BUNDLED.scan("you absolute faggot");

		assertThat(hits).extracting(Lexicon.Hit::category).contains(ModerationCategory.HATE);
	}

	@Test
	void matchesAGermanThreatPhrase() {
		List<Lexicon.Hit> hits = BUNDLED.scan("ich bringe dich um du Idiot");

		assertThat(hits).extracting(Lexicon.Hit::category)
				.contains(ModerationCategory.VIOLENT_THREAT);
	}

	@Test
	void matchesAGermanCompoundInsultAsAWholeToken() {
		assertThat(BUNDLED.scan("du hurensohn")).extracting(Lexicon.Hit::category)
				.contains(ModerationCategory.HARASSMENT);
	}

	@Test
	void scoresChildSexualContentHighest() {
		List<Lexicon.Hit> hits = BUNDLED.scan("selling child porn here");

		assertThat(hits).anySatisfy(hit -> {
			assertThat(hit.category()).isEqualTo(ModerationCategory.SEXUAL_MINORS);
			assertThat(hit.weight()).isGreaterThanOrEqualTo(90);
		});
	}

	// --- allowlist precedence ----------------------------------------------

	/**
	 * The ordering rule that makes the German half work at all: a token on the
	 * allowlist is skipped before the denylist is ever consulted.
	 */
	@Test
	void allowlistWinsOverAnIdenticalDenylistEntry() {
		Lexicon lexicon = Lexicon.of(
				List.of(new Lexicon.Rule("analyse", ModerationCategory.SEXUAL, 90)),
				Set.of("analyse"));

		assertThat(lexicon.scan("bitte analyse durchführen")).isEmpty();
	}

	/** An allowlisted token cannot be used as part of a multi-word rule either. */
	@Test
	void allowlistedTokenCannotFormAPhrase() {
		Lexicon lexicon = Lexicon.of(
				List.of(new Lexicon.Rule("kill you", ModerationCategory.VIOLENT_THREAT, 90)),
				Set.of("kill"));

		assertThat(lexicon.scan("kill you")).isEmpty();
	}

	// --- matching semantics -------------------------------------------------

	@Test
	void matchesWholeTokensOnlyNeverSubstrings() {
		Lexicon lexicon = Lexicon.of(
				List.of(new Lexicon.Rule("ass", ModerationCategory.HARASSMENT, 80)), Set.of());

		assertThat(lexicon.scan("assessment classes passed")).isEmpty();
		assertThat(lexicon.scan("what an ass")).hasSize(1);
	}

	/**
	 * Regression: the scan window is derived from the loaded rules, not fixed. A
	 * four-token rule with a three-token window matches nothing and reports no
	 * error — and the most serious entries in both denylists are exactly four
	 * tokens ("ich bringe dich um", "i will kill you").
	 */
	@Test
	void matchesPhrasesLongerThanThreeTokens() {
		Lexicon lexicon = Lexicon.of(
				List.of(new Lexicon.Rule("a b c d e", ModerationCategory.HATE, 90)), Set.of());

		assertThat(lexicon.scan("x a b c d e y")).hasSize(1);
	}

	@Test
	void everyBundledPhraseIsReachable() {
		assertThat(BUNDLED.scan("i will kill you")).isNotEmpty();
		assertThat(BUNDLED.scan("shut the fuck up")).isNotEmpty();
		assertThat(BUNDLED.scan("ich weiss wo du wohnst")).isNotEmpty();
	}

	@Test
	void prefersTheLongestPhrase() {
		Lexicon lexicon = Lexicon.of(List.of(
				new Lexicon.Rule("kill", ModerationCategory.VIOLENCE, 50),
				new Lexicon.Rule("kill you", ModerationCategory.VIOLENT_THREAT, 90)), Set.of());

		assertThat(lexicon.scan("i will kill you"))
				.singleElement()
				.satisfies(hit -> assertThat(hit.category()).isEqualTo(ModerationCategory.VIOLENT_THREAT));
	}

	// --- normalisation ------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "FAGGOT", "Faggot", "f@ggot", "f4ggot", "faaaaggot" })
	void normalisesCaseElongationAndLeet(String variant) {
		assertThat(BUNDLED.scan("you " + variant)).isNotEmpty();
	}

	@Test
	void stripsZeroWidthCharactersUsedToBreakUpAWord() {
		assertThat(BUNDLED.scan("you fag​got")).isNotEmpty();
	}

	/**
	 * Leet folding must not rewrite a purely numeric token into a word — a version
	 * string or an error code is not a slur.
	 */
	@Test
	void doesNotFoldPurelyNumericTokens() {
		assertThat(Lexicon.normalize("4051")).isEqualTo("4051");
		assertThat(Lexicon.normalize("f4g")).isEqualTo("fag");
	}

	// --- robustness ---------------------------------------------------------

	@Test
	void handlesNullBlankAndPunctuationOnlyInput() {
		assertThat(BUNDLED.scan(null)).isEmpty();
		assertThat(BUNDLED.scan("   ")).isEmpty();
		assertThat(BUNDLED.scan("!!! ... ???")).isEmpty();
	}

	@Test
	void bundledListsActuallyLoaded() {
		assertThat(BUNDLED.ruleCount()).isGreaterThan(100);
		assertThat(BUNDLED.allowedCount()).isGreaterThan(200);
	}
}
