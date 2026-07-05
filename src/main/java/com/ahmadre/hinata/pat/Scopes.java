package com.ahmadre.hinata.pat;

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
	public static final String KB_READ = "kb:read";
	public static final String KB_WRITE = "kb:write";
	public static final String WORKLOG_WRITE = "worklog:write";
	public static final String SEARCH_READ = "search:read";

	/** Every scope a token may be granted. */
	public static final Set<String> ALL = Set.of(
			ISSUES_READ, ISSUES_WRITE, PROJECTS_READ,
			KB_READ, KB_WRITE, WORKLOG_WRITE, SEARCH_READ);

	public static boolean isValid(String scope) {
		return ALL.contains(scope);
	}
}
