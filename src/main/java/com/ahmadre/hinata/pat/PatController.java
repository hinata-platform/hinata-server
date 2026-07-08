package com.ahmadre.hinata.pat;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Self-service management of the caller's Personal Access Tokens (used to
 * authenticate AI clients to the MCP endpoint). Lives under the authenticated
 * {@code /me} namespace. The plaintext token is returned exactly once, on
 * creation; it is never retrievable afterwards.
 */
@RestController
@RequestMapping("/api/v1/me/pats")
@RequiredArgsConstructor
public class PatController {

	private final PatService patService;
	private final CurrentUser currentUser;
	private final AuditService audit;

	/** Safe projection of a token — never carries the hash or the plaintext. */
	public record PatView(String id, String name, String prefix, Set<String> scopes,
			Instant createdAt, Instant lastUsedAt, Instant expiresAt, boolean revoked) {

		static PatView of(PersonalAccessToken token) {
			return new PatView(token.getId(), token.getName(), token.getTokenPrefix(),
					token.getScopes(), token.getCreatedAt(), token.getLastUsedAt(),
					token.getExpiresAt(), token.isRevoked());
		}
	}

	public record CreatePatRequest(
			@NotBlank @Size(max = 100) String name,
			Set<String> scopes,
			/** Lifetime in days; null ⇒ server default, ≤0 ⇒ never expires. */
			Long ttlDays) {
	}

	/** The one and only response that contains the plaintext token. */
	public record CreatedPatView(String token, PatView meta) {
	}

	@GetMapping
	public List<PatView> list() {
		User me = currentUser.require();
		return patService.list(me.getId()).stream().map(PatView::of).toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public CreatedPatView create(@Valid @RequestBody CreatePatRequest request) {
		User me = currentUser.require();
		Duration ttl = request.ttlDays() == null
				? null
				: (request.ttlDays() <= 0 ? Duration.ZERO : Duration.ofDays(request.ttlDays()));
		PatService.CreatedPat created = patService.create(me, request.name(), request.scopes(), ttl);
		audit.event(AuditAction.PAT_CREATED).actor(me)
				.meta("name", created.token().getName())
				.meta("scopes", String.join(",", created.token().getScopes()))
				.log();
		return new CreatedPatView(created.plaintext(), PatView.of(created.token()));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void revoke(@PathVariable String id) {
		User me = currentUser.require();
		patService.revoke(me.getId(), id);
		audit.event(AuditAction.PAT_REVOKED).actor(me).meta("id", id).log();
	}

	/** Permanently removes a token, dropping it from the caller's list entirely. */
	@DeleteMapping("/{id}/permanent")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		User me = currentUser.require();
		patService.deletePermanently(me.getId(), id);
		audit.event(AuditAction.PAT_DELETED).actor(me).meta("id", id).log();
	}
}
