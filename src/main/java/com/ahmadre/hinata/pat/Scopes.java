package com.ahmadre.hinata.pat;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The fixed catalogue of MCP capability scopes. A Personal Access Token is
 * granted a subset of these; each becomes a Spring authority
 * {@code SCOPE_<scope>} on the {@link PatAuthenticationToken}. Write-capable MCP
 * tools gate on the matching scope (see {@code mcp.ScopeGuard}), so a token can
 * be issued least-privilege (e.g. read-only) even though the underlying user
 * holds broader application rights.
 *
 * <p>The string values are a stable wire contract (persisted on the token and
 * shown in the UI) — add new scopes, never rename existing ones.
 */
public final class Scopes {

	private Scopes() {
	}

	/** Authority prefix Spring Security convention uses for OAuth-style scopes. */
	public static final String AUTHORITY_PREFIX = "SCOPE_";

	public static final String ISSUES_READ = "issues:read";
	public static final String ISSUES_WRITE = "issues:write";
	public static final String PROJECTS_READ = "projects:read";
	/**
	 * Creating a project from an existing one: the copy and the template routes of HIN-120.
	 * Deliberately not "edit any project setting" — nothing behind this scope renames, archives
	 * or reconfigures a project that already exists.
	 */
	public static final String PROJECTS_WRITE = "projects:write";
	public static final String BOARDS_READ = "boards:read";
	public static final String SPRINTS_WRITE = "sprints:write";
	public static final String TEAMS_READ = "teams:read";
	public static final String USERS_READ = "users:read";
	public static final String KB_READ = "kb:read";
	public static final String KB_WRITE = "kb:write";
	public static final String WORKLOG_READ = "worklog:read";
	public static final String WORKLOG_WRITE = "worklog:write";
	public static final String SEARCH_READ = "search:read";
	public static final String NOTIFICATIONS_READ = "notifications:read";
	/** Shift planning (HIN-164): reading events, shifts and the holder's own places. */
	public static final String SHIFTS_READ = "shifts:read";
	/** Shift planning: signing up, confirming, declining and giving up the holder's own places. */
	public static final String SHIFTS_WRITE = "shifts:write";
	/**
	 * Shift planning: putting other people on a shift and taking them off it, as a planner. Never
	 * part of {@link #DEFAULT_GRANT}: a client gets it only by asking for it by name.
	 */
	public static final String SHIFTS_PLAN = "shifts:plan";

	/** Every scope a token may be granted. */
	public static final Set<String> ALL = Set.of(
			ISSUES_READ, ISSUES_WRITE, PROJECTS_READ, PROJECTS_WRITE,
			BOARDS_READ, SPRINTS_WRITE, TEAMS_READ, USERS_READ,
			KB_READ, KB_WRITE, WORKLOG_READ, WORKLOG_WRITE,
			SEARCH_READ, NOTIFICATIONS_READ,
			SHIFTS_READ, SHIFTS_WRITE, SHIFTS_PLAN);

	/**
	 * What an OAuth client is offered when it names no {@code scope}: everything except acting for
	 * other people. Planning shifts for others is asked for explicitly or not at all.
	 */
	public static final Set<String> DEFAULT_GRANT = defaultGrant();

	/**
	 * Scopes that belong to a switchable module, by the flag that switches it. The consent screen
	 * leaves them out while the module is off; a token may still hold them, and the tools then
	 * answer that the feature is off.
	 */
	public static final Map<String, String> MODULE_FLAGS = Map.of(
			SHIFTS_READ, "shift_planning",
			SHIFTS_WRITE, "shift_planning",
			SHIFTS_PLAN, "shift_planning");

	private static Set<String> defaultGrant() {
		Set<String> grant = new LinkedHashSet<>(ALL);
		grant.remove(SHIFTS_PLAN);
		return Set.copyOf(grant);
	}

	public static boolean isValid(String scope) {
		return ALL.contains(scope);
	}
}
