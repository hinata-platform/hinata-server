package com.ahmadre.hinata.moderation.filter;

import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.moderation.ContentReportService;
import com.ahmadre.hinata.moderation.ModerationStats;
import com.ahmadre.hinata.moderation.StatMetric;
import com.ahmadre.hinata.richtext.TextField;
import com.ahmadre.hinata.richtext.TextVerdict;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One field, one pass, one verdict (HIN-176): what refuses, what may go before moderation, and
 * what code, logs and own terms change about it.
 */
class LexiconTextGateTest {

	private final SettingsService settings = mock(SettingsService.class);
	private final ModerationStats stats = mock(ModerationStats.class);
	private LexiconTextGate gate;
	private ServerSettings.Filter filter;

	@BeforeEach
	void setUp() {
		ServerSettings stored = new ServerSettings();
		ServerSettings.Moderation moderation = new ServerSettings.Moderation();
		filter = new ServerSettings.Filter();
		moderation.setFilter(filter);
		stored.setModeration(moderation);
		when(settings.get()).thenReturn(stored);
		gate = new LexiconTextGate(settings, mock(ContentReportService.class), stats, Clock.systemUTC());
	}

	/** The gate reads its settings once and again after a change; a test changes them in between. */
	private void reload() {
		gate.onSettingsChanged(new SettingsService.SettingsChangedEvent(settings.get()));
	}

	@Test
	void anUnambiguousTermIsRefusedAndCountedOnceTheWriteIsRefused() {
		TextVerdict verdict = gate.check("ich bringe dich um", TextField.COMMENT, null);

		assertThat(verdict.verdict()).isEqualTo(TextVerdict.Verdict.BLOCK);
		assertThat(verdict.category()).isEqualTo(ReportCategory.THREAT);
		assertThat(verdict.terms()).containsExactly("ich bringe dich um");
		// A look counts nothing: a name from a provider is looked at and replaced, not refused.
		verify(stats, never()).count(eq(StatMetric.TEXT_REFUSED), anyString());

		gate.refused(verdict);
		// Counted by its category, the way the yearly figures report it.
		verify(stats).count(StatMetric.TEXT_REFUSED, "THREAT");
	}

	@Test
	void anUnclearTermPassesWhileFlaggingIsOffAsIsTheDefault() {
		assertThat(gate.check("du arschloch", TextField.COMMENT, null)).isEqualTo(TextVerdict.ALLOW);
	}

	@Test
	void withFlaggingOnOnlyWhatIsNewOpensACase() {
		filter.setFlagEnabled(true);
		reload();

		TextVerdict first = gate.check("du arschloch", TextField.COMMENT, null);
		assertThat(first.verdict()).isEqualTo(TextVerdict.Verdict.FLAG);
		assertThat(first.newFlagTerms()).containsExactly("arschloch");
		// A typo fixed in a text already flagged: the term was there before, nothing new.
		TextVerdict typo = gate.check("du arschloch!", TextField.COMMENT, "du arschloch");
		assertThat(typo.opensCase()).isFalse();
		// A field with nothing to look at afterwards is never flagged.
		assertThat(gate.check("du arschloch", TextField.OAUTH_CLIENT_NAME, null)).isEqualTo(TextVerdict.ALLOW);
	}

	@Test
	void refusingOffStillFlagsWhatWouldHaveBeenRefused() {
		filter.setBlockEnabled(false);
		filter.setFlagEnabled(true);
		reload();

		TextVerdict verdict = gate.check("ich bringe dich um", TextField.COMMENT, null);
		assertThat(verdict.verdict()).isEqualTo(TextVerdict.Verdict.FLAG);
		filter.setFlagEnabled(false);
		reload();
		assertThat(gate.check("ich bringe dich um", TextField.COMMENT, null)).isEqualTo(TextVerdict.ALLOW);
	}

	@Test
	void ownTermsRefuseWhateverTheSwitchesSayAndNeverFlag() {
		filter.setBlockEnabled(false);
		filter.setFlagEnabled(true);
		filter.setBlockTerms(List.of("Spast"));
		reload();

		TextVerdict verdict = gate.check("so ein Spast", TextField.COMMENT, null);
		assertThat(verdict.verdict()).isEqualTo(TextVerdict.Verdict.BLOCK);
		assertThat(verdict.category()).isEqualTo(ReportCategory.OTHER);
		assertThat(verdict.newFlagTerms()).isEmpty();
	}

	@Test
	void aSlurInCodeReachesModerationButAPastedLogIsNeverRefused() {
		filter.setFlagEnabled(true);
		reload();
		String comment = "The build log:\n```\nERROR faggot detected in TestUser.java\n```\nany idea?";

		TextVerdict verdict = gate.check(comment, TextField.COMMENT, null);
		assertThat(verdict.verdict()).isEqualTo(TextVerdict.Verdict.FLAG);
		// The same word in a name is judged whole: a name is no place to hide.
		assertThat(gate.check("faggot", TextField.DISPLAY_NAME, null).blocked()).isTrue();
	}

	@Test
	void threatsAndTheSafetyOfChildrenAreNotSoftenedByCode() {
		String inCode = "```\nchild porn\n```";
		assertThat(gate.check(inCode, TextField.COMMENT, null).blocked()).isTrue();
		assertThat(gate.check("`ich bringe dich um`", TextField.COMMENT, null).blocked()).isTrue();
	}

	/** Code may excuse a slur in a log, never hide a threat: one word in backticks changes nothing. */
	@Test
	void aThreatSplitByBackticksIsStillAThreat() {
		assertThat(gate.check("i will kill `you`", TextField.COMMENT, null).blocked()).isTrue();
		assertThat(gate.check("selling child `porn`", TextField.COMMENT, null).blocked()).isTrue();
	}

	@Test
	void technicalWordsAreTechnical() {
		for (String text : List.of("kill -9 1234", "Der Server kill -9 den Prozess", "master/slave replication",
				"java.lang.IllegalStateException: fatal at com.acme.Killer.run(Killer.java:31)",
				"POST /api/v1/execute returns 500")) {
			assertThat(gate.check(text, TextField.COMMENT, null)).as(text).isEqualTo(TextVerdict.ALLOW);
		}
		verify(stats, never()).count(eq(StatMetric.TEXT_REFUSED), anyString());
	}

	@Test
	void theHeavierCategoryLeads() {
		TextVerdict verdict = gate.check("child porn, and kys", TextField.COMMENT, null);
		assertThat(verdict.category()).isEqualTo(ReportCategory.CHILD_SAFETY);
		assertThat(verdict.terms()).containsExactly("child porn", "kys");
	}
}
