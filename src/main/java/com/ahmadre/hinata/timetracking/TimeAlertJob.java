package com.ahmadre.hinata.timetracking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The schedule of {@link TimeAlerts}; what is measured and when it alerts is there. Hourly, at
 * :35, apart from the timer sweep at :20 and the reminders every quarter hour from :10.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeAlertJob {

	private final TimeAlerts alerts;

	@Scheduled(cron = "0 35 * * * *")
	public void alert() {
		try {
			int sent = alerts.run();
			if (sent > 0) {
				log.info("[time] sent {} budget or estimate alert(s)", sent);
			}
		}
		catch (RuntimeException ex) {
			log.warn("[time] alert run failed: {}", ex.toString(), ex);
		}
	}
}
