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
		String title,
		String role,
		String origin,
		String status,
		boolean twoFA,
		boolean sso,
		int sessions,
		Instant lastActive,
		Instant invitedAt,
		String invitedBy,
		Instant joinedAt) {
}
