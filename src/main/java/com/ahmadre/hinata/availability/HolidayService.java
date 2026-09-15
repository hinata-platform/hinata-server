package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.ics.IcsCalendar;
import com.ahmadre.hinata.ics.IcsFetchError;
import com.ahmadre.hinata.ics.IcsFetchResult;
import com.ahmadre.hinata.ics.IcsFetcher;
import com.ahmadre.hinata.ics.IcsParseException;
import com.ahmadre.hinata.ics.IcsParser;
import com.ahmadre.hinata.ics.IcsUrlCipher;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserZones;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.BulkOperationException;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Holiday calendars and their days, kept by administrators by hand or imported from a feed.
 *
 * <p>The import runs on its own. The route claims the calendar, answers at once, and the calendar
 * carries the outcome ({@link HolidayCalendar#getImportState()}): no request thread waits for a
 * server somebody else runs. The feed goes through {@link IcsFetcher} with its SSRF rules, is read
 * on this service's executor (never the fetcher's threads, never the common pool, see
 * {@link IcsParser}), and only whole days land: all-day events, and events from midnight to
 * midnight ({@link HolidayDays}).
 *
 * <p>The import switch for people's own subscriptions, {@code icsImportEnabled}, does not apply:
 * a holiday calendar is instance configuration an administrator maintains, gated by the module
 * alone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HolidayService implements DisposableBean {

	/** What a feed address is bound to when encrypted, followed by the calendar's id. */
	static final String BINDING = "holiday-calendar:";

	/** An import claimed longer ago than this is taken to have died with its instance. */
	static final Duration IMPORT_STALE = Duration.ofMinutes(5);

	public static final int PAGE_MAX = 100;

	/** The last page a listing reaches; there are at most {@link HolidayCalendar#COUNT_MAX} calendars. */
	static final int PAGE_INDEX_MAX = HolidayCalendar.COUNT_MAX;

	/** The server's code for a key that is already taken. */
	private static final int DUPLICATE_KEY = 11_000;

	private final HolidayCalendarRepository calendars;
	private final HolidayRepository holidays;
	private final MongoTemplate mongo;
	private final IcsFetcher fetcher;
	private final IcsUrlCipher cipher;
	private final SettingsService settings;
	private final AuditService audit;
	private final Clock clock;

	private final ThreadPoolExecutor imports = importPool();

	public record CalendarDraft(String name, String region, String icsUrl, Boolean defaultCalendar) {
	}

	/** An edit; null leaves a field alone, a blank region clears it, a blank address removes the feed. */
	public record CalendarPatch(String name, String region, String icsUrl, Boolean defaultCalendar) {
	}

	public record HolidayDraft(String calendarId, LocalDate date, String name, Boolean halfDay) {
	}

	public record HolidayPatch(LocalDate date, String name, Boolean halfDay) {
	}

	private record Feed(String source, String host) {
	}

	// --- calendars ------------------------------------------------------------

	public Page<HolidayCalendar> calendars(int page, int size) {
		return calendars.findAll(PageRequest.of(Math.clamp(page, 0, PAGE_INDEX_MAX),
				Math.clamp(size, 1, PAGE_MAX), Sort.by(Sort.Order.asc("name"), Sort.Order.asc("_id"))));
	}

	public HolidayCalendar requireCalendar(String id) {
		return calendars.findById(id).orElseThrow(() -> ApiException.notFound("holidayCalendar"));
	}

	public HolidayCalendar createCalendar(User admin, CalendarDraft draft) {
		assertAdmin(admin);
		if (calendars.count() >= HolidayCalendar.COUNT_MAX) {
			throw ApiException.badRequest("error.availability.calendarsTooMany", HolidayCalendar.COUNT_MAX);
		}
		String id = new ObjectId().toHexString();
		Feed feed = feedOf(id, draft.icsUrl());
		Instant now = clock.instant();
		HolidayCalendar saved = calendars.save(HolidayCalendar.builder()
				.id(id)
				.name(required(draft.name(), HolidayCalendar.NAME_MAX))
				.region(optional(draft.region(), HolidayCalendar.REGION_MAX, "error.availability.regionTooLong"))
				.source(feed.source())
				.sourceHost(feed.host())
				.createdBy(admin.getId())
				.createdAt(now)
				.updatedAt(now)
				.build());
		if (Boolean.TRUE.equals(draft.defaultCalendar())) {
			makeDefault(id);
			saved = requireCalendar(id);
		}
		recordCalendar(admin, saved, "created");
		return saved;
	}

	/**
	 * Edits a calendar field by field, never as a whole document: an import may be writing its
	 * state at the same moment, and a replace would put the state from before it back.
	 */
	public HolidayCalendar updateCalendar(User admin, String id, CalendarPatch patch) {
		assertAdmin(admin);
		requireCalendar(id);
		Update update = new Update().set("updatedAt", clock.instant());
		if (patch.name() != null) {
			update.set("name", required(patch.name(), HolidayCalendar.NAME_MAX));
		}
		if (patch.region() != null) {
			setOrUnset(update, "region",
					optional(patch.region(), HolidayCalendar.REGION_MAX, "error.availability.regionTooLong"));
		}
		if (patch.icsUrl() != null) {
			Feed feed = feedOf(id, patch.icsUrl());
			setOrUnset(update, "source", feed.source());
			setOrUnset(update, "sourceHost", feed.host());
			// Validators belong to the address they were earned on.
			update.unset("etag").unset("lastModified");
		}
		mongo.updateFirst(byId(id), update, HolidayCalendar.class);
		if (Boolean.TRUE.equals(patch.defaultCalendar())) {
			makeDefault(id);
		}
		else if (Boolean.FALSE.equals(patch.defaultCalendar())) {
			mongo.updateFirst(byId(id), new Update().unset("defaultCalendar"), HolidayCalendar.class);
		}
		HolidayCalendar saved = requireCalendar(id);
		recordCalendar(admin, saved, "updated");
		return saved;
	}

	/** Removes a calendar with its days; patterns that followed it fall back to the default. */
	public void deleteCalendar(User admin, String id) {
		assertAdmin(admin);
		HolidayCalendar calendar = requireCalendar(id);
		holidays.deleteByCalendarId(id);
		mongo.updateMulti(Query.query(Criteria.where("holidayCalendarId").is(id)),
				new Update().unset("holidayCalendarId"), WorkingSchedule.class);
		calendars.deleteById(id);
		recordCalendar(admin, calendar, "deleted");
	}

	// --- days -----------------------------------------------------------------

	/** A calendar's holidays in one year, in date order. */
	public List<Holiday> holidaysOf(String calendarId, Integer year) {
		requireCalendar(calendarId);
		int wanted = year != null ? year : currentYear(instanceZone());
		assertYear(wanted);
		return mongo.find(Query.query(inYear(calendarId, wanted)).with(Sort.by("date")).limit(Holiday.PER_YEAR_MAX),
				Holiday.class);
	}

	public Holiday addHoliday(User admin, HolidayDraft draft) {
		assertAdmin(admin);
		HolidayCalendar calendar = requireCalendar(draft.calendarId());
		LocalDate date = requireDate(draft.date());
		assertRoomIn(calendar.getId(), date.getYear());
		Holiday holiday = Holiday.builder()
				.calendarId(calendar.getId())
				.date(date)
				.name(required(draft.name(), Holiday.NAME_MAX))
				.halfDay(Boolean.TRUE.equals(draft.halfDay()) ? Boolean.TRUE : null)
				.source(Holiday.Source.MANUAL)
				.updatedAt(clock.instant())
				.build();
		Holiday saved;
		try {
			saved = holidays.insert(holiday);
		}
		catch (DuplicateKeyException taken) {
			throw ApiException.conflict("error.availability.holidayExists");
		}
		recordHoliday(admin, calendar, "added", saved);
		return saved;
	}

	/** Edits a day. A day edited by hand is kept by hand: an import no longer renames it. */
	public Holiday updateHoliday(User admin, String id, HolidayPatch patch) {
		assertAdmin(admin);
		Holiday holiday = holidays.findById(id).orElseThrow(() -> ApiException.notFound("holiday"));
		HolidayCalendar calendar = requireCalendar(holiday.getCalendarId());
		if (patch.date() != null && !patch.date().equals(holiday.getDate())) {
			LocalDate date = requireDate(patch.date());
			if (date.getYear() != holiday.getDate().getYear()) {
				assertRoomIn(calendar.getId(), date.getYear());
			}
			holiday.setDate(date);
		}
		if (patch.name() != null) {
			holiday.setName(required(patch.name(), Holiday.NAME_MAX));
		}
		if (patch.halfDay() != null) {
			holiday.setHalfDay(patch.halfDay() ? Boolean.TRUE : null);
		}
		holiday.setSource(Holiday.Source.MANUAL);
		holiday.setUpdatedAt(clock.instant());
		Holiday saved;
		try {
			saved = holidays.save(holiday);
		}
		catch (DuplicateKeyException taken) {
			throw ApiException.conflict("error.availability.holidayExists");
		}
		recordHoliday(admin, calendar, "updated", saved);
		return saved;
	}

	public void deleteHoliday(User admin, String id) {
		assertAdmin(admin);
		Holiday holiday = holidays.findById(id).orElseThrow(() -> ApiException.notFound("holiday"));
		holidays.delete(holiday);
		calendars.findById(holiday.getCalendarId())
				.ifPresent(calendar -> recordHoliday(admin, calendar, "deleted", holiday));
	}

	// --- import ---------------------------------------------------------------

	/**
	 * Starts importing one year of the calendar's feed, the current year when [year] is null.
	 *
	 * <p>Answers once the calendar is claimed; the future completes with the calendar as the
	 * import left it, always normally. A second import while one runs is a 409, and so is one while
	 * every import thread and queue slot is taken.
	 */
	public CompletableFuture<HolidayCalendar> importYear(User admin, String calendarId, Integer year) {
		assertAdmin(admin);
		HolidayCalendar calendar = requireCalendar(calendarId);
		if (calendar.getSource() == null) {
			throw ApiException.badRequest("error.availability.noFeed");
		}
		if (!cipher.isConfigured()) {
			throw ApiException.badRequest("error.availability.icsSecretMissing");
		}
		ZoneId zone = instanceZone();
		int wanted = year != null ? year : currentYear(zone);
		assertYear(wanted);
		String url;
		try {
			url = cipher.decrypt(calendar.getSource(), BINDING + calendarId);
		}
		catch (IllegalArgumentException unreadable) {
			throw ApiException.badRequest("error.availability.feedUnreadable");
		}
		// Refused before the claim: an import the pool cannot take would hold the calendar until
		// IMPORT_STALE and record its failure on the fetcher's thread.
		if (imports.getQueue().remainingCapacity() == 0) {
			throw ApiException.conflict(IcsFetchError.BUSY.messageKey());
		}
		HolidayCalendar claimed = claim(calendarId);
		if (claimed == null) {
			throw ApiException.conflict("error.availability.importRunning");
		}
		// Validators only for the year they were earned on: a 304 says the feed is unchanged, not
		// that another year was ever read from it.
		boolean sameYear = claimed.getLastImport() != null && claimed.getLastImport().year() == wanted;
		return fetcher.fetch(url, sameYear ? claimed.getEtag() : null, sameYear ? claimed.getLastModified() : null)
				.thenApplyAsync(result -> finish(admin, claimed, wanted, zone, result), imports)
				// A fetch the fetcher refused, or a hand-over the pool refused after the check above:
				// one short write on whichever thread got here.
				.exceptionally(rejected -> fail(admin, claimed, wanted, IcsFetchError.BUSY.messageKey(), null));
	}

	private HolidayCalendar claim(String id) {
		Instant now = clock.instant();
		Query free = Query.query(new Criteria().andOperator(
				Criteria.where("_id").is(id),
				new Criteria().orOperator(
						Criteria.where("importState").ne(HolidayCalendar.ImportState.RUNNING),
						Criteria.where("importStartedAt").lt(now.minus(IMPORT_STALE)))));
		return mongo.findAndModify(free,
				new Update().set("importState", HolidayCalendar.ImportState.RUNNING).set("importStartedAt", now),
				FindAndModifyOptions.options().returnNew(true), HolidayCalendar.class);
	}

	private HolidayCalendar finish(User admin, HolidayCalendar calendar, int year, ZoneId zone,
			IcsFetchResult result) {
		try {
			return switch (result.outcome()) {
				case FAILED -> fail(admin, calendar, year, result.error().messageKey(),
						result.httpStatus() == 0 ? null : result.httpStatus());
				case NOT_MODIFIED -> done(admin, calendar, result,
						new HolidayCalendar.ImportSummary(year, 0, 0, (int) mongo.count(Query.query(
								inYear(calendar.getId(), year)), Holiday.class), 0, false));
				case FETCHED -> {
					IcsCalendar parsed = IcsParser.parse(result.body(), startOf(year, zone), startOf(year + 1, zone),
							zone);
					yield done(admin, calendar, result,
							store(calendar.getId(), year, HolidayDays.of(parsed.events(), year), parsed.truncated()));
				}
			};
		}
		catch (IcsParseException unreadable) {
			return fail(admin, calendar, year, unreadable.messageKey(),
					unreadable.line() > 0 ? unreadable.line() : null);
		}
		catch (RuntimeException ex) {
			// The type only: the message of a parser or driver exception can quote the feed.
			log.warn("[availability] holiday import of calendar {} failed: {}", calendar.getId(),
					ex.getClass().getName());
			return fail(admin, calendar, year, "error.availability.importFailed", null);
		}
	}

	/**
	 * Adds the days that are not there yet, renames imported days whose name changed, and leaves
	 * days kept by hand alone. A second import of the same feed changes nothing. Each kind of write
	 * is one round trip.
	 */
	private HolidayCalendar.ImportSummary store(String calendarId, int year, HolidayDays.Result days,
			boolean truncated) {
		Map<LocalDate, Holiday> existing = new HashMap<>();
		mongo.find(Query.query(inYear(calendarId, year)), Holiday.class)
				.forEach(holiday -> existing.put(holiday.getDate(), holiday));
		int room = Holiday.PER_YEAR_MAX - existing.size();
		int updated = 0;
		int unchanged = 0;
		int capped = days.capped();
		Instant now = clock.instant();
		List<Holiday> fresh = new ArrayList<>();
		BulkOperations renames = null;
		for (HolidayDays.Day day : days.days()) {
			Holiday present = existing.get(day.date());
			if (present == null) {
				if (fresh.size() >= room) {
					capped++;
					continue;
				}
				fresh.add(Holiday.builder().calendarId(calendarId).date(day.date()).name(day.name())
						.source(Holiday.Source.IMPORT).updatedAt(now).build());
			}
			else if (present.getSource() == Holiday.Source.IMPORT && !day.name().equals(present.getName())) {
				if (renames == null) {
					renames = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, Holiday.class);
				}
				renames.updateOne(byId(present.getId()), new Update().set("name", day.name()).set("updatedAt", now));
				updated++;
			}
			else {
				unchanged++;
			}
		}
		if (renames != null) {
			renames.execute();
		}
		int added = insert(fresh);
		return new HolidayCalendar.ImportSummary(year, added, updated, unchanged + fresh.size() - added, capped,
				truncated);
	}

	/**
	 * Inserts new days in one round trip and answers how many landed. A day that is there already was
	 * added by hand, or by another import, since this one read the year: it counts as unchanged.
	 */
	private int insert(List<Holiday> fresh) {
		if (fresh.isEmpty()) {
			return 0;
		}
		try {
			return mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, Holiday.class).insert(fresh).execute()
					.getInsertedCount();
		}
		catch (BulkOperationException partly) {
			if (partly.getErrors().stream().anyMatch(error -> error.getCode() != DUPLICATE_KEY)) {
				throw partly;
			}
			return partly.getResult().getInsertedCount();
		}
	}

	private HolidayCalendar done(User admin, HolidayCalendar calendar, IcsFetchResult result,
			HolidayCalendar.ImportSummary summary) {
		Update update = new Update()
				.set("importState", HolidayCalendar.ImportState.DONE)
				.set("lastImportedAt", clock.instant())
				.set("lastImport", summary)
				.unset("lastImportError")
				.unset("lastImportErrorArg");
		setOrUnset(update, "etag", result.etag());
		setOrUnset(update, "lastModified", result.lastModified());
		recordState(calendar, update);
		audit.event(AuditAction.AVAILABILITY_HOLIDAYS_IMPORTED).actor(admin)
				.target(calendar.getId(), calendar.getName())
				.meta("feedHost", Objects.toString(calendar.getSourceHost(), ""))
				.meta("year", String.valueOf(summary.year()))
				.meta("added", String.valueOf(summary.added()))
				.meta("updated", String.valueOf(summary.updated()))
				.meta("unchanged", String.valueOf(summary.unchanged()))
				.meta("capped", String.valueOf(summary.capped()))
				.meta("truncated", String.valueOf(summary.truncated()))
				.log();
		return calendars.findById(calendar.getId()).orElse(calendar);
	}

	private HolidayCalendar fail(User admin, HolidayCalendar calendar, int year, String messageKey, Integer arg) {
		try {
			Update update = new Update().set("importState", HolidayCalendar.ImportState.FAILED)
					.set("lastImportError", messageKey);
			setOrUnset(update, "lastImportErrorArg", arg);
			recordState(calendar, update);
			audit.event(AuditAction.AVAILABILITY_HOLIDAYS_IMPORTED).actor(admin)
					.target(calendar.getId(), calendar.getName())
					.outcome(AuditLog.Outcome.FAILURE)
					.meta("feedHost", Objects.toString(calendar.getSourceHost(), ""))
					.meta("year", String.valueOf(year))
					.meta("error", messageKey)
					.log();
			return calendars.findById(calendar.getId()).orElse(calendar);
		}
		catch (RuntimeException ex) {
			// The future always completes normally; a claim that stays RUNNING goes stale on its own.
			log.warn("[availability] could not record the failed import of calendar {}: {}", calendar.getId(),
					ex.getClass().getName());
			return calendar;
		}
	}

	/**
	 * Writes an import's outcome onto the calendar, if that import still holds it. An import that ran
	 * past {@link #IMPORT_STALE} may have been taken over, and its late outcome must not overwrite the
	 * state, and the validators, of the one that runs now.
	 */
	private void recordState(HolidayCalendar claimed, Update update) {
		Query stillHeld = Query.query(Criteria.where("_id").is(claimed.getId())
				.and("importStartedAt").is(claimed.getImportStartedAt()));
		if (mongo.updateFirst(stillHeld, update, HolidayCalendar.class).getMatchedCount() == 0) {
			log.info("[availability] holiday import of calendar {} was taken over, so its outcome is not kept",
					claimed.getId());
		}
	}

	@Override
	public void destroy() {
		imports.shutdownNow();
	}

	// --- helpers ----------------------------------------------------------------

	/** The feed of a calendar: encrypted and bound to it, with its host, or none for a blank address. */
	private Feed feedOf(String calendarId, String url) {
		if (url == null || url.isBlank()) {
			return new Feed(null, null);
		}
		fetcher.check(url).ifPresent(error -> {
			throw ApiException.badRequest(error.messageKey());
		});
		if (!cipher.isConfigured()) {
			throw ApiException.badRequest("error.availability.icsSecretMissing");
		}
		return new Feed(cipher.encrypt(url.strip(), BINDING + calendarId), hostOf(url));
	}

	/** The host of a feed address as {@link IcsFetcher} reads it, webcal included. */
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

	private void makeDefault(String id) {
		mongo.updateMulti(Query.query(Criteria.where("_id").ne(id).and("defaultCalendar").is(true)),
				new Update().unset("defaultCalendar"), HolidayCalendar.class);
		mongo.updateFirst(byId(id), new Update().set("defaultCalendar", true), HolidayCalendar.class);
	}

	private void assertRoomIn(String calendarId, int year) {
		if (mongo.count(Query.query(inYear(calendarId, year)), Holiday.class) >= Holiday.PER_YEAR_MAX) {
			throw ApiException.badRequest("error.availability.holidaysPerYear", Holiday.PER_YEAR_MAX);
		}
	}

	private int currentYear(ZoneId zone) {
		return LocalDate.now(clock.withZone(zone)).getYear();
	}

	private ZoneId instanceZone() {
		return UserZones.of(null, settings.get());
	}

	private static Criteria inYear(String calendarId, int year) {
		return Criteria.where("calendarId").is(calendarId)
				.and("date").gte(LocalDate.of(year, 1, 1)).lte(LocalDate.of(year, 12, 31));
	}

	private static Instant startOf(int year, ZoneId zone) {
		return LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant();
	}

	private static void assertYear(int year) {
		CapacityService.assertWindow(LocalDate.of(Math.clamp(year, 1, 9999), 1, 1),
				LocalDate.of(Math.clamp(year, 1, 9999), 1, 1));
	}

	private static LocalDate requireDate(LocalDate date) {
		if (date == null) {
			throw ApiException.badRequest("error.availability.holidayInvalid");
		}
		CapacityService.assertWindow(date, date);
		return date;
	}

	private static String required(String text, int max) {
		if (text == null || text.isBlank()) {
			throw ApiException.badRequest("error.availability.nameRequired");
		}
		return optional(text, max, "error.availability.nameTooLong");
	}

	private static String optional(String text, int max, String tooLongKey) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String clean = text.strip();
		if (clean.length() > max) {
			throw ApiException.badRequest(tooLongKey, max);
		}
		return clean;
	}

	private static void assertAdmin(User user) {
		if (!user.isAdmin()) {
			throw ApiException.forbidden("error.availability.adminOnly");
		}
	}

	private static Query byId(String id) {
		return Query.query(Criteria.where("_id").is(id));
	}

	private static void setOrUnset(Update update, String field, Object value) {
		if (value == null) {
			update.unset(field);
		}
		else {
			update.set(field, value);
		}
	}

	private void recordCalendar(User admin, HolidayCalendar calendar, String change) {
		audit.event(AuditAction.AVAILABILITY_CALENDAR_CHANGED).actor(admin)
				.target(calendar.getId(), calendar.getName())
				.meta("change", change)
				.meta("feedHost", Objects.toString(calendar.getSourceHost(), ""))
				.log();
	}

	private void recordHoliday(User admin, HolidayCalendar calendar, String change, Holiday holiday) {
		audit.event(AuditAction.AVAILABILITY_HOLIDAYS_CHANGED).actor(admin)
				.target(calendar.getId(), calendar.getName())
				.meta("change", change)
				.meta("date", String.valueOf(holiday.getDate()))
				.log();
	}

	private static ThreadPoolExecutor importPool() {
		ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
				Thread.ofPlatform().name("holiday-import-", 1).daemon().factory());
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}
}
