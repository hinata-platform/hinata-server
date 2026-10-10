package com.ahmadre.hinata.availability;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class HolidayRulesTest {

	private final HolidayRules rules = new HolidayRules();

	@Test
	void germanyHasItsNineNationalHolidays() {
		List<HolidayDays.Day> days = rules.holidays("DE", 2027, Locale.GERMAN);

		assertThat(days).extracting(HolidayDays.Day::date).containsExactly(
				LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 26), LocalDate.of(2027, 3, 29),
				LocalDate.of(2027, 5, 1), LocalDate.of(2027, 5, 6), LocalDate.of(2027, 5, 17),
				LocalDate.of(2027, 10, 3), LocalDate.of(2027, 12, 25), LocalDate.of(2027, 12, 26));
		assertThat(days.getFirst().name()).isEqualTo("Neujahr");
	}

	@Test
	void aBundeslandAddsItsOwnDaysAndNoObservances() {
		List<LocalDate> bavaria = rules.holidays("DE-BY", 2027, Locale.GERMAN).stream()
				.map(HolidayDays.Day::date).toList();

		assertThat(bavaria).contains(LocalDate.of(2027, 1, 6), LocalDate.of(2027, 5, 27), LocalDate.of(2027, 11, 1))
				.doesNotContain(LocalDate.of(2027, 2, 14), LocalDate.of(2027, 2, 8));
	}

	@Test
	void everyYearIsThere() {
		assertThat(rules.holidays("DE", 2042, Locale.GERMAN)).hasSize(9);
	}

	@Test
	void codesAreCheckedAndStoredInCapitals() {
		assertThat(rules.normalize("de-by")).contains("DE-BY");
		assertThat(rules.normalize("DE")).contains("DE");
		assertThat(rules.normalize("DE-XX")).isEmpty();
		assertThat(rules.normalize("DE-")).isEmpty();
		assertThat(rules.normalize("DE--BY")).isEmpty();
		assertThat(rules.normalize("ZZ")).isEmpty();
		assertThat(rules.normalize(" ")).isEmpty();
	}

	@Test
	void aRegionIsNamedWithItsCountry() {
		assertThat(rules.nameOf("DE-BY", Locale.GERMAN)).isEqualTo("Bayern, Deutschland");
		assertThat(rules.nameOf("DE", Locale.ENGLISH)).isEqualTo("Germany");
	}

	@Test
	void theZoneNamesTheCountry() {
		assertThat(rules.countryOf(ZoneId.of("Europe/Berlin"))).contains("DE");
		assertThat(rules.countryOf(ZoneId.of("Europe/Vienna"))).contains("AT");
		assertThat(rules.countryOf(ZoneId.of("UTC"))).isEmpty();
	}

	@Test
	void theRegionListHasCountriesWithTheirRegions() {
		HolidayRules.Region germany = rules.regions(Locale.GERMAN).stream()
				.filter(region -> region.code().equals("DE")).findFirst().orElseThrow();

		assertThat(germany.name()).isEqualTo("Deutschland");
		assertThat(germany.subdivisions()).extracting(HolidayRules.Region::code).contains("DE-BY", "DE-NW");
	}
}
