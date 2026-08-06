package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.moderation.ModerationCategory;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The freeze mechanism itself.
 *
 * <p>Two properties are worth more than all the others here, and both are about
 * what happens when something goes wrong rather than when it goes right.
 *
 * <ol>
 *   <li><b>An unknown registry is not an empty one.</b> If the snapshot cannot be
 *       loaded, every guard has to refuse. The failure mode this rules out is the
 *       one nobody would ever see: a Mongo hiccup at startup, a service that
 *       answers "nothing is frozen" to every question for the rest of the process's
 *       life, and content served that the product believes it is withholding.</li>
 *   <li><b>An empty registry costs nothing.</b> The exclusion has to match every
 *       document when there is nothing frozen, or freeze becomes a feature that
 *       silently empties search in every install that never had an incident.</li>
 * </ol>
 */
class FrozenContentServiceTest {

	private FrozenContentRepository repository;
	private AuditService audit;
	private FrozenObjectKeys objectKeys;
	private FrozenContentService service;

	private final User admin = User.builder().id("u-admin").displayName("Ada")
			.roles(Set.of(Role.ADMIN)).build();

	@BeforeEach
	void setUp() {
		repository = mock(FrozenContentRepository.class);
		audit = mock(AuditService.class, RETURNS_DEEP_STUBS);
		objectKeys = mock(FrozenObjectKeys.class);
		when(objectKeys.of(any(), any(), any())).thenReturn(List.of());
		when(repository.findByUnfrozenAtIsNull()).thenReturn(List.of());
		when(repository.findByTargetTypeAndTargetId(any(), any())).thenReturn(Optional.empty());
		when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
		service = new FrozenContentService(repository, audit, objectKeys);
	}

	// --- fail closed -----------------------------------------------------------

	/**
	 * Before the registry has been read — and after a read that failed — the
	 * mechanism does not know whether anything is frozen, and says so with a 503
	 * rather than answering "no".
	 */
	@Test
	void anUnloadedSnapshotRefusesInsteadOfAnsweringNothingIsFrozen() {
		FrozenContentService cold = new FrozenContentService(repository, audit, objectKeys);

		assertThatThrownBy(() -> cold.isFrozen(FrozenTargetType.ISSUE, "i-1"))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus())
						.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}

	@Test
	void anUnreadableRegistryLeavesEveryGuardFailingClosed() {
		when(repository.findByUnfrozenAtIsNull()).thenThrow(new IllegalStateException("mongo down"));

		service.refresh();

		assertThatThrownBy(() -> service.assertReadable(FrozenTargetType.ISSUE, "i-1", "issue"))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> service.exclusion("_id", FrozenTargetType.ISSUE))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> service.assertObjectReadable("media/x"))
				.isInstanceOf(ApiException.class);
	}

	/**
	 * A registry that recovers is used again. Failing closed forever after one
	 * transient error would be a self-inflicted outage, and the refresh is what makes
	 * "fail closed" a state rather than a verdict.
	 */
	@Test
	void aRecoveredRegistryIsUsedAgain() {
		when(repository.findByUnfrozenAtIsNull()).thenThrow(new IllegalStateException("mongo down"));
		service.refresh();

		// doReturn, not when(...): the stubbed call would otherwise be *invoked* while
		// re-stubbing and throw the very exception it is replacing.
		doReturn(List.of()).when(repository).findByUnfrozenAtIsNull();
		service.refresh();

		assertThat(service.isFrozen(FrozenTargetType.ISSUE, "i-1")).isFalse();
	}

	/**
	 * Over the cap the snapshot is dropped, not truncated. A truncated set answers
	 * "not frozen" for every row past the ceiling, which is the one wrong answer this
	 * class exists to never give.
	 */
	@Test
	void aRegistryOverTheCapFailsClosedRatherThanTruncating() {
		List<FrozenContent> tooMany = new ArrayList<>(IntStream
				.rangeClosed(0, FrozenContentService.MAX_FROZEN)
				.mapToObj(i -> FreezeFixtures.row(FrozenTargetType.ISSUE, "i-" + i))
				.toList());
		when(repository.findByUnfrozenAtIsNull()).thenReturn(tooMany);

		service.refresh();

		assertThatThrownBy(() -> service.isFrozen(FrozenTargetType.ISSUE, "i-0"))
				.isInstanceOf(ApiException.class);
	}

	// --- fail closed, but not at the cost of the product -----------------------

	/**
	 * A refresh that fails <em>after</em> a good load keeps the last known set.
	 *
	 * <p>This is the availability half of the contract and it used to be missing:
	 * every failed refresh collapsed the snapshot to unknown, so one transient Mongo
	 * error took the comment feed, the search, the issue list, the board and every
	 * byte in the product to 503 for everybody, for up to a refresh interval. That
	 * trades a certain outage for a hypothetical miss — the last loaded set is still
	 * almost certainly correct, because a freeze is a single-digit event in the life
	 * of an install and the odds one was raised inside the seconds the error lasted
	 * are close to zero.
	 *
	 * <p>Both halves are asserted, and the second is the one that bites: it is not
	 * enough that the service still answers, it has to still answer <em>frozen</em>
	 * for what was frozen. A fallback that kept an empty set would pass an
	 * "it doesn't throw" assertion while serving exactly the material at issue.
	 */
	@Test
	void aRefreshThatFailsAfterAGoodLoadKeepsTheLastKnownSet() {
		doReturn(List.of(FreezeFixtures.row(FrozenTargetType.ISSUE, "i-frozen")))
				.when(repository).findByUnfrozenAtIsNull();
		service.refresh();

		doThrow(new IllegalStateException("mongo down")).when(repository).findByUnfrozenAtIsNull();
		service.refresh();

		assertThat(service.isFrozen(FrozenTargetType.ISSUE, "i-frozen"))
				.as("the set loaded a moment ago is still the best answer available")
				.isTrue();
		assertThat(service.isFrozen(FrozenTargetType.ISSUE, "i-other")).isFalse();
	}

	/**
	 * But only for a bounded number of intervals. Past the budget the set is no
	 * longer "a moment old", and a mechanism serving from a snapshot it can no longer
	 * confirm has stopped being a guard — so it goes back to failing closed.
	 */
	@Test
	void aRegistryThatStaysUnreadableEventuallyFailsClosedAgain() {
		doReturn(List.of(FreezeFixtures.row(FrozenTargetType.ISSUE, "i-frozen")))
				.when(repository).findByUnfrozenAtIsNull();
		service.refresh();
		doThrow(new IllegalStateException("mongo down")).when(repository).findByUnfrozenAtIsNull();

		for (int attempt = 1; attempt < FrozenContentService.MAX_REFRESH_FAILURES; attempt++) {
			service.refresh();
			assertThat(service.isFrozen(FrozenTargetType.ISSUE, "i-frozen"))
					.as("still inside the staleness budget after %d failure(s)", attempt)
					.isTrue();
		}
		service.refresh();

		assertThatThrownBy(() -> service.isFrozen(FrozenTargetType.ISSUE, "i-frozen"))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus())
						.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}

	/**
	 * A recovery resets the budget, or a registry that flaps once an hour would drift
	 * into a permanent outage without ever having been unreadable for long.
	 */
	@Test
	void aSuccessfulRefreshResetsTheStalenessBudget() {
		doReturn(List.of(FreezeFixtures.row(FrozenTargetType.ISSUE, "i-frozen")))
				.when(repository).findByUnfrozenAtIsNull();
		service.refresh();

		for (int cycle = 0; cycle < 3; cycle++) {
			doThrow(new IllegalStateException("mongo down")).when(repository).findByUnfrozenAtIsNull();
			for (int attempt = 0; attempt < FrozenContentService.MAX_REFRESH_FAILURES - 1; attempt++) {
				service.refresh();
			}
			doReturn(List.of(FreezeFixtures.row(FrozenTargetType.ISSUE, "i-frozen")))
					.when(repository).findByUnfrozenAtIsNull();
			service.refresh();
		}

		assertThat(service.isFrozen(FrozenTargetType.ISSUE, "i-frozen")).isTrue();
	}

	/**
	 * The first load is untouched by all of the above. With no set at all there is
	 * nothing to keep, "nothing is frozen" would be a guess, and the cost of guessing
	 * wrong is serving the material — so a cold service that cannot read the registry
	 * refuses, exactly as before.
	 */
	@Test
	void aFirstLoadThatFailsStillHasNothingToFallBackOn() {
		doThrow(new IllegalStateException("mongo down")).when(repository).findByUnfrozenAtIsNull();

		service.refresh();

		assertThatThrownBy(() -> service.isFrozen(FrozenTargetType.ISSUE, "i-1"))
				.isInstanceOf(ApiException.class);
	}

	/**
	 * And a registry that grows past the cap gets the same bounded grace rather than
	 * taking the product down at the instant a runaway trigger crosses the line — but
	 * it does not get to stay there, because the condition never clears on its own.
	 */
	@Test
	void aRegistryOverTheCapKeepsTheLastGoodSetOnlyForTheBudget() {
		doReturn(List.of(FreezeFixtures.row(FrozenTargetType.ISSUE, "i-frozen")))
				.when(repository).findByUnfrozenAtIsNull();
		service.refresh();
		doReturn(new ArrayList<>(IntStream.rangeClosed(0, FrozenContentService.MAX_FROZEN)
				.mapToObj(i -> FreezeFixtures.row(FrozenTargetType.ISSUE, "i-" + i))
				.toList()))
				.when(repository).findByUnfrozenAtIsNull();

		service.refresh();
		assertThat(service.isFrozen(FrozenTargetType.ISSUE, "i-frozen")).isTrue();

		for (int attempt = 1; attempt < FrozenContentService.MAX_REFRESH_FAILURES; attempt++) {
			service.refresh();
		}

		assertThatThrownBy(() -> service.isFrozen(FrozenTargetType.ISSUE, "i-frozen"))
				.isInstanceOf(ApiException.class);
	}

	// --- the ordinary, empty case ---------------------------------------------

	@Test
	void aLoadedEmptyRegistryAnswersNotFrozenWithoutComplaining() {
		service.refresh();

		assertThat(service.isFrozen(FrozenTargetType.ISSUE, "i-1")).isFalse();
		assertThat(service.anythingFrozen()).isFalse();
		assertThatCode(() -> service.assertReadable(FrozenTargetType.ISSUE, "i-1", "issue"))
				.doesNotThrowAnyException();
	}

	/**
	 * The exclusion on an empty set has to be an empty {@code $nin}, which matches
	 * every document — that is what keeps every query in the product byte-for-byte
	 * the one it always was when nothing is frozen.
	 */
	@Test
	void theExclusionOnAnEmptySetMatchesEverything() {
		service.refresh();

		Criteria exclusion = service.exclusion("_id", FrozenTargetType.ISSUE);

		assertThat(exclusion.getCriteriaObject().toJson()).contains("$nin").contains("[]");
	}

	/**
	 * And it is never null — unlike the access filters it is composed with, where
	 * null means "an admin, no restriction" and is dropped by the composer. A freeze
	 * that evaporated for an admin would be a freeze that failed for the one account
	 * it most has to bind.
	 */
	@Test
	void theExclusionIsNeverNull() {
		service.refresh();

		for (FrozenTargetType type : FrozenTargetType.values()) {
			assertThat(service.exclusion("_id", type)).isNotNull();
		}
	}

	// --- freezing --------------------------------------------------------------

	@Test
	void freezingMakesTheTargetAndItsObjectsUnreadable() {
		List<FrozenContent> stored = captureSaves();

		service.freeze(new FrozenContentService.Request(FrozenTargetType.COMMENT, "c-1", "i-1",
				List.of("voice/abc"), ModerationCategory.SEXUAL_MINORS, null, "u-reporter", null,
				"report:SEXUAL_MINORS"));

		assertThat(stored).hasSize(2);
		assertThat(service.isFrozen(FrozenTargetType.COMMENT, "c-1")).isTrue();
		assertThat(service.isFrozenObject("voice/abc")).isTrue();
		assertThatThrownBy(() -> service.assertObjectReadable("voice/abc"))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus())
						.isEqualTo(HttpStatus.NOT_FOUND));
	}

	/**
	 * Freezing something already frozen is a no-op, not a conflict. Two people
	 * reporting the same comment within seconds is the normal case — an urgent report
	 * notifies every admin at once — and a 409 for the second reporter would refuse
	 * the notice the product most wanted.
	 */
	@Test
	void freezingTwiceIsIdempotent() {
		List<FrozenContent> stored = captureSaves();
		FrozenContentService.Request request = new FrozenContentService.Request(
				FrozenTargetType.COMMENT, "c-1", "i-1", List.of(), ModerationCategory.SEXUAL_MINORS,
				null, "u-reporter", null, "report:SEXUAL_MINORS");

		service.freeze(request);
		int afterFirst = stored.size();
		when(repository.findByTargetTypeAndTargetId(FrozenTargetType.COMMENT, "c-1"))
				.thenReturn(Optional.of(stored.getFirst()));
		service.freeze(request);

		assertThat(stored).hasSize(afterFirst);
		assertThat(service.isFrozen(FrozenTargetType.COMMENT, "c-1")).isTrue();
	}

	/** Every freeze is attributable to the account that caused it. */
	@Test
	void aFreezeCarriesItsReporter() {
		List<FrozenContent> stored = captureSaves();

		service.freeze(new FrozenContentService.Request(FrozenTargetType.ARTICLE, "a-1", null,
				List.of(), ModerationCategory.SEXUAL_MINORS, null, "u-reporter", null, "report"));

		assertThat(stored.getFirst().getReporterId()).isEqualTo("u-reporter");
	}

	/**
	 * A freeze defaults to withholding the statement of reasons, and records that it
	 * did. DSA Art. 17 owes the author a notice; telling a suspect their upload was
	 * matched against a hash list tips them off. The row is where that conflict is
	 * recorded rather than silently resolved.
	 */
	@Test
	void aFreezeRecordsThatNoStatementOfReasonsWasIssued() {
		List<FrozenContent> stored = captureSaves();

		service.freeze(new FrozenContentService.Request(FrozenTargetType.ISSUE, "i-1", null,
				List.of(), ModerationCategory.SEXUAL_MINORS, null, "u-reporter", null, "report"));

		assertThat(stored.getFirst().getStatementIssuedAt())
				.as("no statement is issued for a freeze in this product, and the record of that "
						+ "is the absence of a timestamp rather than a flag asserting it")
				.isNull();
	}

	@Test
	void aFreezeIsAudited() {
		service.freeze(new FrozenContentService.Request(FrozenTargetType.ISSUE, "i-1", null,
				List.of(), ModerationCategory.SEXUAL_MINORS, null, "u-reporter", null, "report"));

		verify(audit).event(AuditAction.CONTENT_FROZEN);
	}

	// --- unfreezing ------------------------------------------------------------

	@Test
	void unfreezingWithoutANoteIsRefused() {
		assertThatThrownBy(() -> service.unfreeze(admin, FrozenTargetType.ISSUE, "i-1", "  "))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus())
						.isEqualTo(HttpStatus.BAD_REQUEST));
	}

	@Test
	void unfreezingSomethingThatIsNotFrozenIsANotFound() {
		assertThatThrownBy(() -> service.unfreeze(admin, FrozenTargetType.ISSUE, "i-1", "wrong id"))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus())
						.isEqualTo(HttpStatus.NOT_FOUND));
	}

	/**
	 * The entity and the objects it owns come back together. Releasing the row while
	 * its images stay 404 reads to everyone as the product having lost the content
	 * rather than having restricted it.
	 */
	@Test
	void unfreezingReleasesTheTargetAndItsObjects() {
		FrozenContent row = FrozenContent.builder().id("f-1").targetType(FrozenTargetType.COMMENT)
				.targetId("c-1").objectKeys(List.of("voice/abc")).frozenAt(Instant.now()).build();
		FrozenContent object = FrozenContent.builder().id("f-2").targetType(FrozenTargetType.OBJECT)
				.targetId("voice/abc").frozenAt(Instant.now()).build();
		when(repository.findByTargetTypeAndTargetId(FrozenTargetType.COMMENT, "c-1"))
				.thenReturn(Optional.of(row));
		when(repository.findByTargetTypeAndTargetId(FrozenTargetType.OBJECT, "voice/abc"))
				.thenReturn(Optional.of(object));
		when(repository.findByUnfrozenAtIsNull()).thenReturn(List.of(row, object));
		service.refresh();

		service.unfreeze(admin, FrozenTargetType.COMMENT, "c-1", "wrong target id");

		assertThat(row.getUnfrozenAt()).isNotNull();
		assertThat(row.getUnfrozenBy()).isEqualTo("u-admin");
		assertThat(row.getUnfreezeNote()).isEqualTo("wrong target id");
		assertThat(object.getUnfrozenAt()).isNotNull();
		verify(audit).event(AuditAction.CONTENT_UNFROZEN);
	}

	/** Collects what the repository was asked to save, and keeps the snapshot in step. */
	private List<FrozenContent> captureSaves() {
		List<FrozenContent> stored = new ArrayList<>();
		when(repository.save(any())).thenAnswer(call -> {
			FrozenContent row = call.getArgument(0);
			stored.add(row);
			when(repository.findByUnfrozenAtIsNull()).thenReturn(List.copyOf(stored));
			return row;
		});
		return stored;
	}
}
