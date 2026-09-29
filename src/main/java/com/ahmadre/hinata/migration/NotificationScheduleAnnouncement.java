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
 * Tells everybody once, in the bell, that notification times exist (HIN-131).
 *
 * <p>E-mail and push now follow a schedule: always, or on chosen days between two times,
 * and without a choice of one's own the organisation's default — office hours on working
 * days where the organisation counts in working days. Somebody whose evening mails now
 * arrive the next morning in one summary should know why. One note in the bell, with a
 * link to the setting; no mail and no push.
 *
 * <p>Runs once (a marker in {@code migrations}), never fails the start. The link differs
 * from the notification-days note's by its anchor, so the two are told apart.
 */
@Slf4j
@Component
@Order(43)
@RequiredArgsConstructor
public class NotificationScheduleAnnouncement implements ApplicationRunner {

	static final String MARKER_ID = "notification-schedule-announced";

	/** Where the note leads, and how an earlier, interrupted run's notes are recognised. */
	static final String LINK = "/settings?section=notifications&focus=schedule";

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
				notifications.noteInBell(fresh, "notify.notificationSchedule.title", "notify.notificationSchedule.body", LINK);
				told += fresh.size();
				if (batch.size() < BATCH) break;
				page = page.next();
			}
			markers.markDone(MARKER_ID, new Document("told", told));
		}
		catch (RuntimeException ex) {
			log.warn("NotificationScheduleAnnouncement failed; will retry on next start", ex);
		}
	}
}
