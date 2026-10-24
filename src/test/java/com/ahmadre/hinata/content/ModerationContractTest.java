package com.ahmadre.hinata.content;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The names the app sends and reads for moderation (HIN-173). The app's
 * {@code moderation_contract_test.dart} holds the very same lists, so a value added on one side
 * fails the other until both agree.
 */
class ModerationContractTest {

	@Test
	void theReasonsAreTheOnesTheAppKnowsInItsOrder() {
		assertThat(Arrays.stream(ReportCategory.values()).map(Enum::name).toList()).isEqualTo(List.of(
				"HARASSMENT", "THREAT", "SEXUAL", "CHILD_SAFETY", "PRIVACY", "SPAM", "ILLEGAL", "OTHER"));
	}

	@Test
	void theKindsOfContentAreTheOnesTheAppKnows() {
		assertThat(Arrays.stream(ContentKind.values()).map(Enum::name).toList())
				.isEqualTo(List.of("ISSUE", "COMMENT", "ATTACHMENT", "ARTICLE", "PERSON", "OTHER"));
	}

	@Test
	void aMarkIsRemovedOrRewritten() {
		assertThat(Arrays.stream(MarkState.values()).map(Enum::name).toList())
				.isEqualTo(List.of("REMOVED", "REWRITTEN"));
	}
}
