package com.ahmadre.hinata.timetracking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads every subscribed calendar every half hour (HIN-94).
 *
 * <p>Both switches are read at the start of each run, the module's and the organisation's
 * {@code icsImportEnabled}: with either off the run does nothing. It walks the enabled
 * subscriptions in batches of {@value #BATCH} by {@code _id} and hands each one that is due to
 * {@link CalendarSync}, which claims it, so a second instance running the same minute skips what
 * this one took. Subscriptions switched off, and ones paused after five failures in a row, are not
 * fetched until their owner turns them back on.
 *
 * <p>The walk runs on a thread of its own, never on the scheduler's, because it waits for places in
 * the read queue; it stops when its {@link #RUN_BUDGET} is spent, and what it did not reach is due
 * again in half an hour.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalendarFetchJob implements DisposableBean {

	static final int BATCH = 500;

	static final Duration RUN_BUDGET = Duration.ofMinutes(4);

	/** Fetched within this much of the last fetch is not due yet; less than the interval, so cron jitter never skips one. */
	static final Duration MIN_AGE = Duration.ofMinutes(25);

	private final MongoTemplate mongo;
	private final CalendarSync sync;
	private final TimeTrackingSettings settings;
	private final Clock clock;

	private final ExecutorService walker = Executors.newSingleThreadExecutor(
			Thread.ofPlatform().name("calendar-fetch-run").daemon().factory());
	private final AtomicBoolean running = new AtomicBoolean();

	@Scheduled(cron = "0 0/30 * * * *")
	public void fetch() {
		if (!settings.advancedEnabled() || !settings.icsImportEnabled() || !running.compareAndSet(false, true)) {
			return;
		}
		try {
			walker.execute(() -> {
				try {
					int started = run();
					if (started > 0) {
						log.info("[time] started reading {} calendar subscription(s)", started);
					}
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
				catch (RuntimeException ex) {
					log.warn("[time] calendar fetch run failed: {}", ex.toString());
				}
				finally {
					running.set(false);
				}
			});
		}
		catch (RejectedExecutionException shuttingDown) {
			running.set(false);
		}
	}

	/** One run, on the calling thread; answers how many reads it started. */
	int run() throws InterruptedException {
		if (!settings.advancedEnabled() || !settings.icsImportEnabled()) {
			return 0;
		}
		Instant deadline = clock.instant().plus(RUN_BUDGET);
		Instant fetchedBefore = clock.instant().minus(MIN_AGE);
		int started = 0;
		ObjectId after = null;
		while (true) {
			Criteria due = Criteria.where("enabled").is(true)
					.and("failures").lt(CalendarSubscription.PAUSE_AFTER_FAILURES)
					.orOperator(Criteria.where("lastFetchedAt").is(null),
							Criteria.where("lastFetchedAt").lt(fetchedBefore));
			if (after != null) {
				due = new Criteria().andOperator(due, Criteria.where("_id").gt(after));
			}
			Query page = Query.query(due).with(Sort.by("_id")).limit(BATCH);
			page.fields().include("_id");
			List<CalendarSubscription> batch = mongo.find(page, CalendarSubscription.class);
			for (CalendarSubscription subscription : batch) {
				Duration left = Duration.between(clock.instant(), deadline);
				if (left.isNegative() || left.isZero()) {
					return started;
				}
				if (sync.startWaiting(subscription.getId(), MIN_AGE, left)) {
					started++;
				}
			}
			if (batch.size() < BATCH) {
				return started;
			}
			after = new ObjectId(batch.getLast().getId());
		}
	}

	@Override
	public void destroy() {
		walker.shutdownNow();
	}
}
