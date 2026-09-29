package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.RelativeDate;
import com.ahmadre.hinata.project.ProjectTemplatePolicy;
import com.ahmadre.hinata.user.OrgAdmins;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The organisation's settings, for its organisation admins: working time,
 * approvals, absences, billing (the {@code timeTracking} block) and how new
 * relative deadlines count by default.
 *
 * <p>Deliberately not under {@code /api/v1/admin}: an organisation admin has no
 * business in the admin area, and an administrator has none here. The two roles
 * are separate on purpose — running the platform and running the organisation's
 * legal side are different jobs, often for different people.
 *
 * <p>The write goes through the same module hooks as the admin area's: guards
 * refuse what would break a module's rules, audits describe what moved, and the
 * reopened lock spans stay the route's own.
 */
@Tag(name = "Organisation")
@RestController
@RequestMapping("/api/v1/org/settings")
@RequiredArgsConstructor
public class OrgSettingsController {

	private final SettingsService settings;
	private final CurrentUser currentUser;
	private final AuditService audit;
	private final ProjectTemplatePolicy templates;
	private final List<SettingsPrefill> modulePrefills;
	private final SettingsWrites writes;

	/**
	 * What the organisation page shows. {@code defaultDeadlineBasis} is the stored
	 * choice (null ⇒ the environment decides), {@code effectiveDeadlineBasis} what
	 * that works out to.
	 */
	public record OrgSettings(ServerSettings.TimeTracking timeTracking,
			RelativeDate.Basis defaultDeadlineBasis, RelativeDate.Basis effectiveDeadlineBasis,
			/** The stored switches for the organisation's audit records; absent means the action's default. */
			java.util.Map<String, Boolean> auditEvents) {
	}

	/**
	 * A partial write: an absent {@code timeTracking} keeps the stored block, an
	 * absent basis keeps the stored basis, and {@code clearDefaultDeadlineBasis}
	 * hands it back to the environment.
	 */
	public record OrgSettingsUpdate(@jakarta.validation.Valid ServerSettings.TimeTracking timeTracking,
			RelativeDate.Basis defaultDeadlineBasis, Boolean clearDefaultDeadlineBasis,
			/** Switches for the organisation's audit records; other keys are ignored. Absent keeps them. */
			java.util.Map<String, Boolean> auditEvents) {

		/** The shape before the audit switches, for callers that do not touch them. */
		public OrgSettingsUpdate(ServerSettings.TimeTracking timeTracking, RelativeDate.Basis defaultDeadlineBasis,
				Boolean clearDefaultDeadlineBasis) {
			this(timeTracking, defaultDeadlineBasis, clearDefaultDeadlineBasis, null);
		}
	}

	@GetMapping
	public OrgSettings get() {
		OrgAdmins.require(currentUser);
		return view(settings.get());
	}

	@PutMapping
	public OrgSettings update(@jakarta.validation.Valid @RequestBody OrgSettingsUpdate request) {
		User actor = OrgAdmins.require(currentUser);
		ServerSettings current = settings.get();
		ServerSettings updated = settings.get();
		if (request.timeTracking() != null) {
			updated.setTimeTracking(request.timeTracking());
			writes.keepLockExceptions(updated, current);
		}
		if (Boolean.TRUE.equals(request.clearDefaultDeadlineBasis()) || request.defaultDeadlineBasis() != null) {
			ServerSettings.ProjectTemplates block = updated.getProjectTemplates() != null
					? updated.getProjectTemplates() : new ServerSettings.ProjectTemplates();
			block.setDefaultBasis(Boolean.TRUE.equals(request.clearDefaultDeadlineBasis())
					? null : request.defaultDeadlineBasis());
			updated.setProjectTemplates(block);
		}
		if (request.auditEvents() != null) {
			writes.carryOrganisationalAuditEvents(updated, request.auditEvents());
		}
		writes.check(current, updated);
		audit.event(AuditAction.SETTINGS_CHANGED).actor(actor).meta("area", "organisation").log();
		writes.recordModules(current, updated, actor);
		return view(settings.save(updated));
	}

	private OrgSettings view(ServerSettings document) {
		// The modules fill their read-only "effective" views, as for the admin area.
		modulePrefills.forEach(prefill -> prefill.prefill(document));
		ServerSettings.ProjectTemplates block = document.getProjectTemplates();
		return new OrgSettings(document.getTimeTracking(),
				block == null ? null : block.getDefaultBasis(), templates.defaultBasis(),
				writes.organisationalAuditEvents(document));
	}

}
