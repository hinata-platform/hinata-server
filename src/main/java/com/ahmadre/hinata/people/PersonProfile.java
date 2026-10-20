package com.ahmadre.hinata.people;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A person as another member of the organisation sees them (HIN-165): what the directory shows
 * anyway, the teams and projects both of them are in, and, where the viewer may know it, whether
 * they are away today.
 *
 * <p>{@code email} is filled only for somebody who administers accounts: there is no setting that
 * shows addresses to members, and the profile does not invent one. {@code canAdmin} tells the
 * client to offer the user administration's actions, which go to the existing admin routes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonProfile(
		String id,
		String displayName,
		String username,
		String avatarUrl,
		String title,
		String pronouns,
		/** {@code ADMIN}, {@code ORG_ADMIN}, {@code MEMBER}, in that order, each at most once. */
		List<String> roles,
		/** The person's zone (IANA id): their own, else the organisation's, else {@code UTC}. */
		String timezone,
		List<TeamRef> sharedTeams,
		List<ProjectRef> sharedProjects,
		/** Null when not away today or when the viewer may not know. */
		PersonAbsences.Absence absenceToday,
		/** Null unless {@link #canAdmin}. */
		String email,
		/** False only for a deactivated account, which only an administrator is shown. */
		boolean active,
		boolean self,
		boolean canAdmin) {

	public record TeamRef(String id, String name, String key, String avatarUrl, String icon) {
	}

	public record ProjectRef(String id, String name, String key, String color) {
	}
}
