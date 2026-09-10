package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.ApprovalPeriodConsistent;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One project's time settings: budget, default billability, whether its
 * timesheets are approved and on what rhythm.
 *
 * <p>Members read; leads and administrators write. Reading is deliberately open
 * to the project — a budget is a fact about the work, and somebody logging
 * against it should be able to see what they are logging against — while every
 * change is a lead's decision and is audited as one.
 *
 * <p>A PUT with an empty body is how a project hands every question back to the
 * instance policy: the document is written with nulls rather than deleted, so
 * "explicitly default" and "never configured" stay one behaviour and the
 * {@code updatedBy} trail survives.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/time-settings")
@RequiredArgsConstructor
public class ProjectTimeSettingsController {

	private final ProjectTimeSettingsRepository store;
	private final ProjectService projects;
	private final ProjectReach reach;
	private final CurrentUser currentUser;
	private final AuditService audit;
	private final Clock clock;

	// --- DTOs ------------------------------------------------------------------

	public record ApprovalPeriodDto(TimePolicy.ApprovalPeriod type, DayOfWeek weekStartsOn,
			LocalDate anchorDate, Integer days) {

		static ApprovalPeriodDto from(ProjectTimeSettings.ApprovalPeriod period) {
			return period == null ? null : new ApprovalPeriodDto(period.getType(),
					period.getWeekStartsOn(), period.getAnchorDate(), period.getDays());
		}
	}

	public record AlertThresholdsDto(Integer budgetPercent, Integer estimatePercent) {

		static AlertThresholdsDto from(ProjectTimeSettings.AlertThresholds thresholds) {
			return thresholds == null ? null : new AlertThresholdsDto(
					thresholds.getBudgetPercent(), thresholds.getEstimatePercent());
		}
	}

	/**
	 * What the project has decided. Every field may be null, and null means "the
	 * instance policy decides" — the same three-state shape the admin area's
	 * policies have, for the same reason: an override has to be removable.
	 */
	public record ProjectTimeSettingsResponse(String projectId, Integer budgetMinutes,
			Boolean defaultBillable, Boolean approvalRequired, ApprovalPeriodDto approvalPeriod,
			LocalDate lockBefore, AlertThresholdsDto alertThresholds,
			java.time.Instant updatedAt, String updatedBy) {

		static ProjectTimeSettingsResponse of(String projectId, ProjectTimeSettings settings) {
			if (settings == null) {
				return new ProjectTimeSettingsResponse(projectId, null, null, null, null, null,
						null, null, null);
			}
			return new ProjectTimeSettingsResponse(projectId, settings.getBudgetMinutes(),
					settings.getDefaultBillable(), settings.getApprovalRequired(),
					ApprovalPeriodDto.from(settings.getApprovalPeriod()),
					settings.getLockBefore(),
					AlertThresholdsDto.from(settings.getAlertThresholds()),
					settings.getUpdatedAt(), settings.getUpdatedBy());
		}
	}

	/**
	 * The whole block, replaced. A PUT rather than a PATCH because the form saves
	 * every field at once and "absent" has to keep meaning "no override" — with a
	 * PATCH there would be no way to remove one.
	 */
	@Data
	@ApprovalPeriodConsistent
	public static final class ApprovalPeriodRequest implements ApprovalPeriodConsistent.Period {
		private TimePolicy.ApprovalPeriod type;
		private DayOfWeek weekStartsOn;
		private LocalDate anchorDate;
		@Min(value = 1, message = "error.timeTracking.approvalPeriodInvalid")
		@Max(value = 366, message = "error.timeTracking.approvalPeriodInvalid")
		private Integer days;
	}

	@Data
	public static final class AlertThresholdsRequest {
		@Min(value = 1, message = "error.time.alertThresholdInvalid")
		@Max(value = 1000, message = "error.time.alertThresholdInvalid")
		private Integer budgetPercent;
		@Min(value = 1, message = "error.time.alertThresholdInvalid")
		@Max(value = 1000, message = "error.time.alertThresholdInvalid")
		private Integer estimatePercent;
	}

	@Data
	public static final class ProjectTimeSettingsRequest {
		@Min(value = 0, message = "error.time.budgetInvalid")
		@Max(value = ProjectTimeSettings.MAX_BUDGET_MINUTES, message = "error.time.budgetInvalid")
		private Integer budgetMinutes;
		private Boolean defaultBillable;
		private Boolean approvalRequired;
		@Valid
		private ApprovalPeriodRequest approvalPeriod;
		/**
		 * A freeze for this project alone; null ⇒ the instance lock date.
		 *
		 * <p>Never in the future, for the reason the instance-wide one is not:
		 * a freeze that reaches into the present blocks the recording of working
		 * time that is happening now (§ 16 Abs. 2 ArbZG, EuGH C-55/18). And it only
		 * ever closes <em>more</em> than the instance — {@code TimeLocks} takes the
		 * later of the two — because a project lead must not be able to reopen the
		 * month an administrator archived.
		 */
		private LocalDate lockBefore;
		@Valid
		private AlertThresholdsRequest alertThresholds;
	}

	// --- routes ------------------------------------------------------------------

	@GetMapping
	public ProjectTimeSettingsResponse get(@PathVariable String projectId) {
		User user = currentUser.require();
		Project project = projects.get(projectId);
		if (!reach.canSee(project, user)) {
			throw ApiException.forbidden("error.project.notMember");
		}
		return ProjectTimeSettingsResponse.of(project.getId(),
				store.findByProjectId(project.getId()).orElse(null));
	}

	@PutMapping
	public ProjectTimeSettingsResponse put(@PathVariable String projectId,
			@RequestBody @Valid ProjectTimeSettingsRequest request) {
		User user = currentUser.require();
		Project project = projects.get(projectId);
		projects.assertLeadOrAdmin(project, user);
		// Against the injected clock rather than a @PastOrPresent, for two reasons:
		// the annotation reads the JVM's default zone and default clock, and it
		// would reach the client as a field error under the generic "validation
		// failed" sentence. This is a rule whose consequence has to be said in
		// words — see TimeTrackingSettingsGuard.
		if (request.getLockBefore() != null
				&& request.getLockBefore().isAfter(LocalDate.now(clock))) {
			throw ApiException.badRequest("error.time.lockDateInFuture");
		}
		ProjectTimeSettings before = store.findByProjectId(project.getId()).orElse(null);
		ProjectTimeSettings settings = before != null ? before
				: ProjectTimeSettings.builder().projectId(project.getId()).build();
		settings.setBudgetMinutes(request.getBudgetMinutes());
		settings.setDefaultBillable(request.getDefaultBillable());
		settings.setApprovalRequired(request.getApprovalRequired());
		settings.setApprovalPeriod(periodOf(request.getApprovalPeriod()));
		settings.setLockBefore(request.getLockBefore());
		settings.setAlertThresholds(thresholdsOf(request.getAlertThresholds()));
		settings.setUpdatedAt(clock.instant());
		settings.setUpdatedBy(user.getId());
		ProjectTimeSettings saved = store.save(settings);
		audit.event(AuditAction.TIME_PROJECT_SETTINGS_CHANGED).actor(user)
				.target(project.getId(), project.getName())
				.meta("project", project.getId())
				.meta("budgetMinutes", change(
						before == null ? null : before.getBudgetMinutes(), saved.getBudgetMinutes()))
				.meta("defaultBillable", change(
						before == null ? null : before.getDefaultBillable(), saved.getDefaultBillable()))
				.meta("approvalRequired", change(
						before == null ? null : before.getApprovalRequired(), saved.getApprovalRequired()))
				.meta("approvalPeriod", change(
						before == null ? null : periodOf(before.getApprovalPeriod()),
						periodOf(saved.getApprovalPeriod())))
				.meta("lockBefore", change(
						before == null ? null : before.getLockBefore(), saved.getLockBefore()))
				.meta("alertThresholds", change(
						before == null ? null : thresholdsOf(before.getAlertThresholds()),
						thresholdsOf(saved.getAlertThresholds())))
				.log();
		return ProjectTimeSettingsResponse.of(project.getId(), saved);
	}

	// --- helpers -------------------------------------------------------------------

	private static ProjectTimeSettings.ApprovalPeriod periodOf(ApprovalPeriodRequest request) {
		return request == null ? null : ProjectTimeSettings.ApprovalPeriod.builder()
				.type(request.getType())
				.weekStartsOn(request.getWeekStartsOn())
				.anchorDate(request.getAnchorDate())
				.days(request.getDays())
				.build();
	}

	private static ProjectTimeSettings.AlertThresholds thresholdsOf(AlertThresholdsRequest request) {
		return request == null ? null : ProjectTimeSettings.AlertThresholds.builder()
				.budgetPercent(request.getBudgetPercent())
				.estimatePercent(request.getEstimatePercent())
				.build();
	}

	/**
	 * The whole rhythm, not only its type: an override that moves the anchor date
	 * or the day a week starts on changes which days a submission covers, and a
	 * record that named only "BIWEEKLY" would say nothing happened.
	 */
	private static Object periodOf(ProjectTimeSettings.ApprovalPeriod period) {
		return period == null ? null
				: period.getType() + "/" + period.getWeekStartsOn() + "/"
						+ period.getAnchorDate() + "/" + period.getDays();
	}

	private static Object thresholdsOf(ProjectTimeSettings.AlertThresholds thresholds) {
		return thresholds == null ? null
				: thresholds.getBudgetPercent() + "%/" + thresholds.getEstimatePercent() + "%";
	}

	/**
	 * "old → new", or nothing at all when the field did not move.
	 *
	 * <p>An audit record of a save that lists every field, changed or not, is a
	 * record nobody reads twice. Returning null drops the key: {@code meta}
	 * ignores a null value.
	 */
	private static String change(Object before, Object after) {
		return Objects.equals(before, after) ? null : before + " → " + after;
	}
}
