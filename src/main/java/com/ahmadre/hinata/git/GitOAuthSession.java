package com.ahmadre.hinata.git;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Short-lived server-side record of an in-flight OAuth Authorization-Code flow,
 * keyed by the unguessable {@code state} we hand to the provider. It carries the
 * flow from {@code oauthStart} (PENDING) through the public callback (AUTHORIZED,
 * with the exchanged provider token encrypted at rest) to the final
 * {@code connect} that moves the token onto the project and deletes the session.
 *
 * <p>The token never leaves the server ({@code @JsonIgnore} + encrypted), and the
 * document self-expires after {@link #TTL_SECONDS} so abandoned flows are cleaned
 * up automatically.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("git_oauth_sessions")
public class GitOAuthSession {

	/** Abandoned flows disappear after 15 minutes (Mongo TTL index). */
	public static final long TTL_SECONDS = 900;

	public enum Status { PENDING, AUTHORIZED, ERROR }

	/** The OAuth {@code state} value; also the document id. */
	@Id
	private String state;

	private String projectId;
	private String provider;
	private String userId;

	private Status status;

	/** AES-GCM-encrypted provider access token; set once the callback succeeds. */
	@JsonIgnore
	private String encryptedToken;

	/** Populated when the flow fails (provider error / user denied). */
	private String error;

	@Indexed(expireAfter = "PT15M")
	private Instant createdAt;
}
