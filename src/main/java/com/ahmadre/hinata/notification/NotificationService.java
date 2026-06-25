package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
	private final PushService push;
	private final HinataProperties props;

	private static final String SUBJECT_PREFIX = "[Hinata] ";

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

	// --- Team membership events ----------------------------------------------
	// Fan-out to the single affected user (in-app + e-mail), localized to their
	// own UI language. [teamName]/[teamId] are passed in so the caller need not
	// expose the Team type to this package.

	public void notifyAddedToTeam(String userId, String teamId, String teamName) {
		users.findById(userId).filter(User::isActive).ifPresent(user -> {
			String title = de(user) ? "Zu einem Team hinzugefügt" : "Added to a team";
			String body = de(user)
					? "Du wurdest dem Team \"" + teamName + "\" hinzugefügt."
					: "You've been added to the team \"" + teamName + "\".";
			deliverOne(user, Notification.Type.TEAM_ADDED, title, body, teamLink(teamId));
		});
	}

	public void notifyTeamRoleChanged(String userId, String teamId, String teamName, boolean admin) {
		users.findById(userId).filter(User::isActive).ifPresent(user -> {
			String title = de(user) ? "Team-Rolle aktualisiert" : "Team role updated";
			String body;
			if (de(user)) {
				body = admin
						? "Du bist jetzt Team-Admin von \"" + teamName + "\"."
						: "Deine Rolle in \"" + teamName + "\" ist jetzt Mitglied.";
			}
			else {
				body = admin
						? "You are now a Team-Admin of \"" + teamName + "\"."
						: "Your role in \"" + teamName + "\" is now Member.";
			}
			deliverOne(user, Notification.Type.TEAM_ROLE_CHANGED, title, body, teamLink(teamId));
		});
	}

	public void notifyRemovedFromTeam(String userId, String teamName) {
		users.findById(userId).filter(User::isActive).ifPresent(user -> {
			String title = de(user) ? "Aus einem Team entfernt" : "Removed from a team";
			String body = de(user)
					? "Du wurdest aus dem Team \"" + teamName + "\" entfernt."
					: "You've been removed from the team \"" + teamName + "\".";
			deliverOne(user, Notification.Type.TEAM_REMOVED, title, body, null);
		});
	}

	private void deliverOne(User user, Notification.Type type, String title, String body, String link) {
		notifications.save(Notification.builder()
				.userId(user.getId()).type(type).title(title).body(body).link(link).build());
		// In-app notifications keep the relative route; the e-mail button needs an
		// absolute deep link to the frontend so it works from any mail client.
		mail.send(user.getEmail(), SUBJECT_PREFIX + title, title, body, props.deepLink(link));
		push.sendToUser(user.getId(), title, body, link);
	}

	private String teamLink(String teamId) {
		return "/teams/" + teamId;
	}

	// --- Account lifecycle events ---------------------------------------------
	// These always reach the affected user by e-mail (even once deactivated or
	// deleted), so they bypass the active-user filter used for issue fan-out.

	public void notifyAccountActivated(User user) {
		String title = de(user) ? "Konto aktiviert" : "Account activated";
		String body = de(user)
				? "Dein Hinata-Konto wurde aktiviert. Du kannst dich jetzt wieder anmelden."
				: "Your Hinata account has been activated. You can sign in again now.";
		persist(user, Notification.Type.ACCOUNT_ACTIVATED, title, body, "/login");
		mail.sendTemplate(user.getEmail(), SUBJECT_PREFIX + title, "email/account-activated",
				accountModel(user, signInLink()));
	}

	public void notifyAccountDeactivated(User user) {
		String title = de(user) ? "Konto deaktiviert" : "Account deactivated";
		String body = de(user)
				? "Dein Hinata-Konto wurde deaktiviert. Du kannst dich derzeit nicht anmelden."
				: "Your Hinata account has been deactivated. You currently cannot sign in.";
		persist(user, Notification.Type.ACCOUNT_DEACTIVATED, title, body, null);
		mail.sendTemplate(user.getEmail(), SUBJECT_PREFIX + title, "email/account-deactivated",
				accountModel(user, null));
	}

	public void notifyRolesChanged(User user) {
		boolean isAdmin = user.isAdmin();
		String title = de(user) ? "Rollen aktualisiert" : "Roles updated";
		String body;
		if (de(user)) {
			body = isAdmin ? "Dir wurden Administrator-Rechte erteilt."
					: "Deine Administrator-Rechte wurden entfernt.";
		}
		else {
			body = isAdmin ? "You have been granted administrator privileges."
					: "Your administrator privileges have been removed.";
		}
		persist(user, Notification.Type.ACCOUNT_ROLE_CHANGED, title, body, null);
		Map<String, Object> model = accountModel(user, null);
		model.put("isAdmin", isAdmin);
		model.put("roles", roleLabels(user));
		mail.sendTemplate(user.getEmail(), SUBJECT_PREFIX + title, "email/account-role-changed", model);
	}

	/**
	 * Must be invoked <em>before</em> the user document is removed. No in-app
	 * notification is persisted because the account (and its notifications) are
	 * about to be deleted; the mail is dispatched asynchronously from captured
	 * values.
	 */
	public void notifyAccountDeleted(User user) {
		String title = de(user) ? "Konto gelöscht" : "Account deleted";
		mail.sendTemplate(user.getEmail(), SUBJECT_PREFIX + title, "email/account-deleted",
				accountModel(user, null));
	}

	private void persist(User user, Notification.Type type, String title, String body, String link) {
		notifications.save(Notification.builder()
				.userId(user.getId()).type(type).title(title).body(body).link(link).build());
		push.sendToUser(user.getId(), title, body, link);
	}

	private Map<String, Object> accountModel(User user, String ctaLink) {
		Map<String, Object> model = new HashMap<>();
		model.put("displayName", user.getDisplayName());
		model.put("locale", de(user) ? "de" : "en");
		model.put("ctaLink", ctaLink);
		return model;
	}

	private boolean de(User user) {
		return "de".equalsIgnoreCase(user.getLocale());
	}

	private String roleLabels(User user) {
		String member = de(user) ? "Mitglied" : "Member";
		return user.getRoles().stream()
				.sorted()
				.map(role -> role == Role.ADMIN ? "Administrator" : member)
				.collect(Collectors.joining(", "));
	}

	private String signInLink() {
		return props.deepLink("/login");
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
				// In-app notifications keep the relative route; the e-mail button gets
				// an absolute deep link to the issue on the frontend.
				mail.send(user.getEmail(), SUBJECT_PREFIX + title, title, body, props.deepLink(link));
				push.sendToUser(user.getId(), title, body, link);
			});
		}
	}

	private String issueLink(Issue issue) {
		return "/issues/" + issue.getReadableId();
	}
}
