package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.availability.AvailabilityController;
import com.ahmadre.hinata.availability.HolidayController;
import com.ahmadre.hinata.availability.TimeOff;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * R9 of stage 10 (HIN-91): a holiday, an absence and a day without planned hours are marked, and
 * time on them is recorded like on any other day.
 *
 * <p>§ 9 ArbZG forbids the work, not its record, and § 16 Abs. 2 ArbZG wants Sunday and holiday
 * work recorded; a refusal would suppress exactly the record EuGH C-55/18 and BAG 1 ABR 22/21 ask
 * for. So every surface that writes time is tried on each of the three days: the list, the
 * calendar and the timesheet, which all write through {@code /api/v1/time/entries}, and a stopped
 * timer. {@code ModuleBoundaryTest} holds the structure behind it.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Import(TestClock.Config.class)
@Testcontainers(disabledWithoutDocker = true)
class AvailabilityMarkingIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	/** Wednesday, 23 December 2026, noon. */
	private static final Instant NOW = Instant.parse("2026-12-23T12:00:00Z");
	/** Wednesday: a holiday of the default calendar. */
	private static final LocalDate HOLIDAY = LocalDate.of(2026, 12, 23);
	/** Tuesday: the member is on vacation. */
	private static final LocalDate ABSENT = LocalDate.of(2026, 12, 22);
	/** Monday: no planned hours in the member's pattern. */
	private static final LocalDate NO_HOURS = LocalDate.of(2026, 12, 21);

	private static final List<LocalDate> MARKED = List.of(HOLIDAY, ABSENT, NO_HOURS);

	@Autowired
	private TestClock clock;
	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SettingsService settings;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;
	@Autowired
	private TimeEntryController entriesApi;
	@Autowired
	private TimeTrackingService timeTracking;
	@Autowired
	private TimerService timers;
	@Autowired
	private AvailabilityController availability;
	@Autowired
	private HolidayController holidayApi;
	@Autowired
	private TimeHintsController hintsApi;
	@MockitoBean
	private CurrentUser currentUser;

	private User member;
	private Project project;

	@BeforeEach
	void seed() {
		for (String collection : List.of("projects", "users", "work_items", "running_timers", "working_schedules",
				"time_off", "holiday_calendars", "holidays", "audit_log", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		clock.set(NOW);
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		current.setTimeTracking(block);
		settings.save(current);

		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadIds(new ArrayList<>()).memberIds(new ArrayList<>()).build());
		member = user("member", Role.MEMBER);
		User admin = user("admin", Role.ADMIN);

		as(admin);
		HolidayController.CalendarResponse calendar = holidayApi.createCalendar(
				new HolidayController.CalendarRequest("Deutschland", null, null, true));
		holidayApi.addHoliday(new HolidayController.HolidayRequest(calendar.id(), HOLIDAY, "Testfeiertag", null));

		as(member);
		availability.createTimeOff(new AvailabilityController.TimeOffRequest(null, TimeOff.Type.VACATION, ABSENT,
				ABSENT, null, null));
		availability.saveSchedule(null, new AvailabilityController.PatternRequest(LocalDate.of(2026, 12, 1),
				List.of(0, 480, 480, 480, 480, 0, 0), null));
	}

	private User user(String name, Role role) {
		User saved = users.save(User.builder().email(name + "@example.org").username(name).displayName(name)
				.roles(Set.of(role)).active(true).timezone("UTC").locale("en").build());
		project.getMemberIds().add(saved.getId());
		project = projects.save(project);
		return saved;
	}

	private void as(User user) {
		when(currentUser.require()).thenReturn(users.findById(user.getId()).orElseThrow());
	}

	private WorkItem onlyEntryOn(LocalDate day) {
		return mongo.findOne(Query.query(Criteria.where("userId").is(member.getId()).and("date").is(day)),
				WorkItem.class);
	}

	@Test
	void theCalendarMarksTheHolidayTheAbsenceAndTheDayWithoutHours() {
		as(member);

		TimeEntryController.CalendarResponse week = entriesApi.calendar(NO_HOURS, NO_HOURS.plusDays(6));

		assertThat(week.holidays()).extracting(TimeCalendarLayers.HolidayDay::date).containsExactly(HOLIDAY);
		assertThat(week.absences()).singleElement().satisfies(absence -> {
			assertThat(absence.type()).isEqualTo("VACATION");
			assertThat(absence.from()).isEqualTo(ABSENT);
		});
		assertThat(week.scheduledMinutes()).containsEntry(NO_HOURS, 0).containsEntry(HOLIDAY, 480);
	}

	@Test
	void anEntryOnAMarkedDayIsCreatedChangedAndDeletedLikeAnyOther() {
		as(member);
		for (LocalDate day : MARKED) {
			entriesApi.create(new TimeEntryController.TimeEntryRequest(project.getId(), null, 60, day, null,
					"worked on a marked day", null, null, List.of(), null));
			WorkItem created = onlyEntryOn(day);
			assertThat(created).as("created on %s", day).isNotNull();

			WorkItem changed = timeTracking.update(created.getId(), new TimeTrackingService.WorkItemPatch(90, null, null,
					null, false, null, false, null, null, null), member);
			assertThat(changed.getDurationMinutes()).as("changed on %s", day).isEqualTo(90);

			entriesApi.delete(created.getId());
			assertThat(onlyEntryOn(day)).as("deleted on %s", day).isNull();
		}
	}

	@Test
	void aTimerStoppedOnAMarkedDayFilesItsEntry() {
		as(member);
		for (LocalDate day : MARKED) {
			clock.set(day.atTime(9, 0).toInstant(ZoneOffset.UTC));
			timers.start(TimerService.StartDraft.stopwatch(new TimerService.TimerDraft(project.getId(), null,
					"timed on a marked day", null, List.of(), null)), member);
			clock.advance(Duration.ofMinutes(45));
			timers.stop(new TimerService.StopRequest(null, null, project.getId(), null, "timed on a marked day", null,
					List.of(), null), member);
		}

		List<WorkItem> filed = mongo.find(Query.query(Criteria.where("userId").is(member.getId())), WorkItem.class);

		assertThat(filed).extracting(WorkItem::getDate).containsExactlyInAnyOrderElementsOf(MARKED);
		assertThat(filed).allSatisfy(item -> assertThat(item.getDurationMinutes()).isEqualTo(45));
	}

	@Test
	void anEntryOnTheHolidayIsNamedInTheWorkingTimeHints() {
		ServerSettings current = settings.get();
		current.getTimeTracking().setArbzgHintsEnabled(true);
		settings.save(current);
		as(member);
		entriesApi.create(new TimeEntryController.TimeEntryRequest(project.getId(), null, 60, HOLIDAY, null,
				"worked on the holiday", null, null, List.of(), null));

		assertThat(hintsApi.hints(NO_HOURS, NO_HOURS.plusDays(6)).hints()).singleElement().satisfies(hint -> {
			assertThat(hint.kind()).isEqualTo(WorkingTimeHints.Kind.HOLIDAY_WORK);
			assertThat(hint.date()).isEqualTo(HOLIDAY);
		});
	}

	@Test
	void noSourceOfTheModuleButItsReadersNamesAvailability() throws IOException {
		// The negative grep beside the ArchUnit rule: a fully qualified name in a class
		// would reach availability without an import to see.
		Set<String> readers = Set.of("TimeCalendarLayers.java", "TimeHintsService.java",
				"TimeAvailabilityPolicy.java");
		try (Stream<Path> files = Files.list(Path.of("src/main/java/com/ahmadre/hinata/timetracking"))) {
			List<String> naming = files
					.filter(path -> path.toString().endsWith(".java"))
					.filter(path -> !readers.contains(path.getFileName().toString()))
					.filter(path -> read(path).contains("com.ahmadre.hinata.availability"))
					.map(path -> path.getFileName().toString())
					.toList();
			assertThat(naming).isEmpty();
		}
	}

	private static String read(Path path) {
		try {
			return Files.readString(path);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
