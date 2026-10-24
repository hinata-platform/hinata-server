package com.ahmadre.hinata.richtext;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The text check as the write services use it (HIN-176): refuse before saving, report a find
 * after. Without a {@link TextGate} everything is allowed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextCheck {

	/** What the app reads a refusal by: {@code details.code}, never the status alone. */
	public static final String REFUSED = "moderation.refused";

	private final ObjectProvider<TextGate> gate;

	/** One field of a write: its new text, null when there is nothing new, and the text stored before. */
	public record Entry(TextField field, String text, String before) {

		/** [text] when it differs from [before]; an unchanged field is not judged again. */
		public static Entry ifChanged(TextField field, String text, String before) {
			return new Entry(field, Objects.equals(text, before) ? null : text, before);
		}
	}

	/** The verdict on [text] as [field]; nothing to check is an allow. */
	public TextVerdict verdict(String text, TextField field, User author, String before) {
		TextGate textGate = gate.getIfAvailable();
		if (textGate == null || text == null || text.isBlank()) {
			return TextVerdict.ALLOW;
		}
		return textGate.check(text, field, author, before);
	}

	/**
	 * The verdict on [text] as [field], refusing with 422 {@code error.moderation.refused} when it
	 * blocks: the author sees the term, because the author wrote it.
	 */
	public TextVerdict require(String text, TextField field, User author, String before) {
		TextVerdict verdict = verdict(text, field, author, before);
		if (verdict.blocked()) {
			throw refusal(verdict);
		}
		return verdict;
	}

	/**
	 * The verdicts on several fields of one write, in order. Refuses once with every refused term
	 * when any field is refused, so the author learns about all of them at the same time.
	 */
	public List<TextVerdict> require(User author, Entry... entries) {
		List<TextVerdict> verdicts = new ArrayList<>(entries.length);
		Set<String> refusedTerms = new LinkedHashSet<>();
		TextVerdict first = null;
		for (Entry entry : entries) {
			TextVerdict verdict = verdict(entry.text(), entry.field(), author, entry.before());
			verdicts.add(verdict);
			if (verdict.blocked()) {
				first = first == null ? verdict : first;
				refusedTerms.addAll(verdict.terms());
			}
		}
		if (first != null) {
			throw refusal(new TextVerdict(TextVerdict.Verdict.BLOCK, first.category(), List.copyOf(refusedTerms),
					List.of()));
		}
		return verdicts;
	}

	/** After saving: hands a find to moderation. A failure there never undoes the save. */
	public void afterSave(ContentRef ref, TextField field, TextVerdict verdict, User author) {
		TextGate textGate = gate.getIfAvailable();
		if (textGate == null || verdict == null || !verdict.opensCase() || !field.flaggable() || ref == null
				|| ref.id() == null) {
			return;
		}
		try {
			textGate.flagged(ref, field, verdict, author);
		}
		catch (RuntimeException ex) {
			log.warn("Handing a text find on {} {} to moderation failed: {}", ref.kind(), ref.id(), ex.getMessage());
		}
	}

	/** The refusal of [verdict], for a write that refuses in its own way first, like a mail. */
	public static ApiException refusal(TextVerdict verdict) {
		String terms = String.join(", ", verdict.terms());
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "error.moderation.refused",
				Map.of("code", REFUSED, "category", verdict.category() == null ? "" : verdict.category().name(),
						"terms", terms),
				terms);
	}

	/** Whether [ex] is a refusal of the text check. */
	public static boolean isRefusal(ApiException ex) {
		return REFUSED.equals(ex.getDetails().get("code"));
	}
}
