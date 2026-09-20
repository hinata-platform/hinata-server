package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.project.Project;

/**
 * What an issue's relative deadlines mean, asked by the {@code issue} package and answered by the
 * module that owns them.
 *
 * <p>The direction is inverted on purpose. {@code template} may depend on {@code issue}; the
 * reverse would drag a switchable module into the one package every screen in the product goes
 * through, and the module boundary test says so. {@code availability} and {@code timeoff} already
 * work this way, for the same reason.
 *
 * <p>Two questions, both cheap. Whether offsets exist at all on this instance, so a request that
 * sets one can be refused in one line, and what an issue's written dates should now be, which is
 * the arithmetic and lives on the other side of this interface.
 */
public interface IssueDeadlinePolicy {

	/** Whether the module is switched on, so an offset may be set at all. */
	boolean offsetsEnabled();

	/**
	 * Writes {@code startDate} and {@code dueDate} from the issue's offsets and the project's
	 * event date. A no-op for an issue with no offsets, which is nearly all of them.
	 *
	 * <p>Called after every issue write, so the stored date is always the offset's result. That
	 * is the whole design: every existing reader of {@code dueDate} — the board, the reminder
	 * job, the shipped store app — keeps reading a written date and never learns to compute one.
	 */
	void resolveDates(Issue issue, Project project);
}
