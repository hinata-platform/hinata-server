package com.ahmadre.hinata.richtext;

import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.user.User;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Locale;

/** Text checks for services a test builds by hand. */
public final class TestTextChecks {

	private TestTextChecks() {
	}

	/** Without a gate: everything is allowed. */
	public static TextCheck allowAll() {
		return new TextCheck(new StaticListableBeanFactory().getBeanProvider(TextGate.class));
	}

	/** Refuses every text that contains [term], as the lists would refuse a term of theirs. */
	public static TextCheck refusing(String term) {
		TextGate gate = new TextGate() {
			@Override
			public TextVerdict check(String text, TextField field, User author, String before) {
				return text.toLowerCase(Locale.ROOT).contains(term)
						? new TextVerdict(TextVerdict.Verdict.BLOCK, ReportCategory.OTHER, List.of(term), List.of())
						: TextVerdict.ALLOW;
			}

			@Override
			public void flagged(ContentRef ref, TextField field, TextVerdict verdict, User author) {
			}
		};
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("gate", gate);
		return new TextCheck(beans.getBeanProvider(TextGate.class));
	}
}
