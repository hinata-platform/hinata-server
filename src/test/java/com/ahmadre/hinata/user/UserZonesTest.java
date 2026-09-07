package com.ahmadre.hinata.user;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.setup.ServerSettings;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Profile zone, else the instance zone, else UTC — and only real zones get stored. */
class UserZonesTest {

	private static ServerSettings instanceIn(String zone) {
		ServerSettings settings = new ServerSettings();
		settings.getGeneral().setTimezone(zone);
		return settings;
	}

	@Test
	void theProfileZoneWinsOverTheInstanceZone() {
		User user = User.builder().timezone("Asia/Tokyo").build();

		assertThat(UserZones.of(user, instanceIn("Europe/Berlin"))).isEqualTo(ZoneId.of("Asia/Tokyo"));
	}

	@Test
	void withoutAProfileZoneTheInstanceZoneApplies() {
		assertThat(UserZones.of(User.builder().build(), instanceIn("Europe/Berlin")))
				.isEqualTo(ZoneId.of("Europe/Berlin"));
		assertThat(UserZones.of(null, instanceIn("Europe/Berlin"))).isEqualTo(ZoneId.of("Europe/Berlin"));
	}

	@Test
	void anythingUnreadableFallsThroughToUtc() {
		User garbage = User.builder().timezone("Mars/Olympus").build();

		assertThat(UserZones.of(garbage, instanceIn("Not/AZone"))).isEqualTo(ZoneOffset.UTC);
		assertThat(UserZones.of(garbage, null)).isEqualTo(ZoneOffset.UTC);
		assertThat(UserZones.of(null, new ServerSettings())).isNotNull();
	}

	@Test
	void normalizeKeepsAKnownZoneTrimmedAndClearsBlank() {
		assertThat(UserZones.normalize(" Europe/Berlin ")).isEqualTo("Europe/Berlin");
		assertThat(UserZones.normalize("UTC")).isEqualTo("UTC");
		assertThat(UserZones.normalize("")).isNull();
		assertThat(UserZones.normalize("   ")).isNull();
		assertThat(UserZones.normalize(null)).isNull();
	}

	@Test
	void normalizeRefusesUnknownAndOverlongZones() {
		assertThatThrownBy(() -> UserZones.normalize("Mars/Olympus"))
				.isInstanceOf(ApiException.class)
				.satisfies(thrown -> {
					assertThat(((ApiException) thrown).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
					assertThat(((ApiException) thrown).getMessageKey()).isEqualTo("error.user.invalidTimezone");
				});
		assertThatThrownBy(() -> UserZones.normalize("Europe/" + "x".repeat(UserZones.MAX_LENGTH)))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.user.invalidTimezone");
	}
}
