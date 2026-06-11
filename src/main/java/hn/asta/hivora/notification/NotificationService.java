package hn.asta.hivora.notification;

import hn.asta.hivora.issue.Issue;
import hn.asta.hivora.user.User;
import hn.asta.hivora.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * Fan-out for issue events: persists in-app notifications and sends e-mails.
 * Push (FCM) hooks in here once configured in the admin area.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

	private final NotificationRepository notifications;
	private final UserRepository users;
	private final MailService mail;

	public void notifyIssueAssigned(Issue issue) {
		deliver(Set.of(issue.getAssigneeId()), Notification.Type.ISSUE_ASSIGNED,
				issue.getReadableId() + " assigned to you", issue.getTitle(), issueLink(issue));
	}

	public void notifyIssueUpdated(Issue issue, User editor, String change) {
		deliver(watchersWithout(issue, editor), Notification.Type.ISSUE_UPDATED,
				issue.getReadableId() + " updated", change, issueLink(issue));
	}

	public void notifyIssueCommented(Issue issue, User author) {
		deliver(watchersWithout(issue, author), Notification.Type.ISSUE_COMMENTED,
				"New comment on " + issue.getReadableId(),
				author.getDisplayName() + " commented on \"" + issue.getTitle() + "\"",
				issueLink(issue));
	}

	private Set<String> watchersWithout(Issue issue, User exclude) {
		Set<String> recipients = new HashSet<>(issue.getWatcherIds());
		if (issue.getAssigneeId() != null) recipients.add(issue.getAssigneeId());
		if (issue.getReporterId() != null) recipients.add(issue.getReporterId());
		if (exclude != null) recipients.remove(exclude.getId());
		return recipients;
	}

	private void deliver(Set<String> userIds, Notification.Type type, String title, String body,
			String link) {
		for (String userId : userIds) {
			if (userId == null) continue;
			users.findById(userId).filter(User::isActive).ifPresent(user -> {
				notifications.save(Notification.builder()
						.userId(user.getId()).type(type).title(title).body(body).link(link).build());
				mail.send(user.getEmail(), "[Hivora] " + title, title, body, null);
			});
		}
	}

	private String issueLink(Issue issue) {
		return "/issues/" + issue.getReadableId();
	}
}
