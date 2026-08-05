package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.moderation.image.ImageModerator;
import com.ahmadre.hinata.moderation.image.ImageTierState;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The four states exist because the product previously could not tell them apart.
 * {@code imageEnabled} defaults to true and the admin toggle reads "on", so with no
 * classifier installed an operator was shown a switch promising something no upload
 * ever received — and there was no value anywhere in the system that disagreed.
 *
 * <p>The important assertion is therefore not that {@code ACTIVE} works. It is that
 * "nobody installed one" and "the one you installed is broken" are two different
 * answers, and that neither of them is {@code ACTIVE}.
 */
class ImageTierStateTest {

	private ServerSettings settings;
	private ModerationPolicy policy;

	@BeforeEach
	void setUp() {
		settings = new ServerSettings();
		SettingsService settingsService = mock(SettingsService.class);
		when(settingsService.get()).thenReturn(settings);
		policy = new ModerationPolicy(settingsService, new HinataProperties());
	}

	private ModerationService serviceWith(List<ImageModerator> tiers) {
		return new ModerationService(policy, mock(ModerationRecorder.class), List.of(), tiers);
	}

	@Test
	void withNoTierInstalledTheStateIsNotConfigured() {
		assertThat(serviceWith(List.of()).imageTierState())
				.isEqualTo(ImageTierState.NOT_CONFIGURED);
	}

	@Test
	void withAWorkingTierTheStateIsActive() {
		assertThat(serviceWith(List.of(tier(true))).imageTierState())
				.isEqualTo(ImageTierState.ACTIVE);
	}

	/**
	 * The state that matters most operationally: a bean exists, so "is a classifier
	 * installed" says yes, and it classifies nothing. Reporting this as
	 * {@code ACTIVE} would be the original bug with extra steps.
	 */
	@Test
	void withAnInstalledButUnavailableTierTheStateSaysSo() {
		assertThat(serviceWith(List.of(tier(false))).imageTierState())
				.isEqualTo(ImageTierState.CONFIGURED_UNAVAILABLE);
	}

	@Test
	void oneWorkingTierAmongBrokenOnesIsStillActive() {
		assertThat(serviceWith(List.of(tier(false), tier(true))).imageTierState())
				.isEqualTo(ImageTierState.ACTIVE);
	}

	@Test
	void switchingImageModerationOffIsItsOwnState() {
		settings.getModeration().setImageEnabled(false);

		assertThat(serviceWith(List.of(tier(true))).imageTierState())
				.isEqualTo(ImageTierState.DISABLED_BY_POLICY);
	}

	/** The master switch covers the image tier too, not just the text one. */
	@Test
	void theMasterSwitchAlsoReportsDisabledByPolicy() {
		settings.getModeration().setEnabled(false);

		assertThat(serviceWith(List.of(tier(true))).imageTierState())
				.isEqualTo(ImageTierState.DISABLED_BY_POLICY);
	}

	@Test
	void onlyTheTwoStatesAnOperatorMustActOnWarrantAttention() {
		assertThat(ImageTierState.NOT_CONFIGURED.warrantsAttention()).isTrue();
		assertThat(ImageTierState.CONFIGURED_UNAVAILABLE.warrantsAttention()).isTrue();
		assertThat(ImageTierState.ACTIVE.warrantsAttention()).isFalse();
		// Deliberately quiet: the operator chose this and does not need reminding.
		assertThat(ImageTierState.DISABLED_BY_POLICY.warrantsAttention()).isFalse();
	}

	/**
	 * The behaviour the state describes. Without this pairing the enum could drift
	 * into reporting {@code ACTIVE} while uploads still came back unjudged.
	 */
	@Test
	void notConfiguredMeansUploadsComeBackHonestlyUnjudged() {
		ModerationService service = serviceWith(List.of());

		ModerationVerdict verdict = service.assessImage(new byte[] { 1, 2, 3 }, "image/png",
				ModerationSurface.ATTACHMENT);

		assertThat(service.imageTierState()).isEqualTo(ImageTierState.NOT_CONFIGURED);
		assertThat(verdict.tier()).isEqualTo(ModerationVerdict.ModerationTier.DISABLED);
	}

	private static ImageModerator tier(boolean available) {
		return new ImageModerator() {
			@Override
			public Map<ModerationCategory, Integer> score(byte[] data, String contentType) {
				return Map.of();
			}

			@Override
			public boolean supports(String contentType) {
				return true;
			}

			@Override
			public String id() {
				return available ? "up" : "down";
			}

			@Override
			public boolean available() {
				return available;
			}
		};
	}
}
