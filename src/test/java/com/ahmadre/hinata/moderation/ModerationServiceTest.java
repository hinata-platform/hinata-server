package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.moderation.image.ImageModerator;
import com.ahmadre.hinata.moderation.text.LexiconTextModerator;
import com.ahmadre.hinata.moderation.text.TextModerator;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end behaviour of the gate: what actually gets refused, what gets queued,
 * and — the part that matters most for a bug tracker — what sails through
 * untouched.
 */
class ModerationServiceTest {

	private ServerSettings settings;
	private ModerationPolicy policy;
	private ModerationService service;
	private RecordingRecorder recorder;

	@BeforeEach
	void setUp() {
		settings = new ServerSettings();
		SettingsService settingsService = mock(SettingsService.class);
		when(settingsService.get()).thenReturn(settings);
		policy = new ModerationPolicy(settingsService, new HinataProperties());
		recorder = new RecordingRecorder();
		service = new ModerationService(policy, recorder, List.of(new LexiconTextModerator()),
				List.of());
	}

	// --- the false-positive floor --------------------------------------------

	/**
	 * If any of these is refused or even queued, the feature is a regression in the
	 * product rather than a protection: they are all ordinary defect-report prose.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"The login button does not respond on the second attempt.",
			"kill -9 the worker process and restart the pod",
			"master/slave replication broke after the failover",
			"We should run a penetration test against the attack surface.",
			"Exploit chain from CVE-2021-44228 still reachable in prod",
			"Rename sexPreference to preferredPronouns in the user schema",
			"Der Prozess muss hart beendet werden, sonst bleibt ein Zombie übrig.",
			"Bitte den Angriffsvektor in der Analyse dokumentieren.",
			"Klassenzimmer-Modul zeigt die Sexualkunde-Kurse nicht an",
	})
	void ordinaryEngineeringProseIsUntouched(String text) {
		ModerationVerdict verdict = service.assessText(text, ModerationSurface.ISSUE_DESCRIPTION);

		assertThat(verdict.decision()).isEqualTo(ModerationDecision.ALLOW);
		assertThat(verdict.matches()).isEmpty();
	}

	@Test
	void checkTextDoesNotThrowForOrdinaryProse() {
		assertThatCode(() -> service.checkText(
				"The board does not load when a sprint has no goal.", ModerationSurface.ISSUE_TITLE))
				.doesNotThrowAnyException();
	}

	// --- what gets refused -----------------------------------------------------

	@Test
	void refusesASlurInAProjectName() {
		assertThatThrownBy(() -> service.checkText("faggot project", ModerationSurface.ENTITY_NAME))
				.isInstanceOf(ModerationException.class)
				.hasMessageContaining("blockedReason");
	}

	@Test
	void refusalNamesTheCategoryItFellUnder() {
		ModerationException thrown = catchModeration(
				() -> service.checkText("faggot", ModerationSurface.ENTITY_NAME));

		assertThat(thrown.getVerdict().primaryCategory()).isEqualTo(ModerationCategory.HATE);
		assertThat(thrown.getSurface()).isEqualTo(ModerationSurface.ENTITY_NAME);
	}

	/** The refusal must not hand back the matched term — that turns the filter into an oracle. */
	@Test
	void refusalDoesNotDiscloseTheMatchedTerm() {
		ModerationException thrown = catchModeration(
				() -> service.checkText("faggot", ModerationSurface.ENTITY_NAME));

		assertThat(thrown.getVerdict().matches())
				.allSatisfy(match -> assertThat(match.evidence()).isNull());
	}

	/**
	 * The other half of the same rule: the moderator who has to judge the decision
	 * does get the matched term, because "flagged as hate speech" with no reason is
	 * not something a person can review.
	 */
	@Test
	void theVerdictItselfCarriesEvidenceForTheModerator() {
		ModerationVerdict verdict =
				service.assessText("you absolute faggot", ModerationSurface.COMMENT);

		assertThat(verdict.matches()).isNotEmpty();
		assertThat(verdict.primary().evidence()).isNotBlank();
	}

	@Test
	void redactionLeavesTheCategoryAndScoreIntact() {
		ModerationVerdict verdict =
				service.assessText("you absolute faggot", ModerationSurface.COMMENT);
		ModerationVerdict redacted = verdict.redacted();

		assertThat(redacted.primaryCategory()).isEqualTo(verdict.primaryCategory());
		assertThat(redacted.primary().score()).isEqualTo(verdict.primary().score());
		assertThat(redacted.primary().evidence()).isNull();
	}

	@Test
	void refusalUsesUnprocessableEntityNotBadRequest() {
		ModerationException thrown = catchModeration(
				() -> service.checkText("faggot", ModerationSurface.ENTITY_NAME));

		assertThat(thrown.getStatus()).isEqualTo(ModerationException.STATUS);
		assertThat(thrown.getStatus().value()).isEqualTo(422);
	}

	// --- what a refusal leaves behind ---------------------------------------------

	/**
	 * A refusal is the one outcome the queue cannot see any other way: nothing is
	 * stored, so the caller's own recording step — which sits after the gate — is
	 * never reached, and the row has to be written here, before the throw. Get the
	 * order wrong and refusals become the only outcome of the pipeline that cannot be
	 * counted, which is exactly the number the thresholds are tuned against.
	 */
	@Test
	void aRefusalIsRecordedBeforeItIsThrown() {
		catchModeration(() -> service.checkText("faggot", ModerationSurface.ENTITY_NAME));

		assertThat(recorder.recorded).hasSize(1);
		RecordingRecorder.Recorded row = recorder.recorded.getFirst();
		assertThat(row.verdict().isBlocking()).isTrue();
		assertThat(row.surface()).isEqualTo(ModerationSurface.ENTITY_NAME);
		// Nothing was saved, so there is nothing to point at; the content is what ties
		// one refusal to the next attempt at the same payload.
		assertThat(row.target().id()).isNull();
		assertThat(row.content()).isEqualTo("faggot");
	}

	@Test
	void aRefusedUploadIsRecordedWithItsBytesAndFileName() {
		service = new ModerationService(policy, recorder, List.of(),
				List.of(condemningImageModerator()));

		catchModeration(() -> service.checkImage(new byte[] { 1, 2, 3 }, "image/png", "cat.png",
				ModerationSurface.ATTACHMENT));

		assertThat(recorder.recorded).hasSize(1);
		RecordingRecorder.Recorded row = recorder.recorded.getFirst();
		assertThat(row.target().label()).isEqualTo("cat.png");
		assertThat(row.content()).isNotNull();
	}

	/** An accepted write must not cost a row — the queue is for what needs a human. */
	@Test
	void anAllowedWriteRecordsNothing() {
		service.checkText("The board does not load when a sprint has no goal.",
				ModerationSurface.ISSUE_TITLE);

		assertThat(recorder.recorded).isEmpty();
	}

	// --- what gets queued instead ------------------------------------------------

	@Test
	void aLongBodyIsQueuedRatherThanRefused() {
		ModerationVerdict verdict =
				service.assessText("you absolute faggot", ModerationSurface.COMMENT);

		assertThat(verdict.decision()).isEqualTo(ModerationDecision.FLAG);
		assertThat(verdict.needsReview()).isTrue();
		assertThat(verdict.isBlocking()).isFalse();
	}

	/**
	 * Abuse hidden in a code fence is still seen — but capped below any block
	 * threshold, so a pasted log file is never the thing that refuses a report.
	 */
	@Test
	void abuseInsideACodeFenceIsQueuedNotRefused() {
		String text = """
				Log output:
				```
				you absolute faggot
				```
				""";
		ModerationVerdict verdict = service.assessText(text, ModerationSurface.ISSUE_TITLE);

		assertThat(verdict.decision()).isEqualTo(ModerationDecision.FLAG);
		assertThat(verdict.matches()).isNotEmpty();
		assertThat(verdict.matches())
				.allSatisfy(match -> assertThat(match.score())
						.isLessThan(ModerationPolicy.BLOCK_THRESHOLD_FLOOR));
	}

	// --- the non-overridable categories ------------------------------------------

	/**
	 * Child sexual content is refused on a long body too — it is the one place the
	 * "never lose a defect report" trade does not apply.
	 */
	@Test
	void childSexualContentIsRefusedEvenOnALongBody() {
		assertThatThrownBy(() -> service.checkText(
				"selling child porn here", ModerationSurface.ISSUE_DESCRIPTION))
				.isInstanceOf(ModerationException.class);
	}

	@Test
	void anAdminCannotWeakenChildSexualContentDetection() {
		settings.getModeration().setBlockThreshold(100);
		settings.getModeration().setFlagThreshold(100);
		settings.getModeration().setLongFormFlagOnly(true);

		assertThatThrownBy(() -> service.checkText(
				"child porn", ModerationSurface.ISSUE_DESCRIPTION))
				.isInstanceOf(ModerationException.class);
	}

	// --- switches ------------------------------------------------------------------

	@Test
	void switchingModerationOffLetsEverythingThrough() {
		settings.getModeration().setEnabled(false);

		ModerationVerdict verdict = service.assessText("faggot", ModerationSurface.ENTITY_NAME);

		assertThat(verdict.decision()).isEqualTo(ModerationDecision.ALLOW);
		assertThat(verdict.tier()).isEqualTo(ModerationVerdict.ModerationTier.DISABLED);
	}

	@Test
	void blankAndNullTextAreAllowedWithoutRunningAnything() {
		assertThat(service.assessText(null, ModerationSurface.COMMENT).tier())
				.isEqualTo(ModerationVerdict.ModerationTier.DISABLED);
		assertThat(service.assessText("   ", ModerationSurface.COMMENT).tier())
				.isEqualTo(ModerationVerdict.ModerationTier.DISABLED);
	}

	// --- degradation ----------------------------------------------------------------

	/**
	 * A failing optional tier must not fail the write — but it must leave a mark, or
	 * a provider outage becomes a silent hole nobody notices.
	 */
	@Test
	void aFailingTierDegradesTheVerdictInsteadOfFailingTheWrite() {
		service = new ModerationService(policy, recorder,
				List.of(new LexiconTextModerator(), throwingModerator()), List.of());

		ModerationVerdict verdict = service.assessText("hello there", ModerationSurface.COMMENT);

		assertThat(verdict.decision()).isEqualTo(ModerationDecision.ALLOW);
		assertThat(verdict.degraded()).isTrue();
		assertThat(verdict.needsReview()).isTrue();
	}

	@Test
	void anUnavailableTierAlsoDegrades() {
		service = new ModerationService(policy, recorder, List.of(unavailableModerator()), List.of());

		assertThat(service.assessText("hello", ModerationSurface.COMMENT).degraded()).isTrue();
	}

	// --- images ------------------------------------------------------------------------

	/**
	 * With no image tier configured — the default for a self-hosted install — the
	 * verdict says DISABLED rather than claiming a clean pass the product never made.
	 */
	@Test
	void withNoImageTierTheVerdictIsHonestlyDisabled() {
		ModerationVerdict verdict = service.assessImage(new byte[] { 1, 2, 3 }, "image/png",
				ModerationSurface.ATTACHMENT);

		assertThat(verdict.tier()).isEqualTo(ModerationVerdict.ModerationTier.DISABLED);
		assertThat(verdict.decision()).isEqualTo(ModerationDecision.ALLOW);
	}

	@Test
	void refusesAnImageAnImageTierCondemns() {
		service = new ModerationService(policy, recorder, List.of(),
				List.of(condemningImageModerator()));

		assertThatThrownBy(() -> service.checkImage(new byte[] { 1 }, "image/png", "cat.png",
				ModerationSurface.ATTACHMENT))
				.isInstanceOf(ModerationException.class)
				.hasMessageContaining("blockedFile");
	}

	/**
	 * External ingress is the one place a silent pass is unacceptable: nobody is
	 * waiting on the response and nobody is accountable for the content.
	 */
	@Test
	void anUnavailableTierOnExternalIngressRefusesRatherThanPasses() {
		service = new ModerationService(policy, recorder, List.of(),
				List.of(throwingImageModerator()));

		assertThatThrownBy(() -> service.assessImage(new byte[] { 1 }, "image/png",
				ModerationSurface.EMAIL_ATTACHMENT))
				.isInstanceOf(ModerationException.class)
				.hasMessageContaining("unavailable");
	}

	@Test
	void anUnavailableTierOnInternalUploadFailsOpenButDegraded() {
		service = new ModerationService(policy, recorder, List.of(),
				List.of(throwingImageModerator()));

		ModerationVerdict verdict = service.assessImage(new byte[] { 1 }, "image/png",
				ModerationSurface.ATTACHMENT);

		assertThat(verdict.decision()).isEqualTo(ModerationDecision.ALLOW);
		assertThat(verdict.degraded()).isTrue();
	}

	// --- helpers ----------------------------------------------------------------------

	private static ModerationException catchModeration(Runnable action) {
		try {
			action.run();
		}
		catch (ModerationException ex) {
			return ex;
		}
		throw new AssertionError("expected a ModerationException");
	}

	/**
	 * Captures what the gate handed the recorder. Written out rather than mocked
	 * because the assertions are about the <em>payload</em> — which target, which
	 * content — and a verify() that only proves a call happened would pass against a
	 * row that says nothing a moderator could act on.
	 */
	private static final class RecordingRecorder implements ModerationRecorder {

		private final List<Recorded> recorded = new ArrayList<>();

		private record Recorded(ModerationVerdict verdict, ModerationSurface surface, Target target,
				String content) {
		}

		@Override
		public void record(ModerationVerdict verdict, ModerationSurface surface, Target target) {
			recorded.add(new Recorded(verdict, surface, target, null));
		}

		@Override
		public void record(ModerationVerdict verdict, ModerationSurface surface, Target target,
				String content) {
			recorded.add(new Recorded(verdict, surface, target, content));
		}

		@Override
		public void record(ModerationVerdict verdict, ModerationSurface surface, Target target,
				byte[] content) {
			recorded.add(new Recorded(verdict, surface, target,
					content == null ? null : new String(content, StandardCharsets.UTF_8)));
		}
	}

	private static TextModerator throwingModerator() {
		return new TextModerator() {
			@Override
			public Map<ModerationCategory, Integer> score(String text, ModerationSurface surface) {
				throw new IllegalStateException("provider down");
			}

			@Override
			public String id() {
				return "throwing";
			}
		};
	}

	private static TextModerator unavailableModerator() {
		return new TextModerator() {
			@Override
			public Map<ModerationCategory, Integer> score(String text, ModerationSurface surface) {
				throw new AssertionError("must not be called when unavailable");
			}

			@Override
			public String id() {
				return "unavailable";
			}

			@Override
			public boolean available() {
				return false;
			}
		};
	}

	private static ImageModerator condemningImageModerator() {
		return new ImageModerator() {
			@Override
			public Map<ModerationCategory, Integer> score(byte[] data, String contentType) {
				return Map.of(ModerationCategory.SEXUAL, 99);
			}

			@Override
			public boolean supports(String contentType) {
				return true;
			}

			@Override
			public String id() {
				return "condemning";
			}
		};
	}

	private static ImageModerator throwingImageModerator() {
		return new ImageModerator() {
			@Override
			public Map<ModerationCategory, Integer> score(byte[] data, String contentType) {
				throw new IllegalStateException("model not loaded");
			}

			@Override
			public boolean supports(String contentType) {
				return true;
			}

			@Override
			public String id() {
				return "throwing-image";
			}
		};
	}
}
