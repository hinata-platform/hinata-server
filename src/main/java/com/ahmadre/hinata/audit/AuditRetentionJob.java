package com.ahmadre.hinata.audit;

import com.ahmadre.hinata.setup.SettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Nightly sweep that drops audit records older than the configured retention
 * window ({@code audit.retentionDays}). A value of {@code 0} keeps records
 * forever. Scheduling is enabled application-wide on the main class.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRetentionJob {

	/**
	 * Records the sweep never deletes, however old. A notice that leave is about to lapse is the
	 * evidence the lapse rests on, and the limitation period of the claim it concerns starts only
	 * with that notice (§§ 195, 199 BGB; BAG 20.12.2022 – 9 AZR 266/20): a sweep taking it after a
	 * year would take the employer's only proof while the claim is still open.
	 */
	static final java.util.Set<AuditAction> KEPT = java.util.EnumSet.of(AuditAction.TIME_OFF_EXPIRY_NOTICE_SENT);

	private final AuditLogRepository repository;
	private final SettingsService settings;

	/** Runs daily at 03:30 server time. */
	@Scheduled(cron = "0 30 3 * * *")
	public void purgeExpired() {
		int days = settings.get().getAudit().getRetentionDays();
		if (days <= 0) {
			return; // keep forever
		}
		Instant cutoff = Instant.now().minus(Duration.ofDays(days));
		long removed = repository.deleteByTimestampBeforeAndActionNotIn(cutoff, KEPT);
		if (removed > 0) {
			log.info("[audit] retention sweep removed {} record(s) older than {} day(s)", removed, days);
		}
	}
}
