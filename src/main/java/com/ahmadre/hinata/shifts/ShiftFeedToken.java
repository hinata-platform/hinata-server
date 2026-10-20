package com.ahmadre.hinata.shifts;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * The secret in a person's calendar feed address (HIN-47): {@code /api/v1/shifts/feed/{token}.ics}.
 *
 * <p>Only the SHA-256 of the token is stored, like a personal access token's; the token itself is
 * shown once, when it is made, and nowhere else (not in a later read, a log or the audit). One per
 * person: making a new one replaces the document, so the old address stops working at once, and
 * removing it deletes the document. There is no revoked state to keep, because a deleted hash cannot
 * be presented any more.
 *
 * <p>The field names {@code userId}, {@code createdAt} and {@code lastUsedAt} are read by name in
 * {@link ShiftPersonalData}; {@link ShiftErasure} deletes the document with the account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("shift_feed_tokens")
// One feed per person: the unique index is what makes "a second token for the same person"
// impossible even for two requests at once, and it serves the account section and the erasure.
@CompoundIndex(name = "user", def = "{'userId': 1}", unique = true)
public class ShiftFeedToken {

	@Id
	private String id;

	private String userId;

	/** SHA-256 hex of the token. The feed request looks the token up by it. */
	@Indexed(unique = true)
	private String tokenHash;

	private Instant createdAt;

	/** When a calendar last fetched the feed, written at most once an hour. Null until then. */
	private Instant lastUsedAt;
}
