package com.ahmadre.hinata.timetracking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Applies the retention policy once a night. The rules, the claim and the budget
 * are {@link TimeRetentionService}'s; this is only the schedule, kept separate so
 * the sweep is exercised in tests by calling the service rather than by waiting.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeRetentionJob {

	private final TimeRetentionService retention;

	/** 03:45 server time — after the audit retention sweep at 03:30, never on top of it. */
	@Scheduled(cron = "0 45 3 * * *")
	public void sweep() {
		try {
			retention.run().ifPresent(run -> log.info(
					"[time] retention run {}: {} description(s) cleared, {} entr(y/ies) deleted, "
							+ "{} kept inside approvals, complete={}",
					run.getId(), run.getDescriptionsCleared(), run.getEntriesDeleted(),
					run.getEntriesKept(), run.isComplete()));
		}
		catch (RuntimeException ex) {
			// A scheduled method that throws is silently unscheduled by some pools
			// and noisily retried by others; neither is how to find out that the
			// storage limitation stopped being applied.
			log.warn("[time] retention run failed: {}", ex.toString(), ex);
		}
	}
}
