package com.ahmadre.hinata.pat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A scoped, revocable Personal Access Token a user creates to let an AI client
 * (Claude, Cursor, …) authenticate to the MCP endpoint on their behalf.
 *
 * <p>Security: only the SHA-256 hash of the token is stored — the plaintext is
 * shown to the user exactly once at creation and never persisted or logged. The
 * hash is {@code @JsonIgnore} and lookups happen by hash, so a database read can
 * never recover a usable token.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("personal_access_tokens")
public class PersonalAccessToken {

	@Id
	private String id;

	@Indexed
	private String userId;

	/** Human label chosen by the user, e.g. "Claude Desktop". */
	private String name;

	/** SHA-256 hex of the plaintext token. The plaintext is never stored. */
	@Indexed(unique = true)
	@JsonIgnore
	private String tokenHash;

	/** Leading characters of the plaintext, shown in the UI to identify the token. */
	private String tokenPrefix;

	@Builder.Default
	private Set<String> scopes = new HashSet<>();

	private Instant createdAt;
	private Instant lastUsedAt;

	/** Absolute expiry; {@code null} ⇒ never expires. */
	private Instant expiresAt;

	@Builder.Default
	private boolean revoked = false;

	public boolean isExpired(Instant now) {
		return expiresAt != null && now.isAfter(expiresAt);
	}

	/** Usable = not revoked and not past its expiry. */
	public boolean isUsable(Instant now) {
		return !revoked && !isExpired(now);
	}
}
