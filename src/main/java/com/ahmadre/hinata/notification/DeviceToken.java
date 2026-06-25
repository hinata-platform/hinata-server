package com.ahmadre.hinata.notification;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A registered FCM device token for push delivery. One user can have many
 * (phone, tablet, desktop). The {@code token} is unique so a re-registration
 * from the same device upserts rather than duplicates.
 */
@Data
@Builder
@Document("device_tokens")
public class DeviceToken {

	@Id
	private String id;

	@Indexed
	private String userId;

	@Indexed(unique = true)
	private String token;

	/** "android" | "ios" | "macos" | "web" — informational. */
	private String platform;

	private Instant createdAt;

	private Instant lastSeenAt;
}
