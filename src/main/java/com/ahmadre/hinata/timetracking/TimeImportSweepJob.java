package com.ahmadre.hinata.timetracking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Takes back CSV imports whose commit was started and never finished (HIN-93). */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeImportSweepJob {

	private final TimeImportService imports;
	private final TimeTrackingSettings settings;

	@Scheduled(cron = "0 50 * * * *")
	public void sweep() {
		if (!settings.advancedEnabled()) {
			return;
		}
		try {
			int taken = imports.sweep();
			if (taken > 0) {
				log.warn("[time] took back {} interrupted CSV import(s)", taken);
			}
		}
		catch (RuntimeException ex) {
			log.warn("[time] import sweep failed: {}", ex.toString(), ex);
		}
	}
}
