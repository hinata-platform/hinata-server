package com.ahmadre.hinata.board;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * The two writes that make a sprint the active one, and nothing else.
 *
 * <p>They have to agree or the board is unusable: a board pointing at a sprint that was
 * never started hides the backlog behind an empty active sprint, and a started sprint no
 * board points at cannot be reached from the UI at all — neither state is one a user can
 * fix, so the pair is atomic.
 *
 * <p>A collaborator rather than a {@code @Transactional} method on {@link SprintService}
 * because everything {@link SprintService#start} does before these two writes has to
 * happen <em>outside</em> the transaction — above all the moderation gate, which records
 * a refusal and then throws, so an enclosing transaction would roll back the only account
 * of that refusal along with it. Splitting it off inside the same bean would not have
 * worked: self-invocation never passes the proxy, so a private {@code @Transactional}
 * helper would have dropped the atomicity silently instead of moving the gate.
 */
@Component
@RequiredArgsConstructor
class SprintActivation {

	private final SprintRepository sprints;
	private final AgileBoardRepository boards;

	/**
	 * Applies the start-time edits and hands the board its new active sprint.
	 *
	 * <p>Public on a package-private class rather than package-private itself, even
	 * though the type already decides who may call it. Proxy-based transaction advice is
	 * only guaranteed on public methods: current Spring does advise package-private ones
	 * through a CGLIB subclass, but that holds only as long as CGLIB is the strategy in
	 * force — give this class an interface under JDK proxies and a non-public method
	 * stops being advised, with no error and nothing failing but
	 * {@code SprintStartTest#bothWritesRunInsideOneTransaction}. A boundary whose loss is
	 * that quiet should not rest on it.
	 */
	@Transactional
	public Sprint activate(Sprint sprint, AgileBoard board, String goal, LocalDate endDate) {
		if (goal != null) sprint.setGoal(goal);
		if (endDate != null) sprint.setEndDate(endDate);
		sprint.setArchived(false);
		Sprint saved = sprints.save(sprint);
		board.setActiveSprintId(saved.getId());
		boards.save(board);
		return saved;
	}
}
