package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.moderation.image.ImageModerator;
import com.ahmadre.hinata.moderation.text.TextModerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

	public ModerationService(ModerationPolicy policy,
			ModerationRecorder recorder,
			List<TextModerator> textModerators,
			List<ImageModerator> imageModerators) {
		this.policy = policy;
		this.recorder = recorder;
		this.textModerators = List.copyOf(textModerators);
		this.imageModerators = List.copyOf(imageModerators);
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
		ModerationVerdict verdict = assessImage(data, contentType, surface);
		if (verdict.isBlocking()) {
			recorder.record(verdict, surface, refusal(fileName), data);
			throw ModerationException.blockedFile(verdict, surface, fileName);
		}
		return verdict;
	}

	/** Judges uploaded bytes without throwing. */
	public ModerationVerdict assessImage(byte[] data, String contentType, ModerationSurface surface) {
		if (!policy.imageEnabled() || data == null || data.length == 0) {
			return ModerationVerdict.disabled();
		}
		Map<ModerationCategory, Integer> scores = new EnumMap<>(ModerationCategory.class);
		boolean degraded = false;
		boolean ran = false;
		for (ImageModerator moderator : imageModerators) {
			if (!moderator.supports(contentType) || !moderator.available()) {
				continue;
			}
			try {
				moderator.score(data, contentType)
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
			return ModerationVerdict.disabled();
		}
		if (degraded && !policy.failOpen(surface)) {
			throw ModerationException.unavailable(surface);
		}
		return finish(scores, surface, ModerationVerdict.ModerationTier.LOCAL_MODEL, degraded);
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
