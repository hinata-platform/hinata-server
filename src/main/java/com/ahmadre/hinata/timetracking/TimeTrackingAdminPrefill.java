package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsPrefill;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Tells the admin area what the time-tracking policies currently resolve to,
 * without touching what is stored.
 *
 * <p>The distinction is the whole job. {@code settings.timeTracking} is what an
 * administrator has decided — mostly nulls on a fresh instance, and a null there
 * means "whatever this deployment's environment says".
 * {@code settings.timeTracking.effective} is what those nulls currently work out
 * to. The admin area shows the second and edits the first, so a policy can be
 * handed back to the environment and stay there.
 *
 * <p>The values come from {@link TimeTrackingSettings} rather than from the
 * environment properties directly, so the number on the screen is the number the
 * gate and the jobs enforce. Reading the properties again here would be a second
 * copy of the resolution rule, and the two would eventually disagree — which is
 * the failure this stage set out to end.
 */
@Component
@RequiredArgsConstructor
public class TimeTrackingAdminPrefill implements SettingsPrefill {

	private final TimeTrackingSettings settings;

	@Override
	public void prefill(ServerSettings document) {
		ServerSettings.TimeTracking stored = document.getTimeTracking();
		if (stored == null) {
			// So the admin area always has a block to write an override into,
			// even on an instance that has never saved one. Empty, not filled:
			// every field stays null and keeps deferring to the environment.
			stored = new ServerSettings.TimeTracking();
			document.setTimeTracking(stored);
		}
		stored.setEffective(effective());
	}

	/** Every policy resolved, as the module itself reads them. */
	private ServerSettings.TimeTracking effective() {
		ServerSettings.TimeTracking view = new ServerSettings.TimeTracking();
		view.setAdvancedEnabled(settings.advancedEnabled());

		TimeTrackingSettings.RequiredFields required = settings.requiredFields();
		ServerSettings.TimeTracking.RequiredFields requiredView =
				new ServerSettings.TimeTracking.RequiredFields();
		requiredView.setProject(required.project());
		requiredView.setIssue(required.issue());
		requiredView.setDescription(required.description());
		requiredView.setTag(required.tag());
		view.setRequiredFields(requiredView);

		// Null here is an answer, not a gap: no lock date means nothing is frozen.
		view.setLockBefore(settings.lockBefore());

		TimeTrackingSettings.Rounding rounding = settings.rounding();
		ServerSettings.TimeTracking.Rounding roundingView =
				new ServerSettings.TimeTracking.Rounding();
		roundingView.setMode(rounding.mode());
		roundingView.setIncrement(rounding.increment());
		view.setRounding(roundingView);

		view.setLimitTagAccess(settings.limitTagAccess());
		view.setDefaultBillable(settings.defaultBillable());
		view.setBillingEnabled(settings.billingEnabled());
		view.setCurrency(settings.currency());
		view.setLeadsSeeMemberEntries(settings.leadsSeeMemberEntries());
		view.setApprovalsEnabled(settings.approvalsEnabled());

		TimeTrackingSettings.ApprovalPeriod period = settings.approvalPeriod();
		ServerSettings.TimeTracking.ApprovalPeriod periodView =
				new ServerSettings.TimeTracking.ApprovalPeriod();
		periodView.setType(period.type());
		periodView.setWeekStartsOn(period.weekStartsOn());
		periodView.setAnchorDate(period.anchorDate());
		periodView.setDays(period.days());
		view.setApprovalPeriod(periodView);

		view.setWorkloadReportsEnabled(settings.workloadReportsEnabled());
		view.setAlertsEnabled(settings.alertsEnabled());
		view.setTargetRemindersEnabled(settings.targetRemindersEnabled());
		view.setArbzgHintsEnabled(settings.arbzgHintsEnabled());

		TimeTrackingSettings.Retention retention = settings.retention();
		ServerSettings.TimeTracking.Retention retentionView =
				new ServerSettings.TimeTracking.Retention();
		retentionView.setDescriptionPurgeMonths(retention.descriptionPurgeMonths());
		retentionView.setEntryPurgeMonths(retention.entryPurgeMonths());
		view.setRetention(retentionView);

		view.setPrivacyNotice(settings.privacyNotice());
		view.setIcsImportEnabled(settings.icsImportEnabled());
		return view;
	}
}
