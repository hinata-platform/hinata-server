package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.ics.IcsCalendar;
import com.ahmadre.hinata.ics.IcsEvent;
import com.ahmadre.hinata.ics.IcsFetchError;
import com.ahmadre.hinata.ics.IcsFetchResult;
import com.ahmadre.hinata.ics.IcsFetcher;
import com.ahmadre.hinata.ics.IcsParseException;
import com.ahmadre.hinata.ics.IcsParser;
import com.ahmadre.hinata.ics.IcsUrlCipher;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Fetches a subscribed calendar, replaces its window of events and applies its takeover rule
 * (HIN-94).
 *
 * <p>The same path serves the scheduled run and a refresh somebody asked for. A subscription is
 * claimed first ({@link #claim}), so two instances, or a run and a tap, never read the same feed
 * at once; the claim goes stale after {@link #CLAIM_STALE}, should the instance holding it die.
 * The feed goes through {@link IcsFetcher} with its SSRF rules and is read on this class's own
 * executor, never on the fetcher's threads or the scheduler's.
 *
 * <p><b>The window</b> is the person's today minus {@value #DAYS_BACK} days to plus
 * {@value #DAYS_AHEAD}. A refresh writes only what changed: occurrences are compared by a digest,
 * upserted and removed in batches of {@value #BATCH}. Reading the same feed twice therefore leaves
 * the same documents, ids included.
 *
 * <p><b>The rule</b> takes over only occurrences that have ended and began after it was switched
 * on, never all-day ones, cancelled ones or ones marked free, and each occurrence once: an entry
 * deleted afterwards is not filed again. Every takeover goes through
 * {@link TimeTrackingService#createFromCalendar}, so required fields, freezes and approvals apply;
 * a refusal is kept on the subscription for its owner to see and the occurrence is left alone.
 *
 * <p>Nothing here writes the address, the feed or an event's words to a log.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalendarSync implements DisposableBean {

	/** What a stored address is bound to, followed by the subscription's id. */
	static final String BINDING = "calendar-subscription:";

	static final Duration CLAIM_STALE = Duration.ofMinutes(5);

	static final int DAYS_BACK = 30;
	static final int DAYS_AHEAD = 90;

	static final int BATCH = 500;

	/** Takeovers one fetch makes at most; the rest follow with the next one. */
	static final int TAKEOVERS_PER_FETCH = 200;

	/** How long a read waits for the fetcher: its own ten seconds and a queue's worth of others. */
	private static final Duration FETCH_WAIT = Duration.ofSeconds(90);

	private static final int THREADS = 2;
	private static final int QUEUE = 16;

	private final MongoTemplate mongo;
	private final IcsFetcher fetcher;
	private final IcsUrlCipher cipher;
	private final UserRepository users;
	private final TimeTrackingService timeTracking;
	private final Clock clock;

	private final ExecutorService reads = pool();

	/** Fetches admitted from the claim to the outcome; never more than the queue holds. */
	private final Semaphore admitted = new Semaphore(QUEUE);

	/**
	 * Claims [subscriptionId] and starts reading it. False when it is held already, or when every
	 * place in the queue is taken; nothing was changed then.
	 *
	 * @param due when true, only a subscription that is enabled, not paused and not fetched in the
	 *            last [minAge] is claimed: the scheduled run's question
	 */
	boolean start(String subscriptionId, boolean due, Duration minAge) {
		if (!admitted.tryAcquire()) {
			return false;
		}
		return startAdmitted(subscriptionId, due, minAge);
	}

	/**
	 * {@link #start} for the scheduled run, which waits up to [wait] for a place in the queue
	 * rather than giving up, on a thread of its own. False when no place came up in time.
	 */
	boolean startWaiting(String subscriptionId, Duration minAge, Duration wait) throws InterruptedException {
		if (!admitted.tryAcquire(Math.max(0, wait.toMillis()), TimeUnit.MILLISECONDS)) {
			return false;
		}
		return startAdmitted(subscriptionId, true, minAge);
	}

	private boolean startAdmitted(String subscriptionId, boolean due, Duration minAge) {
		try {
			CalendarSubscription claimed = claim(subscriptionId, due, minAge);
			if (claimed == null) {
				admitted.release();
				return false;
			}
			reads.execute(() -> {
				try {
					run(claimed);
				}
				finally {
					admitted.release();
				}
			});
			return true;
		}
		catch (RuntimeException rejected) {
			admitted.release();
			throw rejected;
		}
	}

	/** Takes the subscription for one fetch, or null when another holds it or it is not due. */
	CalendarSubscription claim(String subscriptionId, boolean due, Duration minAge) {
		Instant now = clock.instant();
		List<Criteria> all = new ArrayList<>();
		all.add(Criteria.where("_id").is(subscriptionId));
		all.add(new Criteria().orOperator(Criteria.where("fetchClaimedAt").is(null),
				Criteria.where("fetchClaimedAt").lt(now.minus(CLAIM_STALE))));
		if (due) {
			all.add(Criteria.where("enabled").is(true));
			all.add(Criteria.where("failures").lt(CalendarSubscription.PAUSE_AFTER_FAILURES));
			all.add(new Criteria().orOperator(Criteria.where("lastFetchedAt").is(null),
					Criteria.where("lastFetchedAt").lt(now.minus(minAge))));
		}
		return mongo.findAndModify(Query.query(new Criteria().andOperator(all)),
				new Update().set("fetchClaimedAt", now).set("lastStatus", CalendarSubscription.Status.RUNNING),
				FindAndModifyOptions.options().returnNew(true), CalendarSubscription.class);
	}

	/** One fetch of a claimed subscription, start to outcome. Never throws. */
	void run(CalendarSubscription claimed) {
		try {
			User owner = users.findById(claimed.getUserId()).filter(User::isActive).orElse(null);
			if (owner == null) {
				release(claimed, new Update().set("lastStatus", CalendarSubscription.Status.PAUSED));
				return;
			}
			String url;
			try {
				url = cipher.decrypt(claimed.getEncryptedUrl(), BINDING + claimed.getId());
			}
			catch (IllegalArgumentException | IllegalStateException unreadable) {
				fail(claimed, "error.calendar.addressUnreadable", null);
				return;
			}
			IcsFetchResult result = fetcher.fetch(url, claimed.getEtag(), claimed.getLastModified())
					.get(FETCH_WAIT.toSeconds(), TimeUnit.SECONDS);
			finish(claimed, owner, result);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			fail(claimed, IcsFetchError.BUSY.messageKey(), null);
		}
		catch (Exception ex) {
			// The type only: the message of a fetch, parser or driver exception can quote the feed.
			log.warn("[time] calendar subscription {} could not be read: {}", claimed.getId(),
					ex.getClass().getName());
			fail(claimed, "error.calendar.fetchFailed", null);
		}
	}

	private void finish(CalendarSubscription claimed, User owner, IcsFetchResult result) {
		switch (result.outcome()) {
			case FAILED -> fail(claimed, result.error().messageKey(),
					result.httpStatus() == 0 ? null : result.httpStatus());
			case NOT_MODIFIED -> {
				succeed(claimed, result, claimed.getEventCount(),
						claimed.getLastStatus() == CalendarSubscription.Status.TRUNCATED);
				takeOver(claimed, owner);
			}
			case FETCHED -> {
				ZoneId zone = timeTracking.zoneOf(owner);
				LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
				IcsCalendar parsed;
				try {
					parsed = IcsParser.parse(result.body(), today.minusDays(DAYS_BACK).atStartOfDay(zone).toInstant(),
							today.plusDays(DAYS_AHEAD + 1L).atStartOfDay(zone).toInstant(), zone);
				}
				catch (IcsParseException unreadable) {
					fail(claimed, unreadable.messageKey(), unreadable.line() > 0 ? unreadable.line() : null);
					return;
				}
				Stored stored = store(claimed, parsed.events(), zone);
				succeed(claimed, result, stored.kept(), parsed.truncated() || stored.capped());
				takeOver(claimed, owner);
			}
		}
	}

	// --- the window ------------------------------------------------------------------------

	record Stored(int kept, boolean capped, int written, int removed) {
	}

	private record Present(String id, String digest) {
	}

	/**
	 * Replaces the subscription's events with [events]: unchanged ones stay as they are, changed
	 * and new ones are upserted, ones that left the feed are removed. Cancelled occurrences are not
	 * kept, and at most {@link CalendarEvent#PER_SUBSCRIPTION_MAX} are.
	 */
	Stored store(CalendarSubscription subscription, List<IcsEvent> events, ZoneId zone) {
		Map<String, Present> present = new HashMap<>();
		Query mine = Query.query(Criteria.where("subscriptionId").is(subscription.getId()));
		mine.fields().include("_id", "uid", "recurrenceId", "digest");
		mongo.getCollection(mongo.getCollectionName(CalendarEvent.class))
				.find(mine.getQueryObject()).projection(mine.getFieldsObject())
				.forEach(row -> present.put(key(row.getString("uid"), row.getString("recurrenceId")),
						new Present(row.getObjectId("_id").toHexString(), row.getString("digest"))));

		List<CalendarEvent> wanted = new ArrayList<>();
		boolean capped = false;
		Map<String, Boolean> seen = new HashMap<>();
		for (IcsEvent event : events) {
			if (event.status() == IcsEvent.Status.CANCELLED) {
				continue;
			}
			CalendarEvent row = rowOf(subscription, event, zone);
			if (seen.putIfAbsent(key(row.getUid(), row.getRecurrenceId()), Boolean.TRUE) != null) {
				continue;
			}
			if (wanted.size() >= CalendarEvent.PER_SUBSCRIPTION_MAX) {
				capped = true;
				break;
			}
			wanted.add(row);
		}

		int written = 0;
		BulkOperations upserts = null;
		int pending = 0;
		for (CalendarEvent row : wanted) {
			Present was = present.remove(key(row.getUid(), row.getRecurrenceId()));
			if (was != null && Objects.equals(was.digest(), row.getDigest())) {
				continue;
			}
			if (upserts == null) {
				upserts = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, CalendarEvent.class);
			}
			upserts.upsert(Query.query(Criteria.where("subscriptionId").is(row.getSubscriptionId())
							.and("uid").is(row.getUid()).and("recurrenceId").is(row.getRecurrenceId())),
					new Update()
							.set("userId", row.getUserId())
							.set("startsAt", row.getStartsAt())
							.set("endsAt", row.getEndsAt())
							.set("timezone", row.getTimezone())
							.set("summary", row.getSummary())
							.set("location", row.getLocation())
							.set("allDay", row.isAllDay())
							.set("free", row.isFree())
							.set("status", row.getStatus())
							.set("digest", row.getDigest()));
			written++;
			if (++pending == BATCH) {
				upserts.execute();
				upserts = null;
				pending = 0;
			}
		}
		if (upserts != null) {
			upserts.execute();
		}

		List<String> gone = present.values().stream().map(Present::id).toList();
		for (int from = 0; from < gone.size(); from += BATCH) {
			List<ObjectId> ids = gone.subList(from, Math.min(gone.size(), from + BATCH)).stream()
					.map(ObjectId::new).toList();
			mongo.remove(Query.query(Criteria.where("_id").in(ids)), CalendarEvent.class);
		}
		return new Stored(wanted.size(), capped, written, gone.size());
	}

	private static CalendarEvent rowOf(CalendarSubscription subscription, IcsEvent event, ZoneId zone) {
		CalendarEvent.CalendarEventBuilder row = CalendarEvent.builder()
				.subscriptionId(subscription.getId())
				.userId(subscription.getUserId())
				.uid(event.uid())
				.recurrenceId(event.recurrenceId())
				.summary(cut(event.summary(), CalendarEvent.SUMMARY_MAX))
				.location(cut(event.location(), CalendarEvent.LOCATION_MAX))
				.free(event.transparent())
				.status(event.status() == null ? null : event.status().name());
		switch (event) {
			case IcsEvent.Timed timed -> row.startsAt(timed.start()).endsAt(timed.end())
					.timezone(timed.zone().getId()).allDay(false);
			case IcsEvent.AllDay day -> row.startsAt(day.start().atStartOfDay(zone).toInstant())
					.endsAt(day.end().atStartOfDay(zone).toInstant()).timezone(zone.getId()).allDay(true);
		}
		CalendarEvent built = row.build();
		built.setDigest(digest(built));
		return built;
	}

	private static String digest(CalendarEvent row) {
		String text = String.join("\u0000", String.valueOf(row.getStartsAt()), String.valueOf(row.getEndsAt()),
				String.valueOf(row.getTimezone()), String.valueOf(row.getSummary()),
				String.valueOf(row.getLocation()), String.valueOf(row.isAllDay()), String.valueOf(row.isFree()),
				String.valueOf(row.getStatus()));
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 16);
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static String key(String uid, String recurrenceId) {
		return uid + "\u0000" + (recurrenceId == null ? "" : recurrenceId);
	}

	private static String cut(String text, int max) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String clean = text.strip();
		return clean.length() <= max ? clean : clean.substring(0, max);
	}

	// --- the rule --------------------------------------------------------------------------

	/**
	 * Applies the subscription's takeover rule to the occurrences that have ended since it was
	 * switched on. Each one is marked once dealt with, taken over or not.
	 */
	void takeOver(CalendarSubscription subscription, User owner) {
		CalendarSubscription.AutoConvert rule = subscription.getAutoConvert();
		if (rule == null || !rule.enabled() || rule.since() == null) {
			return;
		}
		Instant now = clock.instant();
		Query due = Query.query(Criteria.where("subscriptionId").is(subscription.getId())
						.and("autoHandledAt").is(null)
						.and("allDay").is(false)
						.and("free").ne(true)
						.and("status").ne(IcsEvent.Status.TENTATIVE.name())
						.and("startsAt").gte(rule.since())
						.and("endsAt").lte(now))
				.with(Sort.by("startsAt"))
				.limit(TAKEOVERS_PER_FETCH);
		List<CalendarSubscription.Skip> skips = new ArrayList<>();
		for (CalendarEvent event : mongo.find(due, CalendarEvent.class)) {
			try {
				timeTracking.createFromCalendar(draftOf(event, rule.projectId(), null, rule.tags(), rule.billable(),
						null), refOf(event), owner);
			}
			catch (DuplicateKeyException taken) {
				// Taken over by hand in the meantime.
			}
			catch (ApiException refused) {
				skips.add(new CalendarSubscription.Skip(event.getId(), event.getStartsAt(), refused.getMessageKey(),
						now));
			}
			catch (RuntimeException ex) {
				log.warn("[time] takeover from calendar subscription {} failed: {}", subscription.getId(),
						ex.getClass().getName());
				continue;
			}
			mongo.updateFirst(Query.query(Criteria.where("_id").is(event.getId())),
					new Update().set("autoHandledAt", now), CalendarEvent.class);
		}
		if (!skips.isEmpty()) {
			mongo.updateFirst(Query.query(Criteria.where("_id").is(subscription.getId())),
					new Update().push("skips").atPosition(0).slice(CalendarSubscription.SKIPS_MAX)
							.each(skips.reversed().toArray()),
					CalendarSubscription.class);
		}
	}

	/**
	 * What an occurrence becomes as an entry: its own times, its title as the description unless
	 * one is given, and whatever placement and tags the person or the rule chose.
	 */
	static TimeTrackingService.NewEntry draftOf(CalendarEvent event, String projectId, String issueId,
			List<String> tags, Boolean billable, String description) {
		String text = description != null && !description.isBlank() ? description : event.getSummary();
		return new TimeTrackingService.NewEntry(projectId, issueId, null, null, null, text,
				event.getStartsAt(), event.getEndsAt(), tags, billable);
	}

	static WorkItem.CalendarRef refOf(CalendarEvent event) {
		return new WorkItem.CalendarRef(event.getSubscriptionId(), event.getUid(), event.getRecurrenceId());
	}

	// --- outcome ---------------------------------------------------------------------------

	private void succeed(CalendarSubscription claimed, IcsFetchResult result, int kept, boolean truncated) {
		Update update = new Update()
				.set("lastStatus", truncated ? CalendarSubscription.Status.TRUNCATED : CalendarSubscription.Status.OK)
				.set("lastFetchedAt", clock.instant())
				.set("failures", 0)
				.set("eventCount", kept)
				.unset("fetchClaimedAt");
		if (truncated) {
			update.set("lastError", "error.calendar.truncated").set("lastErrorArg", CalendarEvent.PER_SUBSCRIPTION_MAX);
		}
		else {
			update.unset("lastError").unset("lastErrorArg");
		}
		setOrUnset(update, "etag", result.etag());
		setOrUnset(update, "lastModified", result.lastModified());
		record(claimed, update);
	}

	private void fail(CalendarSubscription claimed, String messageKey, Integer arg) {
		try {
			int failures = claimed.getFailures() + 1;
			Update update = new Update()
					.set("lastStatus", failures >= CalendarSubscription.PAUSE_AFTER_FAILURES
							? CalendarSubscription.Status.PAUSED : CalendarSubscription.Status.FAILED)
					.set("lastFetchedAt", clock.instant())
					.set("failures", failures)
					.set("lastError", messageKey)
					.unset("fetchClaimedAt");
			setOrUnset(update, "lastErrorArg", arg);
			record(claimed, update);
		}
		catch (RuntimeException ex) {
			// The claim goes stale on its own.
			log.warn("[time] could not record the failed fetch of calendar subscription {}: {}", claimed.getId(),
					ex.getClass().getName());
		}
	}

	private void release(CalendarSubscription claimed, Update update) {
		record(claimed, update.unset("fetchClaimedAt"));
	}

	/**
	 * Writes the outcome if this fetch still holds the subscription. One that ran past
	 * {@link #CLAIM_STALE} may have been taken over, and its late outcome must not overwrite the
	 * current one, nor bring back a subscription deleted meanwhile.
	 */
	private void record(CalendarSubscription claimed, Update update) {
		Query stillHeld = Query.query(Criteria.where("_id").is(claimed.getId())
				.and("fetchClaimedAt").is(claimed.getFetchClaimedAt()));
		mongo.updateFirst(stillHeld, update, CalendarSubscription.class);
	}

	private static void setOrUnset(Update update, String field, Object value) {
		if (value == null) {
			update.unset(field);
		}
		else {
			update.set(field, value);
		}
	}

	@Override
	public void destroy() {
		reads.shutdownNow();
	}

	private static ExecutorService pool() {
		ThreadPoolExecutor pool = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(QUEUE), Thread.ofPlatform().name("calendar-read-", 1).daemon().factory());
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}
}
