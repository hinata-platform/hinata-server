package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApprovalPeriodConsistent;
import com.ahmadre.hinata.common.TimePolicy;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What one project decides for itself about time.
 *
 * <p>A collection of its own rather than fields on {@code Project}. Two reasons,
 * and both are the reason the module is a module: a project document is read on
 * every board, every issue page and every search result, and it must not grow a
 * block that only exists while {@code advanced_time_tracking} is on; and
 * switching the module off has to be able to leave <em>nothing</em> behind in
 * the core collections. Deleting a project takes its row with it
 * ({@code DeletionService}).
 *
 * <p>Every field is nullable, and null is an answer: "whatever the instance
 * policy says". A project that has never been configured has no document at all,
 * which is the same answer more cheaply. That is why the resolution of a
 * per-project override against the instance policy belongs to the reader — the
 * approval periods in HIN-88, the budget alerts in stage 11 — and not to a value
 * baked in here.
 */
@Data
@Builder(toBuilder = true)
@Document("project_time_settings")
public class ProjectTimeSettings {

	/** A ceiling on a budget: a thousand years of working days, in minutes. */
	public static final int MAX_BUDGET_MINUTES = 100_000_000;

	@Id
	private String id;

	/** The project this belongs to; one document per project. */
	@Indexed(unique = true)
	private String projectId;

	/** How much time the project is expected to take in total; null for no budget. */
	private Integer budgetMinutes;

	/** What {@code billable} becomes for entries filed here; null ⇒ the instance policy. */
	private Boolean defaultBillable;

	/** Whether timesheets covering this project need approval; null ⇒ the instance policy. */
	private Boolean approvalRequired;

	/**
	 * A submission rhythm just for this project; null ⇒ the instance policy.
	 *
	 * <p>The field, its validation and its editor are this stage's; what a period
	 * <em>covers</em> — which days a "biweekly from the 3rd" run is made of — is
	 * arithmetic that lives once, in HIN-88, and nowhere else.
	 */
	private ApprovalPeriod approvalPeriod;

	/**
	 * A freeze just for this project; null ⇒ the instance lock date.
	 *
	 * <p>Projects close at different times — one customer is invoiced and done
	 * while another runs on — and approvals already run per project, so the freeze
	 * that goes with them does too. Resolution is "the later of the two": a
	 * project override never <em>unfreezes</em> a day the instance closed, because
	 * a lead must not be able to reopen the month an administrator archived.
	 */
	private LocalDate lockBefore;

	/** When a lead is warned that the project is running out of budget (stage 11). */
	private AlertThresholds alertThresholds;

	@CreatedDate
	private Instant createdAt;

	private Instant updatedAt;

	/** Who last changed it — a lead or an administrator. */
	private String updatedBy;

	/**
	 * The per-project rhythm, shaped exactly like the instance-wide one so the two
	 * can be resolved field by field without a translation step in between.
	 */
	@Data
	@Builder(toBuilder = true)
	@ApprovalPeriodConsistent
	public static class ApprovalPeriod implements ApprovalPeriodConsistent.Period {
		private TimePolicy.ApprovalPeriod type;
		private DayOfWeek weekStartsOn;
		private LocalDate anchorDate;
		private Integer days;
	}

	/** Percentages of the budget, or of an issue's estimate, at which a lead hears about it. */
	@Data
	@Builder(toBuilder = true)
	public static class AlertThresholds {
		/** Percent of {@link #budgetMinutes} that triggers a warning; null ⇒ none. */
		private Integer budgetPercent;
		/** Percent of an issue's original estimate that triggers a warning; null ⇒ none. */
		private Integer estimatePercent;
	}
}
