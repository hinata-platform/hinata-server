package com.ahmadre.hinata.me;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A signed-in device/session. Refresh tokens carry this record's id as their
 * {@code sid} claim; revoking the record (sign-out) invalidates the refresh
 * token, so the device cannot mint new access tokens once its short-lived
 * access token expires. The list backs the account screen's "Sessions" section.
 */
@Data
@Builder
@Document("sessions")
// The account screen reads this collection one page at a time, newest first.
// On {userId} alone that is an index scan followed by a blocking in-memory
// sort of every session the account has ever opened, to return four rows —
// the exact cost paginating it was meant to remove. With lastActiveAt in the
// index the sort disappears and only the page is examined. The compound index
// is prefixed by userId, so it also serves every lookup and delete that used
// the single-field one.
@CompoundIndex(name = "user_lastActive", def = "{'userId': 1, 'lastActiveAt': -1}")
public class RefreshSession {

	public enum Kind { desktop, phone, tablet }

	@Id
	private String id;

	private String userId;

	@Builder.Default
	private Kind kind = Kind.desktop;

	/** "macOS 15", "iOS 18.2", "Windows 11" … */
	private String os;

	/** "Chrome 126", "hinata iOS 2.4" … */
	private String client;

	/** "Web" | "Mobile". */
	private String app;

	/** Coarse geo, best-effort ("Frankfurt, DE"); may be null. */
	private String location;

	/** Masked source IP ("85.214.xx.xx"). */
	private String ipMasked;

	private Instant createdAt;

	private Instant lastActiveAt;

	private Instant expiresAt;
}
