package com.ahmadre.hinata.issue;

/**
 * A veto on moving an issue whose logged hours may not follow it.
 *
 * <p>Moving an issue re-points {@code projectId} on every work item attached to
 * it, in bulk — the hours have to follow, or every hour ever logged on the issue
 * would keep counting towards the project it just left. But from HIN-88 the
 * tuple that decides whether an entry is immutable is <em>(owner, project,
 * day)</em>, so re-pointing the project moves entries out of a period somebody
 * has handed in, or into one that is already signed off. Neither may happen
 * silently: the submitted total would stop matching the entries behind it, and
 * an approved month would quietly become editable again.
 *
 * <p>The direction is inverted, like {@code setup/SettingsGuard} and
 * {@code SettingsPrefill}: {@code issue} knows it is about to move work items and
 * must not know what makes one immutable, and the module boundary (see
 * {@code timetracking.ModuleBoundaryTest}) forbids it from asking. So the module
 * implements this and {@code IssueMoveService} calls it before its first write.
 *
 * <p>Implementations throw to refuse, and are expected to. With no
 * implementation — the module switched off, or removed — a move behaves exactly
 * as it did before.
 */
public interface WorkItemMoveGuard {

	/**
	 * Refuses, by throwing, when this issue's hours cannot leave {@code fromProjectId}
	 * for {@code toProjectId}.
	 *
	 * @param issueId       the issue whose work items are about to be re-pointed
	 * @param fromProjectId the project they carry now
	 * @param toProjectId   the project they would carry
	 */
	void check(String issueId, String fromProjectId, String toProjectId);
}
