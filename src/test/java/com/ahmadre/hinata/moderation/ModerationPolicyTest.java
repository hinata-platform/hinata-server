package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.moderation.text.LexiconTextModerator;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The policy is where a score becomes a consequence, so these tests are about the
 * asymmetries that make moderation survivable in a bug tracker: long bodies are
 * queued rather than refused, engineering surfaces get violence relief, external
 * ingress is judged harder, and two categories ignore all of it.
 */
class ModerationPolicyTest {

	private ServerSettings settings;
	private HinataProperties properties;
	private ModerationPolicy policy;

	@BeforeEach
	void setUp() {
		settings = new ServerSettings();
		properties = new HinataProperties();
		SettingsService settingsService = mock(SettingsService.class);
		when(settingsService.get()).thenReturn(settings);
		policy = new ModerationPolicy(settingsService, properties);
	}

	// --- defaults -----------------------------------------------------------

	@Test
	void isOnByDefault() {
		assertThat(policy.enabled()).isTrue();
		assertThat(policy.textEnabled()).isTrue();
		assertThat(policy.imageEnabled()).isTrue();
	}

	@Test
	void masterSwitchDisablesEverySubSwitch() {
		settings.getModeration().setEnabled(false);

		assertThat(policy.textEnabled()).isFalse();
		assertThat(policy.imageEnabled()).isFalse();
	}

	@Test
	void databaseOverrideWinsOverEnvDefault() {
		properties.getModeration().setBlockThreshold(90);
		settings.getModeration().setBlockThreshold(70);

		assertThat(policy.blockThreshold(ModerationCategory.HATE, ModerationSurface.ENTITY_NAME))
				.isEqualTo(70);
	}

	// --- the long-form asymmetry --------------------------------------------

	/**
	 * The single most important behaviour in the class: a wrongly refused defect
	 * report is not rewritten, it is abandoned. So on the default setting a
	 * long-form body is queued even at a score that would refuse a project name.
	 */
	@Test
	void longFormBodiesAreQueuedNotRefusedByDefault() {
		assertThat(policy.longFormFlagOnly()).isTrue();

		assertThat(policy.decide(ModerationCategory.HATE, 100, ModerationSurface.ISSUE_DESCRIPTION))
				.isEqualTo(ModerationDecision.FLAG);
		assertThat(policy.decide(ModerationCategory.HATE, 100, ModerationSurface.COMMENT))
				.isEqualTo(ModerationDecision.FLAG);
		assertThat(policy.decide(ModerationCategory.HATE, 100, ModerationSurface.ARTICLE_CONTENT))
				.isEqualTo(ModerationDecision.FLAG);
	}

	@Test
	void shortDisplayFieldsAreAlwaysRefusable() {
		assertThat(policy.decide(ModerationCategory.HATE, 100, ModerationSurface.ENTITY_NAME))
				.isEqualTo(ModerationDecision.BLOCK);
		assertThat(policy.decide(ModerationCategory.HATE, 100, ModerationSurface.PROFILE))
				.isEqualTo(ModerationDecision.BLOCK);
		assertThat(policy.decide(ModerationCategory.HATE, 100, ModerationSurface.ISSUE_TITLE))
				.isEqualTo(ModerationDecision.BLOCK);
	}

	@Test
	void uploadsAreAlwaysRefusable() {
		assertThat(policy.decide(ModerationCategory.SEXUAL, 100, ModerationSurface.ATTACHMENT))
				.isEqualTo(ModerationDecision.BLOCK);
		assertThat(policy.decide(ModerationCategory.SEXUAL, 100, ModerationSurface.AVATAR))
				.isEqualTo(ModerationDecision.BLOCK);
	}

	@Test
	void switchingOffLongFormReliefMakesBodiesRefusable() {
		settings.getModeration().setLongFormFlagOnly(false);

		assertThat(policy.decide(ModerationCategory.HATE, 100, ModerationSurface.ISSUE_DESCRIPTION))
				.isEqualTo(ModerationDecision.BLOCK);
	}

	// --- the technical-surface asymmetry -------------------------------------

	@Test
	void technicalSurfacesGetViolenceRelief() {
		// A base low enough that the relief does not run into the 100 ceiling, so
		// this asserts the rule rather than the clamp.
		settings.getModeration().setBlockThreshold(70);

		int technical = policy.blockThreshold(ModerationCategory.VIOLENCE, ModerationSurface.COMMENT);
		int plain = policy.blockThreshold(ModerationCategory.VIOLENCE, ModerationSurface.ENTITY_NAME);

		assertThat(technical).isEqualTo(plain + ModerationPolicy.TECHNICAL_VIOLENCE_RELIEF);
	}

	/**
	 * The bracket that makes "abuse hidden in a code fence is queued, never
	 * refused" true. These two constants live in different classes and are only
	 * correct relative to each other, so crossing them is a silent behaviour change
	 * in either direction — a bypass if the ceiling drops under the flag threshold,
	 * a bug-report-rejecting regression if it rises over the block floor.
	 */
	@Test
	void technicalSpanCeilingStaysBracketedByThePolicyThresholds() {
		assertThat(LexiconTextModerator.TECHNICAL_SPAN_CEILING)
				.isGreaterThanOrEqualTo(ModerationPolicy.DEFAULT_FLAG_THRESHOLD)
				.isLessThan(ModerationPolicy.BLOCK_THRESHOLD_FLOOR);
	}

	/** Relief is for violence-shaped categories only — a slur is a slur in a stack trace. */
	@Test
	void technicalReliefDoesNotApplyToOtherCategories() {
		assertThat(policy.blockThreshold(ModerationCategory.HATE, ModerationSurface.COMMENT))
				.isEqualTo(policy.blockThreshold(ModerationCategory.HATE, ModerationSurface.ENTITY_NAME));
	}

	// --- the external-ingress asymmetry --------------------------------------

	@Test
	void externalSurfacesAreJudgedHarder() {
		int external = policy.blockThreshold(ModerationCategory.HATE, ModerationSurface.EMAIL_INGEST);
		int internal = policy.blockThreshold(ModerationCategory.HATE, ModerationSurface.ENTITY_NAME);

		assertThat(external).isEqualTo(internal - ModerationPolicy.EXTERNAL_STRICTNESS);
	}

	@Test
	void externalIngressNeverFailsOpen() {
		settings.getModeration().setFailOpen(true);

		assertThat(policy.failOpen(ModerationSurface.COMMENT)).isTrue();
		assertThat(policy.failOpen(ModerationSurface.EMAIL_INGEST)).isFalse();
		assertThat(policy.failOpen(ModerationSurface.EXTERNAL_IMAGE)).isFalse();
	}

	// --- the categories no admin may weaken ----------------------------------

	@Test
	void childSexualContentAndMalwareAreNotOverridable() {
		assertThat(policy.isOverridable(ModerationCategory.SEXUAL_MINORS)).isFalse();
		assertThat(policy.isOverridable(ModerationCategory.MALWARE)).isFalse();
	}

	@ParameterizedTest
	@EnumSource(value = ModerationCategory.class,
			names = { "SEXUAL_MINORS", "MALWARE" })
	void nonOverridableCategoriesIgnoreAdminThresholds(ModerationCategory category) {
		settings.getModeration().setBlockThreshold(100);
		settings.getModeration().setFlagThreshold(100);

		assertThat(policy.blockThreshold(category, ModerationSurface.COMMENT))
				.isEqualTo(properties.getModeration().getStrictBlockThreshold());
	}

	@ParameterizedTest
	@EnumSource(value = ModerationCategory.class, names = { "SEXUAL_MINORS", "MALWARE" })
	void nonOverridableCategoriesIgnoreSurfaceAdjustments(ModerationCategory category) {
		int onTechnical = policy.blockThreshold(category, ModerationSurface.COMMENT);
		int onPlain = policy.blockThreshold(category, ModerationSurface.ENTITY_NAME);
		int onExternal = policy.blockThreshold(category, ModerationSurface.EMAIL_INGEST);

		assertThat(onTechnical).isEqualTo(onPlain).isEqualTo(onExternal);
	}

	// --- guard rails ----------------------------------------------------------

	/**
	 * An admin dragging the threshold to zero would refuse everything, which ends
	 * with moderation switched off entirely — so the floor exists to stop the
	 * setting from being self-defeating.
	 */
	@Test
	void blockThresholdCannotBeDraggedBelowTheFloor() {
		settings.getModeration().setBlockThreshold(1);

		assertThat(policy.blockThreshold(ModerationCategory.HATE, ModerationSurface.ENTITY_NAME))
				.isEqualTo(ModerationPolicy.BLOCK_THRESHOLD_FLOOR);
	}

	/**
	 * A flag threshold above the block threshold would create a band where content
	 * is refused without ever having been reviewable.
	 */
	@Test
	void flagThresholdNeverExceedsBlockThreshold() {
		settings.getModeration().setFlagThreshold(99);
		settings.getModeration().setBlockThreshold(60);

		for (ModerationSurface surface : ModerationSurface.values()) {
			assertThat(policy.flagThreshold(ModerationCategory.HATE, surface))
					.isLessThanOrEqualTo(policy.blockThreshold(ModerationCategory.HATE, surface));
		}
	}

	@ParameterizedTest
	@EnumSource(ModerationSurface.class)
	void thresholdsStayInRangeOnEverySurface(ModerationSurface surface) {
		for (ModerationCategory category : ModerationCategory.values()) {
			assertThat(policy.blockThreshold(category, surface)).isBetween(1, 100);
			assertThat(policy.flagThreshold(category, surface)).isBetween(1, 100);
		}
	}

	@Test
	void aScoreBelowBothThresholdsIsAllowed() {
		assertThat(policy.decide(ModerationCategory.HATE, 0, ModerationSurface.ENTITY_NAME))
				.isEqualTo(ModerationDecision.ALLOW);
	}
}
