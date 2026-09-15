package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What labels an issue may be given. */
class IssueLabelsTest {

	private static final String TOO_LONG = "x".repeat(IssueLabels.MAX_LENGTH + 1);

	private static final List<String> TOO_MANY = IntStream.rangeClosed(0, IssueLabels.MAX_LABELS)
			.mapToObj(i -> "label-" + i)
			.toList();

	/** An emoji of one face: one character, two UTF-16 units. */
	private static final String FACE = "😀";

	/** An emoji of a family: one character, eleven UTF-16 units. */
	private static final String FAMILY = "👨‍👩‍👧‍👦";

	@Test
	void refusesMoreLabelsThanAnIssueCarriesAndLabelsTooLongToWrite() {
		assertThatThrownBy(() -> IssueLabels.check(List.of(), TOO_MANY))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getMessageKey()).isEqualTo("error.issue.labels"));
		assertThatThrownBy(() -> IssueLabels.check(List.of("api"), List.of("api", TOO_LONG)))
				.isInstanceOf(ApiException.class);
		assertThatCode(() -> IssueLabels.check(null, TOO_MANY.subList(0, IssueLabels.MAX_LABELS)))
				.doesNotThrowAnyException();
	}

	@Test
	void countsALabelInTheCharactersSomeoneReads() {
		// Fifty emoji fit the app's field, and they fit here, though each takes two UTF-16 units.
		assertThatCode(() -> IssueLabels.check(List.of(), List.of(FACE.repeat(IssueLabels.MAX_LENGTH))))
				.doesNotThrowAnyException();
		// Twenty emoji of a family are twenty characters, but more units than the search text should hold.
		assertThatThrownBy(() -> IssueLabels.check(List.of(), List.of(FAMILY.repeat(20))))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void keepsWhatAnIssueCarriesAlready() {
		// An issue written before the limits can still be edited and trimmed.
		assertThatCode(() -> IssueLabels.check(List.of(TOO_LONG), List.of(TOO_LONG, "api"))).doesNotThrowAnyException();
		assertThatCode(() -> IssueLabels.check(TOO_MANY, TOO_MANY)).doesNotThrowAnyException();
		assertThatCode(() -> IssueLabels.check(TOO_MANY, TOO_MANY.subList(1, TOO_MANY.size())))
				.doesNotThrowAnyException();
		assertThatCode(() -> IssueLabels.check(List.of("api"), null)).doesNotThrowAnyException();

		// But it gains no label while it carries more than it may, not even in exchange for one.
		List<String> swapped = new ArrayList<>(TOO_MANY);
		swapped.set(0, "new");
		assertThatThrownBy(() -> IssueLabels.check(TOO_MANY, swapped)).isInstanceOf(ApiException.class);
	}

	@Test
	void countsEachLabelOnceHoweverOftenItIsWritten() {
		List<String> repeated = new ArrayList<>(Collections.nCopies(10_000, "api"));
		repeated.addAll(Arrays.asList(" ", null, "ui", "api"));

		assertThat(IssueLabels.distinct(repeated)).containsExactly("api", "ui");
		assertThatCode(() -> IssueLabels.check(List.of("api"), repeated)).doesNotThrowAnyException();
	}
}
