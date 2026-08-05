package com.ahmadre.hinata.moderation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What a verdict turns into on its way to Mongo — which rows are worth a document at
 * all, and what a row is allowed to carry once the content behind it is gone.
 *
 * <p>Plain Mockito and no Spring context, which is itself part of the answer: the
 * recorder saves what it is given, on the thread it was given it on, so there is no
 * wiring left whose absence could quietly stop it. Where the write sits relative to a
 * caller's transaction is the caller's problem now — see
 * {@code com.ahmadre.hinata.board.SprintStartTest}.
 */
class MongoModerationRecorderTest {

	private ModerationRecordRepository records;
	private MongoModerationRecorder recorder;

	@BeforeEach
	void setUp() {
		records = mock(ModerationRecordRepository.class);
		recorder = new MongoModerationRecorder(records);
	}

	/** The single row the repository was handed. */
	private ModerationRecord written() {
		ArgumentCaptor<ModerationRecord> row = ArgumentCaptor.forClass(ModerationRecord.class);
		verify(records).save(row.capture());
		return row.getValue();
	}

	// --- what earns a row ---------------------------------------------------------

	/** An accepted write must not cost a row — the queue is for what needs a human. */
	@Test
	void aCleanVerdictIsNeverWritten() {
		recorder.record(ModerationVerdict.allow(ModerationVerdict.ModerationTier.GATE),
				ModerationSurface.COMMENT, ModerationRecorder.Target.of("comment", "c-1"));
		recorder.record(null, ModerationSurface.COMMENT, ModerationRecorder.Target.of("comment", "c-2"));

		verifyNoInteractions(records);
	}

	/**
	 * A degraded pass is written even though nothing fired. That is the whole of the
	 * fail-open trade: the write is let through, and the bypass becomes a row somebody
	 * can count rather than a log line nobody reads.
	 */
	@Test
	void aDegradedPassIsStillWritten() {
		recorder.record(ModerationVerdict.allow(ModerationVerdict.ModerationTier.EXTERNAL).degrade(),
				ModerationSurface.COMMENT, ModerationRecorder.Target.of("comment", "c-1"));

		assertThat(written().isDegraded()).isTrue();
	}

	// --- what a row may carry -----------------------------------------------------

	/**
	 * The refused text exists nowhere else, so the row has to identify it without
	 * keeping it. Asserted against a literal digest rather than one the test computes
	 * the same way the recorder does: the hash's only job is to match the next attempt
	 * at the same payload, on another node and after another release, and a test that
	 * re-derives it would agree with any change made to it.
	 */
	@Test
	void refusedTextIsStoredOnlyAsItsHash() {
		recorder.record(blocking(), ModerationSurface.ENTITY_DESCRIPTION, refusal(), "refused text");

		ModerationRecord row = written();
		assertThat(row.getContentHash())
				.isEqualTo("c6c0554aa842209722f5c8f80ae09b10075f8b0398e4c828ce9d8f115a88349e");
		assertThat(row.getLabel()).isNull();
		assertThat(row.getTargetId()).isNull();
		assertThat(row.getCategories())
				.extracting(ModerationRecord.CategoryScore::getEvidence)
				.doesNotContain("refused text");
	}

	/**
	 * And it is hashed as UTF-8, not in whatever encoding the JVM happened to default
	 * to: the two nodes of one deployment have to produce the same digest for the same
	 * sentence, or the retry the hash exists to spot looks like a fresh attempt.
	 */
	@Test
	void theTextHashDoesNotDependOnThePlatformCharset() {
		recorder.record(blocking(), ModerationSurface.COMMENT, refusal(), "Grüße, naïve façade");

		assertThat(written().getContentHash())
				.isEqualTo("0f9a1bd8aecfe58a07799d0745254fa5cf5065759394e61f5f68f0f7a1c4c77f")
				// The same sentence under a latin-1 default.
				.isNotEqualTo("94a9743a80163b58e4d27b2a5880efe064ac3093ef72452e1d45c51416b87d18");
	}

	/**
	 * Bytes are hashed as bytes. Squeezing a binary through a {@code String} to reuse
	 * one signature hashes a lossy decoding of the file instead, and that value matches
	 * nothing — not the next upload of the same file, and not the scanner report it
	 * would be held against. These three bytes are not valid UTF-8, so the two routes
	 * give different answers and this test can tell them apart.
	 */
	@Test
	void refusedBytesAreHashedAsBytesAndNotThroughAString() {
		byte[] content = { (byte) 0xff, (byte) 0xfe, 0x00 };

		recorder.record(blocking(), ModerationSurface.ATTACHMENT, refusal(), content);

		assertThat(written().getContentHash())
				.isEqualTo("ba778c0261008c8f71ae4061ad0162ffcbe63b52c91f89f236738131d1217ec7")
				// What the same bytes hash to after a round trip through String.
				.isNotEqualTo("a38c4010948c4373c3c36d74a93dfb0745e43784276de3e1c4738d0cff57e7aa");
	}

	/**
	 * A flag carries no hash. The content behind it was stored, so {@code targetId}
	 * already points at it and a digest would be a second identifier to keep in sync —
	 * and hashing content that still exists is how this collection would start turning
	 * into a copy of the text.
	 */
	@Test
	void aFlaggedRowCarriesNoHash() {
		recorder.record(flagged(), ModerationSurface.COMMENT,
				ModerationRecorder.Target.of("comment", "c-1"), "flagged but stored");

		assertThat(written().getContentHash()).isNull();
	}

	/** Evidence is capped on write so this collection cannot become a copy of the text. */
	@Test
	void evidenceIsTruncatedOnTheWrittenRow() {
		ModerationVerdict verdict = new ModerationVerdict(ModerationDecision.FLAG,
				List.of(new ModerationVerdict.Match(ModerationCategory.HATE, 60, "x".repeat(500))),
				ModerationVerdict.ModerationTier.GATE, false);

		recorder.record(verdict, ModerationSurface.COMMENT,
				ModerationRecorder.Target.of("comment", "c-1"));

		assertThat(written().getCategories().getFirst().getEvidence()).hasSize(120);
	}

	// --- what must never reach the caller ------------------------------------------

	/**
	 * Recording is bookkeeping and must never fail a write policy already decided on:
	 * an unreachable Mongo would otherwise turn an accepted comment into an error its
	 * author can do nothing about.
	 */
	@Test
	void aFailingRepositoryNeverReachesTheCaller() {
		doThrow(new IllegalStateException("mongo down")).when(records).save(any(ModerationRecord.class));

		assertThatCode(() -> recorder.record(blocking(), ModerationSurface.ENTITY_NAME, refusal(), "x"))
				.doesNotThrowAnyException();
	}

	/**
	 * A missing target is a row without a pointer, not a lost row. The three-argument
	 * form is called from too many places for none of them ever to pass null, and the
	 * verdict is the half a moderator acts on anyway.
	 */
	@Test
	void aNullTargetIsTolerated() {
		assertThatCode(() -> recorder.record(flagged(), ModerationSurface.COMMENT, null))
				.doesNotThrowAnyException();

		ModerationRecord row = written();
		assertThat(row.getTargetType()).isNull();
		assertThat(row.getTargetId()).isNull();
		assertThat(row.getDecision()).isEqualTo(ModerationDecision.FLAG);
	}

	// --- fixtures --------------------------------------------------------------------

	private static ModerationVerdict blocking() {
		return new ModerationVerdict(ModerationDecision.BLOCK,
				List.of(new ModerationVerdict.Match(ModerationCategory.HATE, 95, "rule:hate")),
				ModerationVerdict.ModerationTier.GATE, false);
	}

	private static ModerationVerdict flagged() {
		return new ModerationVerdict(ModerationDecision.FLAG,
				List.of(new ModerationVerdict.Match(ModerationCategory.HARASSMENT, 60, "rule:harassment")),
				ModerationVerdict.ModerationTier.GATE, false);
	}

	/** What the gate passes for a refusal: nothing was stored, so there is no id. */
	private static ModerationRecorder.Target refusal() {
		return new ModerationRecorder.Target(null, null, null, null, null);
	}
}
