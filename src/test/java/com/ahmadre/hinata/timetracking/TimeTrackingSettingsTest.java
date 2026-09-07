package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the effective policy is arrived at. The two failure modes this is written
 * against are both silent: a settings document from before the block existed
 * reading as "everything false" instead of "everything default", and a resolver
 * that has to go to Mongo on a path that runs per request.
 */
class TimeTrackingSettingsTest {

	private final HinataProperties properties = new HinataProperties();
	private final SettingsService settings = mock(SettingsService.class);
	private final TimeTrackingSettings policy = new TimeTrackingSettings(settings, properties);

	private HinataProperties.TimeTracking env() {
		return properties.getTimeTracking();
	}

	private void stored(ServerSettings.TimeTracking block) {
		ServerSettings document = new ServerSettings();
		document.setTimeTracking(block);
		when(settings.get()).thenReturn(document);
	}

	@Test
	void aDocumentWithoutTheBlockFallsBackToTheEnvDefaultAndNotToFalse() {
		env().setAdvancedEnabled(true);
		env().setCurrency("CHF");
		stored(null);

		assertThat(policy.advancedEnabled()).isTrue();
		assertThat(policy.currency()).isEqualTo("CHF");
	}

	@Test
	void anOverrideOfOneFieldLeavesTheOtherDefaultsStanding() {
		env().setAdvancedEnabled(true);
		env().setApprovalsEnabled(true);
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setApprovalsEnabled(false);
		stored(block);

		assertThat(policy.approvalsEnabled()).isFalse();
		assertThat(policy.advancedEnabled()).isTrue();
	}

	@Test
	void everyMonitoringCapablePolicyIsOffOutOfTheBox() {
		stored(null);

		// § 87 Abs. 1 Nr. 6 BetrVG: a facility suited to monitoring conduct or
		// performance is co-determined before it runs, so a fresh instance has to
		// come up silent on all of these.
		assertThat(policy.advancedEnabled()).isFalse();
		assertThat(policy.approvalsEnabled()).isFalse();
		assertThat(policy.leadsSeeMemberEntries()).isFalse();
		assertThat(policy.workloadReportsEnabled()).isFalse();
		assertThat(policy.alertsEnabled()).isFalse();
		assertThat(policy.targetRemindersEnabled()).isFalse();
		assertThat(policy.arbzgHintsEnabled()).isFalse();
		assertThat(policy.billingEnabled()).isFalse();
		assertThat(policy.limitTagAccess()).isFalse();
		assertThat(policy.icsImportEnabled()).isFalse();
		assertThat(policy.lockBefore()).isNull();
		assertThat(policy.retention())
				.isEqualTo(new TimeTrackingSettings.Retention(0, 0));
	}

	@Test
	void nothingConfiguredAnywhereMakesTheApprovalPeriodMonthly() {
		stored(null);

		assertThat(policy.approvalPeriod()).isEqualTo(new TimeTrackingSettings.ApprovalPeriod(
				TimePolicy.ApprovalPeriod.MONTHLY, DayOfWeek.MONDAY, null, null));
	}

	@Test
	void theApprovalPeriodIsResolvedFieldByField() {
		LocalDate anchor = LocalDate.parse("2026-01-05");
		env().getApprovalPeriod().setType(TimePolicy.ApprovalPeriod.BIWEEKLY);
		env().getApprovalPeriod().setAnchorDate(anchor);
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		ServerSettings.TimeTracking.ApprovalPeriod override =
				new ServerSettings.TimeTracking.ApprovalPeriod();
		override.setWeekStartsOn(DayOfWeek.SUNDAY);
		block.setApprovalPeriod(override);
		stored(block);

		// Only the week start was overridden, so the type and the anchor it needs
		// still come from the environment.
		assertThat(policy.approvalPeriod()).isEqualTo(new TimeTrackingSettings.ApprovalPeriod(
				TimePolicy.ApprovalPeriod.BIWEEKLY, DayOfWeek.SUNDAY, anchor, null));
	}

	@Test
	void requiredFieldsAndRoundingResolvePerField() {
		env().getRequiredFields().setProject(true);
		env().getRounding().setMode(TimePolicy.Rounding.NEAREST);
		env().getRounding().setIncrement(30);
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		ServerSettings.TimeTracking.RequiredFields required =
				new ServerSettings.TimeTracking.RequiredFields();
		required.setDescription(true);
		block.setRequiredFields(required);
		ServerSettings.TimeTracking.Rounding rounding = new ServerSettings.TimeTracking.Rounding();
		rounding.setIncrement(15);
		block.setRounding(rounding);
		stored(block);

		assertThat(policy.requiredFields())
				.isEqualTo(new TimeTrackingSettings.RequiredFields(true, false, true, false));
		assertThat(policy.rounding())
				.isEqualTo(new TimeTrackingSettings.Rounding(TimePolicy.Rounding.NEAREST, 15));
	}

	@Test
	void aBlankCurrencyOrNoticeIsNotAnOverride() {
		env().setCurrency("USD");
		env().setPrivacyNotice("How we handle your hours.");
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setCurrency("   ");
		block.setPrivacyNotice("");
		stored(block);

		assertThat(policy.currency()).isEqualTo("USD");
		assertThat(policy.privacyNotice()).isEqualTo("How we handle your hours.");
	}

	@Test
	void theStoredBlockIsReadOnceAndThenRefreshedByTheChangeEvent() {
		stored(null);

		for (int i = 0; i < 25; i++) {
			assertThat(policy.advancedEnabled()).isFalse();
		}
		// This is the whole reason for not copying McpSettings: the gate asks per
		// request, and a Mongo round trip per request is not a gate, it is a tax.
		verify(settings, times(1)).get();

		ServerSettings changed = new ServerSettings();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		changed.setTimeTracking(block);
		policy.onSettingsChanged(new SettingsService.SettingsChangedEvent(changed));

		assertThat(policy.advancedEnabled()).isTrue();
		verify(settings, times(1)).get();
	}

	@Test
	void aChangeEventThatDropsTheBlockDoesNotSendTheResolverBackToMongo() {
		ServerSettings cleared = new ServerSettings();
		policy.onSettingsChanged(new SettingsService.SettingsChangedEvent(cleared));

		assertThat(policy.advancedEnabled()).isFalse();
		verify(settings, never()).get();
	}

	@Test
	void theModuleFlagIsTheSameValueTheGateEnforces() {
		env().setAdvancedEnabled(true);
		stored(null);

		assertThat(policy.flagKey()).isEqualTo("advanced_time_tracking");
		assertThat(policy.flagEnabled()).isEqualTo(policy.advancedEnabled());
	}
}
