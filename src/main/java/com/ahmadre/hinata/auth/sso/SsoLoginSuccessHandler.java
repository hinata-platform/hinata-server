package com.ahmadre.hinata.auth.sso;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.auth.Invitations;
import com.ahmadre.hinata.auth.SignInRefusedException;
import com.ahmadre.hinata.auth.TokenService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.SsoAccounts;
import com.ahmadre.hinata.user.SsoIdentity;
import com.ahmadre.hinata.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * After a successful OAuth2/OIDC or SAML login the user is provisioned and the
 * browser is redirected back into the app, carrying a fresh token pair. Native
 * apps receive it via the deep link scheme; the web app (flow started through
 * {@code /api/v1/auth/sso/start?return=...}) via its own origin.
 *
 * <p>The provider having accepted the person is not the whole answer (HIN-179): the account
 * must be the one bound to that person at the provider, and it must be allowed in by the same
 * rules a password sign-in follows (see {@link SsoAccounts}). Otherwise the browser goes back
 * with the reason and no token, and the attempt is in the audit log.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsoLoginSuccessHandler implements AuthenticationSuccessHandler {

	/** The user-info fields that carry an OAuth2 user's own id, in the order they are trusted. */
	private static final List<String> OAUTH2_ID_ATTRIBUTES = List.of("sub", "id");

	private final SsoAccounts accounts;
	private final TokenService tokens;
	private final SettingsService settings;
	private final SsoProfileMapper mapper;
	private final com.ahmadre.hinata.me.SessionService sessions;
	private final com.ahmadre.hinata.config.ClientIpResolver clientIpResolver;
	private final com.ahmadre.hinata.audit.AuditService audit;
	private final SsoHandoffService handoff;
	private final SsoReturn back;
	private final DynamicRelyingPartyRegistrationRepository relyingParties;
	private final Invitations invitations;

	/** What the provider vouched for, in the terms {@link SsoAccounts#signIn} takes. */
	private record SsoLogin(SsoIdentity identity, SsoProfileMapper.SsoProfile profile, User.Origin origin,
			boolean syncProfile) {
	}

	/** A sign-in the provider accepted that names nobody Hinata can bind an account to. */
	private static final class Unusable extends RuntimeException {

		private final String error;

		Unusable(String error, String why) {
			super(why);
			this.error = error;
		}
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		// Success handlers run in the security filter chain, before the
		// DispatcherServlet resolves the request locale — seed it from the
		// browser's Accept-Language so a first-time SSO user is provisioned in
		// their own language (see SsoAccounts#signIn).
		LocaleContextHolder.setLocale(request.getLocale());
		SsoLogin login;
		try {
			login = loginOf(authentication);
		}
		catch (Unusable unusable) {
			log.warn("SSO sign-in from {} not usable: {}", authentication.getClass().getSimpleName(),
					unusable.getMessage());
			back.failed(request, response, unusable.error);
			return;
		}
		// Started from an invitation (see SsoController#start): one still open is completed by
		// this sign-in. One that is not any more leaves an ordinary sign-in.
		String inviteToken = SsoController.inviteOf(request);
		User invitation = inviteToken == null ? null : invitations.pending(inviteToken).orElse(null);
		SsoAccounts.SsoSignIn signIn;
		try {
			signIn = accounts.signIn(login.identity(), login.profile(), login.origin(), login.syncProfile(),
					invitation);
		}
		catch (SignInRefusedException refused) {
			recordRefusal(refused, login);
			back.refused(request, response, refused.refusal());
			return;
		}
		User user = signIn.user();
		com.ahmadre.hinata.me.RefreshSession session = sessions.start(user,
				clientIpResolver.resolve(request), request.getHeader("User-Agent"), signIn.ownerTold());
		TokenService.TokenPair pair = tokens.issue(user, session.getId());
		audit.event(AuditAction.SSO_LOGIN).actor(user)
				.meta("provider", login.origin().name())
				.meta("registration", login.identity().provider())
				.log();
		// The token pair never travels in the URL: it is stashed behind a
		// short-lived, single-use handoff code the app POSTs to
		// /api/v1/auth/sso/exchange to redeem, with the verifier behind the
		// challenge it started the flow with. A code in the query is safe to log
		// (useless after one redemption, TTL-expired otherwise) whereas bearer
		// tokens are not.
		back.signedIn(request, response, handoff.issue(pair, SsoController.challengeOf(request)));
	}

	/**
	 * Records a refused sign-in. The actor is the account that was found but may not come in;
	 * where the identity may not enter the account its address points at, the claimed address
	 * stands in for it, and the provider's key for the person says who tried.
	 */
	private void recordRefusal(SignInRefusedException refused, SsoLogin login) {
		var entry = audit.event(AuditAction.LOGIN_FAILURE)
				.meta("reason", refused.recorded().reason())
				.meta("provider", login.origin().name())
				.meta("registration", login.identity().provider())
				.meta("subject", login.identity().subject());
		if (refused.account() != null) {
			entry.actor(refused.account());
		}
		else {
			entry.actor(null, login.profile().email());
		}
		entry.log();
	}

	/**
	 * Reads what the provider vouched for. Which attribute carries the display name or the
	 * position differs per IdP, so the mapping is read from the provider's own block in the
	 * admin settings instead of being hardcoded here (see {@link SsoProfileMapper}). Who the
	 * person is comes from the provider's own key for them, never from those attributes, and the
	 * provider is named by what identifies it rather than by an address an administrator may
	 * retype: a change of name counts as a switch of provider (see {@link SsoAccounts}).
	 */
	private SsoLogin loginOf(Authentication authentication) {
		ServerSettings current = settings.get();
		if (authentication.getPrincipal() instanceof Saml2AuthenticatedPrincipal saml) {
			ServerSettings.Saml config = current.getSaml();
			SsoProfileMapper.SsoProfile profile = mapper.map(attributesOf(saml), config, saml.getName());
			return login(DynamicRelyingPartyRegistrationRepository.SAML_ID, samlIssuer(), saml.getName(), profile,
					User.Origin.SAML, config.isSyncProfileOnLogin());
		}
		if (authentication.getPrincipal() instanceof OAuth2User oauth) {
			// One principal type covers both registrations; the registration id says
			// whose mapping applies (discovered OIDC vs. hand-configured OAuth2).
			if (isPlainOAuth2(authentication)) {
				ServerSettings.OAuth2 config = current.getOauth2();
				SsoProfileMapper.SsoProfile profile = mapper.map(oauth.getAttributes(), config, oauth.getName())
						.withEmailVerified(flag(oauth.getAttributes().get("email_verified")));
				return login(DynamicClientRegistrationRepository.OAUTH2_ID, hostOf(config.getAuthorizationUri()),
						oauth2Id(oauth), profile, User.Origin.OIDC, config.isSyncProfileOnLogin());
			}
			ServerSettings.Oidc config = current.getOidc();
			boolean oidc = oauth instanceof OidcUser;
			SsoProfileMapper.SsoProfile profile = mapper.map(oauth.getAttributes(), config, oauth.getName())
					.withEmailVerified(oidc ? ((OidcUser) oauth).getEmailVerified()
							: flag(oauth.getAttributes().get("email_verified")));
			String subject = oidc ? ((OidcUser) oauth).getSubject() : oauth.getName();
			return login(DynamicClientRegistrationRepository.OIDC_ID, config.getIssuerUri(), subject, profile,
					User.Origin.OIDC, config.isSyncProfileOnLogin());
		}
		throw new IllegalStateException("Unsupported SSO principal type");
	}

	/**
	 * What the provider vouched for, with its key for the person; a principal without one cannot
	 * be bound to anything. A key that is the address itself binds the account to the address
	 * after all: it holds only as long as the provider keeps people from taking each other's
	 * addresses. Said in the log so an operator can switch to a stable key (SAML
	 * {@code persistent}, an OAuth2 id attribute).
	 */
	private SsoLogin login(String provider, String issuer, String subject, SsoProfileMapper.SsoProfile profile,
			User.Origin origin, boolean syncProfile) {
		if (subject == null || subject.isBlank()) {
			throw new Unusable("sso_no_subject", "no subject for the person from " + provider);
		}
		if (subject.equalsIgnoreCase(profile.email())) {
			log.warn("SSO provider '{}' names people by their address; the account binding is only as strong as "
					+ "the provider's control over addresses", provider);
		}
		return new SsoLogin(new SsoIdentity(provider, issuer, subject), profile, origin, syncProfile);
	}

	/**
	 * The OAuth2 user's own id. The configured name attribute is the address unless an
	 * administrator changed it, and an address is no key for a person; nearly every user-info
	 * endpoint also sends a stable {@code sub} or {@code id}, which is taken first. Only a
	 * provider that sends neither falls back to the name attribute, address or not.
	 */
	private String oauth2Id(OAuth2User user) {
		for (String attribute : OAUTH2_ID_ATTRIBUTES) {
			Object value = user.getAttribute(attribute);
			if (value != null && !value.toString().isBlank()) {
				return value.toString();
			}
		}
		return user.getName();
	}

	/**
	 * The SAML identity provider's entity id, which names it whatever address its metadata is
	 * fetched from, and which the assertion was just checked against. With no registration left to
	 * read it from, SAML was switched off meanwhile: anything else in its place would be taken for
	 * a switch of provider and bind the account anew by its address.
	 */
	private String samlIssuer() {
		RelyingPartyRegistration registration =
				relyingParties.findByRegistrationId(DynamicRelyingPartyRegistrationRepository.SAML_ID);
		String entityId = registration == null ? null : registration.getAssertingPartyMetadata().getEntityId();
		if (entityId == null || entityId.isBlank()) {
			throw new Unusable("sso_failed", "SAML registration gone during the sign-in");
		}
		return entityId;
	}

	/** The host an OAuth2 provider is reached at: retyping its path is no switch of provider. */
	private static String hostOf(String uri) {
		try {
			String host = URI.create(uri.trim()).getHost();
			return host != null ? host.toLowerCase(java.util.Locale.ROOT) : uri;
		}
		catch (IllegalArgumentException | NullPointerException notAUri) {
			return uri;
		}
	}

	/** A claim that is a flag: a boolean, or the strings {@code true}/{@code false}; null otherwise. */
	private static Boolean flag(Object value) {
		if (value instanceof Boolean flag) {
			return flag;
		}
		if (value instanceof String text && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
			return Boolean.parseBoolean(text);
		}
		return null;
	}

	private boolean isPlainOAuth2(Authentication authentication) {
		return authentication instanceof OAuth2AuthenticationToken token
				&& DynamicClientRegistrationRepository.OAUTH2_ID
						.equals(token.getAuthorizedClientRegistrationId());
	}

	/**
	 * A SAML assertion holds every attribute as a list. Widening the map to the
	 * mapper's {@code Map<String, Object>} (which flattens the lists) needs the
	 * wildcard of {@code unmodifiableMap} — a plain cast would not compile.
	 */
	private Map<String, Object> attributesOf(Saml2AuthenticatedPrincipal principal) {
		return java.util.Collections.unmodifiableMap(principal.getAttributes());
	}
}
