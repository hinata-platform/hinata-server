package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.moderation.freeze.FrozenIssues;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Daily reminder that an issue is approaching its due date. Notifies the issue's
 * assignees (gated by their "Sprints &amp; deadlines" channel preference) once per
 * due date: the {@code dueReminderFor} marker prevents re-notifying, yet re-arms
 * automatically if the due date is later changed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DueDateReminderJob {

	/** How many days ahead counts as "due soon". */
	private static final int HORIZON_DAYS = 2;

	private final MongoTemplate mongo;
	private final NotificationService notifications;
	private final FrozenIssues frozenIssues;

	@Scheduled(cron = "0 0 7 * * *")
	public void remind() {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		LocalDate horizon = today.plusDays(HORIZON_DAYS);
		// Open (unresolved) issues due within the window. The two-field "already
		// reminded" check can't be expressed in the query, so it's filtered below.
		Query query = new Query(new Criteria().andOperator(
				Criteria.where("dueDate").gte(today).lte(horizon),
				Criteria.where("resolvedAt").is(null)));
		// Frozen issues are excluded in the query, and this is the single most
		// important exclusion in the product. Every other freeze gap serves content to
		// somebody who asked for it; this one MINTS new notifications and pushes a
		// frozen issue's title out by e-mail and push, on a timer, daily, forever,
		// with no human in the loop. A freeze cannot recall what already left — but it
		// has to stop tomorrow's send, and this is where that happens.
		//
		// In the query rather than a `continue` in the loop, so the dueReminderFor
		// marker below is never written for a skipped issue either: a released freeze
		// then still gets its reminder, instead of the freeze having silently consumed
		// it. Excluded, not deferred — nothing is queued up to fire on release.
		List<Issue> candidates = mongo.find(frozenIssues.scoped(query), Issue.class);

		int sent = 0;
		for (Issue issue : candidates) {
			LocalDate due = issue.getDueDate();
			if (due == null || due.equals(issue.getDueReminderFor())) {
				continue; // no due date, or already reminded for this exact date
			}
			List<String> assignees = issue.getAssigneeIds();
			if (assignees != null && !assignees.isEmpty()) {
				notifications.notifyDueSoon(issue, assignees);
				sent++;
			}
			// Mark handled regardless of recipients so unassigned issues aren't
			// rescanned daily. Targeted $set (not a full save from the possibly-stale
			// candidate loaded at the top of the run) so a concurrent user edit
			// during the scan isn't clobbered.
			issue.setDueReminderFor(due);
			mongo.updateFirst(new Query(Criteria.where("_id").is(issue.getId())),
					new Update().set("dueReminderFor", due), Issue.class);
		}
		if (sent > 0) {
			log.info("Sent due-soon reminders for {} issue(s)", sent);
		}
	}
}
