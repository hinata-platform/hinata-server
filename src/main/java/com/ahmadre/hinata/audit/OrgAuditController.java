package com.ahmadre.hinata.audit;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.OrgAdmins;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * The organisation's part of the audit log, for its organisation admins: who
 * decided which timesheet, who reported sick when, who opened a locked month.
 * The same feed and filters as the admin area's, cut to those records.
 */
@Tag(name = "Organisation")
@RestController
@RequestMapping("/api/v1/org/audit")
@RequiredArgsConstructor
public class OrgAuditController {

	private final AuditFeed feed;
	private final CurrentUser currentUser;
	private final AbsenceRecordReaders absenceReaders;

	@Operation(summary = "The organisation's audit actions, for its per-event switches")
	@GetMapping("/event-types")
	public java.util.List<AuditFeed.EventTypeResponse> eventTypes() {
		OrgAdmins.require(currentUser);
		return java.util.Arrays.stream(AuditAction.values()).filter(AuditAction::organisational)
				.map(a -> new AuditFeed.EventTypeResponse(a.name(), a.category().name(),
						a.defaultSeverity().name(), a.defaultEnabled()))
				.toList();
	}

	@Operation(summary = "Paginated audit records about working time, timesheets and absences")
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
		User reader = OrgAdmins.require(currentUser);
		return feed.page(new AuditFeed.Filter(query, category, action, severity, outcome, actorId, from, to,
				page, perPage), AuditFeed.Scope.ORGANISATION, absenceReaders.reads(reader));
	}
}
