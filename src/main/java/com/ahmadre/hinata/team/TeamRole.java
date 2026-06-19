package com.ahmadre.hinata.team;

/** A member's role <em>within a single team</em>. ADMIN == "Team-Admin": full
 * control of that team (members, projects, settings) but never platform-wide. */
public enum TeamRole {
	ADMIN,
	MEMBER
}
