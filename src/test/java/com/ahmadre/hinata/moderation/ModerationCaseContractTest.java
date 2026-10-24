package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ContentOrigin;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.RestoreOutcome;
import com.ahmadre.hinata.richtext.TextCheck;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The names the moderation page sends and reads (HIN-174). The app's
 * {@code moderation_case_contract_test.dart} holds the very same lists, so a value added on one side
 * fails the other until both agree.
 */
class ModerationCaseContractTest {

	private static List<String> names(Enum<?>[] values) {
		return Arrays.stream(values).map(Enum::name).toList();
	}

	@Test
	void aCaseShowsOneOfTheStatesTheAppWords() {
		assertThat(names(CaseViews.CaseState.values())).isEqualTo(List.of(
				"OPEN", "URGENT", "IN_REVIEW", "PENDING_CONFIRMATION", "APPEAL", "ACTED", "NO_ACTION"));
		assertThat(names(CaseViews.View.values())).isEqualTo(List.of("OPEN", "APPEAL", "DONE"));
		assertThat(names(ReportSource.values())).isEqualTo(List.of("MEMBER", "PUBLIC", "AUTOMATED", "OWN_INITIATIVE"));
		assertThat(names(ContentOrigin.values())).isEqualTo(List.of("MEMBER", "EMAIL", "GIT", "MCP"));
	}

	@Test
	void aDecisionIsMadeOfTheChoicesTheAppOffers() {
		assertThat(names(Decision.Outcome.values())).isEqualTo(List.of("NO_VIOLATION", "UNFOUNDED", "ACTION", "HANDLED"));
		assertThat(names(Decision.Ground.values())).isEqualTo(List.of("RULES", "LAW"));
		assertThat(names(Decision.SuspensionLength.values())).isEqualTo(List.of("DAYS_7", "DAYS_30", "INDEFINITE"));
		assertThat(names(RemovalScope.values())).isEqualTo(List.of(
				"WHOLE", "TITLE", "DESCRIPTION", "REVERT", "AVATAR", "PROFILE_TEXT"));
		assertThat(names(AppealService.AppealDecisionRequest.Result.values())).isEqualTo(List.of("UPHOLD", "REVERSE"));
	}

	/** What the app acts on in a refusal is its message key, sent as {@code details.code}. */
	@Test
	void theRefusalsTheAppActsOnAreNamedByTheirKeys() throws IOException {
		List<String> codes = List.of("error.moderation.refused", "error.moderation.tooManyReports",
				"error.moderation.tooManyReveals", "error.moderation.openAccessUnconfirmed",
				"error.moderation.flagUnconfirmed", "error.moderation.caseDecided", "error.moderation.caseChanged");
		Properties messages = new Properties();
		try (InputStream in = getClass().getResourceAsStream("/messages.properties")) {
			messages.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		assertThat(codes).allSatisfy(code -> assertThat(messages).containsKey(code));
		assertThat(TextCheck.REFUSED).isIn(codes);
	}

	@Test
	void whatHappenedToACaseIsWordedFromTheseNames() {
		assertThat(names(CaseEvent.Kind.values())).isEqualTo(List.of("REPORTED", "CLAIMED", "RELEASED",
				"REPORTER_REVEALED", "EVIDENCE_REQUESTED", "EVIDENCE_REVEALED", "DECIDED", "APPEALED", "APPEAL_DECIDED",
				"RESTORED", "HELD", "CONFIRMATION_REQUESTED", "CONFIRMATION_REJECTED", "SUSPENDED", "SUSPENSION_LIFTED"));
		assertThat(names(Appeal.Via.values())).isEqualTo(List.of("APP", "REPLY"));
		assertThat(names(RestoreOutcome.values())).isEqualTo(List.of("RESTORED", "REWRITTEN_SINCE", "GONE"));
		assertThat(names(RemovalState.values())).isEqualTo(List.of("INCOMPLETE", "DONE"));
	}
}
