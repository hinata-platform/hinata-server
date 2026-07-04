package com.ahmadre.hinata.admin;

import java.util.List;

/**
 * One page of the admin directory plus workspace-wide aggregate counts. The
 * counts are global (independent of the current filters) so the KPI strip and
 * the last-active-admin guard can be rendered without fetching every row.
 */
public record AdminUserListResponse(
		List<AdminUserResponse> items,
		long total,
		int page,
		int perPage,
		Counts counts) {

	/** Global tallies across all users, mirroring the board's KPI strip. */
	public record Counts(long total, long admins, long active, long invited,
			long expiredInvites, long disabled, long activeAdmins, long pendingApproval) {
	}
}
