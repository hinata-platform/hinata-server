package com.ahmadre.hinata.user;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.AuthPolicy;
import com.ahmadre.hinata.auth.SignInRefusal;
import com.ahmadre.hinata.auth.SignInRefusedException;
import com.ahmadre.hinata.auth.sso.DynamicClientRegistrationRepository;
import com.ahmadre.hinata.auth.sso.DynamicRelyingPartyRegistrationRepository;
import com.ahmadre.hinata.auth.sso.SsoProfileMapper.SsoProfile;
import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Which account an OIDC, OAuth2, SAML or LDAP sign-in enters, created on the first one
 * (HIN-179).
 *
 * <p>The account is found by the provider's key for the person ({@link SsoIdentity}), never
 * simply by the address the provider sends: that address is often something a person can edit
 * at the provider, so matching on it alone let anybody into the account of whoever's address
 * they typed, a local administrator's with password and 2FA included. The address may still
 * lead into an account that is not bound yet, in the cases {@link #addressMayDecide} names; the
 * account is bound then, its owner is told, and from that moment only the identity finds it.
 * An invitation the sign-in was started from leads into its own account, whatever the address.
 * Everything else is refused with a {@link SignInRefusedException}, and a refused sign-in never
 * leaves its identity on an account.
 *
 * <p>On a later sign-in the directory stays the source of truth while {@code syncProfile} is on:
 * display name and position follow the IdP. Two rules hold regardless: an attribute the IdP does
 * <em>not</em> send never clears a stored value, and only SSO-provisioned accounts are touched,
 * so an IdP can never rewrite the profile of a local account. The address in Hinata never
 * follows the provider's.
 */
@Service
@RequiredArgsConstructor
public class SsoAccounts {

	/**
	 * The field of {@link User#getSsoLink()}. The targeted writes below go through it; a full
	 * save of a user elsewhere carries the link along as it was read.
	 */
	private static final String SSO_LINK = "ssoLink";

	/** Languages we ship email templates for; anything else keeps the default. */
	private static final Set<String> SUPPORTED_LOCALES =
			Set.of("en", "de", "zh", "hi", "es", "ja", "fr", "ru", "ar");

	private final UserRepository users;
	private final MongoTemplate mongo;
	private final UserService userService;
	private final AuthPolicy authPolicy;
	private final SettingsService settings;
	private final AuditService audit;
	private final NotificationService notifications;
	private final UserWords words;

	/**
	 * The account a sign-in entered. [ownerTold] is set when the sign-in bound the account by its
	 * address and its owner got the security notice for that: the notice about a sign-in from a
	 * new device would only say the same thing a second time.
	 */
	public record SsoSignIn(User user, boolean ownerTold) {
	}

	/**
	 * The account [identity] may enter with [profile], bound to it if it was not yet.
	 *
	 * @throws SignInRefusedException when the identity may not enter the account its address
	 *         points at, when nothing but an address the provider calls unconfirmed or did not
	 *         send could decide, or when the account's state keeps it out
	 */
	public SsoSignIn signIn(SsoIdentity identity, SsoProfile profile, User.Origin origin, boolean syncProfile) {
		return signIn(identity, profile, origin, syncProfile, null);
	}

	/**
	 * As {@link #signIn(SsoIdentity, SsoProfile, User.Origin, boolean)}, for a sign-in started from
	 * the invitation of [invitation], a pending account: the invitation link proved the address,
	 * so the sign-in completes the invitation and binds the account, unless the identity already
	 * has an account of its own.
	 */
	public SsoSignIn signIn(SsoIdentity identity, SsoProfile profile, User.Origin origin, boolean syncProfile,
			User invitation) {
		return signIn(identity, profile, origin, syncProfile, invitation, true);
	}

	private SsoSignIn signIn(SsoIdentity identity, SsoProfile profile, User.Origin origin, boolean syncProfile,
			User invitation, boolean mayRetry) {
		Optional<User> bound = boundAccount(identity);
		if (bound.isPresent()) {
			return entered(admit(bound.get(), profile, syncProfile));
		}
		if (invitation != null) {
			return entered(join(invitation, identity, profile));
		}
		if (!isAddress(profile.email())) {
			throw new SignInRefusedException(SignInRefusal.SSO_NO_EMAIL, null);
		}
		Optional<User> sameAddress = users.findByEmailIgnoreCase(profile.email());
		if (sameAddress.isEmpty()) {
			return create(identity, profile, origin, syncProfile, mayRetry);
		}
		User account = sameAddress.get();
		if (account.getSsoLink() != null && account.getSsoLink().isOf(identity)) {
			// The same person's other sign-in bound it between the two reads above.
			return entered(admit(account, profile, syncProfile));
		}
		// From here on the address alone would decide. One the provider itself calls unconfirmed
		// decides nothing, and says so before anything about the account behind it shows.
		if (Boolean.FALSE.equals(profile.emailVerified())) {
			throw new SignInRefusedException(SignInRefusal.SSO_EMAIL_NOT_VERIFIED, null);
		}
		if (!addressMayDecide(account, identity, origin)) {
			throw new SignInRefusedException(SignInRefusal.SSO_ACCOUNT_NOT_LINKED, null);
		}
		// Only a sign-in that gets in binds: an identity refused for the account's state must not
		// be left on it, to find the account once an administrator lets it in again. Whoever only
		// typed the address is not told that state, whether an invitation is open or an account
		// is off: to them it is an account they may not enter, and the audit log has the rest.
		Optional<SignInRefusal> keptOut = SignInRefusal.of(account);
		if (keptOut.isPresent()) {
			throw new SignInRefusedException(SignInRefusal.SSO_ACCOUNT_NOT_LINKED, account, keptOut.get());
		}
		Optional<User> linked = bind(account, identity, profile);
		if (linked.isEmpty()) {
			// Another sign-in bound the account first: the same person arriving twice finds it
			// through the identity, anybody else may not enter it.
			return entered(admit(boundAccount(identity).orElseThrow(
					() -> new SignInRefusedException(SignInRefusal.SSO_ACCOUNT_NOT_LINKED, null)), profile, syncProfile));
		}
		return new SsoSignIn(applyIdpProfile(linked.get(), profile, syncProfile), true);
	}

	/**
	 * Whether the address the provider sent may lead into [account], which is not bound to
	 * [identity]. Only where nothing better than the address exists, and only for the kind of
	 * account the address was trusted for before accounts were bound:
	 *
	 * <ul>
	 *   <li>an account made by the same kind of sign-in that is not bound yet: every SSO account
	 *       from before HIN-179, once, at its first sign-in since. OIDC and OAuth2 are one kind
	 *       here, as they always shared the account origin;</li>
	 *   <li>an account bound through the same registration to another identity provider: the
	 *       administrator switched the provider behind it, and the new one has other keys;</li>
	 *   <li>a local account while local sign-in is off: SSO is then the only way into it, as it
	 *       was before, and refusing it would lock out an instance's only administrator.</li>
	 * </ul>
	 *
	 * Never a local account while it can sign in with its password, never an account of another
	 * kind, never one bound to another person at the same provider.
	 */
	private boolean addressMayDecide(User account, SsoIdentity identity, User.Origin origin) {
		boolean sameKind = account.getOrigin() == origin
				|| (account.getOrigin() == User.Origin.LOCAL && !authPolicy.localAuthEnabled());
		SsoLink link = account.getSsoLink();
		return sameKind && (link == null || link.replacedBy(identity));
	}

	/**
	 * Binds [account] to [identity], provided it is still bound the way it was when read: of two
	 * sign-ins racing for the same account, exactly one binds it. Empty for the one that lost.
	 *
	 * <p>A binding by address is the one moment the address still opens an account, so it is
	 * recorded whatever the audit settings say and the account's owner is told, at the address
	 * Hinata knows, who signed in and where: if it was not them, they hear about it.
	 */
	private Optional<User> bind(User account, SsoIdentity identity, SsoProfile profile) {
		SsoLink previous = account.getSsoLink();
		Query unchanged = Query.query(Criteria.where("_id").is(account.getId()));
		if (previous == null) {
			unchanged.addCriteria(Criteria.where(SSO_LINK).is(null));
		}
		else {
			unchanged.addCriteria(Criteria.where(SSO_LINK + ".issuer").is(previous.issuer())
					.and(SSO_LINK + ".subject").is(previous.subject()));
		}
		SsoLink link = SsoLink.of(identity, Instant.now());
		if (!updated(unchanged, Update.update(SSO_LINK, link))) {
			return Optional.empty();
		}
		account.setSsoLink(link);
		audit.event(AuditAction.SSO_LINKED).actor(account)
				.meta("registration", identity.provider())
				.meta("issuer", identity.issuer())
				.meta("subject", identity.subject())
				.meta("email", profile.email())
				.meta("previousSubject", previous == null ? null : previous.subject())
				.log();
		notifications.notifySecurityAlert(account,
				words.of(account, "notify.security.ssoLinked.title"),
				words.of(account, "notify.security.ssoLinked.body", providerName(identity),
						profile.displayName() != null ? profile.displayName() : profile.email()));
		return Optional.of(account);
	}

	/**
	 * Completes [invitation] with this sign-in and binds the account to [identity]: the
	 * invitation link proved the address, the provider proves who signs in. Only while the
	 * invitation is still open as read, so a link followed twice joins once; the owner needs no
	 * notice of what they are doing right now.
	 */
	private User join(User invitation, SsoIdentity identity, SsoProfile profile) {
		Instant now = Instant.now();
		Query open = Query.query(Criteria.where("_id").is(invitation.getId())
				.and("joinedAt").is(null)
				.and("inviteTokenHash").is(invitation.getInviteTokenHash())
				.and(SSO_LINK).is(null));
		Update joined = new Update()
				.set(SSO_LINK, SsoLink.of(identity, now))
				.set("joinedAt", now)
				.set("active", true)
				.set("emailVerified", true)
				.unset("inviteTokenHash")
				.unset("inviteExpiresAt");
		String lang = LocaleContextHolder.getLocale().getLanguage();
		if (SUPPORTED_LOCALES.contains(lang)) {
			joined.set("locale", lang);
		}
		if (!updated(open, joined)) {
			// The link was followed twice and the other sign-in joined first.
			return admit(boundAccount(identity).orElseThrow(
					() -> new SignInRefusedException(SignInRefusal.SSO_ACCOUNT_NOT_LINKED, null)), profile, false);
		}
		User account = users.findById(invitation.getId()).orElseThrow();
		audit.event(AuditAction.SSO_LINKED).actor(account)
				.meta("registration", identity.provider())
				.meta("issuer", identity.issuer())
				.meta("subject", identity.subject())
				.meta("email", profile.email())
				.meta("invitation", "accepted")
				.log();
		return account;
	}

	/** Whether [update] changed the one account [query] matches; false where it would take an identity in use. */
	private boolean updated(Query query, Update update) {
		try {
			return mongo.updateFirst(query, update, User.class).getModifiedCount() == 1;
		}
		catch (DuplicateKeyException identityTaken) {
			return false;
		}
	}

	private SsoSignIn create(SsoIdentity identity, SsoProfile profile, User.Origin origin, boolean syncProfile,
			boolean mayRetry) {
		// New SSO users inherit the browser language from the login redirect
		// (Accept-Language) so their emails are localized from the start.
		String email = profile.email();
		String displayName = profile.displayName();
		User.UserBuilder builder = User.builder()
				.email(email.toLowerCase(Locale.ROOT))
				.username(userService.uniqueUsernameFrom(email))
				.displayName(displayName != null && !displayName.isBlank() ? displayName : email)
				.title(trimToNull(profile.title()))
				.roles(Set.of(Role.MEMBER))
				.origin(origin)
				.ssoLink(SsoLink.of(identity, Instant.now()));
		String lang = LocaleContextHolder.getLocale().getLanguage();
		if (SUPPORTED_LOCALES.contains(lang)) builder.locale(lang);
		try {
			return entered(users.save(builder.build()));
		}
		catch (DuplicateKeyException createdMeanwhile) {
			// A second arrival of the same first sign-in created the account a moment earlier.
			// Otherwise somebody else took the address or the username in that moment: one more
			// pass decides afresh, by the address as it is now.
			Optional<User> created = boundAccount(identity);
			if (created.isPresent()) {
				return entered(admit(created.get(), profile, syncProfile));
			}
			if (!mayRetry) {
				throw new SignInRefusedException(SignInRefusal.SSO_ACCOUNT_NOT_LINKED, null);
			}
			return signIn(identity, profile, origin, syncProfile, null, false);
		}
	}

	/**
	 * Undoes the account's binding to its SSO identity, for an administrator. The next SSO
	 * sign-in with the account's address may bind it again, by the rules of
	 * {@link #addressMayDecide}.
	 */
	public void unlink(User user) {
		mongo.updateFirst(Query.query(Criteria.where("_id").is(user.getId())), new Update().unset(SSO_LINK),
				User.class);
		user.setSsoLink(null);
	}

	private Optional<User> boundAccount(SsoIdentity identity) {
		return users.findBySsoLink(identity.provider(), identity.issuer(), identity.subject());
	}

	private static SsoSignIn entered(User account) {
		return new SsoSignIn(account, false);
	}

	/** Lets [account] in with its profile brought up to date, or refuses it for its state. */
	private User admit(User account, SsoProfile profile, boolean syncProfile) {
		Optional<SignInRefusal> refusal = SignInRefusal.of(account);
		if (refusal.isPresent()) {
			throw new SignInRefusedException(refusal.get(), account);
		}
		return applyIdpProfile(account, profile, syncProfile);
	}

	/**
	 * Something that can be an address: a local part and a domain. A provider that sends none
	 * leaves its principal's name in the profile, and that would be taken for an address in Hinata,
	 * where a password sign-in looks the address up before the username.
	 */
	private static boolean isAddress(String email) {
		if (email == null) {
			return false;
		}
		int at = email.indexOf('@');
		return at > 0 && at < email.length() - 1 && email.chars().noneMatch(Character::isWhitespace);
	}

	/** What the person knows the provider as: its name on the sign-in screen. */
	private String providerName(SsoIdentity identity) {
		ServerSettings current = settings.get();
		return switch (identity.provider()) {
			case DynamicClientRegistrationRepository.OIDC_ID -> current.getOidc().getDisplayName();
			case DynamicClientRegistrationRepository.OAUTH2_ID -> current.getOauth2().getDisplayName();
			case DynamicRelyingPartyRegistrationRepository.SAML_ID -> current.getSaml().getDisplayName();
			default -> identity.provider().toUpperCase(Locale.ROOT);
		};
	}

	/**
	 * Re-applies the IdP profile to an existing SSO account; see the class comment. Writes the two
	 * fields alone: a save of the whole account as read at the start of the sign-in would undo
	 * whatever an administrator changed meanwhile, a deactivation included.
	 */
	private User applyIdpProfile(User user, SsoProfile profile, boolean syncProfile) {
		if (!user.isSso()) {
			return user;
		}
		Update changes = new Update();
		// A display name that is just the address was our fallback for an IdP that
		// sent no name — never something the user chose — so it is replaced even
		// when the admin has switched continuous syncing off.
		boolean placeholderName = user.getDisplayName() == null || user.getDisplayName().isBlank()
				|| user.getDisplayName().equalsIgnoreCase(user.getEmail());
		String displayName = trimToNull(profile.displayName());
		if (displayName != null && !displayName.equals(user.getDisplayName())
				&& (syncProfile || placeholderName)) {
			user.setDisplayName(displayName);
			changes.set("displayName", displayName);
		}
		String title = trimToNull(profile.title());
		if (title != null && !title.equals(user.getTitle())
				&& (syncProfile || user.getTitle() == null || user.getTitle().isBlank())) {
			user.setTitle(title);
			changes.set("title", title);
		}
		if (!changes.getUpdateObject().isEmpty()) {
			mongo.updateFirst(Query.query(Criteria.where("_id").is(user.getId())), changes.set("updatedAt", Instant.now()),
					User.class);
		}
		return user;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
