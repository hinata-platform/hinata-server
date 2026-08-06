package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.mcp.McpViews.PageResult;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserDirectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read-only MCP tools over the user directory. Gates on {@code users:read} and
 * mirrors the app's directory endpoints: any authenticated member may look up
 * active users' public profile fields (the same data every assignee picker
 * shows) — never emails, roles or security state of other users. {@code get_me}
 * describes the caller so the model knows whose behalf it acts on.
 */
@Service
@RequiredArgsConstructor
public class PeopleTools {

	private final ScopeGuard scopeGuard;
	private final CurrentUser currentUser;
	// The same collaborator the app's directory endpoints use, so this tool cannot
	// answer a question the app would not — including about a frozen account.
	private final UserDirectoryService directory;

	@McpTool(name = "search_users", title = "Search users",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "Search the user directory by display name, username or title — use "
					+ "this to resolve people to user ids for assignments. An empty query returns "
					+ "the first page of all active users.")
	public PageResult<PersonView> searchUsers(
			@McpToolParam(required = false, description = "Free-text query over name / username / title") String query,
			@McpToolParam(required = false, description = "Zero-based page index (default 0)") Integer page,
			@McpToolParam(required = false, description = "Page size, max 100 (default 25)") Integer size) {
		scopeGuard.require(Scopes.USERS_READ);
		currentUser.require();
		int pageIndex = page == null || page < 0 ? 0 : page;
		int pageSize = size == null || size <= 0 ? 25 : Math.min(size, 100);
		Pageable pageable = PageRequest.of(pageIndex, pageSize,
				Sort.by(Sort.Direction.ASC, "displayName"));
		return PageResult.of(directory.search(query, pageable), PersonView::of);
	}

	@McpTool(name = "get_me", title = "Who am I",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "The profile of the connected user — the person every tool call acts "
					+ "as. Includes the caller's own email and locale.")
	public MeView getMe() {
		scopeGuard.require(Scopes.USERS_READ);
		User me = currentUser.require();
		return new MeView(me.getId(), me.getUsername(), me.getDisplayName(), me.getEmail(),
				me.getTitle(), me.getLocale(), me.isAdmin());
	}

	/** Public directory fields of a user — what the app's people pickers show. */
	public record PersonView(String id, String username, String displayName, String title) {

		static PersonView of(User user) {
			return new PersonView(user.getId(), user.getUsername(), user.getDisplayName(),
					user.getTitle());
		}
	}

	/** The caller's own profile (self-scoped, so the email is fine to include). */
	public record MeView(String id, String username, String displayName, String email,
			String title, String locale, boolean admin) {
	}
}
