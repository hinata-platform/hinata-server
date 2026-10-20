package com.ahmadre.hinata.project;

/**
 * Somebody can no longer see a project: dropped from its member list, or the last team grant that
 * gave it to them was taken away.
 *
 * <p>Published by {@link ProjectService#announceMembersRemoved} only for people who really lost the
 * project, so a direct member who still reaches it through a team is not announced. Modules that keep
 * per-person state inside a project (shift planning's planners and assignments) clear it on this
 * event without the project domain knowing they exist, the way {@code UserService.UserDeletedEvent}
 * works for accounts.
 *
 * <p>Published after the project is saved. A listener that fails must catch its own failure: the
 * membership change has already happened.
 */
public record ProjectMemberRemoved(String projectId, String userId) {
}
