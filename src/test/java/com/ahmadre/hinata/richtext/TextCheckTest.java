package com.ahmadre.hinata.richtext;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.ReportCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A refused write counts once in the yearly figures, however many of its fields were refused, and
 * a look that only decides whether to replace a name counts nothing (HIN-176, HIN-178).
 */
class TextCheckTest {

	private final List<TextVerdict> refusals = new ArrayList<>();
	private final TextCheck check = checkRefusing("idiot");

	private TextCheck checkRefusing(String term) {
		TextGate gate = new TextGate() {
			@Override
			public TextVerdict check(String text, TextField field, String before) {
				return text.contains(term)
						? new TextVerdict(TextVerdict.Verdict.BLOCK, ReportCategory.HARASSMENT, List.of(term), List.of())
						: TextVerdict.ALLOW;
			}

			@Override
			public void refused(TextVerdict verdict) {
				refusals.add(verdict);
			}

			@Override
			public void flagged(ContentRef ref, TextField field, TextVerdict verdict) {
			}
		};
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("gate", gate);
		return new TextCheck(beans.getBeanProvider(TextGate.class));
	}

	@Test
	void aWriteRefusedOnTwoFieldsCountsOnce() {
		assertThatThrownBy(() -> check.require(new TextCheck.Entry(TextField.ISSUE_TITLE, "idiot", null),
				new TextCheck.Entry(TextField.ISSUE_DESCRIPTION, "so ein idiot", null)))
				.isInstanceOf(ApiException.class)
				.satisfies(refused -> assertThat(TextCheck.isRefusal((ApiException) refused)).isTrue());

		assertThat(refusals).hasSize(1);
	}

	@Test
	void aLookCountsNothing() {
		assertThat(check.verdict("idiot", TextField.USERNAME, null).blocked()).isTrue();

		assertThat(refusals).isEmpty();
	}

	@Test
	void theVerdictsOfAWriteAreReadByField() {
		TextCheck.Verdicts verdicts = check.require(new TextCheck.Entry(TextField.ISSUE_TITLE, "Stand B12", null),
				new TextCheck.Entry(TextField.ISSUE_DESCRIPTION, "Aufbau", null));

		assertThat(verdicts.of(TextField.ISSUE_TITLE)).isEqualTo(TextVerdict.ALLOW);
		assertThat(verdicts.of(TextField.COMMENT)).as("not part of the write").isEqualTo(TextVerdict.ALLOW);
	}
}
