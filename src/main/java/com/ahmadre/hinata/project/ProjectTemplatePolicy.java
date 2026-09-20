package com.ahmadre.hinata.project;

import com.ahmadre.hinata.user.User;

/**
 * What the template marker means, asked by the {@code project} package and answered by the module
 * that owns templates.
 *
 * <p>Inverted for the reason {@code issue.IssueDeadlinePolicy} is: {@code template} may depend on
 * {@code project}, never the reverse, or a module an administrator can switch off would be wired
 * into the package every screen goes through.
 *
 * <p>Two questions. Whether templates exist on this instance, so the one field that would not
 * exist without them can be refused while the rest of the settings save goes through; and the
 * record that somebody marked a project, which belongs with the module's other audit events
 * rather than in the generic settings write.
 */
public interface ProjectTemplatePolicy {

	/** Whether the module is switched on, so a project may be marked as a template at all. */
	boolean offered();

	/** Records that {@code project} was marked as a template, or unmarked. */
	void recordMarked(Project project, User actor);
}
