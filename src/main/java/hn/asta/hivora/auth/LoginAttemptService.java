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
		LoginAttempt attempt = mongo.findById(key(identifier, ip), LoginAttempt.class);
		if (attempt != null && attempt.getBlockedUntil() != null
				&& attempt.getBlockedUntil().isAfter(Instant.now())) {
			throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
					"Too many failed attempts. Try again later.");
		}
	}

	public void recordFailure(String identifier, String ip) {
		String id = key(identifier, ip);
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
			log.warn("Login blocked for identifier={} ip={} until={}", identifier, ip, attempt.getBlockedUntil());
		}
		mongo.save(attempt);
	}

	public void recordSuccess(String identifier, String ip) {
		mongo.remove(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("_id").is(key(identifier, ip))),
				LoginAttempt.class);
	}

	private String key(String identifier, String ip) {
		return identifier.toLowerCase() + "|" + ip;
	}
}
