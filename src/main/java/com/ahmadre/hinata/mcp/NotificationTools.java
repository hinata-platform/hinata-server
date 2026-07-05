package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.mcp.McpViews.PageResult;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationRepository;
import com.ahmadre.hinata.pat.Scopes;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Read-only MCP tool over the caller's own notification inbox. Gates on
 * {@code notifications:read}; the query is hard-scoped to the authenticated
 * user's id, so an MCP caller can never read another user's notifications.
 * Reading here never marks anything as read — the inbox badge in the app stays
 * accurate.
 */
@Service
@RequiredArgsConstructor
public class NotificationTools {

	private final ScopeGuard scopeGuard;
	private final CurrentUser currentUser;
	private final NotificationRepository notifications;

	@McpTool(name = "list_my_notifications", title = "List my notifications",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false),
			description = "The caller's notification inbox, newest first, with the unread count — "
					+ "assignments, mentions, comments, sprint starts and system messages.")
	public InboxView listMyNotifications(
			@McpToolParam(required = false, description = "Zero-based page index (default 0)") Integer page,
			@McpToolParam(required = false, description = "Page size, max 100 (default 25)") Integer size) {
		scopeGuard.require(Scopes.NOTIFICATIONS_READ);
		User me = currentUser.require();
		int pageIndex = page == null || page < 0 ? 0 : page;
		int pageSize = size == null || size <= 0 ? 25 : Math.min(size, 100);
		PageResult<NotificationView> items = PageResult.of(
				notifications.findByUserIdOrderByCreatedAtDesc(me.getId(),
						PageRequest.of(pageIndex, pageSize)),
				NotificationView::of);
		return new InboxView(notifications.countByUserIdAndReadFalse(me.getId()), items);
	}

	/** The unread badge count plus one page of the inbox. */
	public record InboxView(long unreadCount, PageResult<NotificationView> notifications) {
	}

	/** One notification as shown in the app's inbox. */
	public record NotificationView(String id, String type, String title, String body,
			String link, boolean read, Instant createdAt) {

		static NotificationView of(Notification n) {
			return new NotificationView(n.getId(),
					n.getType() == null ? null : n.getType().name(),
					n.getTitle(), n.getBody(), n.getLink(), n.isRead(), n.getCreatedAt());
		}
	}
}
