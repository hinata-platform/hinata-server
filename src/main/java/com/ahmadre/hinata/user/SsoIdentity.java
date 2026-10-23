package com.ahmadre.hinata.user;

/**
 * Who an identity provider vouches for at sign-in (HIN-179): the provider's registration in
 * Hinata ({@code oidc}, {@code oauth2}, {@code saml}, {@code ldap}), what names the provider,
 * and the provider's own key for the person.
 *
 * <p>The subject is what an account is bound to, never the e-mail address: a person can often
 * edit their address at the provider, or register with any address they like, while the
 * subject is the provider's own and stays put.
 *
 * @param provider the registration id: {@code OIDC_ID} and {@code OAUTH2_ID} on the client
 *                 registrations, {@code SAML_ID} on the relying-party registrations,
 *                 {@code LDAP_ID} on the LDAP authenticator
 * @param issuer   what names the provider: the OIDC issuer URI, the host of the OAuth2
 *                 authorization URL, the SAML entity id, the LDAP base DN. Another one means the
 *                 administrator pointed the registration at another identity provider
 * @param subject  OIDC {@code sub}, the OAuth2 user's id, SAML NameID, the LDAP entry's own id
 *                 or its DN
 */
public record SsoIdentity(String provider, String issuer, String subject) {

	public SsoIdentity {
		if (provider == null || provider.isBlank() || subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("An SSO identity needs a provider and a subject");
		}
		issuer = normalized(issuer);
	}

	/**
	 * The configured address without the trailing slashes an administrator may or may not
	 * type, so retyping the same address is not taken for a switch of provider.
	 */
	private static String normalized(String issuer) {
		if (issuer == null) {
			return "";
		}
		String trimmed = issuer.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}
}
