package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.UserZones;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps holiday calendars filled without anybody importing them.
 *
 * <ul>
 * <li><b>The platform's calendar.</b> Once setup is done, the platform makes a calendar from its
 * holiday region: the one an administrator chose, or the country of the instance's time zone. When
 * the region changes, that calendar follows it, until the organisation gives it a source of its own.
 * Deleted, it stays deleted: only a new region makes a new one.</li>
 * <li><b>Every year.</b> At start and every night, each calendar with rules or a feed gets this
 * year and the next, once. A year that has been filled is not filled again unasked, so what the
 * organisation removed or renamed stays as it left it.</li>
 * </ul>
 *
 * <p>The region last applied is kept in a document of its own, not in the settings: every settings
 * write stores the whole document, and one that read it a moment earlier would put an older value
 * back and make a deleted calendar return.
 *
 * <p>{@code hinata.availability.holidays.auto-fill=false} switches the rounds off, for an instance
 * that wants every import by hand; the platform calendar and the rules of a calendar saved by hand
 * still fill at once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HolidayAutoFill {

	/** How long a round waits for one feed before it moves on to the next calendar. */
	private static final Duration FEED_WAIT = Duration.ofMinutes(2);

	static final String STATE = "holiday_platform";
	private static final String STATE_ID = "platform";

	private final HolidayService holidays;
	private final HolidayCalendarRepository calendars;
	private final HolidayRules rules;
	private final SettingsService settings;
	private final MongoTemplate mongo;

	/** One round at a time; a round that finds another running leaves it to finish. */
	private final ReentrantLock round = new ReentrantLock();

	/** One look at the platform's region at a time, each with the settings as they are then. */
	private final ReentrantLock platform = new ReentrantLock();

	@Value("${hinata.availability.holidays.auto-fill:true}")
	private boolean enabled;

	@EventListener(ApplicationReadyEvent.class)
	public void onStart() {
		Thread.ofVirtual().name("holiday-auto-fill").start(() -> {
			applyPlatformRegion();
			if (enabled) {
				fillAll();
			}
			// The first administrator to open a region picker after a deploy does not wait for the
			// rules of every country to be read.
			rules.regions(instanceLocale());
		});
	}

	@Scheduled(cron = "${hinata.availability.holidays.auto-fill-cron:0 17 3 * * *}")
	public void nightly() {
		if (enabled) {
			fillAll();
		}
	}

	@EventListener
	public void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		applyPlatformRegion();
	}

	@EventListener
	public void onFeedChanged(HolidayService.FeedChanged event) {
		if (enabled) {
			Thread.ofVirtual().name("holiday-feed-fill").start(() -> fill(event.calendarId(), holidays.yearsToFill()));
		}
	}

	/** Fills this year and the next of every calendar that fills itself and lacks one. */
	public void fillAll() {
		if (!round.tryLock()) {
			return;
		}
		try {
			List<Integer> years = holidays.yearsToFill();
			Query lacking = Query.query(new Criteria().andOperator(
					new Criteria().orOperator(Criteria.where("rules").ne(null), Criteria.where("source").ne(null)),
					Criteria.where("syncedYears").not().all(years)));
			lacking.fields().include("_id");
			for (HolidayCalendar calendar : mongo.find(lacking, HolidayCalendar.class)) {
				fill(calendar.getId(), years);
			}
		}
		catch (RuntimeException ex) {
			log.warn("[availability] holiday round stopped: {}", ex.getClass().getName());
		}
		finally {
			round.unlock();
		}
	}

	private void fill(String calendarId, List<Integer> years) {
		for (int year : years) {
			try {
				HolidayCalendar current = calendars.findById(calendarId).orElse(null);
				if (current == null) {
					return;
				}
				holidays.fillYear(null, current, year).get(FEED_WAIT.toSeconds(), TimeUnit.SECONDS);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return;
			}
			catch (Exception ex) {
				// A slow feed keeps its claim and finishes on its own; the next round looks again.
				log.info("[availability] holiday calendar {} not filled for {}: {}", calendarId, year,
						ex.getClass().getSimpleName());
			}
		}
	}

	/**
	 * Makes, or points, the platform's calendar at the platform's region, when that region is not
	 * the one applied last. Reads the settings itself, inside the lock, so an older snapshot never
	 * runs after a newer one. Never fails the settings save that called it.
	 */
	void applyPlatformRegion() {
		platform.lock();
		try {
			ServerSettings stored = settings.get();
			if (!stored.isSetupCompleted()) {
				return;
			}
			String wanted = regionOf(stored).orElse(ServerSettings.Holidays.NONE);
			if (Objects.equals(wanted, appliedRegion())) {
				return;
			}
			boolean applied = ServerSettings.Holidays.NONE.equals(wanted) || holidays.platformCalendar()
					.map(calendar -> wanted.equals(calendar.getRules())
							|| holidays.followPlatformRegion(calendar, wanted))
					.orElseGet(() -> holidays.createPlatformCalendar(wanted) != null);
			// Not applied (the calendar is importing, or the instance is full): the next start or save
			// tries again.
			if (applied) {
				mongo.upsert(Query.query(Criteria.where("_id").is(STATE_ID)),
						new Update().set("appliedRegion", wanted), STATE);
			}
		}
		catch (RuntimeException ex) {
			log.warn("[availability] could not apply the platform's holiday region: {}", ex.getClass().getName());
		}
		finally {
			platform.unlock();
		}
	}

	private String appliedRegion() {
		Document state = mongo.findById(STATE_ID, Document.class, STATE);
		return state == null ? null : state.getString("appliedRegion");
	}

	/** The platform's region: the one chosen, or the country of the zone; empty for none. */
	Optional<String> regionOf(ServerSettings stored) {
		String chosen = stored.getHolidays() == null ? null : stored.getHolidays().getRegion();
		if (ServerSettings.Holidays.NONE.equalsIgnoreCase(Objects.toString(chosen, ""))) {
			return Optional.empty();
		}
		if (chosen != null && !chosen.isBlank()) {
			return rules.normalize(chosen);
		}
		return rules.countryOf(UserZones.of(null, stored));
	}

	private Locale instanceLocale() {
		String tag = settings.get().getGeneral().getDefaultLocale();
		return tag == null || tag.isBlank() ? Locale.GERMAN : Locale.forLanguageTag(tag);
	}
}
