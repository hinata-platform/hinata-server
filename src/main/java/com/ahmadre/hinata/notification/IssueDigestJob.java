package com.ahmadre.hinata.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Flushes the bundled change mails for watched issues, once a minute.
 *
 * <p>A minute is the granularity the windows are worth: the quiet window is
 * measured in minutes, so checking more often would only add load, and checking
 * less often would blur the ceiling. Mirrors {@link DueDateReminderJob} — a thin
 * scheduling shell over a service that owns all the deciding, so the timing
 * rules stay testable without a scheduler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IssueDigestJob {

	/**
	 * Ceiling on one sweep. An instance that was down over a weekend must not
	 * pull its entire backlog into memory in a single pass; whatever is left is
	 * picked up sixty seconds later.
	 */
	private static final int BATCH = 500;

	private final IssueDigestService digests;

	@Scheduled(cron = "0 * * * * *")
	public void flush() {
		try {
			int sent = digests.sweep(BATCH);
			if (sent > 0) {
				log.info("Sent {} bundled issue-change mail(s)", sent);
			}
		}
		catch (RuntimeException ex) {
			// A scheduled method that throws is logged by Spring and simply not
			// retried; say why here so the next sweep is not a mystery — with the
			// exception itself, because a bare getMessage() on an NPE logs "null".
			log.warn("Issue change digest sweep failed", ex);
		}
	}
}
