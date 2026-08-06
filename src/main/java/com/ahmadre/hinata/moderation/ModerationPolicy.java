package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
 *
 * <h2>Addresses resolve here too, and that has a consequence</h2>
 *
 * <p>{@link #imageEndpoint()} and {@link #escalationUrl()} are resolved the same
 * way a threshold is, so an operator can switch either tier on from the admin
 * panel instead of editing the container environment and restarting. The price is
 * that no consumer may read one of them once and keep it: a {@code RestClient}
 * built in a constructor still points at the old host after an admin edits it,
 * and it fails by quietly classifying nothing rather than by throwing. Both
 * adapters therefore compare what they resolve against what they built with, and
 * rebuild when it differs.
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

	/**
	 * Budget used for an out-of-process tier when nothing configures one.
	 *
	 * <p>Applied here rather than in the two adapters so a nonsense value — zero,
	 * negative, the empty field an admin left behind — cannot reach a client
	 * builder. An unset read timeout against a sidecar that accepted the connection
	 * and then stopped talking holds the uploading request open forever, which is a
	 * worse outage than the classifier being down.
	 */
	public static final Duration DEFAULT_TIER_TIMEOUT = Duration.ofSeconds(5);

	/** Categories no admin setting may weaken or disable. */
	private static final Set<ModerationCategory> NON_OVERRIDABLE =
			EnumSet.of(ModerationCategory.SEXUAL_MINORS, ModerationCategory.MALWARE);

	/** Categories that engineering vocabulary routinely trips. */
	private static final Set<ModerationCategory> VIOLENCE_SHAPED =
			EnumSet.of(ModerationCategory.VIOLENCE, ModerationCategory.VIOLENT_THREAT);

	private final SettingsService settings;
	private final HinataProperties properties;

	private volatile ServerSettings.Moderation cached;

	/**
	 * Adopts the saved block, so an admin edit takes effect without a restart.
	 *
	 * <p>Public rather than package-private, unlike its twin on
	 * {@code SecurityPolicy}: the two adapters that depend on this refresh —
	 * {@code HttpImageModerator} and {@code WebhookModerationEscalation} — live in
	 * sub-packages, and the tests that prove they re-point at runtime have to be
	 * able to deliver the event the container would.
	 */
	@EventListener
	public void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
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
	 * Whether the write may proceed when a classifier tier is unavailable.
	 *
	 * <p>Fail-open for everything the deterministic gate already covers, because an
	 * unreachable optional classifier must not take the product down — but the
	 * verdict is marked {@link ModerationVerdict#degraded()} and lands in the queue,
	 * so the bypass is a row someone can count rather than a silent pass.
	 */
	public boolean failOpen(ModerationSurface surface) {
		return failOpen(surface, ModerationCheck.CLASSIFIER);
	}

	/**
	 * Whether the write may proceed when a check of [check] could not run on
	 * [surface].
	 *
	 * <p>Two independent vetoes, and both have to be satisfied for the write to go
	 * through. The surface veto is the older one: external ingress is the place a
	 * silent pass is not acceptable, because nobody is accountable for the content
	 * and nobody is waiting on the response. The check veto generalises it — some
	 * checks are not tradeable anywhere, whoever is waiting.
	 *
	 * <p>Written as one method rather than as a special case at the caller
	 * deliberately. "This particular tier ignores failOpen" is a rule that lives in
	 * whichever file remembered it, and the next tier added is the one that does
	 * not; expressed here, an unavailable check that must refuse says so through
	 * {@link ModerationCheck} and every enforcement point gets the same answer.
	 */
	public boolean failOpen(ModerationSurface surface, ModerationCheck check) {
		if (check != null && !check.degradable()) {
			return false;
		}
		Boolean override = db().getFailOpen();
		boolean open = override != null ? override : properties.getModeration().isFailOpen();
		return open && !surface.external();
	}

	// --- where the two out-of-process tiers live ---------------------------------
	//
	// These resolve exactly like every threshold above, and that is the whole
	// point: an address that can only be set in the container environment is an
	// address an operator cannot fix from the panel that is telling them it is
	// missing. The consequence is that neither adapter may capture what it reads
	// here — see HttpImageModerator and WebhookModerationEscalation, both of which
	// rebuild their client when the value they resolved last time changes.

	/**
	 * Base URL of the image classification sidecar, or {@code ""} when there is
	 * none. Trimmed, because a value typed into a web form arrives with whatever
	 * whitespace the browser kept.
	 */
	public String imageEndpoint() {
		return resolve(db().getImageEndpoint(), properties.getModeration().getImage().getEndpoint());
	}

	/** Budget for one call to the sidecar, applied to the connect and the read. */
	public Duration imageTimeout() {
		return resolve(db().getImageTimeout(), properties.getModeration().getImage().getTimeout());
	}

	/** Endpoint a signed freeze notice is POSTed to, or {@code ""} when there is nobody to notify. */
	public String escalationUrl() {
		return escalationUrl(db());
	}

	/**
	 * The endpoint [block] would resolve to.
	 *
	 * <p>Takes the block rather than reading the cache for one caller:
	 * {@code AdminSettingsController} has to validate the document an admin is
	 * submitting, and at that moment the cache here still holds the previous one.
	 * Resolving it anywhere else would put this fallback rule in a second place,
	 * which is how a "DB wins over env" and a "non-blank DB wins over env" end up
	 * in the same codebase disagreeing about a trailing space.
	 */
	public String escalationUrl(ServerSettings.Moderation block) {
		return resolve(block == null ? null : block.getEscalationUrl(),
				properties.getModeration().getEscalation().getUrl());
	}

	/**
	 * Shared secret the {@code X-Hinata-Signature} HMAC is taken under, or
	 * {@code ""} when none is configured — which is a refusal to deliver, not a
	 * licence to deliver unsigned. See {@code WebhookModerationEscalation}.
	 */
	public String escalationSecret() {
		return escalationSecret(db());
	}

	/** The secret [block] would resolve to; see {@link #escalationUrl(ServerSettings.Moderation)}. */
	public String escalationSecret(ServerSettings.Moderation block) {
		return resolve(block == null ? null : block.getEscalationSecret(),
				properties.getModeration().getEscalation().getSecret());
	}

	/** Budget for one delivery attempt of a freeze notice. */
	public Duration escalationTimeout() {
		return resolve(db().getEscalationTimeout(),
				properties.getModeration().getEscalation().getTimeout());
	}

	/**
	 * Attempts one notice gets before it is audited as undelivered. Floored at one:
	 * a zero here would mean the webhook is configured, nothing is ever sent, and
	 * the audit row says it was tried zero times.
	 */
	public int escalationMaxAttempts() {
		Integer override = db().getEscalationMaxAttempts();
		int value = override != null ? override : properties.getModeration().getEscalation().getMaxAttempts();
		return Math.max(1, value);
	}

	/** DB override wins when it says something; a blank field is not an answer. */
	private static String resolve(String override, String fallback) {
		String preferred = override == null ? "" : override.trim();
		if (!preferred.isEmpty()) {
			return preferred;
		}
		return fallback == null ? "" : fallback.trim();
	}

	/**
	 * DB override wins when it is a usable budget. Zero and negative are treated as
	 * "unset" rather than honoured — a client built with them either never waits or
	 * waits forever, and both are worse than the default.
	 */
	private static Duration resolve(Duration override, Duration fallback) {
		if (usable(override)) {
			return override;
		}
		return usable(fallback) ? fallback : DEFAULT_TIER_TIMEOUT;
	}

	private static boolean usable(Duration value) {
		return value != null && !value.isZero() && !value.isNegative();
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
