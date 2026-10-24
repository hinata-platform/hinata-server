package com.ahmadre.hinata.issue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** A reaction is one emoji as the eye sees one (HIN-176). */
class ReactionEmojiTest {

	@ParameterizedTest
	@ValueSource(strings = {"👍", "👍🏽", "❤️", "🇩🇪", "👨‍👩‍👧", "1️⃣", "🎉"})
	void oneEmoji(String value) {
		assertThat(ReactionEmoji.isSingle(value)).as(value).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"ok", "1", "a", "👍👍", "👍 ok", "🇩", " ", ""})
	void anythingElse(String value) {
		assertThat(ReactionEmoji.isSingle(value)).as(value).isFalse();
	}
}
