package com.ahmadre.hinata.moderation.filter;

import com.ahmadre.hinata.moderation.ContentReportService;
import com.ahmadre.hinata.moderation.ModerationStats;
import com.ahmadre.hinata.richtext.TextVerdict;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The admin's test says what would happen and why, and counts nothing (HIN-176). */
class AdminFilterControllerTest {

	private final ModerationStats stats = mock(ModerationStats.class);
	private final AdminFilterController controller;

	AdminFilterControllerTest() {
		SettingsService settings = mock(SettingsService.class);
		when(settings.get()).thenReturn(new ServerSettings());
		controller = new AdminFilterController(new LexiconTextGate(settings, mock(ContentReportService.class), stats,
				Clock.systemUTC()));
	}

	@Test
	void aTechnicalWordIsNamedAsTheExceptionThatApplied() {
		AdminFilterController.TestResult result = controller.test(
				new AdminFilterController.TestRequest("Der Server kill -9 den Prozess", null));

		assertThat(result.verdict()).isEqualTo(TextVerdict.Verdict.ALLOW);
		assertThat(result.exceptions()).contains("kill");
	}

	@Test
	void whatIsFoundIsShownWhateverTheSwitchesSay() {
		AdminFilterController.TestResult unclear = controller.test(
				new AdminFilterController.TestRequest("du arschloch", null));
		AdminFilterController.TestResult threat = controller.test(
				new AdminFilterController.TestRequest("ich bringe dich um", null));

		assertThat(unclear.verdict()).as("flagging is off").isEqualTo(TextVerdict.Verdict.ALLOW);
		assertThat(unclear.found()).extracting(AdminFilterController.Found::term).containsExactly("arschloch");
		assertThat(threat.verdict()).isEqualTo(TextVerdict.Verdict.BLOCK);
		verifyNoInteractions(stats);
	}
}
