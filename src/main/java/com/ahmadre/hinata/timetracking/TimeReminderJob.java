package com.ahmadre.hinata.timetracking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The schedule of {@link TimeReminders}; the rules, the claim and the budget are there.
 *
 * <p>Every quarter hour rather than hourly: a reminder set for 17:30 should not arrive at 18:05.
 * A run that finds nobody due costs one index read per 500 people with a target. Offset from
 * the timer sweep at :20 and the alert scan at :35.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeReminderJob {

	private final TimeReminders reminders;

	@Scheduled(cron = "0 10/15 * * * *")
	public void remind() {
		try {
			int sent = reminders.run();
			if (sent > 0) {
				log.info("[time] sent {} target reminder(s)", sent);
			}
		}
		catch (RuntimeException ex) {
			// A scheduled method that throws is silently unscheduled by some pools and noisily
			// retried by others; neither is how to find out that reminders stopped.
			log.warn("[time] target reminder run failed: {}", ex.toString(), ex);
		}
	}
}
