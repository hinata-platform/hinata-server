package com.ahmadre.hinata.setup;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entries are deleted never, or after two years at the earliest (§ 16 Abs. 2 ArbZG,
 * § 17 Abs. 1 MiLoG). The admin area is told so before anything is stored; a value
 * that got past it anyway is raised to two years when it is read.
 */
class RetentionSettingsValidationTest {

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

	private static Set<String> refusalsFor(Integer entryPurgeMonths) {
		ServerSettings.TimeTracking.Retention retention = new ServerSettings.TimeTracking.Retention();
		retention.setEntryPurgeMonths(entryPurgeMonths);
		return validator.validate(retention).stream()
				.map(ConstraintViolation::getMessage)
				.collect(Collectors.toSet());
	}

	@Test
	void entriesAreDeletedNeverOrAfterTwoYearsAtTheEarliest() {
		assertThat(refusalsFor(null)).isEmpty();
		assertThat(refusalsFor(0)).isEmpty();
		assertThat(refusalsFor(24)).isEmpty();
		assertThat(refusalsFor(120)).isEmpty();
		assertThat(refusalsFor(1)).containsExactly("error.timeTracking.entryRetentionTooShort");
		assertThat(refusalsFor(23)).containsExactly("error.timeTracking.entryRetentionTooShort");
	}

	@Test
	void theDescriptionsOfDeletedAccountsKeepTheirShortPeriods() {
		ServerSettings.TimeTracking.Retention retention = new ServerSettings.TimeTracking.Retention();
		retention.setDescriptionPurgeMonths(1);

		assertThat(validator.validate(retention)).isEmpty();
	}
}
