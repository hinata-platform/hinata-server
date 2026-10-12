package com.ahmadre.hinata.notification;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A registered device token. One user can have many (phone, tablet, desktop).
 * The {@code token} is unique so a re-registration from the same device
 * upserts rather than duplicates.
 *
 * <p>Two kinds share the collection. A {@link Kind#PUSH} token is the device's
 * FCM token or WNS channel and receives notifications. A
 * {@link Kind#LIVE_ACTIVITY_UPDATE} token (HIN-103) is the APNs token of one
 * Live Activity on one iPhone: it receives only the encrypted state of the
 * running timer, never a notification, and lives as long as the activity.
 *
 * <p>Every field added since the first version is an object type, so a missing
 * field reads as null; {@code kind} has a getter default. Spring Data builds
 * this document through its all-args constructor, and a primitive field missing
 * from a stored document would make every token written before the field
 * existed unreadable.
 */
@Data
@Builder
@Document("device_tokens")
@CompoundIndex(name = "user_kind", def = "{'userId': 1, 'kind': 1}")
public class DeviceToken {

	/** What a token is for. Stored by name; absent on documents older than HIN-103. */
	public enum Kind {
		PUSH, LIVE_ACTIVITY_UPDATE
	}

	@Id
	private String id;

	/** Found through the {@code user_kind} index, which starts with this field. */
	private String userId;

	@Indexed(unique = true)
	private String token;

	/** "android" | "ios" | "macos" | "web" | "windows" — informational. */
	private String platform;

	/** Null on every document written before HIN-103; read through {@link #getKind()}. */
	private Kind kind;

	/**
	 * The app's own opaque reference of the activity a {@link Kind#LIVE_ACTIVITY_UPDATE}
	 * token belongs to. Names nothing on the server; it binds the encryption.
	 */
	private String activityId;

	/**
	 * The device's FCM token, which FCM needs beside the activity token to route
	 * a Live Activity update. Only on {@link Kind#LIVE_ACTIVITY_UPDATE}.
	 */
	private String fcmToken;

	/**
	 * The activity's 256-bit content key, encrypted at rest with
	 * {@link LiveActivityKeys}. Only on {@link Kind#LIVE_ACTIVITY_UPDATE}.
	 */
	private String contentKey;

	private Instant createdAt;

	private Instant lastSeenAt;

	/**
	 * When the database removes the token by itself. Set only on Live Activity
	 * tokens; a document without it never expires, which is what push tokens
	 * need.
	 */
	@Indexed(expireAfter = "0s")
	private Instant expiresAt;

	/** Never null: a token registered before kinds existed is a push token. */
	public Kind getKind() {
		return kind == null ? Kind.PUSH : kind;
	}

	public boolean isLiveActivity() {
		return getKind() == Kind.LIVE_ACTIVITY_UPDATE;
	}
}
