package com.ahmadre.hinata.oauth;

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
 * A long-lived grant backing a refresh token — the durable link between a user
 * and an authorized client. The refresh token is stored only as a SHA-256 hash
 * (like a PAT), rotated on every use, and revocable. Access tokens are stateless
 * JWTs derived from this grant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("oauth_grants")
public class OAuthGrant {

	@Id
	private String id;

	@Indexed
	private String userId;
	private String clientId;

	@Builder.Default
	private Set<String> scopes = new HashSet<>();

	private String resource;

	/** SHA-256 hex of the current refresh token. Rotated on use. Never serialized. */
	@Indexed(unique = true)
	@JsonIgnore
	private String refreshTokenHash;

	private Instant createdAt;
	private Instant lastUsedAt;
	private Instant expiresAt;

	@Builder.Default
	private boolean revoked = false;

	public boolean isUsable(Instant now) {
		return !revoked && (expiresAt == null || now.isBefore(expiresAt));
	}
}
