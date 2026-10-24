package com.ahmadre.hinata.moderation;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The numbered house rules a decision rests on (HIN-171), for moderators to pick from: the
 * built-in ones until an instance writes its own, in versions (HIN-175).
 */
@Component
@RequiredArgsConstructor
public class ModerationRules {

	/** How many rules the built-in house rules have. */
	static final int BUILT_IN = 7;

	private final MessageSource messages;

	/** One rule as a decision names it. */
	public record Rule(int number, String title) {
	}

	/** The rules in force; [version] is null for the built-in ones. */
	public record RuleSet(Integer version, List<Rule> rules) {
	}

	public RuleSet current(Locale locale) {
		List<Rule> rules = new ArrayList<>();
		for (int number = 1; number <= BUILT_IN; number++) {
			rules.add(new Rule(number, messages.getMessage("moderation.rule." + number, null, locale)));
		}
		return new RuleSet(null, rules);
	}
}
