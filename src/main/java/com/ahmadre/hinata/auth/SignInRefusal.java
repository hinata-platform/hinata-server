package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.user.User;

import java.util.Optional;

/**
 * Why a sign-in is refused although the credentials or the identity provider checked out:
 * password, LDAP or SSO. One list, so a door added later cannot forget one of them, the way
 * the SSO callback did until HIN-179 and handed fresh tokens to accounts an administrator had
 * deactivated.
 *
 * <p>The first three are states of the account, read by {@link #of}; the last three are about an
 * SSO or LDAP sign-in and the account its address points at, decided in
 * {@link com.ahmadre.hinata.user.SsoAccounts}.
 */
public enum SignInRefusal {

	/** A self-registered local account whose address was never confirmed. */
	EMAIL_NOT_VERIFIED("emailNotVerified", "error.auth.emailNotVerified"),
	/** A verified self-registration an administrator has not approved yet. */
	PENDING_APPROVAL("pendingApproval", "error.auth.pendingApproval"),
	/** An account an administrator has deactivated. */
	ACCOUNT_DEACTIVATED("accountDeactivated", "error.auth.accountDeactivated"),
	/** An identity whose address belongs to an account it may not enter. */
	SSO_ACCOUNT_NOT_LINKED("ssoAccountNotLinked", "error.auth.ssoAccountNotLinked"),
	/** A provider that says the address it sent is unconfirmed: such an address decides nothing. */
	SSO_EMAIL_NOT_VERIFIED("ssoEmailNotVerified", "error.auth.ssoEmailNotVerified"),
	/** A provider that sent no address at all, for a person who has no account bound to them yet. */
	SSO_NO_EMAIL("ssoNoEmail", "error.auth.ssoNoEmail");

	private final String reason;
	private final String messageKey;

	SignInRefusal(String reason, String messageKey) {
		this.reason = reason;
		this.messageKey = messageKey;
	}

	/** Why, as the audit log records it and the apps receive it after an SSO sign-in. */
	public String reason() {
		return reason;
	}

	/** The message the person gets. */
	public String messageKey() {
		return messageKey;
	}

	/**
	 * Whether the refusal counts against the sign-in's attempts like a wrong password: the ones
	 * about the sign-in itself, which somebody trying addresses at a provider runs into. A state
	 * of the account does not, on any door, as with a password.
	 */
	public boolean countsAsFailedAttempt() {
		return this == SSO_ACCOUNT_NOT_LINKED || this == SSO_EMAIL_NOT_VERIFIED || this == SSO_NO_EMAIL;
	}

	/**
	 * What state of [user] keeps them out, if any. The address check applies to local accounts
	 * only: an SSO or LDAP account was never asked to confirm one, and {@code emailVerified}
	 * defaults to true for invited and admin-created accounts, so it only stops a genuine
	 * unconfirmed sign-up.
	 */
	public static Optional<SignInRefusal> of(User user) {
		if (user.getOrigin() == User.Origin.LOCAL && !user.isEmailVerified()) {
			return Optional.of(EMAIL_NOT_VERIFIED);
		}
		if (user.isAwaitingApproval()) {
			return Optional.of(PENDING_APPROVAL);
		}
		if (!user.isActive()) {
			return Optional.of(ACCOUNT_DEACTIVATED);
		}
		return Optional.empty();
	}
}
