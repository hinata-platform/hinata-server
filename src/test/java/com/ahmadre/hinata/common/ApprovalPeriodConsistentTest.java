package com.ahmadre.hinata.common;

import com.ahmadre.hinata.setup.ServerSettings;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A submission rhythm has to be answerable before anyone tries to compute a
 * span from it (HIN-88). "Every 14 days, starting never" and "monthly, every 14
 * days" are both configurations someone could save from a half-filled form, and
 * both would leave the period arithmetic to pick a winner in silence.
 */
class ApprovalPeriodConsistentTest {

	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void openValidator() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		factory.close();
	}

	private static ServerSettings.TimeTracking.ApprovalPeriod period(
			TimePolicy.ApprovalPeriod type, LocalDate anchor, Integer days) {
		ServerSettings.TimeTracking.ApprovalPeriod period =
				new ServerSettings.TimeTracking.ApprovalPeriod();
		period.setType(type);
		period.setAnchorDate(anchor);
		period.setDays(days);
		return period;
	}

	private Set<ConstraintViolation<ServerSettings.TimeTracking.ApprovalPeriod>> violations(
			ServerSettings.TimeTracking.ApprovalPeriod period) {
		return validator.validate(period);
	}

	@Test
	void aMonthlyPeriodNeedsNothingElse() {
		assertThat(violations(period(TimePolicy.ApprovalPeriod.MONTHLY, null, null))).isEmpty();
	}

	@Test
	void anEmptyPeriodIsReadAsMonthlyAndPasses() {
		// Everything null means "inherit the environment default", and the default
		// out of the box is monthly — which needs neither an anchor nor a count.
		assertThat(violations(period(null, null, null))).isEmpty();
	}

	@Test
	void daysBelongToACustomPeriodAndNowhereElse() {
		assertThat(violations(period(TimePolicy.ApprovalPeriod.MONTHLY, null, 14)))
				.singleElement()
				.satisfies(violation -> {
					assertThat(violation.getPropertyPath()).hasToString("days");
					assertThat(violation.getMessage())
							.isEqualTo("error.timeTracking.approvalPeriodInvalid");
				});
		assertThat(violations(period(TimePolicy.ApprovalPeriod.WEEKLY, null, 7))).hasSize(1);
		assertThat(violations(period(null, null, 14))).hasSize(1);
	}

	@Test
	void aCustomPeriodWithoutACountIsNotAPeriod() {
		assertThat(violations(period(TimePolicy.ApprovalPeriod.CUSTOM_DAYS,
				LocalDate.parse("2026-01-05"), null)))
				.singleElement()
				.satisfies(violation -> assertThat(violation.getPropertyPath()).hasToString("days"));
	}

	@Test
	void biweeklyAndCustomPeriodsNeedSomethingToCountFrom() {
		assertThat(violations(period(TimePolicy.ApprovalPeriod.BIWEEKLY, null, null)))
				.singleElement()
				.satisfies(violation ->
						assertThat(violation.getPropertyPath()).hasToString("anchorDate"));
		assertThat(violations(period(TimePolicy.ApprovalPeriod.CUSTOM_DAYS, null, 10)))
				.singleElement()
				.satisfies(violation ->
						assertThat(violation.getPropertyPath()).hasToString("anchorDate"));
	}

	@Test
	void aCoherentBiweeklyOrCustomPeriodPasses() {
		LocalDate anchor = LocalDate.parse("2026-01-05");
		assertThat(violations(period(TimePolicy.ApprovalPeriod.BIWEEKLY, anchor, null))).isEmpty();
		assertThat(violations(period(TimePolicy.ApprovalPeriod.CUSTOM_DAYS, anchor, 10))).isEmpty();
		assertThat(violations(period(TimePolicy.ApprovalPeriod.FREE, null, null))).isEmpty();
	}

	@Test
	void theCalendarRhythmsNeedNothingCountedFrom() {
		// Half a month and a quarter are read off the calendar, like a month: there
		// is no anchor to count from and no day count to carry. An operator who
		// picks one of them supplies nothing else, and is not asked to.
		assertThat(violations(period(TimePolicy.ApprovalPeriod.SEMI_MONTHLY, null, null))).isEmpty();
		assertThat(violations(period(TimePolicy.ApprovalPeriod.QUARTERLY, null, null))).isEmpty();
		assertThat(violations(period(TimePolicy.ApprovalPeriod.QUARTERLY, null, 90)))
				.singleElement()
				.satisfies(violation ->
						assertThat(violation.getPropertyPath()).hasToString("days"));
	}

	@Test
	void theRuleReachesTheBlockThroughTheWholeSettingsDocument() {
		// Jakarta Validation does not descend into a nested object unless the field
		// says @Valid — the exact trap ServerSettings.general documents. Without it
		// the constraint is declared and never evaluated.
		ServerSettings document = new ServerSettings();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setApprovalPeriod(period(TimePolicy.ApprovalPeriod.BIWEEKLY, null, null));
		document.setTimeTracking(block);

		assertThat(validator.validate(document))
				.singleElement()
				.satisfies(violation -> assertThat(violation.getPropertyPath())
						.hasToString("timeTracking.approvalPeriod.anchorDate"));
	}

	@Test
	void theEnvironmentDefaultsCarryTheSameRule() {
		com.ahmadre.hinata.config.HinataProperties properties =
				new com.ahmadre.hinata.config.HinataProperties();
		properties.getTimeTracking().getApprovalPeriod()
				.setType(TimePolicy.ApprovalPeriod.CUSTOM_DAYS);

		assertThat(validator.validate(properties))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains("timeTracking.approvalPeriod.days");
	}
}
