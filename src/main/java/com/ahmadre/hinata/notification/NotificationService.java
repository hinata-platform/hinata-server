package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.moderation.report.UserBlockService;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
	private final GatewayService gateway;
	private final RichTextService richText;
	// Decides per recipient whether a project-scoped link resolves for them. Injected as
	// the rule component, not ProjectService — that service depends on this one.
	private final ProjectReach reach;
	// A block that holds in the thread but not in the push is not a block: the
	// recipient still gets the message, just on the one surface they cannot look away
	// from. See #blockersOf.
	private final UserBlockService userBlocks;

	private static final String SUBJECT_PREFIX = "[Hinata] ";

	/** Notify each given assignee (except the actor) that the issue is theirs. */
	public void notifyAssigned(Issue issue, User actor, java.util.Collection<String> assigneeIds) {
		Set<String> recipients = new HashSet<>(assigneeIds != null ? assigneeIds : Set.of());
		if (actor != null) recipients.remove(actor.getId());
		if (recipients.isEmpty()) return;
		deliver(recipients, Notification.Type.ISSUE_ASSIGNED,
				de -> de ? issue.getReadableId() + " dir zugewiesen"
						: issue.getReadableId() + " assigned to you",
				de -> issue.getTitle(), // user content — same in every language
				issueLink(issue), issue.getProjectId());
	}

	/**
	 * Notifies a project's members that a new issue landed via inbound e-mail
	 * ingestion. There is no human actor — the sender is an external e-mail
	 * address — so every active member is a recipient. Delivery is gated by each
	 * member's {@code ingest} channel preference (default: push on, e-mail off).
	 */
	public void notifyIssueIngested(Issue issue, java.util.Collection<String> memberIds, String senderEmail) {
		Set<String> recipients = new HashSet<>(memberIds != null ? memberIds : Set.of());
		if (recipients.isEmpty()) return;
		boolean hasSender = senderEmail != null && !senderEmail.isBlank()
				&& !"unknown".equalsIgnoreCase(senderEmail);
		deliver(recipients, Notification.Type.ISSUE_INGESTED,
				de -> de ? "Neue Aufgabe per E-Mail: " + issue.getReadableId()
						: "New issue via e-mail: " + issue.getReadableId(),
				de -> hasSender
						? (de ? "Von " + senderEmail + ": \"" + issue.getTitle() + "\""
								: "From " + senderEmail + ": \"" + issue.getTitle() + "\"")
						: issue.getTitle(),
				issueLink(issue), issue.getProjectId());
	}

	/** Notify the issue's watchers that its workflow state changed. */
	public void notifyStateChanged(Issue issue, User editor, String newState) {
		deliver(watchersWithout(issue, editor), Notification.Type.ISSUE_UPDATED,
				de -> de ? issue.getReadableId() + " aktualisiert"
						: issue.getReadableId() + " updated",
				de -> de ? "Status geändert zu \"" + newState + "\""
						: "State changed to \"" + newState + "\"",
				issueLink(issue), issue.getProjectId());
	}

	/**
	 * Fan-out for a new comment. Users named with an {@code @}-mention get a
	 * direct {@code MENTION} notification; the author of the comment a reply
	 * answers gets a {@code COMMENT_REPLY} notification; the issue's watchers get
	 * the broader {@code ISSUE_COMMENTED} notice. Each stronger notice supersedes
	 * the weaker one for the same recipient (mention &gt; reply &gt; watcher), so
	 * nobody is pinged twice. The comment author never notifies themselves.
	 *
	 * <p>Nobody who has blocked {@code author} hears about this comment on any of
	 * the three notices — including a direct {@code @}-mention, which is the one an
	 * author can aim. A mention is not a way to reach past a block; being able to
	 * type someone's name and land on their lock screen is precisely what the block
	 * exists to stop.
	 */
	public void notifyComment(Issue issue, User author, IssueComment comment) {
		String doc = comment == null ? null : comment.getTextDoc();
		String preview = preview(doc);
		// Resolved once for the whole fan-out and passed down: the three notices below
		// each ask the same question about the same author, and answering it per
		// recipient would put a query inside the delivery loop.
		Set<String> blockers = blockersOf(author);
		Set<String> mentioned = new HashSet<>(richText.mentionedUsers(doc));
		mentioned.remove(author.getId());
		mentioned.removeAll(blockers);
		notifyMentions(issue, author, mentioned, preview, blockers);
		// Everyone already directly notified about this comment — start with the
		// mentioned users, then add the reply target so the watcher notice below
		// skips them too.
		Set<String> notified = new HashSet<>(mentioned);
		notifyReply(issue, author, comment, preview, notified, blockers);
		Set<String> watchers = watchersWithout(issue, author);
		watchers.removeAll(notified);
		watchers.removeAll(blockers);
		if (!watchers.isEmpty()) {
			// Lead with a teaser of the comment itself so the recipient can triage
			// straight from the push/e-mail; fall back to the issue title when the
			// comment has no readable text (e.g. attachment-only).
			deliver(watchers, Notification.Type.ISSUE_COMMENTED,
					de -> de ? "Neuer Kommentar zu " + issue.getReadableId()
							: "New comment on " + issue.getReadableId(),
					de -> preview.isBlank()
							? (de ? author.getDisplayName() + " hat \"" + issue.getTitle() + "\" kommentiert"
									: author.getDisplayName() + " commented on \"" + issue.getTitle() + "\"")
							: author.getDisplayName() + (de ? " kommentierte: \"" : " commented: \"")
									+ preview + "\"",
					issueLink(issue), issue.getProjectId());
		}
	}

	/**
	 * Notifies the author of the comment a reply answers ({@code COMMENT_REPLY}),
	 * deep-linking straight to the new reply. No-op for a top-level comment, a
	 * self-reply, or when that author was already {@code @}-mentioned in the same
	 * reply (the mention supersedes). The recipient is added to {@code notified}
	 * so the broader watcher notice skips them. Shares the {@code mentions}
	 * preference event — the setting already reads "mentions you or replies to
	 * your comment", so replies honour the same toggle.
	 */
	private void notifyReply(Issue issue, User actor, IssueComment comment, String preview,
			Set<String> notified, Set<String> blockers) {
		if (comment == null) return;
		String recipient = comment.getReplyToAuthorId();
		if (recipient == null || recipient.isBlank()) return; // top-level comment
		if (recipient.equals(actor.getId())) return; // replying to oneself
		// Marked notified before the block test, not after: the recipient must stay
		// off the broader watcher notice too, or the reply they were meant not to
		// hear about reaches them as "new comment on HIN-42" instead.
		boolean first = notified.add(recipient);
		if (blockers.contains(recipient)) return;
		if (!first) return; // already mentioned — don't double-ping
		boolean hasPreview = preview != null && !preview.isBlank();
		deliver(Set.of(recipient), Notification.Type.COMMENT_REPLY,
				de -> de
						? actor.getDisplayName() + " hat auf deinen Kommentar in "
								+ issue.getReadableId() + " geantwortet"
						: actor.getDisplayName() + " replied to your comment on "
								+ issue.getReadableId(),
				de -> hasPreview
						? actor.getDisplayName() + ": \"" + preview + "\""
						: (de ? actor.getDisplayName() + " hat auf deinen Kommentar zu \""
								+ issue.getTitle() + "\" geantwortet"
								: actor.getDisplayName() + " replied to your comment on \""
										+ issue.getTitle() + "\""),
				commentLink(issue, comment.getId()), issue.getProjectId());
	}

	/**
	 * Sends a direct {@code MENTION} notification to each given user (excluding the
	 * actor, who never notifies themselves). Used for mentions in comments and in
	 * the issue description.
	 */
	public void notifyMentions(Issue issue, User actor, Set<String> mentionedIds) {
		notifyMentions(issue, actor, mentionedIds, null);
	}

	/**
	 * As {@link #notifyMentions(Issue, User, Set)}, but surfaces a short plain-text
	 * {@code preview} of the surrounding text (the comment or description) in the
	 * notification body so the recipient sees what they were mentioned about. A
	 * blank preview falls back to the generic issue-title wording.
	 */
	public void notifyMentions(Issue issue, User actor, Set<String> mentionedIds, String preview) {
		if (actor == null) return; // system/seed authored — no human to attribute
		notifyMentions(issue, actor, mentionedIds, preview, blockersOf(actor));
	}

	/**
	 * As above, with [actor]'s blockers already resolved — for a caller that is
	 * sending several notices about one write and must not ask again per notice.
	 */
	private void notifyMentions(Issue issue, User actor, Set<String> mentionedIds, String preview,
			Set<String> blockers) {
		if (actor == null) return; // system/seed authored — no human to attribute
		Set<String> recipients = new HashSet<>(mentionedIds);
		recipients.remove(actor.getId());
		recipients.removeAll(blockers);
		if (recipients.isEmpty()) return;
		boolean hasPreview = preview != null && !preview.isBlank();
		deliver(recipients, Notification.Type.MENTION,
				de -> de
						? actor.getDisplayName() + " hat dich in " + issue.getReadableId() + " erwähnt"
						: actor.getDisplayName() + " mentioned you in " + issue.getReadableId(),
				de -> hasPreview
						? actor.getDisplayName() + ": \"" + preview + "\""
						: (de ? actor.getDisplayName() + " hat dich zu \"" + issue.getTitle() + "\" erwähnt"
								: actor.getDisplayName() + " mentioned you on \"" + issue.getTitle() + "\""),
				issueLink(issue), issue.getProjectId());
	}

	/**
	 * Notifies users who are mentioned in {@code after} but were not already
	 * mentioned in {@code before} — so creating or editing a description pings only
	 * the newly added mentions, never re-pinging existing ones on unrelated edits.
	 */
	public void notifyNewMentions(Issue issue, User actor, String before, String after) {
		Set<String> added = new HashSet<>(richText.mentionedUsers(after));
		added.removeAll(richText.mentionedUsers(before));
		if (added.isEmpty()) return;
		notifyMentions(issue, actor, added, preview(after));
	}

	/**
	 * The recipients [actor]'s writing must not reach, resolved ONCE per event.
	 *
	 * <p>Applied to the person-to-person notices only — a comment, a reply, a
	 * mention. It deliberately does <em>not</em> touch assignment, state-change,
	 * sprint or due-date notices: those are the work talking, not a colleague, and a
	 * block that silently stops someone hearing that an issue was assigned to them
	 * would let a personal filter cost them their job, which is the one thing
	 * {@link com.ahmadre.hinata.moderation.report.UserBlock} says a block must never
	 * do.
	 */
	private Set<String> blockersOf(User actor) {
		return actor == null ? Set.of() : userBlocks.blockersOf(actor.getId());
	}

	/** Max characters of comment/description text surfaced in a notification preview. */
	private static final int PREVIEW_MAX = 160;

	/**
	 * Renders a stored Lexical document into a short, single-line plain-text teaser
	 * fit for a push body, e-mail and bell entry: a user mention becomes
	 * {@code @DisplayName}, everything else contributes the words a reader would
	 * see, whitespace is collapsed and the result is truncated with an ellipsis.
	 * Never returns {@code null}. The output is consumed only as plain text — the
	 * e-mail layer HTML-escapes it and the push layer JSON-escapes it — so this is
	 * about readability, not sanitisation.
	 *
	 * <p>Reading the document rather than a markdown string is what keeps the
	 * teaser free of syntax characters without a pile of stripping regexes: there
	 * is no syntax in the document to strip.
	 */
	String preview(String doc) {
		String s = richText.plainText(doc, (type, node) -> {
			if (!"smartlink".equals(type)) return null;
			if (!"user".equals(node.path("kind").asText(""))) return null;
			return "@" + mentionName(node.path("targetId").asText("").trim());
		});
		s = s.replaceAll("\\s+", " ").trim();
		if (s.length() > PREVIEW_MAX) {
			s = s.substring(0, PREVIEW_MAX - 1).trim() + "…";
		}
		return s;
	}

	/** Display name for a mentioned user id, or a neutral fallback if unknown. */
	private String mentionName(String userId) {
		if (userId.isEmpty()) return "someone";
		return users.findById(userId).map(User::getDisplayName).orElse("someone");
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
			deliverGated(user, Notification.Type.TEAM_ADDED, title, body, teamLink(teamId));
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

	/**
	 * Notifies a user that they were added to a project (in-app + e-mail + push),
	 * localized to their own UI language. Mirrors {@link #notifyAddedToTeam}: the
	 * caller passes the project name/id so this package need not depend on the
	 * project type. No-op for unknown or deactivated users.
	 */
	public void notifyAddedToProject(String userId, String projectId, String projectName) {
		users.findById(userId).filter(User::isActive).ifPresent(user -> {
			String title = de(user) ? "Zu einem Projekt hinzugefügt" : "Added to a project";
			String body = de(user)
					? "Du wurdest dem Projekt \"" + projectName + "\" hinzugefügt."
					: "You've been added to the project \"" + projectName + "\".";
			deliverGated(user, Notification.Type.PROJECT_ADDED, title, body, projectLink(projectId));
		});
	}

	private void deliverOne(User user, Notification.Type type, String title, String body, String link) {
		notifications.save(Notification.builder()
				.userId(user.getId()).type(type).title(title).body(body).link(link).build());
		// In-app notifications keep the relative route; the e-mail button needs an
		// absolute deep link that the native app intercepts as a Universal/App Link.
		mail.send(user.getEmail(), SUBJECT_PREFIX + title, title, body, appLink(link),
				buttonLabel(de(user)));
		push.sendToUser(user.getId(), title, body, link);
	}

	/**
	 * Single-recipient delivery that respects the user's channel preferences: the
	 * in-app (bell) notification is always recorded, while e-mail and push are
	 * gated by {@code prefs.deliversEmail/Push(eventId(type))}. Used for events the
	 * user can toggle (invites, digest); locked events (security) always deliver.
	 */
	private void deliverGated(User user, Notification.Type type, String title, String body, String link) {
		if (user == null || !user.isActive()) return;
		String eventId = eventId(type);
		notifications.save(Notification.builder()
				.userId(user.getId()).type(type).title(title).body(body).link(link).build());
		NotificationPreferences prefs = prefsOf(user);
		boolean de = de(user);
		if (prefs.deliversEmail(eventId)) {
			mail.send(user.getEmail(), SUBJECT_PREFIX + title, title, body, appLink(link), buttonLabel(de));
		}
		if (prefs.deliversPush(eventId)) {
			push.sendToUser(user.getId(), title, body, link);
		}
	}

	/**
	 * Notifies every member of the sprint's project(s) that it has started. Gated
	 * by the recipient's {@code sprint} channel preference; the actor who started
	 * it is not notified.
	 */
	public void notifySprintStarted(java.util.Collection<String> recipients, String sprintName,
			String link, User actor) {
		Set<String> ids = new HashSet<>(recipients != null ? recipients : Set.of());
		if (actor != null) ids.remove(actor.getId());
		if (ids.isEmpty()) return;
		deliver(ids, Notification.Type.SPRINT_STARTED,
				de -> de ? "Sprint gestartet: " + sprintName : "Sprint started: " + sprintName,
				de -> de ? "Der Sprint \"" + sprintName + "\" wurde gestartet."
						: "The sprint \"" + sprintName + "\" has started.",
				link);
	}

	/** As {@link #notifySprintStarted}, for sprint completion. */
	public void notifySprintCompleted(java.util.Collection<String> recipients, String sprintName,
			String link, User actor) {
		Set<String> ids = new HashSet<>(recipients != null ? recipients : Set.of());
		if (actor != null) ids.remove(actor.getId());
		if (ids.isEmpty()) return;
		deliver(ids, Notification.Type.SPRINT_COMPLETED,
				de -> de ? "Sprint abgeschlossen: " + sprintName : "Sprint completed: " + sprintName,
				de -> de ? "Der Sprint \"" + sprintName + "\" wurde abgeschlossen."
						: "The sprint \"" + sprintName + "\" has been completed.",
				link);
	}

	/**
	 * Reminds the given recipients (typically the issue's assignees) that an issue
	 * is due soon. Gated by the {@code sprint} channel preference (the "Sprints &
	 * deadlines" setting also covers approaching due dates).
	 */
	public void notifyDueSoon(Issue issue, java.util.Collection<String> recipients) {
		Set<String> ids = new HashSet<>(recipients != null ? recipients : Set.of());
		if (ids.isEmpty()) return;
		deliver(ids, Notification.Type.ISSUE_DUE_SOON,
				de -> de ? issue.getReadableId() + " ist bald fällig"
						: issue.getReadableId() + " is due soon",
				de -> de ? "\"" + issue.getTitle() + "\" ist am " + issue.getDueDate() + " fällig."
						: "\"" + issue.getTitle() + "\" is due on " + issue.getDueDate() + ".",
				issueLink(issue), issue.getProjectId());
	}

	/**
	 * Sends a user their periodic digest. Title/body are pre-composed and localized
	 * by the caller (the digest job). Gated by the {@code digest} channel preference.
	 */
	public void notifyDigest(User user, String title, String body, String link) {
		deliverGated(user, Notification.Type.DIGEST, title, body, link);
	}

	/**
	 * Delivers the weekly summary: an in-app (bell) notice linking to the in-app
	 * Weekly Summary page, and — gated by the {@code digest} channel preference — a
	 * rich templated e-mail ({@code email/weekly-summary}) built from {@code model}
	 * and a push. Mirrors {@link #deliverGated} but swaps the plain inline mail for
	 * the summary template so the e-mail matches the in-app page. The CTA deep link
	 * is injected here so the caller need not know about the gateway relay.
	 */
	public void notifyWeeklySummary(User user, String title, String body,
			java.util.Map<String, Object> model) {
		if (user == null || !user.isActive()) return;
		String link = "/weekly-summary";
		notifications.save(Notification.builder()
				.userId(user.getId()).type(Notification.Type.DIGEST)
				.title(title).body(body).link(link).build());
		NotificationPreferences prefs = prefsOf(user);
		if (prefs.deliversEmail(eventId(Notification.Type.DIGEST))) {
			model.put("ctaLink", appLink(link));
			mail.sendTemplate(user.getEmail(), SUBJECT_PREFIX + title, "email/weekly-summary", model);
		}
		if (prefs.deliversPush(eventId(Notification.Type.DIGEST))) {
			push.sendToUser(user.getId(), title, body, link);
		}
	}

	/**
	 * Security alert (new sign-in, password / e-mail change, 2FA change). Maps to
	 * the locked {@code security} event, so it always reaches the user on every
	 * channel — in-app, e-mail and push. Title/body are pre-localized by the caller.
	 */
	public void notifySecurityAlert(User user, String title, String body) {
		deliverGated(user, Notification.Type.SECURITY_ALERT, title, body, "/settings");
	}

	private String teamLink(String teamId) {
		return "/teams/" + teamId;
	}

	private String projectLink(String projectId) {
		// The project's landing view is its board (there is no bare /projects/:id route).
		return "/projects/" + projectId + "/boards";
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

	/**
	 * In-app (bell) notice to each admin that a verified self-registration is
	 * waiting for approval. The templated approval e-mail is sent separately by
	 * {@code AuthMailService}, so this only persists the in-app notification (+push).
	 */
	public void notifyAdminsPendingApproval(java.util.Collection<User> admins, User newUser) {
		for (User admin : admins) {
			if (admin == null) continue;
			String title = de(admin) ? "Registrierung wartet auf Freigabe"
					: "Registration awaiting approval";
			String body = (de(admin)
					? "%s (%s) hat sich registriert und benötigt deine Freigabe."
					: "%s (%s) registered and needs your approval.")
					.formatted(newUser.getDisplayName(), newUser.getEmail());
			persist(admin, Notification.Type.SYSTEM, title, body,
					"/admin/users?user=" + newUser.getId());
		}
	}

	/**
	 * In-app (bell) notice + push to each admin that a user reported content or
	 * another user, deep-linking to the report in the moderation queue.
	 *
	 * <p>The body names the reporter and the thing reported, and deliberately carries
	 * neither the reported content nor the reason the reporter picked. A push notice is
	 * the least private surface in the product — it lands on a lock screen — and
	 * accusing someone of, say, harassment there, in front of whoever is looking at the
	 * phone, is a judgement no machine has made yet. The moderator opens the queue and
	 * reads the case; the notification only says there is one.
	 *
	 * <p>Not gated by {@link NotificationPreferences}: it maps to the locked
	 * {@code security} event via {@link #eventId}, because a report an admin can switch
	 * off is a report nobody is accountable for acting on within the time DSA Art. 16(6)
	 * expects.
	 */
	public void notifyAdminsContentReported(java.util.Collection<User> admins, User reporter,
			String targetLabel, boolean urgent, String link) {
		for (User admin : admins) {
			if (admin == null || !admin.isActive()) continue;
			boolean de = de(admin);
			persist(admin, Notification.Type.CONTENT_REPORTED, reportTitle(de, urgent),
					reportBody(de, reporter, targetLabel), link);
		}
	}

	private String reportTitle(boolean de, boolean urgent) {
		if (de) {
			return urgent ? "Dringende Meldung wartet auf Prüfung" : "Neue Meldung wartet auf Prüfung";
		}
		return urgent ? "Urgent report awaiting review" : "New report awaiting review";
	}

	private String reportBody(boolean de, User reporter, String targetLabel) {
		String name = reporter != null && reporter.getDisplayName() != null
				? reporter.getDisplayName()
				: (de ? "Jemand" : "Someone");
		boolean hasTarget = targetLabel != null && !targetLabel.isBlank();
		if (de) {
			return hasTarget
					? name + " hat Inhalte zur Prüfung gemeldet: \"" + targetLabel + "\""
					: name + " hat Inhalte zur Prüfung gemeldet.";
		}
		return hasTarget
				? name + " reported content for review: \"" + targetLabel + "\""
				: name + " reported content for review.";
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
		return appLink("/login");
	}

	/**
	 * Absolute deep link for a mail CTA, routed through Hinata Connect so the
	 * native app intercepts it as a Universal/App Link on any platform (the
	 * server's own {@code webBaseUrl} is never registered as one — self-hosters
	 * can pick any domain). Falls back to a plain web link if the gateway is
	 * unreachable. {@code null} when there's no in-app destination to link to.
	 */
	private String appLink(String link) {
		if (link == null || link.isBlank()) return null;
		return gateway.relayLink(link, null);
	}

	private Set<String> watchersWithout(Issue issue, User exclude) {
		Set<String> recipients = new HashSet<>(issue.getWatcherIds());
		if (issue.getAssigneeIds() != null) recipients.addAll(issue.getAssigneeIds());
		if (issue.getReporterId() != null) recipients.add(issue.getReporterId());
		if (exclude != null) recipients.remove(exclude.getId());
		return recipients;
	}

	/**
	 * Fan-out to a set of recipients, localizing the title/body <em>per recipient</em>
	 * from their persisted {@code User.locale} (watchers of one issue may each read a
	 * different language). {@code title}/{@code body} receive {@code true} for German.
	 *
	 * <p>For a link that only some recipients can follow, use
	 * {@link #deliver(Set, Notification.Type, L10n, L10n, String, String)}.
	 */
	private void deliver(Set<String> userIds, Notification.Type type, L10n title, L10n body,
			String link) {
		deliver(userIds, type, title, body, link, null);
	}

	/**
	 * As {@link #deliver(Set, Notification.Type, L10n, L10n, String)}, for a link into a
	 * project: {@code linkProjectId} names the project whose access decides, per
	 * recipient, whether the link is theirs to follow (see {@link #linkFor}). Pass
	 * {@code null} for a link everyone can reach, such as a personal page.
	 */
	private void deliver(Set<String> userIds, Notification.Type type, L10n title, L10n body,
			String link, String linkProjectId) {
		String eventId = eventId(type);
		for (String userId : userIds) {
			if (userId == null) continue;
			users.findById(userId).filter(User::isActive).ifPresent(user -> {
				boolean de = de(user);
				String t = title.of(de);
				String b = body.of(de);
				String userLink = linkFor(user, link, linkProjectId);
				// The in-app (bell) notification is always recorded; e-mail and push
				// are gated by the recipient's per-event channel preferences.
				notifications.save(Notification.builder()
						.userId(user.getId()).type(type).title(t).body(b).link(userLink).build());
				NotificationPreferences prefs = prefsOf(user);
				// In-app notifications keep the relative route; the e-mail button gets
				// an absolute deep link that the native app intercepts as a
				// Universal/App Link, straight to the issue.
				if (prefs.deliversEmail(eventId)) {
					mail.send(user.getEmail(), SUBJECT_PREFIX + t, t, b, appLink(userLink),
							buttonLabel(de));
				}
				if (prefs.deliversPush(eventId)) {
					push.sendToUser(user.getId(), t, b, userLink);
				}
			});
		}
	}

	/**
	 * The link this recipient can actually follow — {@code null} when they cannot see
	 * the project it points into.
	 *
	 * <p>A watcher does not have to be a project member: an e-mail-ingested ticket is
	 * attributed to the sender's account whether or not they belong to the project, and
	 * anyone can be removed from a project after subscribing to one of its issues. They
	 * should still hear that their own request moved, so the notice goes out either way —
	 * only the CTA is dropped, because the e-mail button, the push deep link and the bell
	 * entry would all land on a 403. Both delivery layers already render nothing for a
	 * null link (no button in the mail, no {@code data.link} in the push), and the app
	 * simply doesn't navigate.
	 */
	private String linkFor(User user, String link, String linkProjectId) {
		if (link == null || linkProjectId == null) return link;
		return reach.canSee(linkProjectId, user) ? link : null;
	}

	/** Produces a string in the recipient's language ({@code true} ⇒ German). */
	@FunctionalInterface
	private interface L10n {
		String of(boolean de);
	}

	/** Localized label for the e-mail call-to-action button. */
	private String buttonLabel(boolean de) {
		return de ? "In Hinata öffnen" : "Open in Hinata";
	}

	/** Recipient's notification preferences, normalised (defaults for legacy users). */
	private NotificationPreferences prefsOf(User user) {
		NotificationPreferences prefs = user.getNotificationPreferences();
		return (prefs == null ? NotificationPreferences.defaults() : prefs).sanitized();
	}

	/**
	 * Maps a notification type to its preference event id (see
	 * {@link NotificationPreferences#EVENTS}). Transactional account/team/system
	 * events map to the locked {@code security} event, so they always deliver.
	 */
	private static String eventId(Notification.Type type) {
		return switch (type) {
			case MENTION, COMMENT_REPLY -> "mentions";
			case ISSUE_ASSIGNED -> "assigned";
			case ISSUE_COMMENTED -> "comments";
			case ISSUE_UPDATED -> "status";
			case ISSUE_INGESTED -> "ingest";
			case ISSUE_DUE_SOON, SPRINT_STARTED, SPRINT_COMPLETED -> "sprint";
			case TEAM_ADDED, PROJECT_ADDED -> "invites";
			case DIGEST -> "digest";
			default -> NotificationPreferences.LOCKED;
		};
	}

	private String issueLink(Issue issue) {
		return "/issues/" + issue.getReadableId();
	}

	/**
	 * Deep link to a specific comment within its issue — the app's router honours
	 * {@code ?comment=<id>} and scrolls to (and highlights) that comment.
	 */
	private String commentLink(Issue issue, String commentId) {
		return (commentId == null || commentId.isBlank())
				? issueLink(issue)
				: issueLink(issue) + "?comment=" + commentId;
	}
}
