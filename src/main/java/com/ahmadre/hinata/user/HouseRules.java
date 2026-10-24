package com.ahmadre.hinata.user;

import com.ahmadre.hinata.common.ApiException;

/**
 * The house rules of the instance as the core asks about them (HIN-175): which version is current,
 * and whether a person still has to accept them. Declared here and implemented by moderation, so
 * that no core package reaches into the module; without an implementation there is no gate.
 */
public interface HouseRules {

	/** The current version of the rules, or null while there is none yet. */
	Integer currentVersion();

	/**
	 * Whether [user] has to accept the rules before writing: a version asked everybody to accept
	 * anew, the first one included, and [user] accepted none since.
	 */
	boolean pending(User user);

	/** The page of the current rules, which anybody can open without an account. */
	String publicRulesUrl();

	/** The form for reports from people without an account (DSA Art. 16). */
	String publicReportUrl();

	/** 409 {@code error.rules.acceptRequired} while [user] has to accept the rules first. */
	default void requireAccepted(User user) {
		if (pending(user)) {
			throw ApiException.conflict("error.rules.acceptRequired");
		}
	}
}
