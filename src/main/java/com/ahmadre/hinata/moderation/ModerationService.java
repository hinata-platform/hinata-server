package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.moderation.escalation.ModerationEscalation;
import com.ahmadre.hinata.moderation.image.ImageModerator;
import com.ahmadre.hinata.moderation.image.ImageTierState;
import com.ahmadre.hinata.moderation.image.KnownIllegalHashProvider;
import com.ahmadre.hinata.moderation.text.TextModerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The gate every piece of user content passes through.
 *
 * <p>Runs the configured tiers, folds their scores into one verdict via
 * {@link ModerationPolicy}, and — for {@code check*} — refuses the write when the
 * verdict says so. It is deliberately synchronous and deliberately cheap: the
 * always-on tier is an in-process hash lookup over stripped text, so putting it on
 * the write path of every comment costs microseconds and needs no network.
 *
 * <p>Optional tiers (an external text classifier, an image model) are injected as
 * lists and may be empty — which is the default, and is what makes a self-hosted
 * instance work with no third-party dependency at all. When an optional tier is
 * configured but unreachable, the verdict is marked
 * {@link ModerationVerdict#degrade() degraded} and the content is queued for a
 * human instead of the write failing, except on surfaces where
 * {@link ModerationPolicy#failOpen} refuses that trade.
 *
 * <p><b>Recording a flag is the caller's job</b>, once it has an id to point at:
 * a queue row that cannot link to the content it is about is useless to the
 * moderator who has to judge it, and this class knows the verdict but not the
 * entity. The one exception is a <em>refusal</em>, which is recorded here — see
 * {@link #checkText}. Persistence itself stays behind {@link ModerationRecorder},
 * so the gate remains callable from a unit test, from the e-mail poller and from
 * inside a transaction without dragging a repository along.
 */
@Slf4j
@Service
public class ModerationService {

	private final ModerationPolicy policy;
	private final ModerationRecorder recorder;
	private final List<TextModerator> textModerators;
	private final List<ImageModerator> imageModerators;
	private final List<KnownIllegalHashProvider> hashProviders;
	private final List<ModerationEscalation> escalations;

	/**
	 * Without the hash tier or an escalation target — the shape every test and
	 * every install that has not been given a credentialed provider uses.
	 *
	 * <p>The constructor below carries {@code @Autowired} because this one exists:
	 * Spring resolves two unannotated constructors by preferring the shortest, and
	 * the shortest here is the one that never runs the hash check.
	 */
	public ModerationService(ModerationPolicy policy,
			ModerationRecorder recorder,
			List<TextModerator> textModerators,
			List<ImageModerator> imageModerators) {
		this(policy, recorder, textModerators, imageModerators, List.of(), List.of());
	}

	@org.springframework.beans.factory.annotation.Autowired
	public ModerationService(ModerationPolicy policy,
			ModerationRecorder recorder,
			List<TextModerator> textModerators,
			List<ImageModerator> imageModerators,
			List<KnownIllegalHashProvider> hashProviders,
			List<ModerationEscalation> escalations) {
		this.policy = policy;
		this.recorder = recorder;
		this.textModerators = List.copyOf(textModerators);
		this.imageModerators = List.copyOf(imageModerators);
		this.hashProviders = List.copyOf(hashProviders);
		this.escalations = List.copyOf(escalations);
	}

	/**
	 * Judges [text] and refuses the write when the policy says to.
	 *
	 * <p>A refusal is recorded <em>before</em> it is thrown, and that order is the
	 * whole point: the exception leaves the caller's recording step unreached, so
	 * anything not written here is never written at all — which would make refusals
	 * the one outcome of the pipeline that cannot be counted, exactly the outcome the
	 * queue exists to account for. The row carries no entity id because nothing was
	 * saved (the case {@link ModerationRecorder.Target} documents) and no author
	 * because the gate is deliberately given content and a surface, nothing else; the
	 * content hash is what ties repeated attempts together instead.
	 *
	 * @return the verdict, so a caller can record a {@link ModerationDecision#FLAG}
	 * @throws ModerationException when the content is refused
	 */
	public ModerationVerdict checkText(String text, ModerationSurface surface) {
		ModerationVerdict verdict = assessText(text, surface);
		if (verdict.isBlocking()) {
			recorder.record(verdict, surface, refusal(null), text);
			throw ModerationException.blocked(verdict, surface);
		}
		return verdict;
	}

	/**
	 * The target of a refusal: nothing but [label], because nothing was stored. A
	 * file name is worth carrying — it is what a moderator recognises the row by and
	 * it is not the content — while text refusals have no handle at all, and inventing
	 * one from the text would put the refused words in the queue.
	 */
	private static ModerationRecorder.Target refusal(String label) {
		return new ModerationRecorder.Target(null, null, null, null, label);
	}

	/** Judges [text] without throwing — for callers that handle the outcome themselves. */
	public ModerationVerdict assessText(String text, ModerationSurface surface) {
		if (!policy.textEnabled() || text == null || text.isBlank()) {
			return ModerationVerdict.disabled();
		}
		Map<ModerationCategory, Integer> scores = new EnumMap<>(ModerationCategory.class);
		boolean degraded = false;
		ModerationVerdict.ModerationTier tier = ModerationVerdict.ModerationTier.GATE;
		for (TextModerator moderator : textModerators) {
			if (!moderator.available()) {
				degraded = true;
				continue;
			}
			try {
				moderator.score(text, surface)
						.forEach((category, score) -> scores.merge(category, score, Math::max));
				if (!"lexicon".equals(moderator.id())) {
					tier = ModerationVerdict.ModerationTier.EXTERNAL;
				}
			}
			catch (RuntimeException ex) {
				// An optional tier failing must not fail the write; it makes the
				// verdict degraded so the item lands in the queue and the bypass is
				// a countable row rather than a log line nobody reads.
				log.warn("Text moderator {} failed on {}: {}", moderator.id(), surface, ex.toString());
				degraded = true;
			}
		}
		ModerationVerdict verdict = finish(scores, surface, tier, degraded);
		return verdict.matches().isEmpty() ? verdict : withEvidence(verdict, text, surface);
	}

	/**
	 * Re-asks the tiers what they matched, once a verdict already has something to
	 * explain. Deliberately a second pass rather than a richer return type from
	 * {@link TextModerator#score}: the overwhelmingly common case is that nothing
	 * fires, and that path should not pay for evidence nobody will read.
	 */
	private ModerationVerdict withEvidence(ModerationVerdict verdict, String text,
			ModerationSurface surface) {
		Map<ModerationCategory, String> evidence = new EnumMap<>(ModerationCategory.class);
		for (TextModerator moderator : textModerators) {
			if (!moderator.available()) {
				continue;
			}
			try {
				moderator.evidence(text, surface).forEach((category, detail) ->
						evidence.merge(category, detail, (a, b) -> a + "; " + b));
			}
			catch (RuntimeException ex) {
				log.debug("Evidence unavailable from {}: {}", moderator.id(), ex.toString());
			}
		}
		if (evidence.isEmpty()) {
			return verdict;
		}
		List<ModerationVerdict.Match> enriched = verdict.matches().stream()
				.map(match -> new ModerationVerdict.Match(match.category(), match.score(),
						evidence.get(match.category())))
				.toList();
		return new ModerationVerdict(verdict.decision(), enriched, verdict.tier(), verdict.degraded());
	}

	/**
	 * Judges uploaded bytes and refuses the upload when the policy says to.
	 *
	 * <p>Recorded before the throw for the reason {@link #checkText} sets out, and
	 * with the bytes rather than a rendering of them: a refused upload is the case
	 * where the hash earns the most, because "the same file was retried forty times"
	 * and "forty malicious files arrived" are the same row without it.
	 *
	 * @throws ModerationException when the file is refused
	 */
	public ModerationVerdict checkImage(byte[] data, String contentType, String fileName,
			ModerationSurface surface) {
		return checkImage(data, contentType, data, contentType, fileName, surface);
	}

	/**
	 * As {@link #checkImage(byte[], String, String, ModerationSurface)}, for the two
	 * surfaces that <b>re-encode before storing</b>: the hash tier judges
	 * {@code uploaded}, the classifiers judge {@code stored}.
	 *
	 * <p>Avatars are recompressed to JPEG and the organisation logo is normalised to
	 * PNG, and both used to hand the re-encoded result to the whole pipeline. For a
	 * classifier that is right — the stored image is what every colleague will
	 * actually see, and it is the version certain to be a decodable raster. For the
	 * hash tier it is wrong in a way that silently disables it: an exact-hash
	 * programme matches a byte-for-byte digest of a known file, and a JPEG this
	 * server just re-encoded is byte-for-byte nothing at all. The check ran, cost a
	 * metered call, and could not have matched on those two surfaces however many
	 * known files were uploaded. Perceptual providers were unaffected, which is
	 * exactly why nobody would have noticed.
	 *
	 * <p>Two arrays rather than a second call, because the tier is a network call to
	 * a metered external programme — {@link #judgeImage} already refuses to run it
	 * twice per upload for that reason, and adding a separate "check the original"
	 * entry point would have reintroduced precisely that cost.
	 *
	 * @throws ModerationException when the file is refused
	 */
	public ModerationVerdict checkImage(byte[] uploaded, String uploadedType, byte[] stored,
			String storedType, String fileName, ModerationSurface surface) {
		Judgement judgement =
				judgeImage(uploaded, uploadedType, stored, storedType, fileName, surface);
		ModerationVerdict verdict = judgement.verdict();
		if (verdict.isBlocking()) {
			if (!judgement.recorded()) {
				// The stored bytes, matching what the classifiers judged. A refusal on
				// this path is a classifier refusal; the hash path records its own row,
				// with the uploaded bytes, inside recordKnownIllegal.
				recorder.record(verdict, surface, refusal(fileName), stored);
			}
			throw ModerationException.blockedFile(verdict, surface, fileName);
		}
		return verdict;
	}

	/**
	 * A verdict plus whether the row for it has already been written.
	 *
	 * <p>Only one path sets {@link #recorded()}: a known-illegal match, which has to
	 * write its own row because that row carries a field no other call may pass
	 * ({@link ModerationRecorder#recordKnownIllegal}) and because the escalation it
	 * raises needs the row's id. The flag exists so {@link #checkImage} does not
	 * then write a second, poorer row for the same refusal.
	 */
	private record Judgement(ModerationVerdict verdict, boolean recorded) {

		static Judgement of(ModerationVerdict verdict) {
			return new Judgement(verdict, false);
		}
	}

	/**
	 * Whether images are actually being classified right now.
	 *
	 * <p>Not derivable from {@link ModerationPolicy#imageEnabled()} alone, which is
	 * the whole point: policy states an intention, and with no tier installed that
	 * intention has no effect on a single upload. Reported to the admin panel and
	 * logged once at startup so the difference is visible somewhere.
	 */
	public ImageTierState imageTierState() {
		if (!policy.imageEnabled()) {
			return ImageTierState.DISABLED_BY_POLICY;
		}
		if (imageModerators.isEmpty()) {
			return ImageTierState.NOT_CONFIGURED;
		}
		// Availability, not mere presence: a sidecar whose model failed to load is a
		// bean that exists and classifies nothing, which is the case an operator most
		// needs told apart from a working one.
		return imageModerators.stream().anyMatch(ImageModerator::available)
				? ImageTierState.ACTIVE
				: ImageTierState.CONFIGURED_UNAVAILABLE;
	}

	/**
	 * Says once, on startup, that images are not being checked.
	 *
	 * <p>At {@code WARN} and naming the key that would change it, because the
	 * alternative — a toggle in the admin panel reading "on" — actively tells the
	 * operator the opposite. Deliberately not a startup failure: running without an
	 * image tier is a supported configuration, it just must not be a silent one.
	 */
	@EventListener(ApplicationReadyEvent.class)
	void reportImageTierOnStartup() {
		ImageTierState state = imageTierState();
		if (state == ImageTierState.NOT_CONFIGURED) {
			log.warn("Image moderation is enabled but no classifier is installed — uploaded images "
					+ "are NOT being checked. Set hinata.moderation.image.endpoint to a running "
					+ "moderation sidecar, or set hinata.moderation.image-enabled=false to make "
					+ "this explicit.");
		}
		else if (state == ImageTierState.CONFIGURED_UNAVAILABLE) {
			log.warn("An image classifier is configured but reports itself unavailable — uploaded "
					+ "images are NOT being checked until it recovers.");
		}
	}

	/**
	 * Judges uploaded bytes without throwing — except for the one check that is
	 * allowed to.
	 *
	 * <p>{@link #matchKnownIllegal} runs first and refuses when it cannot run, which
	 * is a deliberate exception to this method's "does not throw" contract and is
	 * why the contract is stated with one. Everything else here is a score folded
	 * into a verdict the caller decides about; that check is not a score, and there
	 * is no verdict that honestly represents "the mandatory check did not happen".
	 */
	public ModerationVerdict assessImage(byte[] data, String contentType, ModerationSurface surface) {
		return judgeImage(data, contentType, data, contentType, null, surface).verdict();
	}

	/**
	 * The whole image pipeline, in the order the checks have to run.
	 *
	 * <p>Shared by {@link #checkImage} and {@link #assessImage} rather than one
	 * calling the other, because the hash tier is a network call to a metered
	 * external programme and running it twice per upload is not a cost worth paying
	 * for a call-graph shape. The consequence — that neither entry point can skip
	 * it — is the whole point: a check that only the enforcing caller runs is a
	 * check any future non-enforcing caller silently walks around.
	 *
	 * @param uploaded the bytes as they arrived, which is what the hash tier has to
	 *                 see; equal to {@code stored} for every caller that does not
	 *                 re-encode
	 * @param stored   the bytes that will be kept, which is what a classifier should
	 *                 score — it is what readers will see, and it is the version
	 *                 certain to be a decodable raster
	 */
	private Judgement judgeImage(byte[] uploaded, String uploadedType, byte[] stored,
			String storedType, String fileName, ModerationSurface surface) {
		if (stored == null || stored.length == 0) {
			return Judgement.of(ModerationVerdict.disabled());
		}
		// Before the classifiers, and before the imageEnabled switch. A hash match is
		// not image classification and is not what that switch is about: an admin who
		// turns image scoring off is saying "do not run a model over our screenshots",
		// not "accept material an accredited body has already adjudicated". The
		// category is non-overridable, so the switch that could disable this is one
		// the policy already refuses to honour.
		//
		// And on the UPLOADED bytes, which for every caller but two are the same array.
		// For the two that re-encode — the avatar and the organisation logo — an exact
		// hash of the server's own JPEG could never have matched anything, so this
		// check was running and unable to succeed on exactly the surfaces whose images
		// are seen most widely.
		byte[] toHash = uploaded == null || uploaded.length == 0 ? stored : uploaded;
		String hashType = uploaded == null || uploaded.length == 0 ? storedType : uploadedType;
		Optional<KnownIllegalHashProvider.HashMatch> illegal =
				matchKnownIllegal(toHash, hashType, surface);
		if (illegal.isPresent()) {
			// The uploaded bytes, not the stored ones: the row exists so that "the same
			// file was retried forty times" is one row, and the digest that identifies
			// the file is the one of what arrived.
			return recordKnownIllegal(illegal.get(), toHash, fileName, surface);
		}
		if (!policy.imageEnabled()) {
			return Judgement.of(ModerationVerdict.disabled());
		}
		Map<ModerationCategory, Integer> scores = new EnumMap<>(ModerationCategory.class);
		boolean degraded = false;
		boolean ran = false;
		for (ImageModerator moderator : imageModerators) {
			if (!moderator.supports(storedType) || !moderator.available()) {
				continue;
			}
			try {
				moderator.score(stored, storedType)
						.forEach((category, score) -> scores.merge(category, score, Math::max));
				ran = true;
			}
			catch (RuntimeException ex) {
				log.warn("Image moderator {} failed on {}: {}", moderator.id(), surface, ex.toString());
				degraded = true;
			}
		}
		if (!ran && !degraded) {
			// No classifier is configured for this type. That is the documented
			// default for a self-hosted install, and it is honest about it: the
			// bytes were not judged, so the verdict says DISABLED rather than
			// claiming a clean pass the product never actually made.
			return Judgement.of(ModerationVerdict.disabled());
		}
		if (degraded && !policy.failOpen(surface)) {
			throw ModerationException.unavailable(surface);
		}
		return Judgement.of(
				finish(scores, surface, ModerationVerdict.ModerationTier.LOCAL_MODEL, degraded));
	}

	// --- known-illegal hash matching ---------------------------------------------

	/**
	 * Asks every configured hash provider, and refuses when one cannot answer.
	 *
	 * <p>The asymmetry with the classifier loop directly above is the point.
	 * There, an unavailable tier sets {@code degraded} and the upload continues,
	 * because a classifier is an optional opinion and the surface rule decides what
	 * that costs. Here, unavailability throws — on <em>every</em> surface, whatever
	 * {@code failOpen} is set to, expressed through
	 * {@link ModerationCheck#KNOWN_ILLEGAL_HASH} so the rule sits in the policy
	 * rather than in this loop.
	 *
	 * <p>An operator who configured a provider asked for these bytes to be checked
	 * against an adjudicated list before they are stored. "It was unreachable, so we
	 * stored them" is not a degraded version of that answer.
	 *
	 * @return the first match; providers after it are not asked, because a second
	 *         opinion on material already adjudicated adds nothing and the outcome
	 *         is identical
	 */
	private Optional<KnownIllegalHashProvider.HashMatch> matchKnownIllegal(byte[] data,
			String contentType, ModerationSurface surface) {
		for (KnownIllegalHashProvider provider : hashProviders) {
			try {
				if (!provider.available()) {
					throw new IllegalStateException("provider unavailable");
				}
				Optional<KnownIllegalHashProvider.HashMatch> match = provider.match(data, contentType);
				if (match != null && match.isPresent()) {
					return match;
				}
			}
			catch (RuntimeException ex) {
				// The message deliberately names the provider and not the payload:
				// this string reaches a log line, and the payload is the one thing
				// that must not.
				log.error("Known-illegal hash provider {} could not check an upload on {} — refusing "
						+ "the upload: {}", provider.id(), surface, ex.toString());
				if (!policy.failOpen(surface, ModerationCheck.KNOWN_ILLEGAL_HASH)) {
					throw ModerationException.unavailable(surface);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * The verdict a match produces. Not computed from a score and not passed
	 * through {@link ModerationPolicy#decide}: there is no confidence to compare
	 * against a threshold, so 100 is not a score here but a statement that the
	 * question of confidence does not arise.
	 */
	private static ModerationVerdict knownIllegalVerdict() {
		return new ModerationVerdict(ModerationDecision.BLOCK,
				List.of(new ModerationVerdict.Match(ModerationCategory.SEXUAL_MINORS, 100, null)),
				ModerationVerdict.ModerationTier.EXTERNAL, false);
	}

	/**
	 * Writes the row and raises the escalation for a match.
	 *
	 * <p>The row carries the programme's reference; the escalation deliberately does
	 * not. Those two sentences are the whole of this method's design — the reference
	 * identifies adjudicated material inside a hash programme, so it is stored where
	 * only the server can read it and is excluded from the one payload that leaves
	 * the machine.
	 *
	 * <p>Escalation failures are not this method's problem to solve: the adapter
	 * audits them, and there is nothing sensible to do here with the knowledge that
	 * an endpoint was down. The upload is refused either way.
	 */
	private Judgement recordKnownIllegal(KnownIllegalHashProvider.HashMatch match, byte[] data,
			String fileName, ModerationSurface surface) {
		ModerationVerdict verdict = knownIllegalVerdict();
		String recordId = recorder.recordKnownIllegal(verdict, surface, refusal(fileName), data,
				reference(match));
		// The record id, not the file name. Nothing was stored, so there is no object
		// key to point at — but a file name is content: it is chosen by whoever
		// uploaded it, it routinely describes what the file depicts, and this payload
		// travels to an operator-supplied webhook outside the product. The record it
		// names carries everything the operator's incident process needs, behind
		// their own authentication.
		escalate(new ModerationEscalation.Event(recordId, ModerationCategory.SEXUAL_MINORS, surface,
				recordId == null ? null : "record:" + recordId, Instant.now()));
		return new Judgement(verdict, true);
	}

	/** {@code source:reference}, or just one of them when the provider omitted the other. */
	private static String reference(KnownIllegalHashProvider.HashMatch match) {
		if (match.source() == null || match.source().isBlank()) {
			return match.reference();
		}
		return match.source() + ":" + match.reference();
	}

	/**
	 * Hands [event] to every configured escalation target.
	 *
	 * <p>Wrapped, because an escalation adapter is the one collaborator here that
	 * talks to somebody else's network. It must not be able to turn a refusal that
	 * has already been decided and recorded into a 500 for the uploader.
	 */
	private void escalate(ModerationEscalation.Event event) {
		for (ModerationEscalation target : escalations) {
			try {
				target.escalate(event);
			}
			catch (RuntimeException ex) {
				log.error("Escalation target {} failed: {}", target.id(), ex.toString());
			}
		}
	}

	/**
	 * Turns per-category scores into one verdict, keeping the strictest decision and
	 * every category that reached at least the flag band — so a moderator sees the
	 * full picture even though the user is only told the primary reason.
	 */
	private ModerationVerdict finish(Map<ModerationCategory, Integer> scores, ModerationSurface surface,
			ModerationVerdict.ModerationTier tier, boolean degraded) {
		ModerationDecision decision = ModerationDecision.ALLOW;
		List<ModerationVerdict.Match> matches = new ArrayList<>();
		for (Map.Entry<ModerationCategory, Integer> entry : scores.entrySet()) {
			ModerationDecision categoryDecision = policy.decide(entry.getKey(), entry.getValue(), surface);
			if (categoryDecision != ModerationDecision.ALLOW) {
				matches.add(new ModerationVerdict.Match(entry.getKey(), entry.getValue(), null));
				decision = decision.max(categoryDecision);
			}
		}
		ModerationVerdict verdict = new ModerationVerdict(decision, matches, tier, degraded);
		return degraded ? verdict.degrade() : verdict;
	}
}
