package com.ahmadre.hinata.moderation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The {@link ModerationRecorder} that actually writes rows.
 *
 * <p>Everything here follows from one rule: recording is bookkeeping, and bookkeeping
 * must never fail a write that policy already allowed. A clean verdict is therefore
 * dropped before it costs a round trip, and every persistence failure is swallowed
 * with a warning — an unreachable Mongo would otherwise turn an accepted comment into
 * an error its author can do nothing about.
 *
 * <p>The insert is issued directly, on the calling thread, wherever the caller happens
 * to be. That is deliberately the whole of it. Routing the row through a
 * transaction-phase listener so that it could outlive a rollback would buy one case and
 * pay for it with a failure mode nobody would ever find: a synchronization left
 * registered on the thread anywhere in the process turns every record into a queued
 * event that is never delivered — no exception, no log line, no failing request, just
 * an empty queue. For a path whose entire job is to leave a trace, silence is the one
 * unacceptable way to break.
 *
 * <p>The price, stated plainly: a row written from inside a caller's transaction shares
 * that transaction's fate. It matters in exactly one shape — {@link ModerationService}
 * records a refusal and then throws, so a gate running inside a transaction would roll
 * back the very row that explains why it rolled back, and a refused write is stored
 * nowhere else. The answer is to keep such a gate out of the transaction rather than to
 * make every record survive one; {@code SprintService.start} was the only gate that ran
 * inside one, and no longer does.
 *
 * <p>What is written is the verdict and a pointer to the item, never the item's
 * content — see {@link ModerationRecord}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoModerationRecorder implements ModerationRecorder {

	/**
	 * Hard cap on the stored {@link ModerationRecord.CategoryScore#getEvidence()}.
	 * Evidence is meant to name the rule that fired, not quote the text, but this
	 * collection must never become a second copy of the content — and the cap is what
	 * keeps that true no matter what a future provider client decides to put there.
	 */
	private static final int MAX_EVIDENCE_LENGTH = 120;

	private final ModerationRecordRepository records;

	@Override
	public void record(ModerationVerdict verdict, ModerationSurface surface, Target target) {
		save(verdict, surface, target, null);
	}

	@Override
	public void record(ModerationVerdict verdict, ModerationSurface surface, Target target, String content) {
		// UTF-8 and not the platform charset: the hash has to be reproducible across
		// the servers of one deployment, and a default-encoding digest of the same
		// sentence differs between them.
		save(verdict, surface, target,
				content == null ? null : content.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public void record(ModerationVerdict verdict, ModerationSurface surface, Target target, byte[] content) {
		save(verdict, surface, target, content);
	}

	/**
	 * Builds the row and writes it. [content] is the refused payload and is used for
	 * nothing but its digest — it is never stored, logged or passed on.
	 *
	 * <p>The whole body is inside the try, not just the save: a malformed target or a
	 * verdict with a surprise in it must fail the same way an unreachable Mongo does,
	 * because from the caller's side both are the same event — bookkeeping did not
	 * happen — and neither is a reason to fail a write policy already decided on.
	 */
	private void save(ModerationVerdict verdict, ModerationSurface surface, Target target, byte[] content) {
		if (verdict == null || (verdict.decision() == ModerationDecision.ALLOW && !verdict.degraded())) {
			return;
		}
		try {
			Target subject = target != null ? target : new Target(null, null, null, null, null);
			records.save(ModerationRecord.builder()
					.surface(surface)
					.decision(verdict.decision())
					.tier(verdict.tier())
					.degraded(verdict.degraded())
					.categories(verdict.matches().stream()
							.map(MongoModerationRecorder::categoryScore)
							.toList())
					.targetType(subject.type())
					.targetId(subject.id())
					.projectId(subject.projectId())
					.authorId(subject.authorId())
					.label(subject.label())
					// Only a refusal leaves nothing behind to point at; anything stored
					// is reachable through targetId, and a hash of it would be a second
					// identifier to keep in sync for no gain.
					.contentHash(verdict.isBlocking() ? sha256(content) : null)
					.reviewState(ModerationRecord.ReviewState.OPEN)
					.build());
		}
		catch (Exception ex) {
			warn(verdict.decision(), surface, ex);
		}
	}

	/**
	 * The only reaction a failed record gets. Recording is bookkeeping: the write it
	 * describes has already been decided, and there is nobody left to tell.
	 */
	private static void warn(ModerationDecision decision, ModerationSurface surface, Exception ex) {
		log.warn("Failed to record moderation verdict {} on {}: {}", decision, surface, ex.toString());
	}

	/** Flattens one match into its stored form, capping the evidence. */
	private static ModerationRecord.CategoryScore categoryScore(ModerationVerdict.Match match) {
		String evidence = match.evidence();
		return ModerationRecord.CategoryScore.builder()
				.category(match.category())
				.score(match.score())
				.evidence(evidence != null && evidence.length() > MAX_EVIDENCE_LENGTH
						? evidence.substring(0, MAX_EVIDENCE_LENGTH)
						: evidence)
				.build();
	}

	/** Lowercase hex SHA-256 of [content], or null when there is nothing to hash. */
	private static String sha256(byte[] content) {
		if (content == null || content.length == 0) {
			return null;
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(content);
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xf, 16));
				hex.append(Character.forDigit(b & 0xf, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException ex) {
			// Unreachable on any JRE, and not worth losing the record over if it ever
			// were: the verdict is the part a moderator acts on.
			log.warn("SHA-256 unavailable; moderation record stored without a content hash");
			return null;
		}
	}
}
