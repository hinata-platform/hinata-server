package com.ahmadre.hinata.user;

/**
 * Whether a moderator may open the case behind a suspension (HIN-26). Declared by the core,
 * implemented by moderation: whoever wrote the content of a case does not see the case, its
 * number included.
 */
public interface SuspensionCases {

	/** Whether [viewer] may open case [caseId]. */
	boolean mayOpen(String caseId, User viewer);
}
