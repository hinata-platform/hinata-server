package com.ahmadre.hinata.timetracking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ends timers that have run for a full day.
 *
 * <p>Separate from {@link TimerService} so that the schedule is visible on its
 * own and the service stays callable — the sweep is exercised in tests by
 * calling {@link TimerService#stopExpired()} with a moved clock, not by waiting
 * an hour.
 *
 * <p>The flag is read at the start of every run rather than at startup. An
 * administrator switching the module off expects it to stop doing things, and a
 * job wired by {@code @ConditionalOnProperty} would keep running until the next
 * restart; one wired by nothing at all would never start when the module is
 * switched on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimerSweepJob {

	private final TimerService timers;
	private final TimeTrackingSettings settings;

	/**
	 * Hourly, offset from the top of the hour so it does not contend with the
	 * digest and reminder jobs that run there.
	 */
	@Scheduled(cron = "0 20 * * * *")
	public void sweep() {
		if (!settings.advancedEnabled()) {
			return;
		}
		try {
			int stopped = timers.stopExpired();
			if (stopped > 0) {
				log.info("[time] auto-stopped {} timer(s) that had run for 24h", stopped);
			}
		}
		catch (RuntimeException ex) {
			// A scheduled method that throws is silently unscheduled by some
			// pools and noisily retried by others; neither is a good way to find
			// out that timers stopped being swept.
			log.warn("[time] timer sweep failed: {}", ex.toString(), ex);
		}
	}
}
