package com.ahmadre.hinata.user;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import com.ahmadre.hinata.me.NotificationPreferences;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@Builder
@Document("users")
public class User {

	public enum Origin { LOCAL, OIDC, SAML, LDAP }

	@Id
	private String id;

	@Indexed(unique = true)
	private String email;

	@Indexed(unique = true)
	@TextIndexed(weight = 5)
	private String username;

	@TextIndexed(weight = 10)
	private String displayName;

	/** BCrypt hash; null for SSO-provisioned accounts. Never serialized. */
	@JsonIgnore
	private String passwordHash;

	@Builder.Default
	private Set<Role> roles = new HashSet<>(Set.of(Role.MEMBER));

	@Builder.Default
	private Origin origin = Origin.LOCAL;

	private String avatarUrl;

	/** Job title shown e.g. in the dashboard performance ranking. */
	@TextIndexed(weight = 3)
	private String title;

	@Builder.Default
	private String locale = "en";

	@Builder.Default
	private boolean active = true;

	@CreatedDate
	private Instant createdAt;

	@LastModifiedDate
	private Instant updatedAt;

	// --- Self-service account state (/me surface) ----------------------------

	/** Whether {@link #email} has been proven (double-opt-in). */
	@Builder.Default
	private boolean emailVerified = true;

	/** Set while a change to {@link #email} awaits confirmation at the new address. */
	private String pendingEmail;

	/** BCrypt hash of the pending email-change token; never serialized. */
	@JsonIgnore
	private String emailChangeTokenHash;

	private Instant emailChangeExpiresAt;

	/** BCrypt hash of the one-time password-reset token; never serialized. */
	@JsonIgnore
	private String passwordResetTokenHash;

	private Instant passwordResetExpiresAt;

	private Instant passwordChangedAt;

	// --- Admin invite lifecycle ----------------------------------------------

	/** When an admin invited this (still-pending) local account; null once joined. */
	private Instant invitedAt;

	/** userId of the admin who issued the latest invitation. */
	private String invitedBy;

	/** First accepted invite / first successful sign-in. Backfilled to {@link #createdAt}. */
	private Instant joinedAt;

	/** BCrypt hash of the one-time invite token; never serialized. */
	@JsonIgnore
	private String inviteTokenHash;

	private Instant inviteExpiresAt;

	// --- Two-factor (TOTP) ---------------------------------------------------

	@Builder.Default
	private boolean totpEnabled = false;

	/** Active Base32 TOTP secret; never serialized. Encrypt at rest in prod. */
	@JsonIgnore
	private String totpSecret;

	/** Base32 secret held during enrolment until the first code verifies. */
	@JsonIgnore
	private String totpPendingSecret;

	/** BCrypt hashes of the unused recovery codes; never serialized. */
	@JsonIgnore
	@Builder.Default
	private List<String> recoveryCodeHashes = new ArrayList<>();

	private Instant totpEnabledAt;

	// --- Notification preferences (matrix + master channel switches) ---------

	@Builder.Default
	private NotificationPreferences notificationPreferences = NotificationPreferences.defaults();

	public boolean isAdmin() {
		return roles != null && roles.contains(Role.ADMIN);
	}

	public boolean isSso() {
		return origin != null && origin != Origin.LOCAL;
	}

	/** A still-pending invitation: created by an admin but never accepted. */
	public boolean isInvitePending() {
		return invitedAt != null && joinedAt == null;
	}

	public int recoveryCodesRemaining() {
		return recoveryCodeHashes == null ? 0 : recoveryCodeHashes.size();
	}
}
