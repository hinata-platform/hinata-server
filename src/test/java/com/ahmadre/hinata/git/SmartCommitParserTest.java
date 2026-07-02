package com.ahmadre.hinata.git;

import com.ahmadre.hinata.git.SmartCommitParser.Command;
import com.ahmadre.hinata.git.SmartCommitParser.Type;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SmartCommitParserTest {

	@Test
	void parsesAComment() {
		List<Command> commands = SmartCommitParser.parse("HIN-42 #comment ready for QA");
		assertThat(commands).containsExactly(new Command("HIN-42", Type.COMMENT, "ready for QA"));
	}

	@Test
	void parsesTimeAndTransition() {
		List<Command> commands = SmartCommitParser.parse("API-230 #time 2h 30m #done");
		assertThat(commands).containsExactly(
				new Command("API-230", Type.TIME, "2h 30m"),
				new Command("API-230", Type.TRANSITION, "done"));
	}

	@Test
	void bindsCommandsToTheMostRecentKey() {
		List<Command> commands = SmartCommitParser.parse(
				"HIN-1 #comment first MOB-2 #comment second");
		assertThat(commands).containsExactly(
				new Command("HIN-1", Type.COMMENT, "first"),
				new Command("MOB-2", Type.COMMENT, "second"));
	}

	@Test
	void ignoresCommandsWithoutAKeyAndPlainMessages() {
		assertThat(SmartCommitParser.parse("#done tidy up")).isEmpty();
		assertThat(SmartCommitParser.parse("HIN-7 Fix the drag jank on large sprints")).isEmpty();
		assertThat(SmartCommitParser.parse("")).isEmpty();
		assertThat(SmartCommitParser.parse(null)).isEmpty();
	}

	@Test
	void lowercasesTransitionWordButKeepsCommentTextVerbatim() {
		List<Command> commands = SmartCommitParser.parse("INF-9 #In-Progress HIN-3 #comment Keep The Case");
		assertThat(commands).containsExactly(
				new Command("INF-9", Type.TRANSITION, "in-progress"),
				new Command("HIN-3", Type.COMMENT, "Keep The Case"));
	}

	@Test
	void bindsKeysWrappedInPunctuation() {
		// Commit messages often prefix the key like "[HN-2]:" or "(HN-2)".
		assertThat(SmartCommitParser.parse("[HN-2]: #comment ready for QA"))
				.containsExactly(new Command("HN-2", Type.COMMENT, "ready for QA"));
		assertThat(SmartCommitParser.parse("[HN-2]: docs: #comment Add closing note"))
				.containsExactly(new Command("HN-2", Type.COMMENT, "Add closing note"));
		assertThat(SmartCommitParser.parse("(API-9) #done"))
				.containsExactly(new Command("API-9", Type.TRANSITION, "done"));
	}

	@Test
	void convertsDurationsToMinutes() {
		assertThat(SmartCommitParser.minutes("2h 30m")).isEqualTo(150);
		assertThat(SmartCommitParser.minutes("2h30m")).isEqualTo(150);
		assertThat(SmartCommitParser.minutes("90m")).isEqualTo(90);
		assertThat(SmartCommitParser.minutes("1d")).isEqualTo(8 * 60);
		assertThat(SmartCommitParser.minutes("1w")).isEqualTo(5 * 8 * 60);
		assertThat(SmartCommitParser.minutes(null)).isZero();
	}
}
