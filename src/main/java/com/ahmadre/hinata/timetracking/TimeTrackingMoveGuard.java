package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.issue.WorkItemMoveGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * An issue does not move while its hours are frozen.
 *
 * <p>{@code IssueMoveService} re-points {@code projectId} on every work item of
 * the issue, in one bulk update, and that update never passes
 * {@link TimeLocks#assertWritable}. Until HIN-88 that was a cascade of somebody
 * else's decision and nothing more; now the project is half of the tuple that
 * decides immutability, so the same statement can carry an entry out of a period
 * its owner has handed in — or into one that is already signed off. The
 * submitted total stops matching the entries behind it, an approved month
 * becomes editable again, and the only trace is an {@code ISSUE_MOVED} record.
 * A move is authorised by membership of both projects, so this is reachable by
 * any member.
 *
 * <p>So the move is refused rather than the entries being left behind: leaving
 * them would put the hours on the project the issue no longer belongs to, which
 * is the thing {@code followWorkItems} exists to prevent. The way through is the
 * way through everywhere else — reopen the period, move the issue, hand it in
 * again — and the refusal carries the same reason, holder and remedy every other
 * frozen answer does.
 *
 * <p>Both sides are asked, because both are writes to a frozen day: the project
 * the entry is leaving and the one it is arriving at.
 */
@Component
@RequiredArgsConstructor
public class TimeTrackingMoveGuard implements WorkItemMoveGuard {

	/**
	 * How many of the issue's entries are examined.
	 *
	 * <p>A guard that read every entry of a five-year-old issue to answer one move
	 * would be its own denial of service. Beyond this many the answer is the same
	 * either way in practice — an issue with a thousand logged entries has hours in
	 * every period there is — and the cap is stated rather than discovered.
	 */
	private static final int MAX_EXAMINED = 500;

	private final MongoTemplate mongo;
	private final TimeLocks locks;
	private final TimeTrackingSettings policy;

	@Override
	public void check(String issueId, String fromProjectId, String toProjectId) {
		if (!policy.advancedEnabled()) {
			// The module is off: nothing freezes anything, and the move behaves
			// exactly as it did before this class existed.
			return;
		}
		List<WorkItem> attached = mongo.find(
				Query.query(Criteria.where("issueId").is(issueId)).limit(MAX_EXAMINED),
				WorkItem.class);
		for (WorkItem item : attached) {
			for (String projectId : List.of(String.valueOf(fromProjectId),
					String.valueOf(toProjectId))) {
				TimeLocks.LockState frozen = locks.lockStateFor(item.getUserId(),
						"null".equals(projectId) ? null : projectId, item.getDate());
				if (frozen != null) {
					throw frozen.refusal();
				}
			}
		}
	}
}
