package hn.asta.hivora.auth;

import hn.asta.hivora.common.ApiException;
import hn.asta.hivora.config.HivoraProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Persists failed logins per (identifier, ip) and blocks further attempts once
 * the configured threshold is reached. Backed by MongoDB so blocks survive
 * restarts and apply across instances.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

	private final MongoTemplate mongo;
	private final HivoraProperties properties;

	public void assertNotBlocked(String identifier, String ip) {
		// Block if EITHER the (identifier, ip) pair OR the account as a whole is
		// over the threshold. The account-wide counter (keyed on the identifier
		// only) makes IP rotation — e.g. via a spoofed X-Forwarded-For — useless
		// for brute forcing a single account (OWASP A07).
		if (isBlocked(accountKey(identifier)) || isBlocked(key(identifier, ip))) {
			throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
					"Too many failed attempts. Try again later.");
		}
	}

	public void recordFailure(String identifier, String ip) {
		bumpFailure(key(identifier, ip), identifier, ip);
		bumpFailure(accountKey(identifier), identifier, null);
	}

	public void recordSuccess(String identifier, String ip) {
		remove(key(identifier, ip));
		remove(accountKey(identifier));
	}

	private boolean isBlocked(String id) {
		LoginAttempt attempt = mongo.findById(id, LoginAttempt.class);
		return attempt != null && attempt.getBlockedUntil() != null
				&& attempt.getBlockedUntil().isAfter(Instant.now());
	}

	private void bumpFailure(String id, String identifier, String ip) {
		LoginAttempt attempt = mongo.findById(id, LoginAttempt.class);
		if (attempt == null) {
			attempt = LoginAttempt.builder().id(id).identifier(identifier).ip(ip).build();
		}
		attempt.setFailures(attempt.getFailures() + 1);
		attempt.setLastFailureAt(Instant.now());
		attempt.setExpiresAt(Instant.now().plus(Duration.ofDays(2)));
		HivoraProperties.RateLimit limits = properties.getRateLimit();
		if (attempt.getFailures() >= limits.getMaxLoginFailures()) {
			attempt.setBlockedUntil(Instant.now().plus(Duration.ofMinutes(limits.getLoginBlockMinutes())));
			log.warn("Login blocked key={} until={}", id, attempt.getBlockedUntil());
		}
		mongo.save(attempt);
	}

	private void remove(String id) {
		mongo.remove(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)),
				LoginAttempt.class);
	}

	private String key(String identifier, String ip) {
		return identifier.toLowerCase() + "|" + ip;
	}

	/** Account-wide key (no IP) so the block survives client IP rotation. */
	private String accountKey(String identifier) {
		return identifier.toLowerCase() + "|account";
	}
}
