package com.ahmadre.hinata.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends what waited for people's notification windows (HIN-131), every five minutes.
 *
 * <p>Five minutes is how late a window's first mail may be: nobody sets their office hours
 * to the minute and expects the night's summary on the dot. A thin scheduling shell over
 * {@link HeldNotifications}, like {@link IssueDigestJob}, so the deciding stays testable
 * without a scheduler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeldNotificationJob {

	/** People per sweep; the rest follow five minutes later. */
	private static final int BATCH = 500;

	private final HeldNotifications held;

	@Scheduled(cron = "0 */5 * * * *")
	public void release() {
		try {
			int sent = held.sweep(BATCH);
			if (sent > 0) {
				log.info("Sent held notifications to {} people", sent);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Held notification sweep failed", ex);
		}
	}
}
