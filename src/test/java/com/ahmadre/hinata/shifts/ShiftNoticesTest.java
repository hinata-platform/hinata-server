package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.common.UserWordsFixture;
import com.ahmadre.hinata.notification.PersonalNotice;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The words of the shift notifications (HIN-46): the bell names shift, event, day and time in the
 * event's zone (and the reader's, when that clock differs), the push names none of it.
 */
class ShiftNoticesTest {

	private static final Instant START = Instant.parse("2026-10-21T12:00:00Z");
	private static final Instant END = Instant.parse("2026-10-21T16:00:00Z");

	private final ShiftNotices notices = new ShiftNotices(UserWordsFixture.real(), settings());

	private static SettingsService settings() {
		SettingsService service = mock(SettingsService.class);
		when(service.get()).thenReturn(new ServerSettings());
		return service;
	}

	private static User reader(String locale, String zone) {
		return User.builder().id("u-" + locale).locale(locale).timezone(zone).active(true).build();
	}

	@Test
	void theBellNamesShiftEventDayAndTimeInTheEventsZone() {
		PersonalNotice de = notices.of(reader("de", "Europe/Berlin"), ShiftNotices.Kind.ASSIGNED, "Aufbau",
				"Sommerfest", START, END, "Europe/Berlin");
		assertThat(de.title()).isEqualTo("Du hast eine neue Schicht");
		assertThat(de.body()).startsWith("Du wurdest für Aufbau bei Sommerfest eingeteilt, 21.10.2026, 14:00 bis 18:00.");
		assertThat(de.ctaLabel()).isEqualTo("Schichten öffnen");

		PersonalNotice en = notices.of(reader("en", "Europe/Paris"), ShiftNotices.Kind.REMINDER, null,
				"Trade fair stand", START, END, "Europe/Berlin");
		assertThat(en.title()).isEqualTo("Your shift starts soon");
		assertThat(en.body()).contains("Trade fair stand").contains("Oct 21, 2026").doesNotContain("Europe/");
	}

	@Test
	void aReaderOnAnotherClockSeesBothTimes() {
		PersonalNotice notice = notices.of(reader("de", "America/New_York"), ShiftNotices.Kind.REMINDER, "Kasse",
				"Vereinsfest", START, END, "Europe/Berlin");
		assertThat(notice.body()).contains("14:00 bis 18:00 (Europe/Berlin)")
				.contains("bei dir 21.10.2026, 08:00 bis 12:00 (America/New_York)");
	}

	@Test
	void aShiftOverMidnightNamesBothDays() {
		PersonalNotice notice = notices.of(reader("de", "Europe/Berlin"), ShiftNotices.Kind.SHIFT_MOVED, "Nachtwache",
				"Werkstatt", Instant.parse("2026-10-21T20:00:00Z"), Instant.parse("2026-10-22T04:00:00Z"),
				"Europe/Berlin");
		assertThat(notice.body()).isEqualTo("Neue Zeit für Nachtwache bei Werkstatt: 21.10.2026, 22:00 bis 22.10.2026, 06:00.");
	}

	@Test
	void plannersHearTheNumbersButNoName() {
		PersonalNotice notice = notices.of(reader("de", "Europe/Berlin"), ShiftNotices.Kind.SHORT, "Einlass",
				"Sommerfest", START, END, "Europe/Berlin", 2, 3);
		assertThat(notice.body()).isEqualTo(
				"Jemand hat sich aus der Schicht Einlass bei Sommerfest ausgetragen, 21.10.2026, 14:00 bis 18:00. Besetzt sind jetzt 2 von 3.");
	}

	@Test
	void thePushNamesNoShiftEventPlaceOrTime() {
		for (String locale : List.of("de", "en", "fr", "es", "ru", "ar", "hi", "ja", "zh")) {
			for (ShiftNotices.Kind kind : ShiftNotices.Kind.values()) {
				PersonalNotice notice = notices.of(reader(locale, "America/New_York"), kind, "Aufbau", "Sommerfest",
						START, END, "Europe/Berlin", 2, 3);
				String push = notice.pushTitle() + " " + notice.pushBody();
				assertThat(push).as("%s %s", locale, kind)
						.doesNotContain("Aufbau", "Sommerfest", "Europe", "America", "notify.")
						.doesNotContainPattern("\\d");
				assertThat(notice.title() + notice.body()).as("%s %s", locale, kind)
						.doesNotContain("notify.", "{0}", "{1}", "{2}", "{3}");
			}
		}
	}
}
