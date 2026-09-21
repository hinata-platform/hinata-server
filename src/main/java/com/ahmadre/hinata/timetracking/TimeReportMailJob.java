package com.ahmadre.hinata.timetracking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The hour of scheduled report mails (HIN-93); see {@link TimeReportMails}. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeReportMailJob {

	private final TimeReportMails mails;
	private final TimeTrackingSettings settings;

	@Scheduled(cron = "0 5 * * * *")
	public void send() {
		if (!settings.advancedEnabled()) {
			return;
		}
		try {
			int sent = mails.run();
			if (sent > 0) {
				log.debug("[time] sent {} scheduled report(s)", sent);
			}
		}
		catch (RuntimeException ex) {
			log.warn("[time] scheduled reports failed: {}", ex.toString(), ex);
		}
	}
}
