package com.ahmadre.hinata.timeoff;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The schedule of absence management's three nightly jobs (HIN-119). The rules are in the classes
 * they call — {@link TimeOffYearRun}, {@link TimeOffNotices}, {@link TimeOffRetention} — so tests
 * exercise them by calling the services rather than by waiting; this is only when they run.
 *
 * <p>Each catches what it throws: a scheduled method that throws is silently unscheduled by some
 * pools, and that is not how anybody should find out leave stopped being carried over.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TimeOffJobs {

	private final TimeOffYearRun yearRun;
	private final TimeOffNotices notices;
	private final TimeOffRetention retention;

	/** 02:20 server time: the turn of the year before anybody's morning. */
	@Scheduled(cron = "0 20 2 * * *")
	void turnYear() {
		try {
			yearRun.run().ifPresent(run -> log.info(
					"[timeoff] yearly run {}: {} accrual(s), {} carried, {} lapsed, {} held, {} proposal(s)",
					run.getId(), run.getAccrued(), run.getCarried(), run.getExpired(), run.getHeld(),
					run.getProposals()));
		}
		catch (RuntimeException ex) {
			log.warn("[timeoff] yearly run failed: {}", ex.toString(), ex);
		}
	}

	/** 08:00 server time: notices arrive during the working day, not at night. */
	@Scheduled(cron = "0 0 8 * * *")
	void sendNotices() {
		try {
			int sent = notices.run();
			if (sent > 0) {
				log.info("[timeoff] {} expiry notice(s) sent", sent);
			}
		}
		catch (RuntimeException ex) {
			log.warn("[timeoff] expiry notices failed: {}", ex.toString(), ex);
		}
	}

	/** 03:50 server time: after the audit and time retention sweeps, never on top of them. */
	@Scheduled(cron = "0 50 3 * * *")
	void sweep() {
		try {
			retention.run().ifPresent(run -> log.info(
					"[timeoff] retention run {}: {} sick detail(s) coarsened, {} request(s), {} journal row(s) removed",
					run.getId(), run.getSickCoarsened(), run.getRequestsRemoved(), run.getLedgerRemoved()));
		}
		catch (RuntimeException ex) {
			log.warn("[timeoff] retention run failed: {}", ex.toString(), ex);
		}
	}
}
