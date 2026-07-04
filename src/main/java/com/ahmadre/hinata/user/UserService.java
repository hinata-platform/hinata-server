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
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

	public static final int MIN_PASSWORD_LENGTH = 10;

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final MongoTemplate mongo;
	private final com.ahmadre.hinata.audit.AuditService audit;

	public User get(String id) {
		return users.findById(id).orElseThrow(() -> ApiException.notFound("user"));
	}

	/**
	 * Permanently removes a user and scrubs the references that would otherwise
	 * dangle: their in-app notifications are dropped, issues assigned to them are
	 * unassigned, and they are removed from every watcher list. Historical author
	 * references (reporter, comment authors) are intentionally retained.
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

	/** Find-or-create for accounts arriving via OIDC, SAML or LDAP. */
	public User provisionSso(String email, String displayName, User.Origin origin) {
		return users.findByEmailIgnoreCase(email).orElseGet(() -> {
			// New SSO users inherit the browser language from the login redirect
			// (Accept-Language) so their emails are localized from the start.
			User.UserBuilder builder = User.builder()
					.email(email.toLowerCase(Locale.ROOT))
					.username(uniqueUsernameFrom(email))
					.displayName(displayName != null && !displayName.isBlank() ? displayName : email)
					.roles(Set.of(Role.MEMBER))
					.origin(origin);
			String lang = LocaleContextHolder.getLocale().getLanguage();
			if (SUPPORTED_LOCALES.contains(lang)) builder.locale(lang);
			return users.save(builder.build());
		});
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
	}

	public void validatePassword(String rawPassword) {
		if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
			throw ApiException.badRequest(
					"error.user.passwordTooShort", MIN_PASSWORD_LENGTH);
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
