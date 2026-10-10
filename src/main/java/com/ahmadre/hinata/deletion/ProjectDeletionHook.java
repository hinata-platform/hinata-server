package com.ahmadre.hinata.deletion;

import com.ahmadre.hinata.project.Project;

/**
 * What a module that keeps records about a project has to say when the project is deleted —
 * without the core knowing the module exists.
 *
 * <p>Inverted on purpose, like {@code issue.WorkItemMoveGuard}: billing keeps invoices that tax law
 * wants kept, and the deletion cascade may not depend on billing (ModuleBoundaryTest). So billing
 * implements this and the cascade asks every implementation.
 */
public interface ProjectDeletionHook {

	/**
	 * Refuses the deletion, with an {@code ApiException}, when something must outlive the project.
	 * Asked when the deletion is requested and again as the cascade starts.
	 */
	default void assertDeletable(Project project) {
	}

	/** Removes what the module kept about the project, just before the project itself goes. */
	default void projectDeleted(String projectId) {
	}
}
