package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Single source of truth for the runtime moderation policy, resolved DB-over-env
 * exactly like {@link com.ahmadre.hinata.auth.SecurityPolicy}: an admin override
 * stored on {@link ServerSettings.Moderation} wins, otherwise the env-driven
 * {@link HinataProperties} default. Every enforcement point reads through here so
 * the admin panel is the actual source of truth and no threshold is hardcoded
 * downstream. The resolved block is cached and refreshed on
 * {@link SettingsService.SettingsChangedEvent}, because this sits on the write path
 * of every comment in the product and must not hit Mongo per request.
 *
 * <h2>Why a score becomes a different decision on different surfaces</h2>
 *
 * <p>The hard part of moderating a bug tracker is not detecting profanity, it is
 * not destroying the product while doing so. Two asymmetries drive the whole
 * design:
 *
 * <ol>
 *   <li><b>A refused long-form body is lost work.</b> Someone who spent ten minutes
 *       writing a defect report, hit save, and got "your content was rejected" does
 *       not carefully rewrite it — they give up, or they paste it into a chat where
 *       nobody can moderate it at all. So long-form prose has to be very obviously
 *       abusive before it is refused; the middle band is flagged and a human looks.
 *       A short display name has no such cost: retyping a project name is trivial,
 *       and the name is what everyone sees in every list, so it is gated hard.</li>
 *   <li><b>Engineering prose looks violent.</b> "kill -9", "abort the transaction",
 *       "nuke the cache", "attack vector", "exploit chain", "master/slave" are all
 *       ordinary and all score on a violence axis. {@link ModerationSurface#technical()}
 *       surfaces therefore get {@link #TECHNICAL_VIOLENCE_RELIEF} added to their
 *       violence and threat thresholds — on top of the code being stripped out
 *       before scoring at all.</li>
 * </ol>
 *
 * <p>The one place this reasoning stops is
 * {@link ModerationCategory#SEXUAL_MINORS}: it is not relieved on technical
 * surfaces, not loosenable by an admin, and not subject to the degrade path. See
 * {@link #isOverridable}.
 */
@Component
@RequiredArgsConstructor
public class ModerationPolicy {

	/**
	 * Score at or above which content is refused outright, when no override is set.
	 * Deliberately high: the built-in lexicon only reaches it on an unambiguous term
	 * that survived the allowlist and the code stripper.
	 */
	public static final int DEFAULT_BLOCK_THRESHOLD = 85;

	/** Score at or above which content is stored but queued for a human. */
	public static final int DEFAULT_FLAG_THRESHOLD = 55;

	/**
	 * Floor for the block threshold on the categories an admin may tune. Stops a
	 * well-meaning "make it stricter" from turning every ticket containing a swear
	 * word into a refusal, which is the failure mode that gets moderation switched
	 * off entirely.
	 *
	 * <p>It also has to stay strictly above
	 * {@link com.ahmadre.hinata.moderation.text.LexiconTextModerator#TECHNICAL_SPAN_CEILING}:
	 * that is what guarantees a match found inside a stripped-out code fence can
	 * only ever queue content for review, never refuse it. The two constants are
	 * checked against each other in {@code ModerationPolicyTest}, because if they
	 * ever cross, pasting a log file starts rejecting bug reports.
	 */
	public static final int BLOCK_THRESHOLD_FLOOR = 65;

	/**
	 * Added to the block/flag thresholds for violence-shaped categories on technical
	 * surfaces. Sized so a single ordinary engineering idiom cannot reach the block
	 * band on its own while a genuine threat, which stacks several signals, still
	 * can.
	 */
	public static final int TECHNICAL_VIOLENCE_RELIEF = 25;

	/**
	 * Subtracted from thresholds on {@link ModerationSurface#external()} surfaces —
	 * inbound e-mail, proxied images, webhook commit messages. The author is not a
	 * colleague who signed in, the content is unsolicited, and a false positive
	 * costs an external sender a bounce rather than a colleague their work.
	 */
	public static final int EXTERNAL_STRICTNESS = 15;

	/** Categories no admin setting may weaken or disable. */
	private static final Set<ModerationCategory> NON_OVERRIDABLE =
			EnumSet.of(ModerationCategory.SEXUAL_MINORS, ModerationCategory.MALWARE);

	/** Categories that engineering vocabulary routinely trips. */
	private static final Set<ModerationCategory> VIOLENCE_SHAPED =
			EnumSet.of(ModerationCategory.VIOLENCE, ModerationCategory.VIOLENT_THREAT);

	private final SettingsService settings;
	private final HinataProperties properties;

	private volatile ServerSettings.Moderation cached;

	@EventListener
	void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		cached = event.settings().getModeration();
	}

	/** Whether moderation runs at all. */
	public boolean enabled() {
		Boolean override = db().getEnabled();
		return override != null ? override : properties.getModeration().isEnabled();
	}

	/** Whether user-authored text is scanned. */
	public boolean textEnabled() {
		Boolean override = db().getTextEnabled();
		return enabled() && (override != null ? override : properties.getModeration().isTextEnabled());
	}

	/** Whether uploaded images are classified. */
	public boolean imageEnabled() {
		Boolean override = db().getImageEnabled();
		return enabled() && (override != null ? override : properties.getModeration().isImageEnabled());
	}

	/**
	 * Effective score at or above which content on [surface] is refused for
	 * [category], with the surface adjustments applied.
	 */
	public int blockThreshold(ModerationCategory category, ModerationSurface surface) {
		return thresholdFor(category, surface, baseBlockThreshold(category));
	}

	/** Effective score at or above which content on [surface] is queued for review. */
	public int flagThreshold(ModerationCategory category, ModerationSurface surface) {
		int flag = thresholdFor(category, surface, baseFlagThreshold(category));
		// A flag threshold above the block threshold would create a dead band where
		// content is refused without ever having been reviewable.
		return Math.min(flag, blockThreshold(category, surface));
	}

	/**
	 * Turns a score into a decision for one category on one surface. The single
	 * place the score-to-action mapping lives, so no classifier client decides
	 * policy.
	 */
	public ModerationDecision decide(ModerationCategory category, int score, ModerationSurface surface) {
		if (score >= blockThreshold(category, surface)) {
			return blocksOn(category, surface) ? ModerationDecision.BLOCK : ModerationDecision.FLAG;
		}
		if (score >= flagThreshold(category, surface)) {
			return ModerationDecision.FLAG;
		}
		return ModerationDecision.ALLOW;
	}

	/**
	 * Whether a refusal is an acceptable outcome for this category on this surface.
	 *
	 * <p>Long-form bodies are normally exempt: on the "flag only" setting they are
	 * never refused, however high the score, because losing a written defect report
	 * to a false positive is a worse outcome for a bug tracker than showing an
	 * abusive one to a moderator an hour later. Everything short — names, titles,
	 * profiles — is always refusable, and so is every binary.
	 *
	 * <p><b>The exemption stops at the non-overridable categories.</b> "Never lose a
	 * defect report" is a trade about false positives in ordinary prose; it is not a
	 * reason to accept child sexual content or a malicious file because it arrived
	 * in a description field rather than a project name. Checking the surface alone
	 * here silently converts the one guarantee this class makes into a suggestion.
	 */
	private boolean blocksOn(ModerationCategory category, ModerationSurface surface) {
		if (!isOverridable(category) || !longFormFlagOnly()) {
			return true;
		}
		return switch (surface) {
			case ISSUE_DESCRIPTION, COMMENT, ARTICLE_CONTENT, WORKLOG -> false;
			default -> true;
		};
	}

	/** Whether long-form bodies are flagged rather than refused. */
	public boolean longFormFlagOnly() {
		Boolean override = db().getLongFormFlagOnly();
		return override != null ? override : properties.getModeration().isLongFormFlagOnly();
	}

	/**
	 * Whether [category] may be weakened or switched off by an admin. False for
	 * child sexual content and malware: a tenant setting must not be able to make a
	 * self-hosted instance a safe place to put either.
	 */
	public boolean isOverridable(ModerationCategory category) {
		return !NON_OVERRIDABLE.contains(category);
	}

	/**
	 * Whether the write may proceed when a tier is unavailable.
	 *
	 * <p>Fail-open for everything the deterministic gate already covers, because an
	 * unreachable optional classifier must not take the product down — but the
	 * verdict is marked {@link ModerationVerdict#degraded()} and lands in the queue,
	 * so the bypass is a row someone can count rather than a silent pass.
	 */
	public boolean failOpen(ModerationSurface surface) {
		Boolean override = db().getFailOpen();
		boolean open = override != null ? override : properties.getModeration().isFailOpen();
		// External ingress is the one place a silent pass is not acceptable: nobody
		// is accountable for the content and nobody is waiting on the response.
		return open && !surface.external();
	}

	private int baseBlockThreshold(ModerationCategory category) {
		if (!isOverridable(category)) {
			return properties.getModeration().getStrictBlockThreshold();
		}
		Integer override = db().getBlockThreshold();
		int value = override != null ? override : properties.getModeration().getBlockThreshold();
		return Math.max(BLOCK_THRESHOLD_FLOOR, value);
	}

	private int baseFlagThreshold(ModerationCategory category) {
		if (!isOverridable(category)) {
			return properties.getModeration().getStrictFlagThreshold();
		}
		Integer override = db().getFlagThreshold();
		return override != null ? override : properties.getModeration().getFlagThreshold();
	}

	/**
	 * Applies the surface adjustments to a base threshold. Non-overridable
	 * categories are returned untouched — they are neither relieved on technical
	 * surfaces nor further tightened, so their behaviour is identical everywhere.
	 */
	private int thresholdFor(ModerationCategory category, ModerationSurface surface, int base) {
		if (!isOverridable(category)) {
			return base;
		}
		int value = base;
		if (surface.technical() && VIOLENCE_SHAPED.contains(category)) {
			value += TECHNICAL_VIOLENCE_RELIEF;
		}
		if (surface.external()) {
			value -= EXTERNAL_STRICTNESS;
		}
		return Math.clamp(value, 1, 100);
	}

	private ServerSettings.Moderation db() {
		ServerSettings.Moderation c = cached;
		if (c == null) {
			c = settings.get().getModeration();
			cached = c;
		}
		return c != null ? c : new ServerSettings.Moderation();
	}
}
