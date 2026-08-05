package com.ahmadre.hinata.board;

import com.ahmadre.hinata.issue.IssueActivityRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.moderation.ModerationCategory;
import com.ahmadre.hinata.moderation.ModerationDecision;
import com.ahmadre.hinata.moderation.ModerationException;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.moderation.ModerationSurface;
import com.ahmadre.hinata.moderation.ModerationVerdict;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Starting a sprint, on either side of the transaction boundary.
 *
 * <p>The gate and the two writes were one {@code @Transactional} method until a refusal
 * turned out to roll back its own moderation record. Splitting them left two things that
 * fail silently if they are ever got wrong — the writes losing their atomicity, and the
 * gate drifting back inside — and neither shows up in any other test, so both are pinned
 * here.
 */
class SprintStartTest {

	// --- the transaction is still there -------------------------------------------

	/**
	 * That the split did not quietly drop {@code @Transactional}. Moving a transactional
	 * body out of the method that used to hold it is exactly the refactor that loses it —
	 * a call that no longer passes the proxy is advised by nothing, and Spring says so
	 * nowhere: the annotation is simply ignored, and the sprint and the board's pointer
	 * to it go back to being two writes that can diverge.
	 *
	 * <p>Run against a real transaction manager with nothing behind it, because the
	 * resource is not what is in question: {@code MongoTransactionManager} and the one
	 * below are the same {@link AbstractPlatformTransactionManager}, and binding a
	 * transaction to the thread is that base class's job, not the driver's.
	 */
	@Test
	void bothWritesRunInsideOneTransaction() {
		List<String> inTransaction = new ArrayList<>();
		try (AnnotationConfigApplicationContext context =
				new AnnotationConfigApplicationContext(ActivationContext.class)) {
			SprintRepository sprints = context.getBean(SprintRepository.class);
			AgileBoardRepository boards = context.getBean(AgileBoardRepository.class);
			when(sprints.save(any(Sprint.class))).thenAnswer(invocation -> {
				noteIfTransactional("sprint", inTransaction);
				return invocation.<Sprint>getArgument(0);
			});
			when(boards.save(any(AgileBoard.class))).thenAnswer(invocation -> {
				noteIfTransactional("board", inTransaction);
				return invocation.<AgileBoard>getArgument(0);
			});

			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			context.getBean(SprintActivation.class)
					.activate(sprint(), board(), "new goal", null);
		}

		assertThat(inTransaction).containsExactly("sprint", "board");
	}

	/** Notes [what] only while a transaction is actually bound to this thread. */
	private static void noteIfTransactional(String what, List<String> into) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			into.add(what);
		}
	}

	/** The activation still does the job the transaction is there to make atomic. */
	@Test
	void theBoardIsPointedAtTheStartedSprint() {
		SprintRepository sprints = mock(SprintRepository.class);
		AgileBoardRepository boards = mock(AgileBoardRepository.class);
		when(sprints.save(any(Sprint.class))).thenAnswer(i -> i.getArgument(0));
		AgileBoard board = board();

		Sprint saved = new SprintActivation(sprints, boards).activate(sprint(), board, "new goal", null);

		assertThat(saved.getGoal()).isEqualTo("new goal");
		assertThat(saved.isArchived()).isFalse();
		assertThat(board.getActiveSprintId()).isEqualTo("s-1");
	}

	// --- the gate is outside it -----------------------------------------------------

	/**
	 * A refused goal must not reach a write. Not for tidiness: the gate records the
	 * refusal and then throws, and a transaction opened before that point takes the
	 * record down with it on the way out — nothing else stores a refused goal, so the
	 * row is the only account of it there will ever be.
	 */
	@Test
	void aRefusedGoalNeverReachesARepository() {
		Fixture fixture = new Fixture();
		when(fixture.moderation.checkText("hateful goal", ModerationSurface.ENTITY_DESCRIPTION))
				.thenThrow(ModerationException.blocked(blocking(), ModerationSurface.ENTITY_DESCRIPTION));

		assertThatThrownBy(() -> fixture.service.start("s-1", "hateful goal", null, admin()))
				.isInstanceOf(ModerationException.class);

		verifyNoInteractions(fixture.activation);
	}

	/** And an accepted one does, exactly once. */
	@Test
	void anAcceptedGoalIsHandedToTheActivation() {
		Fixture fixture = new Fixture();
		Sprint started = sprint();
		started.setGoal("fine goal");
		when(fixture.activation.activate(any(), any(), anyString(), any())).thenReturn(started);

		Sprint saved = fixture.service.start("s-1", "fine goal", null, admin());

		assertThat(saved.getGoal()).isEqualTo("fine goal");
	}

	// --- fixtures ---------------------------------------------------------------------

	/** {@link SprintService} with everything it reads stubbed and its writes mocked out. */
	private static final class Fixture {

		private final ModerationService moderation = mock(ModerationService.class);
		private final SprintActivation activation = mock(SprintActivation.class);
		private final SprintService service;

		private Fixture() {
			SprintRepository sprints = mock(SprintRepository.class);
			AgileBoardRepository boards = mock(AgileBoardRepository.class);
			when(sprints.findById("s-1")).thenReturn(Optional.of(sprint()));
			when(boards.findById("b-1")).thenReturn(Optional.of(board()));
			service = new SprintService(sprints, boards, mock(IssueRepository.class),
					mock(IssueActivityRepository.class), mock(ProjectService.class),
					mock(NotificationService.class), mock(MongoTemplate.class), moderation, activation);
		}
	}

	private static Sprint sprint() {
		return Sprint.builder().id("s-1").boardId("b-1").name("Sprint 1").archived(true).build();
	}

	private static AgileBoard board() {
		return AgileBoard.builder().id("b-1").name("Board").type(AgileBoard.Type.SCRUM).build();
	}

	/** An admin, so board access resolves without a project graph behind it. */
	private static User admin() {
		return User.builder().id("u-1").roles(Set.of(Role.ADMIN)).build();
	}

	private static ModerationVerdict blocking() {
		return new ModerationVerdict(ModerationDecision.BLOCK,
				List.of(new ModerationVerdict.Match(ModerationCategory.HATE, 95, "rule:hate")),
				ModerationVerdict.ModerationTier.GATE, false);
	}

	/**
	 * The activation as Spring wires it, with transaction management switched on —
	 * {@link EnableTransactionManagement} is what registers the advisor, so leaving it
	 * out would make the test pass against a bean that is not proxied at all.
	 */
	@Configuration
	@EnableTransactionManagement
	static class ActivationContext {

		@Bean
		SprintRepository sprints() {
			return mock(SprintRepository.class);
		}

		@Bean
		AgileBoardRepository boards() {
			return mock(AgileBoardRepository.class);
		}

		@Bean
		SprintActivation activation(SprintRepository sprints, AgileBoardRepository boards) {
			return new SprintActivation(sprints, boards);
		}

		@Bean
		PlatformTransactionManager transactionManager() {
			return new ResourcelessTransactionManager();
		}
	}

	/**
	 * A transaction over nothing. Everything asserted here — binding a transaction to
	 * the thread and unbinding it again — lives in
	 * {@link AbstractPlatformTransactionManager}, which is also where
	 * {@code MongoTransactionManager} gets it from; the subclass only supplies the
	 * resource, and a resource is what this test does not need.
	 */
	static final class ResourcelessTransactionManager extends AbstractPlatformTransactionManager {

		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
			// Nothing to begin: the thread binding is the base class's doing, and it is
			// the only part under test.
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) {
			// No resource, so nothing to commit.
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) {
			// No resource, so nothing to roll back.
		}
	}
}
