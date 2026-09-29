package com.ahmadre.hinata.audit;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Read-only admin surface over the security audit log: a filtered, paginated
 * feed plus the catalogue of event types that drives the per-event toggles in
 * the admin settings. Writes happen only through {@link AuditService} at the
 * source of each action. Admin-gated by the {@code /api/v1/admin/**} rule.
 *
 * <p>Records about working time, approvals and absences are the organisation's
 * and appear here only for a reader who is also an organisation admin; records
 * naming project content come without their metadata. See {@link AuditFeed}.
 */
@Tag(name = "Admin · Audit")
@RestController
@RequestMapping("/api/v1/admin/audit")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AuditController {

	private final AuditFeed feed;
	private final CurrentUser currentUser;
	private final AbsenceRecordReaders absenceReaders;

	// --- Read ----------------------------------------------------------------

	@Operation(summary = "Paginated, filtered security audit log")
	@GetMapping
	public AuditFeed.AuditPageResponse list(
			@RequestParam(required = false) String query,
			@RequestParam(required = false) String category,
			@RequestParam(required = false) String action,
			@RequestParam(required = false) String severity,
			@RequestParam(required = false) String outcome,
			@RequestParam(required = false) String actorId,
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "30") int perPage) {
		// Read from the stored account: whether the reader also runs the organisation
		// decides whether its records are in this feed.
		com.ahmadre.hinata.user.User reader = currentUser.require();
		AuditFeed.Scope scope = reader.isOrgAdmin()
				? AuditFeed.Scope.PLATFORM_AND_ORGANISATION : AuditFeed.Scope.PLATFORM;
		return feed.page(new AuditFeed.Filter(query, category, action, severity, outcome, actorId, from, to,
				page, perPage), scope, absenceReaders.reads(reader));
	}

	@Operation(summary = "Catalogue of audit event types (for the per-event toggles)")
	@GetMapping("/event-types")
	public List<AuditFeed.EventTypeResponse> eventTypes() {
		// The organisation's actions have their switches on the Organisation page.
		return Arrays.stream(AuditAction.values()).filter(a -> !a.organisational())
				.map(a -> new AuditFeed.EventTypeResponse(a.name(), a.category().name(),
						a.defaultSeverity().name(), a.defaultEnabled()))
				.toList();
	}
}
