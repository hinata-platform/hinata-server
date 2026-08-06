package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.audit.AuditService;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real {@link FrozenContentService} instances for tests that need one but are not
 * about freezing.
 *
 * <p>A real service over a mocked repository rather than a mocked service, because
 * the class's whole contract is that an unloaded snapshot answers 503 rather than
 * "nothing is frozen". A {@code mock(FrozenContentService.class)} returns
 * {@code false} and {@code null} by default, which is the fail-open answer this
 * class is written to make impossible — every collaborator test would then pass
 * against a guard that never ran, and the one that mattered would pass too.
 *
 * <p>{@code FrozenObjectKeys} <em>is</em> mocked, and that asymmetry is deliberate:
 * it resolves storage keys for the write path, answers an empty list by default,
 * and nothing about a collaborator's read depends on it. Mocking the read side is
 * what would be dangerous; mocking the resolver only means these fixtures freeze
 * exactly the rows they were told to.
 */
public final class FreezeFixtures {

	private FreezeFixtures() {
	}

	/** A loaded registry with nothing in it — the state of every healthy install. */
	public static FrozenContentService nothingFrozen() {
		return loaded(List.of());
	}

	/** A loaded registry holding exactly these freezes. */
	public static FrozenContentService frozen(FrozenContent... rows) {
		return loaded(Arrays.asList(rows));
	}

	/** One active freeze row, with only the fields the snapshot reads. */
	public static FrozenContent row(FrozenTargetType type, String targetId) {
		return FrozenContent.builder()
				.id(type.name().toLowerCase() + "-" + targetId)
				.targetType(type)
				.targetId(targetId)
				.frozenAt(Instant.now())
				.build();
	}

	private static FrozenContentService loaded(List<FrozenContent> rows) {
		FrozenContentRepository repository = mock(FrozenContentRepository.class);
		when(repository.findByUnfrozenAtIsNull()).thenReturn(rows);
		FrozenContentService service = new FrozenContentService(repository,
				mock(AuditService.class, RETURNS_DEEP_STUBS), mock(FrozenObjectKeys.class));
		// The container fires this on ApplicationReadyEvent; without it the snapshot
		// is "unknown" and every guard answers 503, which is correct behaviour and
		// not what a collaborator's test is trying to exercise.
		service.refresh();
		return service;
	}
}
