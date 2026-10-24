package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ReportCategory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Where Hinata's eight reasons stand among the categories of the Commission's template
 * (Implementing Regulation (EU) 2024/2835, Annex I and the instructions in Annex II, Part I
 * section 7): one high-level category and the sub-category within it, so the sub-categories add
 * up to their category as the instructions require. The template has no sub-category for some of
 * Hinata's reasons; those stand under "Not captured by any other sub-category" with a description.
 */
final class TransparencyCategories {

	private TransparencyCategories() {
	}

	/** A place in the template: the category, the sub-category, and the description an "Other" needs. */
	record Placement(String category, String keyword, String other) {

		Placement(String category, String keyword) {
			this(category, keyword, null);
		}
	}

	static final String OTHER_KEYWORD = "KEYWORD_OTHER";

	private static final Placement HARASSMENT = new Placement("STATEMENT_CATEGORY_CYBER_VIOLENCE",
			"KEYWORD_CYBER_HARASSMENT");
	private static final Placement THREAT = new Placement("STATEMENT_CATEGORY_VIOLENCE",
			"KEYWORD_INCITEMENT_VIOLENCE_HATRED");
	private static final Placement CHILD_SAFETY = new Placement("STATEMENT_CATEGORY_PROTECTION_OF_MINORS",
			"KEYWORD_CHILD_SEXUAL_ABUSE_MATERIAL");
	private static final Placement PRIVACY = new Placement("STATEMENT_CATEGORY_DATA_PROTECTION_AND_PRIVACY_VIOLATIONS",
			"KEYWORD_MISSING_PROCESSING_GROUND");
	private static final Placement SPAM = new Placement("STATEMENT_CATEGORY_SCAMS_AND_FRAUD", OTHER_KEYWORD,
			"Spam, phishing and malicious files");
	private static final Placement RIGHTS = new Placement("STATEMENT_CATEGORY_INTELLECTUAL_PROPERTY_INFRINGEMENTS",
			OTHER_KEYWORD, "Copyright or another right of others");
	private static final Placement SEXUAL_ILLEGAL = new Placement("STATEMENT_CATEGORY_CYBER_VIOLENCE",
			"KEYWORD_NON_CONSENSUAL_IMAGE_SHARING");
	private static final Placement SEXUAL_RULES = new Placement("STATEMENT_CATEGORY_OTHER_VIOLATION_TC",
			"KEYWORD_ADULT_SEXUAL_MATERIAL");
	private static final Placement OTHER_RULES = new Placement("STATEMENT_CATEGORY_OTHER_VIOLATION_TC",
			OTHER_KEYWORD, "Other breaches of the house rules");
	private static final Placement NOT_SPECIFIED = new Placement("STATEMENT_CATEGORY_NOT_SPECIFIED_NOTICE", null);

	/** Uploads of a type the instance does not take, refused by the upload gate (HIN-177). */
	static final Placement FILE_TYPES = new Placement("STATEMENT_CATEGORY_OTHER_VIOLATION_TC", OTHER_KEYWORD,
			"Files of a type the instance does not accept");

	/**
	 * A notice of [category]. Notices are about alleged illegal content (section 4 of the
	 * template); a reason that does not tell which law stands as not specified by the notifier.
	 */
	static Placement notice(ReportCategory category) {
		return switch (category) {
			case HARASSMENT -> HARASSMENT;
			case THREAT -> THREAT;
			case CHILD_SAFETY -> CHILD_SAFETY;
			case PRIVACY -> PRIVACY;
			case SPAM -> SPAM;
			case SEXUAL, ILLEGAL, OTHER -> NOT_SPECIFIED;
		};
	}

	/**
	 * A measure on the ground of the law (section 5); null for "something else", which none of
	 * the template's categories of illegal content describes. It still counts in the total.
	 */
	static Placement illegal(ReportCategory category) {
		return switch (category) {
			case HARASSMENT -> HARASSMENT;
			case THREAT -> THREAT;
			case CHILD_SAFETY -> CHILD_SAFETY;
			case PRIVACY -> PRIVACY;
			case SPAM -> SPAM;
			case ILLEGAL -> RIGHTS;
			case SEXUAL -> SEXUAL_ILLEGAL;
			case OTHER -> null;
		};
	}

	/** A measure on the ground of the house rules (section 6). */
	static Placement termsAndConditions(ReportCategory category) {
		return switch (category) {
			case HARASSMENT -> HARASSMENT;
			case THREAT -> THREAT;
			case CHILD_SAFETY -> CHILD_SAFETY;
			case PRIVACY -> PRIVACY;
			case SPAM -> SPAM;
			case ILLEGAL -> RIGHTS;
			case SEXUAL -> SEXUAL_RULES;
			case OTHER -> OTHER_RULES;
		};
	}

	/** Hinata's reasons in words, for the contextual information of the category names. */
	private static final Map<ReportCategory, String> WORDS = Map.of(
			ReportCategory.HARASSMENT, "harassment, insults or hate",
			ReportCategory.THREAT, "threats or violence",
			ReportCategory.SEXUAL, "sexual content",
			ReportCategory.CHILD_SAFETY, "endangering children",
			ReportCategory.PRIVACY, "private data without consent",
			ReportCategory.SPAM, "spam, fraud or malware",
			ReportCategory.ILLEGAL, "infringement of the law",
			ReportCategory.OTHER, "something else");

	/**
	 * For each category of the template Hinata reports under, which of its reasons stand there:
	 * the contextual information the instructions invite (Annex II, Part I section 7).
	 */
	static Map<String, String> contextual() {
		Map<String, List<String>> reasons = new LinkedHashMap<>();
		for (ReportCategory category : ReportCategory.values()) {
			add(reasons, notice(category), "notices of " + WORDS.get(category));
			add(reasons, illegal(category), WORDS.get(category) + " on the ground of the law");
			add(reasons, termsAndConditions(category), WORDS.get(category) + " on the ground of the house rules");
		}
		add(reasons, FILE_TYPES, "uploads the instance does not accept");
		return reasons.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
				entry -> "Hinata reports here: " + String.join("; ", entry.getValue()) + ".",
				(a, b) -> a, LinkedHashMap::new));
	}

	private static void add(Map<String, List<String>> reasons, Placement placement, String what) {
		if (placement == null) {
			return;
		}
		List<String> list = reasons.computeIfAbsent(placement.category(), key -> new ArrayList<>());
		if (!list.contains(what)) {
			list.add(what);
		}
	}
}
