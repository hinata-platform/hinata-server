package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.moderation.escalation.ModerationEscalation;
import com.ahmadre.hinata.moderation.image.ImageModerator;
import com.ahmadre.hinata.moderation.image.KnownIllegalHashProvider;
import com.ahmadre.hinata.moderation.text.LexiconTextModerator;
import com.ahmadre.hinata.moderation.text.TextModerator;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

	// --- known-illegal hash matching (WP-3 §6.1/§6.2) ---------------------------------

	/**
	 * A match is a refusal, in the one category no setting can weaken, from the tier
	 * that names an external body — and nothing about it is a score.
	 */
	@Test
	void aKnownIllegalHashMatchBlocksAsSexualMinorsFromTheExternalTier() {
		service = new ModerationService(policy, recorder, List.of(), List.of(),
				List.of(matchingProvider()), List.of());

		ModerationException thrown = catchModeration(() -> service.checkImage(new byte[] { 1 },
				"image/png", "cat.png", ModerationSurface.ATTACHMENT));

		assertThat(thrown.getVerdict().decision()).isEqualTo(ModerationDecision.BLOCK);
		assertThat(thrown.getVerdict().primaryCategory()).isEqualTo(ModerationCategory.SEXUAL_MINORS);
		assertThat(thrown.getVerdict().primary().score()).isEqualTo(100);
		assertThat(thrown.getVerdict().tier()).isEqualTo(ModerationVerdict.ModerationTier.EXTERNAL);
	}

	/**
	 * The verdict that reaches the author carries no evidence — and here that word
	 * means the hash programme's own reference, which is the handle by which an
	 * accredited body identifies adjudicated material. Handing it back would be worse
	 * than the lexicon oracle {@code refusalDoesNotDiscloseTheMatchedTerm} guards
	 * against: not a term to rephrase around, but an identifier inside somebody
	 * else's corpus.
	 */
	@Test
	void theRefusalHandedToTheAuthorCarriesNoProviderReference() {
		service = new ModerationService(policy, recorder, List.of(), List.of(),
				List.of(matchingProvider()), List.of());

		ModerationException thrown = catchModeration(() -> service.checkImage(new byte[] { 1 },
				"image/png", "cat.png", ModerationSurface.ATTACHMENT));

		assertThat(thrown.getVerdict().matches())
				.isNotEmpty()
				.allSatisfy(match -> assertThat(match.evidence()).isNull());
		assertThat(thrown.getMessage()).doesNotContain("REF-9911");
	}

	/** The reference is written to the record — the one place it may exist. */
	@Test
	void theProviderReferenceIsRecordedOnTheRowAndNowhereElse() {
		service = new ModerationService(policy, recorder, List.of(), List.of(),
				List.of(matchingProvider()), List.of());

		catchModeration(() -> service.checkImage(new byte[] { 1 }, "image/png", "cat.png",
				ModerationSurface.ATTACHMENT));

		assertThat(recorder.recorded).hasSize(1);
		RecordingRecorder.Recorded row = recorder.recorded.getFirst();
		assertThat(row.externalReference()).isEqualTo("photodna:REF-9911");
		assertThat(row.target().label()).isEqualTo("cat.png");
		// One row, not two: the known-illegal path writes its own and checkImage must
		// not then add a second, poorer one for the same refusal.
		assertThat(row.verdict().primaryCategory()).isEqualTo(ModerationCategory.SEXUAL_MINORS);
	}

	/**
	 * A match escalates, and the payload is a pointer and a classification. The
	 * negative half is the point: no bytes, no file content, and above all not the
	 * reference — the escalation leaves the machine, and that value must not.
	 */
	@Test
	void aMatchEscalatesWithoutContentAndWithoutTheHashReference() {
		RecordingEscalation escalation = new RecordingEscalation();
		service = new ModerationService(policy, recorder, List.of(), List.of(),
				List.of(matchingProvider()), List.of(escalation));

		catchModeration(() -> service.checkImage("bytes".getBytes(StandardCharsets.UTF_8),
				"image/png", "cat.png", ModerationSurface.ATTACHMENT));

		assertThat(escalation.events).hasSize(1);
		ModerationEscalation.Event event = escalation.events.getFirst();
		assertThat(event.category()).isEqualTo(ModerationCategory.SEXUAL_MINORS);
		assertThat(event.surface()).isEqualTo(ModerationSurface.ATTACHMENT);
		assertThat(event.recordId()).isNotBlank();
		assertThat(event.at()).isNotNull();
		// The hash reference and the bytes were already asserted absent. The file
		// name is the one that got through: it is chosen by the uploader, routinely
		// describes what the file depicts, and this payload leaves the product for an
		// operator-supplied webhook. Asserting on the whole event rather than one
		// field, because the leak was in a field nobody thought to look at.
		assertThat(event.toString())
				.doesNotContain("REF-9911")
				.doesNotContain("photodna")
				.doesNotContain("bytes")
				// Not a bare "cat": that is a substring of "category=", which would
				// fail on the field name rather than on a leak.
				.doesNotContain("cat.png");
		assertThat(event.reference()).isEqualTo("record:" + event.recordId());
	}

	/**
	 * A configured-but-unavailable provider refuses the upload on <em>every</em>
	 * surface, including the internal ones where an unavailable classifier is
	 * explicitly allowed to degrade and pass. That contrast is the test: the same
	 * surface, the same failOpen setting, opposite outcomes, because the check is
	 * different in kind rather than in confidence.
	 */
	@ParameterizedTest
	@EnumSource(ModerationSurface.class)
	void anUnavailableHashProviderRefusesOnEverySurface(ModerationSurface surface) {
		service = new ModerationService(policy, recorder, List.of(), List.of(),
				List.of(unavailableProvider()), List.of());

		assertThatThrownBy(() -> service.checkImage(new byte[] { 1 }, "image/png", "cat.png", surface))
				.isInstanceOf(ModerationException.class)
				.hasMessageContaining("unavailable");
	}

	/** And it is not degrade-and-pass: nothing about the verdict says "queued". */
	@Test
	void anUnavailableHashProviderDoesNotDegradeAndPass() {
		service = new ModerationService(policy, recorder, List.of(), List.of(),
				List.of(unavailableProvider()), List.of());

		assertThatThrownBy(() -> service.assessImage(new byte[] { 1 }, "image/png",
				ModerationSurface.ATTACHMENT))
				.isInstanceOf(ModerationException.class);
	}

	/**
	 * Not even with fail-open turned on and image classification switched off — the
	 * two settings that make an unavailable classifier pass. Neither reaches this
	 * check, which is what {@code ModerationCheck.KNOWN_ILLEGAL_HASH} encodes.
	 */
	@Test
	void noAdminSettingLetsAnUnavailableHashProviderThrough() {
		settings.getModeration().setFailOpen(true);
		settings.getModeration().setImageEnabled(false);
		service = new ModerationService(policy, recorder, List.of(), List.of(),
				List.of(unavailableProvider()), List.of());

		assertThatThrownBy(() -> service.checkImage(new byte[] { 1 }, "image/png", "cat.png",
				ModerationSurface.ATTACHMENT))
				.isInstanceOf(ModerationException.class);
	}

	/** A working provider that finds nothing must not change a single upload. */
	@Test
	void aCleanProviderLeavesTheUploadExactlyAsItWas() {
		service = new ModerationService(policy, recorder, List.of(), List.of(),
				List.of(cleanProvider()), List.of());

		ModerationVerdict verdict = service.assessImage(new byte[] { 1 }, "image/png",
				ModerationSurface.ATTACHMENT);

		assertThat(verdict.decision()).isEqualTo(ModerationDecision.ALLOW);
		assertThat(verdict.degraded()).isFalse();
		assertThat(recorder.recorded).isEmpty();
	}

	/**
	 * Turning image classification off does not turn the hash check off. An admin
	 * disabling the model is saying "do not score our screenshots", and reading that
	 * as "accept material an accredited body already adjudicated" would make a
	 * checkbox weaken the one category the policy refuses to let anyone weaken.
	 */
	@Test
	void switchingImageModerationOffDoesNotSwitchOffTheHashCheck() {
		settings.getModeration().setImageEnabled(false);
		service = new ModerationService(policy, recorder, List.of(), List.of(),
				List.of(matchingProvider()), List.of());

		assertThatThrownBy(() -> service.checkImage(new byte[] { 1 }, "image/png", "cat.png",
				ModerationSurface.ATTACHMENT))
				.isInstanceOf(ModerationException.class);
	}

	/**
	 * The hash tier runs BEFORE the classifiers. Asserted through a classifier that
	 * would throw if it were reached: order is not observable from the verdict — both
	 * paths refuse — and an implementation that scored first and matched second would
	 * send the bytes to a sidecar that has no business receiving them.
	 */
	@Test
	void theHashTierRunsBeforeTheClassifierTiers() {
		service = new ModerationService(policy, recorder, List.of(),
				List.of(explodingImageModerator()), List.of(matchingProvider()), List.of());

		assertThatThrownBy(() -> service.checkImage(new byte[] { 1 }, "image/png", "cat.png",
				ModerationSurface.ATTACHMENT))
				.isInstanceOf(ModerationException.class);
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
				String content, String externalReference) {
		}

		@Override
		public String record(ModerationVerdict verdict, ModerationSurface surface, Target target) {
			return add(new Recorded(verdict, surface, target, null, null));
		}

		@Override
		public String record(ModerationVerdict verdict, ModerationSurface surface, Target target,
				String content) {
			return add(new Recorded(verdict, surface, target, content, null));
		}

		@Override
		public String record(ModerationVerdict verdict, ModerationSurface surface, Target target,
				byte[] content) {
			return add(new Recorded(verdict, surface, target, text(content), null));
		}

		@Override
		public String recordKnownIllegal(ModerationVerdict verdict, ModerationSurface surface,
				Target target, byte[] content, String externalReference) {
			return add(new Recorded(verdict, surface, target, text(content), externalReference));
		}

		private String add(Recorded row) {
			recorded.add(row);
			return "record-" + recorded.size();
		}

		private static String text(byte[] content) {
			return content == null ? null : new String(content, StandardCharsets.UTF_8);
		}
	}

	/** An escalation target that keeps what it was handed instead of sending it. */
	private static final class RecordingEscalation implements ModerationEscalation {

		private final List<Event> events = new ArrayList<>();

		@Override
		public void escalate(Event event) {
			events.add(event);
		}

		@Override
		public String id() {
			return "recording";
		}
	}

	/** A provider that matches everything it is shown. */
	private static KnownIllegalHashProvider matchingProvider() {
		return new KnownIllegalHashProvider() {
			@Override
			public Optional<HashMatch> match(byte[] data, String contentType) {
				return Optional.of(new HashMatch("photodna", "REF-9911"));
			}

			@Override
			public String id() {
				return "matching";
			}

			@Override
			public boolean available() {
				return true;
			}
		};
	}

	/** A provider that never matches — the shape of a working, quiet subscription. */
	private static KnownIllegalHashProvider cleanProvider() {
		return new KnownIllegalHashProvider() {
			@Override
			public Optional<HashMatch> match(byte[] data, String contentType) {
				return Optional.empty();
			}

			@Override
			public String id() {
				return "clean";
			}

			@Override
			public boolean available() {
				return true;
			}
		};
	}

	/** A provider that is installed and cannot answer. */
	private static KnownIllegalHashProvider unavailableProvider() {
		return new KnownIllegalHashProvider() {
			@Override
			public Optional<HashMatch> match(byte[] data, String contentType) {
				throw new AssertionError("must not be asked while unavailable");
			}

			@Override
			public String id() {
				return "down";
			}

			@Override
			public boolean available() {
				return false;
			}
		};
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
	private static ImageModerator explodingImageModerator() {
		return new ImageModerator() {
			@Override
			public Map<ModerationCategory, Integer> score(byte[] data, String contentType) {
				throw new AssertionError("the classifier must never see bytes the hash tier matched");
			}

			@Override
			public boolean supports(String contentType) {
				return true;
			}

			@Override
			public String id() {
				return "exploding";
			}
		};
	}
}
