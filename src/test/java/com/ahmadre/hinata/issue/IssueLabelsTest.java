package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.ApiException;
import org.junit.jupiter.api.Test;

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
	void keepsWhatAnIssueCarriesAlready() {
		// An issue written before the limits can still be edited, as long as it gains nothing past them.
		assertThatCode(() -> IssueLabels.check(List.of(TOO_LONG), List.of(TOO_LONG, "api"))).doesNotThrowAnyException();
		assertThatCode(() -> IssueLabels.check(TOO_MANY, TOO_MANY)).doesNotThrowAnyException();
		assertThatCode(() -> IssueLabels.check(List.of("api"), null)).doesNotThrowAnyException();
	}
}
