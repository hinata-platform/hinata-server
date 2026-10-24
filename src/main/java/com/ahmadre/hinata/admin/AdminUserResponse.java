package com.ahmadre.hinata.admin;

import java.time.Instant;

/**
 * Admin-board projection of a platform user. Deliberately a hand-rolled record
 * (not the raw {@link com.ahmadre.hinata.user.User} entity) so password/2FA
 * secrets never leave the server and the lifecycle {@code status} is derived in
 * one place. {@code role} is the single effective platform role (ADMIN | USER);
 * {@code status} is ACTIVE | DISABLED | INVITED.
 */
public record AdminUserResponse(
		String id,
		String name,
		String username,
		String email,
		String avatarUrl,
		String title,
		String pronouns,
		String role,
		String origin,
		String status,
		boolean twoFA,
		boolean sso,
		int sessions,
		Instant lastActive,
		Instant invitedAt,
		String invitedBy,
		Instant joinedAt,
		/** Whether the person runs the organisation's time, absence and billing side. */
		boolean orgAdmin,
		/**
		 * Whether the account is bound to an identity at an SSO provider (HIN-179), which an
		 * administrator can undo when the person's key there changed.
		 */
		boolean ssoLinked,
		/** Whether the person reviews reported content and decides on it (HIN-26). */
		boolean moderator,
		/**
		 * Whether moderation suspended the account (HIN-172). {@code status} stays DISABLED for it,
		 * because the published app reads a status it does not know as active.
		 */
		boolean suspended,
		/** When the suspension ends by itself; null until further notice, or without one. */
		Instant suspendedUntil) {
}
