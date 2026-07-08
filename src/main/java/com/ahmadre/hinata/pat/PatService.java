package com.ahmadre.hinata.pat;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Lifecycle for {@link PersonalAccessToken}s: creation (plaintext returned once),
 * verification (constant-shape hash lookup) and revocation.
 *
 * <p>Tokens are {@code hn_pat_} + 32 bytes of {@link SecureRandom} entropy,
 * Base64URL-encoded. Only the SHA-256 hash is stored; the plaintext is returned
 * to the caller a single time and never persisted or logged.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatService {

	/** Every token starts with this; used to route auth away from the JWT decoder. */
	public static final String TOKEN_PREFIX = "hn_pat_";

	private static final int TOKEN_BYTES = 32;
	private static final int MAX_NAME_LENGTH = 100;
	/** How much of the plaintext is retained (prefix only) for UI identification. */
	private static final int DISPLAY_PREFIX_LENGTH = TOKEN_PREFIX.length() + 6;

	private final PersonalAccessTokenRepository repository;
	private final HinataProperties properties;
	private final SecureRandom random = new SecureRandom();

	/** The plaintext token paired with its stored metadata (returned once). */
	public record CreatedPat(String plaintext, PersonalAccessToken token) {
	}

	public CreatedPat create(User user, String name, Set<String> scopes, Duration ttl) {
		String label = name == null ? "" : name.trim();
		if (label.isEmpty() || label.length() > MAX_NAME_LENGTH) {
			throw ApiException.badRequest("error.pat.nameInvalid");
		}
		Set<String> requested = scopes == null ? Set.of() : scopes;
		if (requested.isEmpty()) {
			throw ApiException.badRequest("error.pat.scopesEmpty");
		}
		for (String scope : requested) {
			if (!Scopes.isValid(scope)) {
				throw ApiException.badRequest("error.pat.scopeUnknown", scope);
			}
		}
		long active = repository.countByUserIdAndRevokedFalse(user.getId());
		if (active >= properties.getMcp().getMaxPatsPerUser()) {
			throw ApiException.badRequest("error.pat.limitReached");
		}

		byte[] buffer = new byte[TOKEN_BYTES];
		random.nextBytes(buffer);
		String plaintext = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);

		Instant now = Instant.now();
		Duration effectiveTtl = ttl != null ? ttl : defaultTtl();
		Instant expiresAt = (effectiveTtl != null && !effectiveTtl.isZero())
				? now.plus(effectiveTtl)
				: null;

		PersonalAccessToken token = PersonalAccessToken.builder()
				.userId(user.getId())
				.name(label)
				.tokenHash(sha256(plaintext))
				.tokenPrefix(plaintext.substring(0, Math.min(DISPLAY_PREFIX_LENGTH, plaintext.length())))
				.scopes(new LinkedHashSet<>(requested))
				.createdAt(now)
				.expiresAt(expiresAt)
				.revoked(false)
				.build();
		return new CreatedPat(plaintext, repository.save(token));
	}

	/**
	 * Resolves a presented bearer to its live token, or empty when it is not a
	 * PAT, is unknown, revoked or expired. Touches {@code lastUsedAt} best-effort.
	 */
	public Optional<PersonalAccessToken> verify(String presented) {
		if (presented == null || !presented.startsWith(TOKEN_PREFIX)) {
			return Optional.empty();
		}
		Instant now = Instant.now();
		return repository.findByTokenHash(sha256(presented))
				.filter(token -> token.isUsable(now))
				.map(this::touch);
	}

	public List<PersonalAccessToken> list(String userId) {
		return repository.findByUserIdOrderByCreatedAtDesc(userId);
	}

	public void revoke(String userId, String id) {
		PersonalAccessToken token = repository.findById(id)
				.filter(candidate -> candidate.getUserId().equals(userId))
				.orElseThrow(() -> ApiException.notFound("pat"));
		if (!token.isRevoked()) {
			token.setRevoked(true);
			repository.save(token);
		}
	}

	/**
	 * Permanently removes an owned token from storage. Unlike {@link #revoke},
	 * the row is deleted outright, so the token drops from the caller's list.
	 */
	public void deletePermanently(String userId, String id) {
		PersonalAccessToken token = repository.findById(id)
				.filter(candidate -> candidate.getUserId().equals(userId))
				.orElseThrow(() -> ApiException.notFound("pat"));
		repository.delete(token);
	}

	private PersonalAccessToken touch(PersonalAccessToken token) {
		try {
			token.setLastUsedAt(Instant.now());
			repository.save(token);
		}
		catch (Exception ex) {
			// Recording last-used must never fail an authenticated MCP request.
			log.warn("Failed to touch lastUsedAt for PAT {}: {}", token.getId(), ex.getMessage());
		}
		return token;
	}

	private Duration defaultTtl() {
		long days = properties.getMcp().getDefaultTokenTtlDays();
		return days <= 0 ? null : Duration.ofDays(days);
	}

	static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xf, 16));
				hex.append(Character.forDigit(b & 0xf, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required but unavailable", ex);
		}
	}
}
