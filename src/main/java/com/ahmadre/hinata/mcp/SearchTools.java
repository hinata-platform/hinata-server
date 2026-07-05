package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.search.SearchResponse;
import com.ahmadre.hinata.search.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/**
 * Read-only MCP tool exposing the unified global search (the ⌘K palette
 * backend). Gates on {@code search:read}, resolves the caller via
 * {@link CurrentUser} and delegates to {@link SearchService}, which already
 * restricts hits to active projects. The {@link SearchResponse} carries only
 * presentation-safe fields (no tokens or storage keys).
 */
@Service
@RequiredArgsConstructor
public class SearchTools {

	private final ScopeGuard scopeGuard;
	private final CurrentUser currentUser;
	private final SearchService searchService;

	@McpTool(name = "search", title = "Global search",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "Unified search across issues, projects, people, boards and knowledge "
					+ "articles. Returns results grouped by category. scope is optional: 'all' "
					+ "(default) or one of ISSUES, PROJECTS, PEOPLE, BOARDS, DOCS.")
	public SearchResponse search(
			@McpToolParam(description = "Free-text query") String q,
			@McpToolParam(required = false, description = "Category scope: all | ISSUES | PROJECTS | PEOPLE | BOARDS | DOCS") String scope) {
		scopeGuard.require(Scopes.SEARCH_READ);
		currentUser.require();
		return searchService.search(q, scope);
	}
}
