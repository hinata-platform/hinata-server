package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.availability.Holiday;
import com.ahmadre.hinata.availability.HolidayCalendar;
import com.ahmadre.hinata.availability.TimeOff;
import com.ahmadre.hinata.availability.WorkingSchedule;
import com.ahmadre.hinata.me.TimePreferences;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Target reminders against a real database (HIN-92): the claim is a unique insert, and whether it
 * holds under two runs at once is a property of MongoDB, not of the code.
 *
 * <p>2026-09-07 is a Monday. Every person has the instance default of eight hours Monday to
 * Friday unless a test gives them a pattern.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true",
		"hinata.time-tracking.target-reminders-enabled=true"
})
@Import(TestClock.Config.class)
@Testcontainers(disabledWithoutDocker = true)
class TimeReminderIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	static final LocalDate MONDAY = LocalDate.of(2026, 9, 7);

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimeReminders reminders;
	@Autowired
	private UserRepository users;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private NotificationRepository notifications;
	@Autowired
	private SettingsService settings;
	@Autowired
	private TestClock clock;
	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping routes;

	@BeforeEach
	void clean() {
		for (Class<?> type : List.of(User.class, WorkItem.class, Notification.class, TimeMark.class,
				WorkingSchedule.class, TimeOff.class, Holiday.class, HolidayCalendar.class)) {
			mongo.remove(new Query(), type);
		}
		settings.save(new ServerSettings());
		clock.set(TestClock.START);
	}

	@Test
	void remindsAtFivePmOnThePersonsOwnClock() {
		User berlin = person("berlin", "Europe/Berlin", 480, null);
		User tokyo = person("tokyo", "Asia/Tokyo", 480, null);
		User west = person("west", "Etc/GMT+8", 480, null);

		clock.set(Instant.parse("2026-09-07T08:10:00Z"));
		reminders.run();
		assertThat(remindersOf(tokyo)).isEqualTo(1);
		assertThat(remindersOf(berlin)).isZero();
		assertThat(remindersOf(west)).isZero();

		clock.set(Instant.parse("2026-09-07T15:10:00Z"));
		reminders.run();
		assertThat(remindersOf(berlin)).isEqualTo(1);
		assertThat(remindersOf(tokyo)).isEqualTo(1);
		assertThat(remindersOf(west)).isZero();

		// 17:10 on Monday at UTC-8 is Tuesday morning in Tokyo and Berlin.
		clock.set(Instant.parse("2026-09-08T01:10:00Z"));
		reminders.run();
		assertThat(remindersOf(west)).isEqualTo(1);
		assertThat(remindersOf(tokyo)).isEqualTo(1);
		assertThat(remindersOf(berlin)).isEqualTo(1);
	}

	@Test
	void twoRunsAtOnceAndARestartStillRemindOnce() throws Exception {
		User berlin = person("berlin", "Europe/Berlin", 480, null);
		clock.set(Instant.parse("2026-09-07T15:10:00Z"));

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		Callable<Integer> run = () -> {
			start.await();
			return reminders.run();
		};
		Future<Integer> first = pool.submit(run);
		Future<Integer> second = pool.submit(run);
		start.countDown();
		assertThat(first.get() + second.get()).isEqualTo(1);
		pool.shutdown();

		clock.set(Instant.parse("2026-09-07T20:10:00Z"));
		assertThat(reminders.run()).isZero();
		assertThat(remindersOf(berlin)).isEqualTo(1);
	}

	@Test
	void aHolidayAWholeAbsenceOrADayWithoutHoursIsNoReminderDayButHalfAnAbsenceIs() {
		HolidayCalendar bavaria = mongo.insert(HolidayCalendar.builder().name("Bayern").build());
		mongo.insert(Holiday.builder().calendarId(bavaria.getId()).date(MONDAY).name("Feiertag").build());
		User holiday = person("holiday", "Europe/Berlin", 480, null);
		mongo.insert(WorkingSchedule.builder().userId(holiday.getId()).validFrom(LocalDate.of(2026, 1, 1))
				.minutesPerWeekday(List.of(480, 480, 480, 480, 480, 0, 0)).holidayCalendarId(bavaria.getId()).build());
		User absent = person("absent", "Europe/Berlin", 480, null);
		mongo.insert(TimeOff.builder().userId(absent.getId()).type(TimeOff.Type.VACATION)
				.from(MONDAY).to(MONDAY).halfDay(false).build());
		User half = person("half", "Europe/Berlin", 480, null);
		mongo.insert(TimeOff.builder().userId(half.getId()).type(TimeOff.Type.VACATION)
				.from(MONDAY).to(MONDAY).halfDay(true).build());
		User noHours = person("noHours", "Europe/Berlin", 480, null);
		mongo.insert(WorkingSchedule.builder().userId(noHours.getId()).validFrom(LocalDate.of(2026, 1, 1))
				.minutesPerWeekday(List.of(0, 480, 480, 480, 480, 0, 0)).build());

		clock.set(Instant.parse("2026-09-07T15:10:00Z"));
		reminders.run();

		assertThat(remindersOf(holiday)).isZero();
		assertThat(remindersOf(absent)).isZero();
		assertThat(remindersOf(noHours)).isZero();
		assertThat(remindersOf(half)).isEqualTo(1);
		// Nothing anywhere says why a day was skipped.
		assertThat(notifications.findAll()).extracting(Notification::getBody)
				.noneMatch(body -> body.toLowerCase().contains("absen") || body.toLowerCase().contains("holiday"));
	}

	@Test
	void aReachedTargetIsNoReminderAndAShortDaySaysWhatIsMissing() {
		User done = person("done", "Europe/Berlin", 480, null);
		User shortDay = person("short", "Europe/Berlin", 480, null);
		record(done, MONDAY, 480);
		record(shortDay, MONDAY, 300);
		record(shortDay, MONDAY.minusDays(1), 600);

		clock.set(Instant.parse("2026-09-07T15:10:00Z"));
		reminders.run();

		assertThat(remindersOf(done)).isZero();
		assertThat(notificationsOf(shortDay)).singleElement().satisfies(sent -> {
			assertThat(sent.getBody()).isEqualTo("You still need 3 h to reach your daily target of 8 h.");
			assertThat(sent.getLink()).isEqualTo("/time");
		});
	}

	@Test
	void theWeekReminderComesOnFridayAfternoonOverTheWholeWeek() {
		User week = person("week", "Europe/Berlin", null, 2400);
		for (int day = 0; day < 4; day++) {
			record(week, MONDAY.plusDays(day), 480);
		}

		clock.set(Instant.parse("2026-09-10T14:10:00Z"));
		reminders.run();
		assertThat(remindersOf(week)).isZero();

		clock.set(Instant.parse("2026-09-11T14:10:00Z"));
		reminders.run();
		assertThat(notificationsOf(week)).singleElement().extracting(Notification::getBody)
				.isEqualTo("You still need 8 h this week to reach your target of 40 h.");
	}

	@Test
	void withThePolicyOffTheRunDoesNothing() {
		person("berlin", "Europe/Berlin", 480, null);
		ServerSettings off = new ServerSettings();
		off.setTimeTracking(new ServerSettings.TimeTracking());
		off.getTimeTracking().setTargetRemindersEnabled(false);
		settings.save(off);
		clock.set(Instant.parse("2026-09-07T15:10:00Z"));

		assertThat(reminders.run()).isZero();
		assertThat(mongo.count(new Query(), TimeMark.class)).isZero();
		assertThat(notifications.count()).isZero();
	}

	@Test
	void nothingListsWhoWasRemindedOrWhoFellShort() {
		User shortDay = person("short", "Europe/Berlin", 480, null);
		record(shortDay, MONDAY, 60);
		clock.set(Instant.parse("2026-09-07T15:10:00Z"));
		reminders.run();

		assertThat(routes.getHandlerMethods().keySet())
				.flatExtracting(info -> List.copyOf(info.getPatternValues()))
				.noneMatch(pattern -> pattern.toLowerCase().contains("remind"));
		// A mark holds its key and its time, and nothing about the day it was taken for. _class is the
		// type hint Spring Data writes with an entity insert: the name TimeMark, the same on every mark.
		assertThat(mongo.findAll(Document.class, "time_marks"))
				.allSatisfy(mark -> assertThat(mark.keySet()).isSubsetOf("_id", "at", "_class"));
	}

	private User person(String name, String zone, Integer daily, Integer weekly) {
		TimePreferences prefs = new TimePreferences();
		prefs.setDailyTargetMinutes(daily);
		prefs.setWeeklyTargetMinutes(weekly);
		return users.save(User.builder().username(name).displayName(name).email(name + "@example.test")
				.active(true).locale("en").timezone(zone).timePreferences(prefs.sanitized()).build());
	}

	private void record(User person, LocalDate day, int minutes) {
		workItems.save(WorkItem.builder().userId(person.getId()).date(day).durationMinutes(minutes).build());
	}

	private List<Notification> notificationsOf(User person) {
		return notifications.findAll().stream()
				.filter(sent -> person.getId().equals(sent.getUserId()))
				.filter(sent -> sent.getType() == Notification.Type.TIME_TARGET_REMINDER)
				.toList();
	}

	private long remindersOf(User person) {
		return notificationsOf(person).size();
	}
}
