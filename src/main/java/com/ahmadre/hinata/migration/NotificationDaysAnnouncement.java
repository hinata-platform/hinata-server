package com.ahmadre.hinata.migration;

import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Tells everybody once, in the bell, that notification days exist (HIN-129).
 *
 * <p>Until now e-mail and push came on every day of the week. From this version
 * they come on the working days where a person lives unless they choose other
 * days, so somebody who counted on a mention reaching them on a Saturday would
 * stop getting it without being told why. One note in the bell, with a link to
 * the setting, says it; no mail and no push, because it is news, not an alarm.
 *
 * <p>Runs once (a marker in {@code migrations}), never fails the start.
 */
@Slf4j
@Component
@Order(42)
@RequiredArgsConstructor
public class NotificationDaysAnnouncement implements ApplicationRunner {

	static final String MARKER_ID = "notification-days-announced";

	/** Where the note leads, and how an earlier, interrupted run's notes are recognised. */
	static final String LINK = "/settings?section=notifications";

	private static final int BATCH = 500;

	private final UserRepository users;
	private final NotificationService notifications;
	private final MigrationMarkers markers;

	@Override
	public void run(ApplicationArguments args) {
		if (markers.done(MARKER_ID)) return;
		try {
			int told = 0;
			// In pages, each written in one go, and skipping whoever already has the note:
			// a start that fails halfway continues on the next one instead of telling the
			// first half twice.
			Pageable page = PageRequest.of(0, BATCH, Sort.by("_id"));
			while (true) {
				List<User> batch = users.findByActiveIsTrue(page).getContent();
				if (batch.isEmpty()) break;
				Set<String> noted = notifications.alreadyNoted(batch.stream().map(User::getId).toList(), LINK);
				List<User> fresh = batch.stream().filter(user -> !noted.contains(user.getId())).toList();
				notifications.noteInBell(fresh, "notify.notificationDays.title", "notify.notificationDays.body", LINK);
				told += fresh.size();
				if (batch.size() < BATCH) break;
				page = page.next();
			}
			markers.markDone(MARKER_ID, new Document("told", told));
		}
		catch (RuntimeException ex) {
			log.warn("NotificationDaysAnnouncement failed; will retry on next start", ex);
		}
	}
}
