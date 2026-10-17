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

	/** A ceiling on a money budget: a hundred billion in the instance's currency, in cents. */
	public static final long MAX_BUDGET_CENTS = 10_000_000_000_000L;

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

	/** When the leads, or an issue's assignees, hear that time is running out (HIN-92). */
	private AlertThresholds alertThresholds;

	/**
	 * What the project's budget and the sum of its estimates do once time reaches them (HIN-153);
	 * null ⇒ {@link LimitMode#WARN}. Neither ever refuses an entry.
	 */
	private LimitMode budgetLimitMode;

	/** The same for every issue's own estimate; null ⇒ {@link LimitMode#WARN}. */
	private LimitMode estimateLimitMode;

	/**
	 * A money budget beside the time budget (HIN-155), in cents of the instance's currency; null for
	 * none. Exists only while billing is on, and only leads and administrators see it.
	 */
	private Long budgetCents;

	/** Which rates value it; null ⇒ {@link RateKind#BILLABLE}. {@code COST} only an administrator sets. */
	private RateKind budgetRateKind;

	@CreatedDate
	private Instant createdAt;

	/** Indexed for the alert scan, which measures a project again once its settings changed. */
	@Indexed(name = "updated_at")
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

	/**
	 * What reaching a limit does (HIN-153). Recording time is never refused over a budget
	 * (§ 16 Abs. 2 ArbZG): both modes let the entry through.
	 */
	public enum LimitMode {
		/** Only the hourly alert of HIN-92, as before. */
		OPEN,
		/** A note in the form while recording, and the leads hear at once. */
		WARN;

		/** The mode a project has, with null meaning the default. */
		public static LimitMode of(LimitMode stored) {
			return stored == null ? WARN : stored;
		}
	}

	/**
	 * What a money budget is measured in (HIN-155): revenue of billable entries, or the labour cost
	 * of all of them. Cost rates can reveal a salary, so a cost budget is an administrator's alone.
	 */
	public enum RateKind {
		BILLABLE, COST;

		public static RateKind of(RateKind stored) {
			return stored == null ? BILLABLE : stored;
		}
	}

	/** The money budget's rate kind, with null resolved. */
	public RateKind rateKind() {
		return RateKind.of(budgetRateKind);
	}

	/**
	 * Whether a reader sees a money budget of [kind] (HIN-155). A revenue budget is valued at the
	 * billable rates and is the lead's business; a cost budget is valued at the cost rates, which can
	 * reveal a salary, and is an administrator's alone. A member never sees either, and a team
	 * administrator who is no lead runs the project's other settings but not its money. Every surface
	 * that answers or writes a money budget asks here (the settings, the usage, the report, a copy).
	 */
	public static boolean moneyReadable(boolean orgAdmin, boolean lead, RateKind kind) {
		return orgAdmin || (lead && kind == RateKind.BILLABLE);
	}

	/** {@link #moneyReadable(boolean, boolean, RateKind)} of [settings]; true when it has none to hide. */
	public static boolean moneyReadable(boolean orgAdmin, boolean lead, ProjectTimeSettings settings) {
		return settings == null || settings.getBudgetCents() == null
				|| moneyReadable(orgAdmin, lead, settings.rateKind());
	}

	/** The budget's mode of [settings], the default when there are none. */
	public static LimitMode budgetModeOf(ProjectTimeSettings settings) {
		return settings == null ? LimitMode.of(null) : settings.budgetMode();
	}

	/** The estimates' mode of [settings], the default when there are none. */
	public static LimitMode estimateModeOf(ProjectTimeSettings settings) {
		return settings == null ? LimitMode.of(null) : settings.estimateMode();
	}

	/** The budget's mode, with null resolved. */
	public LimitMode budgetMode() {
		return LimitMode.of(budgetLimitMode);
	}

	/** The estimates' mode, with null resolved. */
	public LimitMode estimateMode() {
		return LimitMode.of(estimateLimitMode);
	}

	/** Percentages at which the alerts of HIN-92 go out ({@code TimeAlerts}). */
	@Data
	@Builder(toBuilder = true)
	public static class AlertThresholds {
		/**
		 * Percent of the budget, and of the sum of the estimates, at which the leads hear about it,
		 * besides 100 %; null ⇒ 80 %.
		 */
		private Integer budgetPercent;
		/** Percent of an issue's estimate at which its assignees hear about it; null ⇒ 100 %. */
		private Integer estimatePercent;
	}
}
