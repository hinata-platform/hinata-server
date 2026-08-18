package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.project.ProjectReach;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
	private final IssueChangeRenderer changeRenderer;
	// Where the watchers' change mails go instead of the mail server; the bell and
	// the push still fire from here, immediately.
	private final IssueDigestService digests;

	static final String SUBJECT_PREFIX = "[Hinata] ";

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

	/**
	 * The one notice an update sends: everything that changed, in a single
	 * message, to everyone with a stake in the issue.
	 *
	 * <p>One notification per update operation, not one per field — a user who
	 * re-prioritises an issue and moves it to another sprint in the same save
	 * causes one interruption, not two. And the interruption says what happened:
	 * the previous behaviour let an assignment silently swallow a state change and
	 * ignored every other field entirely.
	 *
	 * <p>{@code alreadyNotified} names the recipients this same update has already
	 * reached with a more specific notice (an assignment, a mention). They are
	 * skipped rather than told twice, exactly as {@code notifyComment} treats a
	 * mention as superseding the watcher notice.
	 */
	public void notifyUpdated(Issue issue, List<FieldChange> changes, User actor,
			Set<String> alreadyNotified) {
		List<FieldChange> collapsed = FieldChange.collapse(changes);
		if (collapsed.isEmpty()) return;
		Audience audience = audienceFor(issue, actor);
		Set<String> watchers = new HashSet<>(audience.watchers());
		Set<String> stakeholders = new HashSet<>(audience.stakeholders());
		if (alreadyNotified != null) {
			watchers.removeAll(alreadyNotified);
			stakeholders.removeAll(alreadyNotified);
		}
		Set<String> recipients = new HashSet<>(watchers);
		recipients.addAll(stakeholders);
		if (recipients.isEmpty()) return;
		// Rendered twice for the whole fan-out, not once per recipient. Resolving a
		// change list costs a point read per value — a display name, a sprint name,
		// a parent's key — and there are only ever two distinct results, one per
		// language, however many watchers an issue has.
		String summaryDe = changeRenderer.summary(collapsed, true);
		String summaryEn = changeRenderer.summary(collapsed, false);
		deliver(recipients, Notification.Type.ISSUE_UPDATED,
				de -> de ? issue.getReadableId() + " aktualisiert"
						: issue.getReadableId() + " updated",
				de -> de ? summaryDe : summaryEn,
				issueLink(issue), issue.getProjectId(),
				new Routing(
						// A watcher subscribed themselves and switches that off under
						// "watching"; an assignee or the reporter is on the issue by
						// assignment, which is the "status" setting. Same notification,
						// two different reasons to receive it — so two different toggles.
						userId -> watchers.contains(userId)
								? NotificationPreferences.WATCHING
								: eventId(Notification.Type.ISSUE_UPDATED),
						// Only the watcher stream is bundled. An assignee's mail keeps
						// arriving at the moment of the change, as it always has.
						recipient -> watchers.contains(recipient.getId())
								&& digests.queue(issue, recipient, collapsed)));
	}

	/** As {@link #notifyUpdated(Issue, List, User, Set)} with nobody pre-notified. */
	public void notifyUpdated(Issue issue, List<FieldChange> changes, User actor) {
		notifyUpdated(issue, changes, actor, Set.of());
	}

	/**
	 * Fan-out for a new comment. Users named with an {@code @}-mention get a
	 * direct {@code MENTION} notification; the author of the comment a reply
	 * answers gets a {@code COMMENT_REPLY} notification; the issue's watchers get
	 * the broader {@code ISSUE_COMMENTED} notice. Each stronger notice supersedes
	 * the weaker one for the same recipient (mention &gt; reply &gt; watcher), so
	 * nobody is pinged twice. The comment author never notifies themselves.
	 */
	public void notifyComment(Issue issue, User author, IssueComment comment) {
		String doc = comment == null ? null : comment.getTextDoc();
		String preview = preview(doc);
		Set<String> mentioned = new HashSet<>(richText.mentionedUsers(doc));
		mentioned.remove(author.getId());
		notifyMentions(issue, author, mentioned, preview);
		// Everyone already directly notified about this comment — start with the
		// mentioned users, then add the reply target so the watcher notice below
		// skips them too.
		Set<String> notified = new HashSet<>(mentioned);
		notifyReply(issue, author, comment, preview, notified);
		Set<String> watchers = watchersWithout(issue, author);
		watchers.removeAll(notified);
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
			Set<String> notified) {
		if (comment == null) return;
		String recipient = comment.getReplyToAuthorId();
		if (recipient == null || recipient.isBlank()) return; // top-level comment
		if (recipient.equals(actor.getId())) return; // replying to oneself
		if (!notified.add(recipient)) return; // already mentioned — don't double-ping
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
		Set<String> recipients = new HashSet<>(mentionedIds);
		recipients.remove(actor.getId());
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
	 *
	 * @return the ids that were pinged, so the caller can keep the broader change
	 *         notice from reaching the same people twice for one save
	 */
	public Set<String> notifyNewMentions(Issue issue, User actor, String before, String after) {
		Set<String> added = new HashSet<>(richText.mentionedUsers(after));
		added.removeAll(richText.mentionedUsers(before));
		if (added.isEmpty()) return Set.of();
		notifyMentions(issue, actor, added, preview(after));
		return added;
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
		mail.sendNotification(user.getEmail(), SUBJECT_PREFIX + title, title, body, appLink(link),
				buttonLabel(de(user)), localeOf(user), eyebrowKey(type));
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
			mail.sendNotification(user.getEmail(), SUBJECT_PREFIX + title, title, body, appLink(link),
					buttonLabel(de), localeOf(user), eyebrowKey(type));
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

	private String localeOf(User user) {
		return de(user) ? "de" : "en";
	}

	/**
	 * The message key for the notification's eyebrow label, so the mail can say
	 * "Assigned to you" or "You were mentioned" above the headline. Falls back to
	 * the neutral label in the template if the key is missing from the bundle.
	 */
	private String eyebrowKey(Notification.Type type) {
		return type != null ? "email.eyebrow." + type.name() : null;
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

	/**
	 * Who hears about a change to an issue, split by <em>why</em>.
	 *
	 * <p>{@code stakeholders} are the assignees and the reporter — people the
	 * issue was handed to or who raised it. {@code watchers} are the people who
	 * subscribed themselves. The split matters twice over: it decides which
	 * preference toggle gates each recipient, and only the watcher half is
	 * filtered for access.
	 */
	private record Audience(Set<String> watchers, Set<String> stakeholders) {
	}

	/**
	 * Splits the issue's audience and — for the subscribed half only — drops
	 * anyone who can no longer see the project.
	 *
	 * <p>Watching grants nothing, so a watcher who loses project access must stop
	 * hearing about the issue; this is the check that guarantees it, because a
	 * subscription can outlive the access that allowed it (a removed member, a
	 * revoked team grant) and the cleanup that prunes those lists is best-effort
	 * hygiene, not a promise.
	 *
	 * <p>Assignees and the reporter stay unfiltered on purpose (see
	 * {@link #linkFor}): an e-mail-ingested ticket is attributed to its sender's
	 * account whether or not they belong to the project, and they must still hear
	 * that their own request moved. They get the notice without a working link,
	 * never silence.
	 *
	 * <p>The filter is one bulk question about one project rather than a
	 * {@code canSee} per watcher — a hundred-watcher issue would otherwise re-read
	 * the project and re-query the teams a hundred times per event.
	 */
	private Audience audienceFor(Issue issue, User exclude) {
		Set<String> stakeholders = new HashSet<>();
		if (issue.getAssigneeIds() != null) stakeholders.addAll(issue.getAssigneeIds());
		if (issue.getReporterId() != null) stakeholders.add(issue.getReporterId());
		Set<String> watchers = new HashSet<>(
				issue.getWatcherIds() != null ? issue.getWatcherIds() : Set.of());
		// Someone who is both counts as a stakeholder: the stronger relationship
		// wins, so an assignee who also subscribed keeps the immediate mail their
		// assignment earns them rather than being quietly moved into the digest.
		watchers.removeAll(stakeholders);
		if (!watchers.isEmpty()) {
			watchers = new HashSet<>(reach.whoCanSee(issue.getProjectId(), watchers));
		}
		if (exclude != null) {
			stakeholders.remove(exclude.getId());
			watchers.remove(exclude.getId());
		}
		return new Audience(watchers, stakeholders);
	}

	/** Everyone {@link #audienceFor} found, for the notices that treat them alike. */
	private Set<String> watchersWithout(Issue issue, User exclude) {
		Audience audience = audienceFor(issue, exclude);
		Set<String> recipients = new HashSet<>(audience.stakeholders());
		recipients.addAll(audience.watchers());
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
		deliver(userIds, type, title, body, link, linkProjectId, Routing.of(type));
	}

	/**
	 * As above, with the two per-recipient decisions the plain fan-out cannot
	 * make: which preference event gates this particular recipient, and whether
	 * their e-mail is bundled instead of sent now (see {@link Routing}).
	 */
	private void deliver(Set<String> userIds, Notification.Type type, L10n title, L10n body,
			String link, String linkProjectId, Routing routing) {
		// Who may follow the link, asked once for the whole set. The per-recipient
		// question costs a project read and, for anyone who is not a direct member,
		// a team query — so on a busy issue it re-asks the very thing the bulk
		// primitive exists to answer, once per watcher, on every single edit.
		Set<String> canFollowLink = (link == null || linkProjectId == null)
				? Set.of()
				: reach.whoCanSee(linkProjectId, userIds);
		for (String userId : userIds) {
			if (userId == null) continue;
			users.findById(userId).filter(User::isActive).ifPresent(user -> {
				boolean de = de(user);
				String t = title.of(de);
				String b = body.of(de);
				String userLink = linkFor(user, link, linkProjectId, canFollowLink);
				String eventId = routing.eventFor().apply(user.getId());
				// The in-app (bell) notification is always recorded; e-mail and push
				// are gated by the recipient's per-event channel preferences.
				notifications.save(Notification.builder()
						.userId(user.getId()).type(type).title(t).body(b).link(userLink).build());
				NotificationPreferences prefs = prefsOf(user);
				// In-app notifications keep the relative route; the e-mail button gets
				// an absolute deep link that the native app intercepts as a
				// Universal/App Link, straight to the issue.
				if (prefs.deliversEmail(eventId) && !routing.emailSink().takeOver(user)) {
					mail.sendNotification(user.getEmail(), SUBJECT_PREFIX + t, t, b, appLink(userLink),
							buttonLabel(de), localeOf(user), eyebrowKey(type));
				}
				if (prefs.deliversPush(eventId)) {
					push.sendToUser(user.getId(), t, b, userLink);
				}
			});
		}
	}

	/**
	 * Per-recipient delivery routing for one fan-out.
	 *
	 * <p>{@code eventId} answers which preference toggle governs this recipient —
	 * the same notification can reach two people for two different reasons (a
	 * watcher and an assignee), and honesty demands each be switchable by the
	 * reason they actually have. {@code emailSink} answers where their e-mail goes
	 * instead of the mail server, which keeps the bundling decision here rather
	 * than duplicating the whole fan-out for one type.
	 */
	private record Routing(Function<String, String> eventFor, EmailSink emailSink) {

		/** The ordinary case: one event for everyone, every mail sent at once. */
		static Routing of(Notification.Type type) {
			String fixed = NotificationService.eventId(type);
			return new Routing(userId -> fixed, EmailSink.NONE);
		}
	}

	/**
	 * Somewhere a recipient's e-mail can be handed to instead of being sent now.
	 *
	 * <p>Deliberately <em>not</em> a {@code Predicate}: taking a mail over
	 * <strong>writes to the database</strong> — it upserts the change into that
	 * recipient's open digest bundle. {@code Predicate.test} is side-effect free
	 * by universal convention, so as one it read like a question and answered like
	 * a command: reordering the {@code &&} for readability, logging the answer, or
	 * wrapping the fan-out in a dry run would each have doubled or invented queue
	 * writes without looking like a behaviour change at all.
	 */
	@FunctionalInterface
	private interface EmailSink {

		/** Nobody takes anything over — every mail is sent at the moment of the change. */
		EmailSink NONE = recipient -> false;

		/**
		 * Offers this recipient's mail to the sink. <strong>Writes.</strong> Call it
		 * exactly once per recipient, and only where the mail would otherwise be
		 * sent.
		 *
		 * @return {@code true} when the mail has been persisted to the digest queue
		 *         and must therefore not be sent now
		 */
		boolean takeOver(User recipient);
	}

	/**
	 * The link this recipient can actually follow — {@code null} when they cannot see
	 * the project it points into. {@code canFollowLink} is that answer for the whole
	 * fan-out, resolved once by {@link #deliver}, so this is a set lookup.
	 *
	 * <p>Who reaches here <em>without</em> access is a short and deliberate list:
	 * the assignees and the reporter. An e-mail-ingested ticket is attributed to
	 * the sender's account whether or not they belong to the project, and they must
	 * still hear that their own request moved — so the notice goes out either way
	 * and only the CTA is dropped, because the e-mail button, the push deep link
	 * and the bell entry would all land on a 403. Both delivery layers already
	 * render nothing for a null link (no button in the mail, no {@code data.link}
	 * in the push), and the app simply doesn't navigate.
	 *
	 * <p>Watchers are <em>not</em> on that list, and this method is not what stops
	 * them: {@link #audienceFor} removes every watcher who cannot see the project
	 * before delivery begins. A watcher arriving here therefore always has access.
	 * That split is load-bearing — dropping the filter upstream on the assumption
	 * that this one covers it would turn a missing CTA into a leaked issue title.
	 */
	private String linkFor(User user, String link, String linkProjectId, Set<String> canFollowLink) {
		if (link == null || linkProjectId == null) return link;
		return canFollowLink.contains(user.getId()) ? link : null;
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
	 *
	 * <p>This is the default per type. {@code ISSUE_UPDATED} is the one type whose
	 * event depends on the recipient rather than only on the type — a watcher's
	 * copy is gated by {@code watching}, an assignee's or the reporter's by
	 * {@code status} — which {@link #notifyUpdated} decides through its
	 * {@link Routing}. Every type must appear here or fall into {@code default},
	 * where it becomes locked-on and the user can never switch it off.
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
