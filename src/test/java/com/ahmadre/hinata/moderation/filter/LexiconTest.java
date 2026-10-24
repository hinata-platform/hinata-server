package com.ahmadre.hinata.moderation.filter;

import com.ahmadre.hinata.content.ReportCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped lists (HIN-176): every rule fires where it stands in a sentence, ordinary words that
 * contain a listed one never do, and the two repairs against the August branch hold.
 */
class LexiconTest {

	private static final Lexicon BUNDLED = Lexicon.bundled();

	private static List<String> terms(String text) {
		return BUNDLED.scan(text).hits().stream().map(Lexicon.Hit::term).toList();
	}

	@Test
	void everyShippedRuleFiresInASentence() {
		assertThat(BUNDLED.ruleCount()).isGreaterThan(150);
		for (Lexicon.Rule rule : BUNDLED.rules()) {
			assertThat(terms("Heute: " + rule.term() + ", gestern nicht."))
					.as("the rule '%s' fires", rule.term()).contains(rule.term());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"Analyse", "Klassenzimmer", "Dickicht", "Kanalisation", "Schwanzmeise", "Sexualkunde",
			"assessment", "Scunthorpe", "cocktail", "passage"})
	void wordsThatContainAListedOneStayClean(String word) {
		assertThat(terms("Bitte " + word + " prüfen")).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"kill the process", "the master and slave replicas", "nuke the cache",
			"the exploit is patched", "abort the job", "fatal error in the parser", "mongo is down again"})
	void engineeringLanguageStaysClean(String text) {
		assertThat(terms(text)).isEmpty();
	}

	/** The repair: a phrase wins over the exception for its first word. */
	@Test
	void aPhraseFiresAlthoughItsFirstWordIsAnException() {
		assertThat(terms("just kill yourself")).containsExactly("kill yourself");
		assertThat(terms("i want to kill myself")).containsExactly("kill myself");
		// "kill" alone is a technical word, and the scan names it as the exception that applied.
		Lexicon.Scan scan = BUNDLED.scan("kill -9 the stuck worker");
		assertThat(scan.hits()).isEmpty();
		assertThat(scan.exempt()).contains("kill");
	}

	/** The other repair: four words and more, because the window comes from the rules. */
	@Test
	void phrasesOfFourWordsAndMoreFire() {
		assertThat(terms("Hör zu, ich bringe dich um.")).containsExactly("ich bringe dich um");
		assertThat(terms("I know where you live, Jonas")).containsExactly("i know where you live");
		Lexicon five = Lexicon.of(List.of(new Lexicon.Rule("a b c d e", ReportCategory.THREAT, 90, false)),
				Set.of());
		assertThat(five.scan("x a b c d e y").hits()).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"FAGGOT", "faaaaggot", "f4ggot", "f@ggot", "fag​got"})
	void spellingsMeantToSlipThroughAreCaught(String variant) {
		assertThat(terms("you " + variant)).containsExactly("faggot");
	}

	@Test
	void digitsAloneStayDigits() {
		assertThat(Lexicon.normalize("4051")).isEqualTo("4051");
		assertThat(Lexicon.normalize("k1ll")).isEqualTo("kill");
	}

	@Test
	void ownTermsRefuseAndOwnExceptionsNeverClearChildSafety() {
		Lexicon own = BUNDLED.with(List.of("Spast"), List.of("child porn", "arschloch"));
		assertThat(own.scan("so ein Spast").hits()).singleElement().satisfies(hit -> {
			assertThat(hit.own()).isTrue();
			assertThat(hit.category()).isEqualTo(ReportCategory.OTHER);
		});
		assertThat(own.scan("du arschloch").hits()).isEmpty();
		assertThat(own.scan("selling child porn").hits()).extracting(Lexicon.Hit::category)
				.containsExactly(ReportCategory.CHILD_SAFETY);
	}

	@Test
	void nothingIsNothing() {
		assertThat(BUNDLED.scan(null).hits()).isEmpty();
		assertThat(BUNDLED.scan("   ").hits()).isEmpty();
		assertThat(BUNDLED.scan("!!! ... ???").hits()).isEmpty();
	}
}
