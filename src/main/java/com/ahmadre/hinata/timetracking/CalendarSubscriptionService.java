package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.ics.IcsFetchError;
import com.ahmadre.hinata.ics.IcsFetcher;
import com.ahmadre.hinata.ics.IcsUrlCipher;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import okhttp3.HttpUrl;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A person's calendar subscriptions and what they do with the events (HIN-94).
 *
 * <p>Everything here is the caller's own and nobody else's: every lookup is by id <em>and</em>
 * owner, and somebody else's subscription or event answers exactly like one that does not exist.
 * Everything is behind the module's gate and, on top of it, behind the organisation's
 * {@code icsImportEnabled}; with that off every route answers 404 and the calendar shows no events.
 */
@Service
@RequiredArgsConstructor
public class CalendarSubscriptionService {

	/** Asked-for refreshes one subscription allows. */
	static final Duration REFRESH_INTERVAL = Duration.ofMinutes(1);

	private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
	private static final String DEFAULT_COLOR = "#4F7DD9";

	private final MongoTemplate mongo;
	private final IcsFetcher fetcher;
	private final IcsUrlCipher cipher;
	private final CalendarSync sync;
	private final TimeTrackingSettings settings;
	private final TimeTrackingService timeTracking;
	private final AuditService audit;
	private final Clock clock;

	public record Draft(String name, String url, String color, Boolean enabled, RuleDraft autoConvert) {
	}

	/** An edit; null leaves a field alone. A new address resets the fetch state and the events. */
	public record Patch(String name, String url, String color, Boolean enabled, RuleDraft autoConvert) {
	}

	public record RuleDraft(boolean enabled, String projectId, List<String> tags, Boolean billable) {
	}

	/** What the person chose in the editor when taking an event over. */
	public record Conversion(String projectId, String issueId, List<String> tags, Boolean billable,
			String description) {
	}

	/** An event of the calendar layer, its subscription's colour, and the entry taken over from it, if any. */
	public record LayerEvent(CalendarEvent event, String color, String convertedEntryId) {
	}

	/** Whether the person can subscribe at all: the module's gate is checked before this. */
	public boolean enabled() {
		return settings.icsImportEnabled();
	}

	// --- subscriptions ---------------------------------------------------------------------

	public List<CalendarSubscription> list(User user) {
		requireEnabled();
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId()))
				.with(Sort.by("createdAt", "_id")).limit(CalendarSubscription.PER_USER_MAX), CalendarSubscription.class);
	}

	public CalendarSubscription require(User user, String id) {
		requireEnabled();
		CalendarSubscription found = ObjectId.isValid(id) ? mongo.findOne(Query.query(
				Criteria.where("_id").is(id).and("userId").is(user.getId())), CalendarSubscription.class) : null;
		if (found == null) {
			throw ApiException.notFound("calendarSubscription");
		}
		return found;
	}

	public CalendarSubscription create(User user, Draft draft) {
		requireEnabled();
		String url = requireUrl(draft.url());
		if (!cipher.isConfigured()) {
			throw ApiException.conflict("error.ics.notConfigured");
		}
		if (mongo.count(Query.query(Criteria.where("userId").is(user.getId())), CalendarSubscription.class)
				>= CalendarSubscription.PER_USER_MAX) {
			throw ApiException.badRequest("error.calendar.tooMany", CalendarSubscription.PER_USER_MAX);
		}
		String id = new ObjectId().toHexString();
		Instant now = clock.instant();
		CalendarSubscription saved = mongo.insert(CalendarSubscription.builder()
				.id(id)
				.userId(user.getId())
				.name(requireName(draft.name()))
				.color(colorOf(draft.color()))
				.encryptedUrl(cipher.encrypt(url, CalendarSync.BINDING + id))
				.hostMasked(hostOf(url))
				.enabled(draft.enabled() == null || draft.enabled())
				.autoConvert(ruleOf(draft.autoConvert(), null, user))
				.lastStatus(CalendarSubscription.Status.PENDING)
				.createdAt(now)
				.updatedAt(now)
				.build());
		audit.event(AuditAction.TIME_CALENDAR_SUBSCRIPTION_CREATED).actor(user)
				.target(saved.getId(), saved.getName())
				.meta("host", saved.getHostMasked())
				.log();
		if (saved.isEnabled()) {
			sync.start(saved.getId(), false, Duration.ZERO);
		}
		return reread(saved);
	}

	/**
	 * Edits field by field, never as a whole document: a fetch may be writing its outcome at the
	 * same moment, and a replace would put the state from before it back.
	 */
	public CalendarSubscription update(User user, String id, Patch patch) {
		CalendarSubscription current = require(user, id);
		Update update = new Update().set("updatedAt", clock.instant());
		boolean fetchNow = false;
		if (patch.name() != null) {
			update.set("name", requireName(patch.name()));
		}
		if (patch.color() != null) {
			update.set("color", colorOf(patch.color()));
		}
		if (patch.url() != null) {
			String url = requireUrl(patch.url());
			if (!cipher.isConfigured()) {
				throw ApiException.conflict("error.ics.notConfigured");
			}
			update.set("encryptedUrl", cipher.encrypt(url, CalendarSync.BINDING + id))
					.set("hostMasked", hostOf(url))
					.set("lastStatus", CalendarSubscription.Status.PENDING)
					.set("failures", 0)
					.set("eventCount", 0)
					.unset("etag").unset("lastModified").unset("lastError").unset("lastErrorArg")
					.unset("lastFetchedAt").unset("skips");
			// The events were another calendar's.
			mongo.remove(Query.query(Criteria.where("subscriptionId").is(id)), CalendarEvent.class);
			fetchNow = true;
		}
		if (patch.enabled() != null) {
			update.set("enabled", patch.enabled());
			if (patch.enabled() && !current.isEnabled()) {
				// Switching it back on is the person's answer to a pause.
				update.set("failures", 0);
				fetchNow = true;
			}
		}
		if (patch.autoConvert() != null) {
			update.set("autoConvert", ruleOf(patch.autoConvert(), current.getAutoConvert(), user));
		}
		mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("userId").is(user.getId())), update,
				CalendarSubscription.class);
		CalendarSubscription saved = require(user, id);
		if (fetchNow && saved.isEnabled()) {
			sync.start(id, false, Duration.ZERO);
			saved = reread(saved);
		}
		return saved;
	}

	/** Removes the subscription with its events. Entries taken over stay: they are the person's record. */
	public void delete(User user, String id) {
		CalendarSubscription subscription = require(user, id);
		mongo.remove(Query.query(Criteria.where("subscriptionId").is(id)), CalendarEvent.class);
		mongo.remove(Query.query(Criteria.where("_id").is(id).and("userId").is(user.getId())),
				CalendarSubscription.class);
		audit.event(AuditAction.TIME_CALENDAR_SUBSCRIPTION_DELETED).actor(user)
				.target(subscription.getId(), subscription.getName())
				.meta("host", subscription.getHostMasked())
				.log();
	}

	/**
	 * Starts reading the subscription now, at most once a minute. Answers at once with the
	 * subscription in the state {@code RUNNING}; reading it again shows how it went.
	 */
	public CalendarSubscription refresh(User user, String id) {
		require(user, id);
		Instant now = clock.instant();
		CalendarSubscription spent = mongo.findAndModify(Query.query(new Criteria().andOperator(
						Criteria.where("_id").is(id), Criteria.where("userId").is(user.getId()),
						new Criteria().orOperator(Criteria.where("manualRefreshAt").is(null),
								Criteria.where("manualRefreshAt").lte(now.minus(REFRESH_INTERVAL))))),
				new Update().set("manualRefreshAt", now), FindAndModifyOptions.options().returnNew(true),
				CalendarSubscription.class);
		if (spent == null) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "error.calendar.refreshTooSoon");
		}
		boolean started = sync.start(id, false, Duration.ZERO);
		CalendarSubscription after = reread(spent);
		if (!started && after.getLastStatus() != CalendarSubscription.Status.RUNNING) {
			// Not held by another fetch, so every place in the queue was taken.
			throw ApiException.conflict(IcsFetchError.BUSY.messageKey());
		}
		return after;
	}

	// --- events ----------------------------------------------------------------------------

	/**
	 * The caller's own events from [from] to [to], inclusive, for the calendar layer: of enabled
	 * subscriptions only, and none at all while the organisation has the import switched off.
	 */
	public List<LayerEvent> layer(User user, LocalDate from, LocalDate to) {
		if (!enabled()) {
			return List.of();
		}
		Query enabledOnes = Query.query(Criteria.where("userId").is(user.getId()).and("enabled").is(true))
				.limit(CalendarSubscription.PER_USER_MAX);
		enabledOnes.fields().include("_id", "color");
		Map<String, String> colors = new HashMap<>();
		mongo.find(enabledOnes, CalendarSubscription.class)
				.forEach(subscription -> colors.put(subscription.getId(), subscription.getColor()));
		List<String> shown = List.copyOf(colors.keySet());
		if (shown.isEmpty()) {
			return List.of();
		}
		ZoneId zone = timeTracking.zoneOf(user);
		Instant start = from.atStartOfDay(zone).toInstant();
		Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
		List<CalendarEvent> events = mongo.find(Query.query(Criteria.where("userId").is(user.getId())
						.and("subscriptionId").in(shown)
						.and("startsAt").lt(end)
						.and("endsAt").gt(start))
				.with(Sort.by("startsAt", "_id"))
				.limit(TimeTrackingService.CALENDAR_CAP), CalendarEvent.class);
		Map<String, String> taken = takenOver(user, shown, from.minusDays(1), to.plusDays(1));
		return events.stream()
				.map(event -> new LayerEvent(event, colors.get(event.getSubscriptionId()),
						taken.get(refKey(event.getSubscriptionId(), event.getUid(), event.getRecurrenceId()))))
				.toList();
	}

	/**
	 * Takes one of the caller's events over as an entry. A second call for the same occurrence
	 * answers with the entry the first one made, whoever made it, the person or the rule.
	 */
	public WorkItem convert(User user, String eventId, Conversion conversion) {
		requireEnabled();
		CalendarEvent event = ObjectId.isValid(eventId) ? mongo.findOne(Query.query(
				Criteria.where("_id").is(eventId).and("userId").is(user.getId())), CalendarEvent.class) : null;
		if (event == null) {
			throw ApiException.notFound("calendarEvent");
		}
		WorkItem existing = existingEntry(event);
		if (existing != null) {
			return existing;
		}
		try {
			return timeTracking.createFromCalendar(CalendarSync.draftOf(event, conversion.projectId(),
					conversion.issueId(), conversion.tags(), conversion.billable(), conversion.description()),
					CalendarSync.refOf(event), user);
		}
		catch (DuplicateKeyException taken) {
			WorkItem raced = existingEntry(event);
			if (raced == null) {
				throw taken;
			}
			return raced;
		}
	}

	/** How many of the caller's events of [day] are over, timed, and not taken over yet. */
	public int openToday(User user) {
		if (!enabled()) {
			return 0;
		}
		ZoneId zone = timeTracking.zoneOf(user);
		LocalDate today = LocalDate.now(clock.withZone(zone));
		Instant now = clock.instant();
		return (int) layer(user, today, today).stream()
				.filter(shown -> shown.convertedEntryId() == null
						&& !shown.event().isAllDay()
						&& !shown.event().isFree()
						&& !shown.event().getEndsAt().isAfter(now))
				.count();
	}

	// --- helpers ---------------------------------------------------------------------------

	private WorkItem existingEntry(CalendarEvent event) {
		Criteria ref = Criteria.where("calendarRef.subscriptionId").is(event.getSubscriptionId())
				.and("calendarRef.uid").is(event.getUid())
				.and("calendarRef.recurrenceId").is(event.getRecurrenceId());
		return mongo.findOne(Query.query(ref), WorkItem.class);
	}

	/**
	 * The entries taken over from the caller's subscriptions around the window, by occurrence. By
	 * the entry's day, which is where the calendar draws it: a day either side covers an occurrence
	 * whose day in the person's zone differs from the window's edge.
	 */
	private Map<String, String> takenOver(User user, List<String> subscriptions, LocalDate from, LocalDate to) {
		Query query = Query.query(Criteria.where("userId").is(user.getId())
				.and("date").gte(from).lte(to)
				.and("calendarRef.subscriptionId").in(subscriptions));
		query.fields().include("_id", "calendarRef");
		Map<String, String> out = new HashMap<>();
		// Raw rows: a projected WorkItem fails on its primitive fields.
		for (Document row : mongo.find(query, Document.class, mongo.getCollectionName(WorkItem.class))) {
			Document ref = row.get("calendarRef", Document.class);
			out.put(refKey(ref.getString("subscriptionId"), ref.getString("uid"), ref.getString("recurrenceId")),
					row.getObjectId("_id").toHexString());
		}
		return out;
	}

	private static String refKey(String subscriptionId, String uid, String recurrenceId) {
		return subscriptionId + "\u0000" + uid + "\u0000" + (recurrenceId == null ? "" : recurrenceId);
	}

	private CalendarSubscription.AutoConvert ruleOf(RuleDraft draft, CalendarSubscription.AutoConvert before,
			User user) {
		if (draft == null || !draft.enabled()) {
			return CalendarSubscription.AutoConvert.OFF;
		}
		if (draft.projectId() != null && !draft.projectId().isBlank()) {
			// The placement is checked now, against what the person may reach, so a rule never names a
			// project they could not file to by hand.
			timeTracking.resolvePlacement(draft.projectId(), null, user);
		}
		Instant since = before != null && before.enabled() && before.since() != null ? before.since()
				: clock.instant();
		return new CalendarSubscription.AutoConvert(true,
				draft.projectId() == null || draft.projectId().isBlank() ? null : draft.projectId(),
				draft.tags() == null ? List.of() : draft.tags().stream().limit(20).toList(),
				draft.billable(), since);
	}

	private String requireUrl(String url) {
		if (url == null || url.isBlank()) {
			throw ApiException.badRequest("error.ics.urlInvalid");
		}
		fetcher.check(url).ifPresent(error -> {
			throw ApiException.badRequest(error.messageKey());
		});
		return url.strip();
	}

	private static String requireName(String name) {
		if (name == null || name.isBlank()) {
			throw ApiException.badRequest("error.calendar.nameRequired");
		}
		String clean = name.strip();
		if (clean.length() > CalendarSubscription.NAME_MAX) {
			throw ApiException.badRequest("error.calendar.nameTooLong", CalendarSubscription.NAME_MAX);
		}
		return clean;
	}

	private static String colorOf(String color) {
		if (color == null || color.isBlank()) {
			return DEFAULT_COLOR;
		}
		if (!COLOR.matcher(color.strip()).matches()) {
			throw ApiException.badRequest("error.calendar.colorInvalid");
		}
		return color.strip().toUpperCase(Locale.ROOT);
	}

	/** The host of an address as {@link IcsFetcher} reads it, webcal included. */
	static String hostOf(String url) {
		String text = url.strip();
		for (String alias : List.of("webcal://", "webcals://")) {
			if (text.regionMatches(true, 0, alias, 0, alias.length())) {
				text = "https://" + text.substring(alias.length());
				break;
			}
		}
		HttpUrl parsed = HttpUrl.parse(text);
		return parsed == null ? null : parsed.host().toLowerCase(Locale.ROOT);
	}

	private CalendarSubscription reread(CalendarSubscription subscription) {
		CalendarSubscription now = mongo.findById(subscription.getId(), CalendarSubscription.class);
		return now == null ? subscription : now;
	}

	private void requireEnabled() {
		if (!enabled()) {
			throw new ApiException(HttpStatus.NOT_FOUND, AdvancedTimeTrackingGate.DISABLED_KEY);
		}
	}
}
