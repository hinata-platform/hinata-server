package com.ahmadre.hinata.richtext;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
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

	/** The verdicts on the fields of one write, by field; a field not checked is an allow. */
	public record Verdicts(Map<TextField, TextVerdict> byField) {

		public Verdicts {
			byField = Map.copyOf(byField);
		}

		public TextVerdict of(TextField field) {
			return byField.getOrDefault(field, TextVerdict.ALLOW);
		}
	}

	/** The verdict on [text] as [field], only a look: nothing to check is an allow, nothing counts. */
	public TextVerdict verdict(String text, TextField field, String before) {
		TextGate textGate = gate.getIfAvailable();
		if (textGate == null || text == null || text.isBlank()) {
			return TextVerdict.ALLOW;
		}
		return textGate.check(text, field, before);
	}

	/**
	 * The verdict on [text] as [field], refusing with 422 {@code error.moderation.refused} when it
	 * blocks: the author sees the term, because the author wrote it.
	 */
	public TextVerdict require(String text, TextField field, String before) {
		return require(new Entry(field, text, before)).of(field);
	}

	/**
	 * The verdicts on the fields of one write. Refuses once with every refused term when any field
	 * is refused, so the author learns about all of them at the same time, and counts it once.
	 */
	public Verdicts require(Entry... entries) {
		Map<TextField, TextVerdict> verdicts = new EnumMap<>(TextField.class);
		Set<String> refusedTerms = new LinkedHashSet<>();
		TextVerdict first = null;
		for (Entry entry : entries) {
			TextVerdict verdict = verdict(entry.text(), entry.field(), entry.before());
			verdicts.put(entry.field(), verdict);
			if (verdict.blocked()) {
				first = first == null ? verdict : first;
				refusedTerms.addAll(verdict.terms());
			}
		}
		if (first != null) {
			TextVerdict refusal = new TextVerdict(TextVerdict.Verdict.BLOCK, first.category(),
					List.copyOf(refusedTerms), List.of());
			refused(refusal);
			throw refusal(refusal);
		}
		return new Verdicts(verdicts);
	}

	/** Counts a write refused for [verdict], for one that refuses in its own way, like a mail. */
	public void refused(TextVerdict verdict) {
		TextGate textGate = gate.getIfAvailable();
		if (textGate != null) {
			textGate.refused(verdict);
		}
	}

	/** After saving: hands a find to moderation. A failure there never undoes the save. */
	public void afterSave(ContentRef ref, TextField field, TextVerdict verdict) {
		TextGate textGate = gate.getIfAvailable();
		if (textGate == null || verdict == null || !verdict.opensCase() || !field.flaggable() || ref == null
				|| ref.id() == null) {
			return;
		}
		try {
			textGate.flagged(ref, field, verdict);
		}
		catch (RuntimeException ex) {
			log.warn("Handing a text find on {} {} to moderation failed: {}", ref.kind(), ref.id(), ex.getMessage());
		}
	}

	/** After saving: every find of [verdicts] handed to moderation, one field at a time. */
	public void afterSave(ContentRef ref, Verdicts verdicts) {
		verdicts.byField().forEach((field, verdict) -> afterSave(ref, field, verdict));
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
