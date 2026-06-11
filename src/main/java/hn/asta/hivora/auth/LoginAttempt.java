package hn.asta.hivora.auth;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Database-backed brute-force protection: failed logins are persisted so
 * blocks survive restarts and work across replicas (OWASP A07).
 */
@Data
@Builder
@Document("login_attempts")
public class LoginAttempt {

	/** Composite key "identifier|ip". */
	@Id
	private String id;

	private String identifier;

	private String ip;

	@Builder.Default
	private int failures = 0;

	private Instant lastFailureAt;

	private Instant blockedUntil;

	/** TTL cleanup: Mongo removes stale entries automatically. */
	@Indexed(expireAfter = "P2D")
	private Instant expiresAt;
}
