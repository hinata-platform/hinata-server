package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.moderation.rules.HouseRulesService;
import com.ahmadre.hinata.moderation.rules.RulesVersion;
import com.ahmadre.hinata.setup.ServerSettings.RulesMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The numbered house rules a decision rests on (HIN-171), for moderators to pick from: the
 * built-in ones, the version in force (HIN-175), and which rule each category of report points at.
 */
@Component
@RequiredArgsConstructor
public class ModerationRules {

	private final HouseRulesService houseRules;

	/** One rule as a decision names it. */
	public record Rule(int number, String title) {
	}

	/** A category of report and the number of the built-in rule it is about. */
	public record CategoryRule(ReportCategory category, int rule) {
	}

	/**
	 * The rules in force: [version] null before the first; [builtIn] whether they are the built-in
	 * ones the numbers refer to, rather than an instance's own text or page.
	 */
	public record RuleSet(Integer version, boolean builtIn, List<Rule> rules, List<CategoryRule> categories) {
	}

	public RuleSet current(Locale locale) {
		List<Rule> rules = new ArrayList<>();
		houseRules.builtInRules(locale).forEach((number, title) -> rules.add(new Rule(number, title)));
		RulesVersion latest = houseRules.latest();
		return new RuleSet(latest == null ? null : latest.getVersion(),
				latest == null || latest.getMode() == RulesMode.BUILT_IN, rules,
				Arrays.stream(ReportCategory.values()).map(category -> new CategoryRule(category, ruleOf(category)))
						.toList());
	}

	/** The built-in rule a category of report is about. */
	static int ruleOf(ReportCategory category) {
		return switch (category) {
			case HARASSMENT -> 2;
			case THREAT -> 3;
			case SEXUAL, CHILD_SAFETY -> 4;
			case PRIVACY -> 5;
			case SPAM -> 6;
			case ILLEGAL -> 7;
			case OTHER -> 1;
		};
	}
}
