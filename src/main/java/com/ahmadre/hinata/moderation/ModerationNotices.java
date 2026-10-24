package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.notification.PersonalNotice;
import com.ahmadre.hinata.setup.OperatorContact;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What moderation tells moderators and admins (HIN-170), and in which words. None of it carries
 * content, a category or a name: a push is read on a lock screen, and a bell entry outlives the case.
 */
@Component
@RequiredArgsConstructor
public class ModerationNotices {

	/** One ping per person and type within this window; the bell records every notice. */
	static final Duration QUIET = Duration.ofMinutes(15);

	private final NotificationService notifications;
	private final CaseAccess access;
	private final UserRepository users;
	private final UserWords words;
	private final OperatorContact contact;
	private final SettingsService settings;

	static String caseLink(ModerationCase moderationCase) {
		return "/moderation/cases/" + moderationCase.getId();
	}

	/** A new case, or one that just became urgent: to whoever may see it. */
	void caseOpened(ModerationCase moderationCase) {
		String key = moderationCase.isUrgent() ? "moderation.notice.urgent" : "moderation.notice.opened";
		notifications.notifyPeople(Notification.Type.MODERATION_CASE_OPENED,
				access.eligibleModerators(moderationCase, CaseAccess.Purpose.SEE), caseLink(moderationCase),
				person -> notice(person, key), QUIET);
	}

	/** One reminder per person and run, with how many of their cases wait. */
	void overdue(Map<User, Integer> waiting) {
		waiting.forEach((person, count) -> notifications.notifyPeople(Notification.Type.MODERATION_CASE_OVERDUE,
				List.of(person), "/moderation", reader -> notice(reader, "moderation.notice.overdue", count), null));
	}

	/** A second pair of eyes asked for: to whoever may give it. */
	void confirmRequested(ModerationCase moderationCase) {
		notifications.notifyPeople(Notification.Type.MODERATION_CONFIRM_REQUESTED,
				access.eligibleModerators(moderationCase, CaseAccess.Purpose.SECOND_PERSON), caseLink(moderationCase),
				person -> notice(person, "moderation.notice.confirm"), QUIET);
	}

	/** A note to everybody who moderates, about [moderationCase] or the page when null. */
	void teamNote(ModerationCase moderationCase, String key) {
		notifications.notifyPeople(Notification.Type.MODERATION_TEAM_NOTE, users.findByRolesContainingAndActiveIsTrue(
						Role.MODERATOR),
				moderationCase == null ? "/moderation" : caseLink(moderationCase), person -> notice(person, key), null);
	}

	/** A word to the admins about one case; see {@link #adminAlert(Collection, String)}. */
	void adminAlert(ModerationCase moderationCase, String key) {
		adminAlert(List.of(moderationCase), key);
	}

	/**
	 * One word to the admins, without content, so they can staff moderation: to every admin who
	 * wrote nothing of [cases]. With no admin left, the instance's contact gets it by mail.
	 */
	void adminAlert(Collection<ModerationCase> cases, String key) {
		Map<String, User> admins = new LinkedHashMap<>();
		for (ModerationCase moderationCase : cases) {
			access.eligibleAdmins(moderationCase).forEach(admin -> admins.putIfAbsent(admin.getId(), admin));
		}
		notifyAdmins(admins.values(), key);
	}

	private void notifyAdmins(Collection<User> admins, String key) {
		if (admins.isEmpty()) {
			String address = contact.current().contactEmail();
			if (address != null) {
				notifications.mailAddress(address, settings.instanceLocale(), key + ".title", key + ".body");
			}
			return;
		}
		notifications.notifyPeople(Notification.Type.MODERATION_ADMIN_ALERT, admins, "/admin/users",
				person -> notice(person, key), QUIET);
	}

	private PersonalNotice notice(User person, String key, Object... args) {
		String push = words.of(person, "moderation.notice.push");
		return new PersonalNotice(words.of(person, key + ".title", args), words.of(person, key + ".body", args), push,
				words.of(person, "moderation.notice.pushBody"), null);
	}
}
