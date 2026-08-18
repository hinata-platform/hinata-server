package com.ahmadre.hinata.user;

import com.ahmadre.hinata.common.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

	/**
	 * Baseline default password length (used by the demo seeder and mirrored by the
	 * {@code hinata.security.password-min-length} env default). NOT the enforcement
	 * value — {@link #validatePassword} reads the effective minimum from
	 * {@link com.ahmadre.hinata.auth.SecurityPolicy}, which an admin can raise.
	 */
	public static final int MIN_PASSWORD_LENGTH = 10;

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final MongoTemplate mongo;
	private final com.ahmadre.hinata.audit.AuditService audit;
	private final com.ahmadre.hinata.notification.NotificationService notifications;
	private final com.ahmadre.hinata.auth.SecurityPolicy securityPolicy;

	public User get(String id) {
		return users.findById(id).orElseThrow(() -> ApiException.notFound("user"));
	}

	/**
	 * Resolves an e-mail address to the account that owns it, for attributing content
	 * that arrives from outside an authenticated session (e-mail ingestion). Empty when
	 * the address belongs to nobody or to an account that cannot act on the platform
	 * (deactivated, still-pending invite, awaiting approval) — such an account would
	 * never see the attribution or receive the notifications, so attributing to it
	 * would only mislead.
	 *
	 * <p>An inbound {@code From} header is unauthenticated and trivially spoofable, so
	 * callers must treat the result as attribution only and never derive permission
	 * from it.
	 */
	public Optional<User> findActiveByEmail(String email) {
		if (email == null || email.isBlank()) {
			return Optional.empty();
		}
		return users.findByEmailIgnoreCase(email.trim()).filter(User::isActive);
	}

	/**
	 * Permanently removes a user and scrubs the references that would otherwise
	 * dangle: their in-app notifications are dropped, issues assigned to them are
	 * unassigned, they are removed from every watcher list and the change mails
	 * still queued for them are discarded. Historical author references (reporter,
	 * comment authors) are intentionally retained.
	 */
	public void delete(User user) {
		String id = user.getId();
		mongo.remove(new Query(Criteria.where("userId").is(id)), "notifications");
		// Remove the user from every assignee list and re-derive the primary
		// assignee for those issues (setAssigneeIds keeps assigneeId in sync).
		for (com.ahmadre.hinata.issue.Issue issue :
				mongo.find(new Query(Criteria.where("assigneeIds").is(id)),
						com.ahmadre.hinata.issue.Issue.class)) {
			java.util.List<String> remaining = new java.util.ArrayList<>(issue.getAssigneeIds());
			remaining.remove(id);
			issue.setAssigneeIds(remaining);
			mongo.save(issue);
		}
		// Clear the legacy single field for any un-migrated doc not covered above.
		mongo.updateMulti(new Query(Criteria.where("assigneeId").is(id)),
				new Update().unset("assigneeId"), "issues");
		mongo.updateMulti(new Query(Criteria.where("watcherIds").is(id)),
				new Update().pull("watcherIds", id), "issues");
		// The subscriptions are gone; the mails queued for them would otherwise
		// outlive the account by up to half an hour — and the rows themselves hold
		// the deleted user's id alongside field values that can name other users.
		mongo.remove(new Query(Criteria.where("userId").is(id)), "issue_mail_digests");
		users.delete(user);
		log.info("Deleted user {} ({}) and scrubbed dangling references", id, user.getUsername());
	}

	public User createLocal(String email, String username, String displayName, String rawPassword,
			Set<Role> roles) {
		validatePassword(rawPassword);
		if (users.existsByEmailIgnoreCase(email)) {
			throw ApiException.conflict("error.user.emailInUse");
		}
		if (users.existsByUsernameIgnoreCase(username)) {
			throw ApiException.conflict("error.user.usernameInUse");
		}
		return users.save(User.builder()
				.email(email.toLowerCase(Locale.ROOT))
				.username(username)
				.displayName(displayName)
				.passwordHash(passwordEncoder.encode(rawPassword))
				.roles(roles)
				.origin(User.Origin.LOCAL)
				.build());
	}

	/**
	 * Creates a self-registered LOCAL account from the public sign-up flow. The
	 * account is inactive and unverified until the user proves their email via the
	 * verification link; {@code active}/{@code emailVerified} are flipped by
	 * {@code RegistrationService} once verified (and, when required, approved).
	 */
	public User createSelfRegistered(String email, String username, String displayName,
			String rawPassword) {
		validatePassword(rawPassword);
		if (users.existsByEmailIgnoreCase(email)) {
			throw ApiException.conflict("error.user.emailInUse");
		}
		if (users.existsByUsernameIgnoreCase(username)) {
			throw ApiException.conflict("error.user.usernameInUse");
		}
		return users.save(User.builder()
				.email(email.toLowerCase(Locale.ROOT))
				.username(username)
				.displayName(displayName != null && !displayName.isBlank() ? displayName : username)
				.passwordHash(passwordEncoder.encode(rawPassword))
				.roles(new java.util.HashSet<>(Set.of(Role.MEMBER)))
				.origin(User.Origin.LOCAL)
				.active(false)
				.emailVerified(false)
				.awaitingApproval(false)
				.build());
	}

	/**
	 * Creates a still-pending, password-less LOCAL invite. The caller supplies the
	 * already-hashed one-time token and its expiry; the account stays inactive until
	 * the invitee accepts (see the public invite-accept flow).
	 */
	public User createInvited(String email, String displayName, Set<Role> roles, String invitedBy,
			String inviteTokenHash, Instant invitedAt, Instant inviteExpiresAt) {
		String normalized = email.toLowerCase(Locale.ROOT);
		return users.save(User.builder()
				.email(normalized)
				.username(uniqueUsernameFrom(normalized))
				.displayName(displayName)
				.roles(roles)
				.origin(User.Origin.LOCAL)
				.active(false)
				.emailVerified(false)
				.invitedAt(invitedAt)
				.invitedBy(invitedBy)
				.inviteTokenHash(inviteTokenHash)
				.inviteExpiresAt(inviteExpiresAt)
				.build());
	}

	/** Languages we ship email templates for; anything else keeps the default. */
	private static final Set<String> SUPPORTED_LOCALES = Set.of("en", "de");

	/**
	 * Find-or-create for accounts arriving via OIDC, SAML or LDAP. The profile is
	 * whatever {@link com.ahmadre.hinata.auth.sso.SsoProfileMapper} could resolve
	 * from the IdP's attributes.
	 *
	 * <p>On a later login the directory stays the source of truth while
	 * [syncProfile] is on: display name and position follow the IdP. Two rules
	 * hold regardless — an attribute the IdP does <em>not</em> send never clears a
	 * stored value, and only SSO-provisioned accounts are touched, so an IdP can
	 * never rewrite the profile of a local account that happens to share the
	 * address.
	 */
	public User provisionSso(com.ahmadre.hinata.auth.sso.SsoProfileMapper.SsoProfile profile,
			User.Origin origin, boolean syncProfile) {
		String email = profile.email();
		return users.findByEmailIgnoreCase(email)
				.map(existing -> applyIdpProfile(existing, profile, syncProfile))
				.orElseGet(() -> {
					// New SSO users inherit the browser language from the login redirect
					// (Accept-Language) so their emails are localized from the start.
					String displayName = profile.displayName();
					User.UserBuilder builder = User.builder()
							.email(email.toLowerCase(Locale.ROOT))
							.username(uniqueUsernameFrom(email))
							.displayName(displayName != null && !displayName.isBlank() ? displayName : email)
							.title(trimToNull(profile.title()))
							.roles(Set.of(Role.MEMBER))
							.origin(origin);
					String lang = LocaleContextHolder.getLocale().getLanguage();
					if (SUPPORTED_LOCALES.contains(lang)) builder.locale(lang);
					return users.save(builder.build());
				});
	}

	/** Re-applies the IdP profile to an existing SSO account; see {@link #provisionSso}. */
	private User applyIdpProfile(User user, com.ahmadre.hinata.auth.sso.SsoProfileMapper.SsoProfile profile,
			boolean syncProfile) {
		if (!user.isSso()) {
			return user;
		}
		boolean changed = false;
		// A display name that is just the address was our fallback for an IdP that
		// sent no name — never something the user chose — so it is replaced even
		// when the admin has switched continuous syncing off.
		boolean placeholderName = user.getDisplayName() == null || user.getDisplayName().isBlank()
				|| user.getDisplayName().equalsIgnoreCase(user.getEmail());
		String displayName = trimToNull(profile.displayName());
		if (displayName != null && !displayName.equals(user.getDisplayName())
				&& (syncProfile || placeholderName)) {
			user.setDisplayName(displayName);
			changed = true;
		}
		String title = trimToNull(profile.title());
		if (title != null && !title.equals(user.getTitle())
				&& (syncProfile || user.getTitle() == null || user.getTitle().isBlank())) {
			user.setTitle(title);
			changed = true;
		}
		return changed ? users.save(user) : user;
	}

	private String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	public void changePassword(User user, String currentPassword, String newPassword) {
		if (user.getPasswordHash() == null
				|| !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			throw ApiException.badRequest("error.user.currentPasswordIncorrect");
		}
		validatePassword(newPassword);
		user.setPasswordHash(passwordEncoder.encode(newPassword));
		users.save(user);
		audit.event(com.ahmadre.hinata.audit.AuditAction.PASSWORD_CHANGED).actor(user).log();
		boolean de = "de".equalsIgnoreCase(user.getLocale());
		notifications.notifySecurityAlert(user,
				de ? "Passwort geändert" : "Password changed",
				de ? "Das Passwort deines Kontos wurde geändert. Warst du das nicht, ändere es sofort."
						: "Your account password was changed. If this wasn't you, change it immediately.");
	}

	/**
	 * Validates a raw password against the <em>effective</em> minimum length, which
	 * an admin can raise at runtime via the Security panel ({@link
	 * com.ahmadre.hinata.auth.SecurityPolicy} resolves DB-over-env). Never uses a
	 * hardcoded constant, so this is the single enforcement point for the policy.
	 */
	public void validatePassword(String rawPassword) {
		int min = securityPolicy.passwordMinLength();
		if (rawPassword == null || rawPassword.length() < min) {
			throw ApiException.badRequest("error.user.passwordTooShort", min);
		}
	}

	private String uniqueUsernameFrom(String email) {
		String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9._-]", "");
		String candidate = base;
		int suffix = 1;
		while (users.existsByUsernameIgnoreCase(candidate)) {
			candidate = base + suffix++;
		}
		return candidate;
	}
}
