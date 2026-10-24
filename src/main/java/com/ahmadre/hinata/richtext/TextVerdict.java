package com.ahmadre.hinata.richtext;

import com.ahmadre.hinata.content.ReportCategory;

import java.util.List;

/**
 * What the text check said about one field (HIN-176).
 *
 * @param verdict      allow, flag for moderation after saving, or refuse
 * @param category     the category of what was found; null with nothing found
 * @param terms        the terms found, as written in the lists, for the refusal the author reads
 * @param newFlagTerms the flag terms the stored text did not hold yet; only these open a case
 */
public record TextVerdict(Verdict verdict, ReportCategory category, List<String> terms, List<String> newFlagTerms) {

	public enum Verdict {
		ALLOW, FLAG, BLOCK
	}

	public static final TextVerdict ALLOW = new TextVerdict(Verdict.ALLOW, null, List.of(), List.of());

	public TextVerdict {
		terms = terms == null ? List.of() : List.copyOf(terms);
		newFlagTerms = newFlagTerms == null ? List.of() : List.copyOf(newFlagTerms);
	}

	public boolean blocked() {
		return verdict == Verdict.BLOCK;
	}

	/** Whether saving should be followed by a case: flagged, with something not seen before. */
	public boolean opensCase() {
		return verdict == Verdict.FLAG && !newFlagTerms.isEmpty();
	}
}
