package com.ahmadre.hinata.shifts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pulls every open series along once a night at 03:40 (HIN-161), so occurrences keep existing up
 * to 90 days ahead. The work and its guarantees are {@link ShiftSeriesService#nightly}'s: with the
 * module off nothing happens, a series of a deleted project is marked finished, and a run after a
 * long pause starts at today.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftSeriesJob {

	private final ShiftSeriesService series;

	@Scheduled(cron = "0 40 3 * * *")
	public void nightly() {
		try {
			int created = series.nightly();
			if (created > 0) {
				log.info("[shifts] series run created {} occurrence(s)", created);
			}
		}
		catch (RuntimeException failure) {
			log.warn("[shifts] series run failed: {}", failure.toString(), failure);
		}
	}
}
