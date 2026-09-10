package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.FeatureFlags;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * The effective time-tracking policy: an administrator's stored override wins
 * per field, otherwise the environment default. Every consumer — the request
 * gate, the write hook, the jobs and later the reports — reads through here, so
 * the admin area is the actual source of truth and nothing downstream carries a
 * constant of its own.
 *
 * <p>Modelled on {@link com.ahmadre.hinata.auth.SecurityPolicy}: the stored
 * block is held in a volatile field and replaced when
 * {@link SettingsService.SettingsChangedEvent} says it changed, which is how the
 * SSO registries and the SMTP sender already refresh. Deliberately <em>not</em>
 * modelled on {@code McpSettings}, which reads Mongo on every call — that is
 * affordable for a transport gate consulted once per connection and not for a
 * resolver on the path of every request to the module.
 *
 * <p>Nothing here decides a rhythm: {@link #approvalPeriod()} answers what the
 * operator configured, down to "a span they pick themselves", and the arithmetic
 * that turns it into a date range is HIN-88's. There is exactly one default —
 * monthly, when nothing is configured anywhere — and it is stated below and in
 * the environment property, and nowhere else.
 */
@Component
@RequiredArgsConstructor
public class TimeTrackingSettings implements FeatureFlags.Module {

	/** The client-visible flag name, snake_case like {@code multi_assignee}. */
	public static final String FLAG = "advanced_time_tracking";

	/** Nothing configured anywhere: timesheets are submitted per calendar month. */
	private static final TimePolicy.ApprovalPeriod DEFAULT_PERIOD = TimePolicy.ApprovalPeriod.MONTHLY;

	private final SettingsService settings;
	private final HinataProperties properties;
	private final Clock clock;

	/**
	 * The stored overrides. Never holds null once loaded — an instance with no
	 * {@code timeTracking} block caches an empty one, so the absence costs a
	 * single read rather than one per request.
	 */
	private volatile ServerSettings.TimeTracking cached;

	@EventListener
	void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		cached = orEmpty(event.settings().getTimeTracking());
	}

	// --- what the module is allowed to do ------------------------------------

	@Override
	public String flagKey() {
		return FLAG;
	}

	@Override
	public boolean flagEnabled() {
		return advancedEnabled();
	}

	/** Whether the extended module is switched on at all. */
	public boolean advancedEnabled() {
		Boolean override = db().getAdvancedEnabled();
		return override != null ? override : env().isAdvancedEnabled();
	}

	// --- policies ------------------------------------------------------------

	/** Which fields an entry must carry to be accepted (enforced from stage 6). */
	public RequiredFields requiredFields() {
		ServerSettings.TimeTracking.RequiredFields override = db().getRequiredFields();
		HinataProperties.TimeTracking.RequiredFields fallback = env().getRequiredFields();
		if (override == null) {
			return new RequiredFields(fallback.isProject(), fallback.isIssue(),
					fallback.isDescription(), fallback.isTag());
		}
		return new RequiredFields(
				bool(override.getProject(), fallback.isProject()),
				bool(override.getIssue(), fallback.isIssue()),
				bool(override.getDescription(), fallback.isDescription()),
				bool(override.getTag(), fallback.isTag()));
	}

	/**
	 * The day <em>before</em> which entries are frozen — the day itself stays open —
	 * or null for no lock.
	 *
	 * <p>Clamped to today. A date in the future is refused when an administrator
	 * saves one ({@code TimeTrackingSettingsGuard}), but one that arrives from the
	 * environment has nobody to refuse it to: a server that would not start, or one
	 * that silently stopped accepting today's hours, are both worse answers than
	 * freezing one day fewer than the variable asked for. The reason is the same in
	 * both directions — a freeze that reaches into the present blocks the recording
	 * of working time that § 16 Abs. 2 ArbZG requires to be recordable.
	 */
	public LocalDate lockBefore() {
		LocalDate override = db().getLockBefore();
		LocalDate configured = override != null ? override : env().getLockBefore();
		if (configured == null) {
			return null;
		}
		LocalDate today = LocalDate.now(clock);
		return configured.isAfter(today) ? today : configured;
	}

	/**
	 * The spans an administrator has reopened inside the freeze. Never null.
	 *
	 * <p>The one policy with no environment fallback, and deliberately so: an
	 * exception records who opened it and when, and a deployment variable can
	 * answer neither. It is created through the API and audited, so the stored
	 * block is the only place it can come from.
	 */
	public List<ServerSettings.TimeTracking.LockException> lockExceptions() {
		List<ServerSettings.TimeTracking.LockException> stored = db().getLockExceptions();
		return stored == null ? List.of() : stored;
	}

	/** How reported durations are folded onto an increment. */
	public Rounding rounding() {
		ServerSettings.TimeTracking.Rounding override = db().getRounding();
		HinataProperties.TimeTracking.Rounding fallback = env().getRounding();
		if (override == null) {
			return new Rounding(fallback.getMode(), fallback.getIncrement());
		}
		return new Rounding(
				override.getMode() != null ? override.getMode() : fallback.getMode(),
				override.getIncrement() != null ? override.getIncrement() : fallback.getIncrement());
	}

	/** Whether only administrator-defined tags may be used. */
	public boolean limitTagAccess() {
		Boolean override = db().getLimitTagAccess();
		return override != null ? override : env().isLimitTagAccess();
	}

	/** What {@code billable} becomes when an entry does not say. */
	public boolean defaultBillable() {
		Boolean override = db().getDefaultBillable();
		return override != null ? override : env().isDefaultBillable();
	}

	/** Whether rates, costs and invoices exist on this instance. */
	public boolean billingEnabled() {
		Boolean override = db().getBillingEnabled();
		return override != null ? override : env().isBillingEnabled();
	}

	/** ISO-4217 code the billing figures are expressed in. */
	public String currency() {
		String override = db().getCurrency();
		return override != null && !override.isBlank() ? override : env().getCurrency();
	}

	/** Whether a project lead sees member entries with the person attached. */
	public boolean leadsSeeMemberEntries() {
		Boolean override = db().getLeadsSeeMemberEntries();
		return override != null ? override : env().isLeadsSeeMemberEntries();
	}

	/** Whether timesheets are submitted and approved at all. */
	public boolean approvalsEnabled() {
		Boolean override = db().getApprovalsEnabled();
		return override != null ? override : env().isApprovalsEnabled();
	}

	/**
	 * How often a timesheet is submitted. Resolved field by field, so an operator
	 * who overrides only the type keeps the anchor the environment supplies —
	 * and an instance that configured nothing at all lands on
	 * {@link TimePolicy.ApprovalPeriod#MONTHLY} rather than on nothing.
	 */
	public ApprovalPeriod approvalPeriod() {
		ServerSettings.TimeTracking.ApprovalPeriod override = db().getApprovalPeriod();
		HinataProperties.TimeTracking.ApprovalPeriod fallback = env().getApprovalPeriod();
		TimePolicy.ApprovalPeriod type = fallback.getType();
		DayOfWeek weekStartsOn = fallback.getWeekStartsOn();
		LocalDate anchorDate = fallback.getAnchorDate();
		Integer days = fallback.getDays();
		if (override != null) {
			type = override.getType() != null ? override.getType() : type;
			weekStartsOn = override.getWeekStartsOn() != null ? override.getWeekStartsOn() : weekStartsOn;
			anchorDate = override.getAnchorDate() != null ? override.getAnchorDate() : anchorDate;
			days = override.getDays() != null ? override.getDays() : days;
		}
		return new ApprovalPeriod(type != null ? type : DEFAULT_PERIOD,
				weekStartsOn != null ? weekStartsOn : DayOfWeek.MONDAY, anchorDate, days);
	}

	/** Whether workload reports (booked against capacity) exist on this instance. */
	public boolean workloadReportsEnabled() {
		Boolean override = db().getWorkloadReportsEnabled();
		return override != null ? override : env().isWorkloadReportsEnabled();
	}

	/** Whether budget and estimate alerts are sent to leads. */
	public boolean alertsEnabled() {
		Boolean override = db().getAlertsEnabled();
		return override != null ? override : env().isAlertsEnabled();
	}

	/** Whether people get reminders about their own targets. */
	public boolean targetRemindersEnabled() {
		Boolean override = db().getTargetRemindersEnabled();
		return override != null ? override : env().isTargetRemindersEnabled();
	}

	/** Whether working-hours-act self-hints are shown to the person themselves. */
	public boolean arbzgHintsEnabled() {
		Boolean override = db().getArbzgHintsEnabled();
		return override != null ? override : env().isArbzgHintsEnabled();
	}

	/**
	 * How long entries are kept, and how long a departed person's free text is.
	 * {@code 0} means indefinitely. The two are different rules — see
	 * {@link Retention}.
	 */
	public Retention retention() {
		ServerSettings.TimeTracking.Retention override = db().getRetention();
		HinataProperties.TimeTracking.Retention fallback = env().getRetention();
		if (override == null) {
			return new Retention(fallback.getDescriptionPurgeMonths(), fallback.getEntryPurgeMonths());
		}
		return new Retention(
				override.getDescriptionPurgeMonths() != null
						? override.getDescriptionPurgeMonths() : fallback.getDescriptionPurgeMonths(),
				override.getEntryPurgeMonths() != null
						? override.getEntryPurgeMonths() : fallback.getEntryPurgeMonths());
	}

	/** The privacy notice shown before the module is first used (blank ⇒ built-in template). */
	public String privacyNotice() {
		String override = db().getPrivacyNotice();
		return override != null && !override.isBlank() ? override : env().getPrivacyNotice();
	}

	/** Whether external calendars may be subscribed to. */
	public boolean icsImportEnabled() {
		Boolean override = db().getIcsImportEnabled();
		return override != null ? override : env().isIcsImportEnabled();
	}

	// --- effective shapes ------------------------------------------------------

	/** Which fields an entry must carry. */
	public record RequiredFields(boolean project, boolean issue, boolean description, boolean tag) {
	}

	/** How reported durations are folded onto an increment. */
	public record Rounding(TimePolicy.Rounding mode, int increment) {
	}

	/**
	 * The configured submission rhythm. {@code weekStartsOn} matters for
	 * WEEKLY/BIWEEKLY, {@code anchorDate} for BIWEEKLY/CUSTOM_DAYS, {@code days}
	 * for CUSTOM_DAYS; turning this into a concrete range is HIN-88.
	 */
	public record ApprovalPeriod(TimePolicy.ApprovalPeriod type, DayOfWeek weekStartsOn,
			LocalDate anchorDate, Integer days) {
	}

	/**
	 * Retention in months from the entry's day; {@code 0} keeps data
	 * indefinitely. {@code descriptionPurgeMonths} empties the free text on a
	 * <em>deleted</em> account's entries only — the hours stay, because they are
	 * the project's record. {@code entryPurgeMonths} removes entries outright,
	 * for everyone.
	 */
	public record Retention(int descriptionPurgeMonths, int entryPurgeMonths) {
	}

	// --- resolution ------------------------------------------------------------

	private static boolean bool(Boolean override, boolean fallback) {
		return override != null ? override : fallback;
	}

	private ServerSettings.TimeTracking db() {
		ServerSettings.TimeTracking current = cached;
		if (current == null) {
			current = orEmpty(settings.get().getTimeTracking());
			cached = current;
		}
		return current;
	}

	private static ServerSettings.TimeTracking orEmpty(ServerSettings.TimeTracking stored) {
		return stored != null ? stored : new ServerSettings.TimeTracking();
	}

	private HinataProperties.TimeTracking env() {
		return properties.getTimeTracking();
	}
}
