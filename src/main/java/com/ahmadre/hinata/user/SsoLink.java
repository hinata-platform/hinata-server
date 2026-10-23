package com.ahmadre.hinata.user;

import java.time.Instant;

/**
 * The provider identity an account is bound to (HIN-179). Set at the first SSO sign-in that
 * enters the account and from then on the only way an SSO sign-in finds it; see
 * {@link SsoAccounts}.
 */
public record SsoLink(String provider, String issuer, String subject, Instant linkedAt) {

	static SsoLink of(SsoIdentity identity, Instant now) {
		return new SsoLink(identity.provider(), identity.issuer(), identity.subject(), now);
	}

	/** Whether this is the link [identity] makes. */
	boolean isOf(SsoIdentity identity) {
		return provider.equals(identity.provider()) && identity.issuer().equals(issuer)
				&& identity.subject().equals(subject);
	}

	/**
	 * Whether this link was made through the same registration at an identity provider known
	 * by another name: the administrator has switched the provider behind it.
	 */
	boolean replacedBy(SsoIdentity identity) {
		return provider.equals(identity.provider()) && !identity.issuer().equals(issuer);
	}
}
