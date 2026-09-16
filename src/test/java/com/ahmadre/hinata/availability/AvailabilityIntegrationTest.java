package com.ahmadre.hinata.availability;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.ics.IcsFetchResult;
import com.ahmadre.hinata.ics.IcsFetcher;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.me.MeService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Stage 10 (HIN-91) against a real database: patterns with their history, capacity with holidays
 * and absences, who sees and keeps whose absences, the holiday import with its caps and its
 * address rules, and what an account deletion and a data export do.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		// 32 bytes, so feed addresses can be stored.
		"hinata.ics.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@Import(AvailabilityIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class AvailabilityIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	static final Instant NOW = Instant.parse("2026-12-16T12:00:00Z");

	private static final String FEED = "https://feeds.example.org/holidays.ics";

	@TestConfiguration
	static class FrozenClock {
		@Bean
		@Primary
		Clock testClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private SettingsService settings;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;
	@Autowired
	private UserService userService;
	@Autowired
	private AvailabilityController availability;
	@Autowired
	private HolidayController holidayApi;
	@Autowired
	private HolidayService holidays;
	@Autowired
	private MeService me;
	@MockitoSpyBean
	private IcsFetcher fetcher;
	@MockitoBean
	private CurrentUser currentUser;

	private User member;
	private User lead;
	private User stranger;
	private User admin;
	private Project project;

	@BeforeEach
	void seed() {
		for (String collection : List.of("projects", "users", "teams", "issues", "work_items", "working_schedules", "time_off",
				"holiday_calendars", "holidays", "audit_log", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		policy(false);
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadIds(new ArrayList<>()).memberIds(new ArrayList<>()).build());
		member = user("member", Role.MEMBER, true);
		lead = user("lead", Role.MEMBER, true);
		stranger = user("stranger", Role.MEMBER, false);
		admin = user("admin", Role.ADMIN, false);
		project.getLeadIds().add(lead.getId());
		project = projects.save(project);
	}

	private User user(String name, Role role, boolean inProject) {
		User saved = users.save(User.builder().email(name + "@example.org").username(name).displayName(name)
				.roles(Set.of(role)).active(true).timezone("UTC").locale("en").build());
		if (inProject) {
			project.getMemberIds().add(saved.getId());
			project = projects.save(project);
		}
		return saved;
	}

	/** The module on, and whether leads see their members' entries (and so their absences). */
	private void policy(boolean leadsSeeMembers) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		block.setLeadsSeeMemberEntries(leadsSeeMembers);
		current.setTimeTracking(block);
		settings.save(current);
	}

	/** An hour on [issue] in its project, written the way [source] writes it. */
	private void worked(User user, Issue issue, LocalDate day, WorkItem.Source source) {
		mongo.insert(WorkItem.builder().userId(user.getId()).projectId(issue.getProjectId()).issueId(issue.getId())
				.date(day).durationMinutes(60).activityType("Development").source(source).build());
	}

	private Issue issue(Project in, String readableId, List<String> formerReadableIds) {
		int number = Integer.parseInt(readableId.substring(readableId.indexOf('-') + 1));
		return mongo.insert(Issue.builder().projectId(in.getId()).numberInProject(number).readableId(readableId)
				.title(readableId).formerReadableIds(new ArrayList<>(formerReadableIds)).build());
	}

	private void as(User user) {
		when(currentUser.require()).thenReturn(users.findById(user.getId()).orElseThrow());
	}

	private static LocalDate day(int month, int dayOfMonth) {
		return LocalDate.of(2026, month, dayOfMonth);
	}

	// --- patterns and capacity ------------------------------------------------------

	@Test
	void aPatternAppliesFromItsDayAndTheOneBeforeItStays() {
		as(member);
		availability.saveSchedule(null, new AvailabilityController.PatternRequest(day(12, 1),
				List.of(480, 480, 480, 480, 480, 0, 0), null));
		availability.saveSchedule(null, new AvailabilityController.PatternRequest(LocalDate.of(2027, 1, 1),
				List.of(360, 360, 360, 360, 360, 0, 0), null));

		// Monday 28 December to Sunday 3 January: four days of the old pattern, a Friday of the new.
		AvailabilityController.CapacityResponse turn = availability.capacity(day(12, 28), LocalDate.of(2027, 1, 3),
				null);

		assertThat(turn.capacityMinutes()).isEqualTo(4 * 480 + 360);
		AvailabilityController.ScheduleResponse schedule = availability.schedule(null);
		assertThat(schedule.history()).hasSize(2);
		assertThat(schedule.current().validFrom()).isEqualTo(day(12, 1));
	}

	@Test
	void withoutAPatternTheDefaultHoursAndTheDefaultCalendarApply() {
		as(admin);
		HolidayController.CalendarResponse calendar = holidayApi.createCalendar(
				new HolidayController.CalendarRequest("Deutschland", "Bundesweit", null, true));
		holidayApi.addHoliday(new HolidayController.HolidayRequest(calendar.id(), day(12, 25), "1. Weihnachtstag",
				null));
		holidayApi.addHoliday(new HolidayController.HolidayRequest(calendar.id(), day(12, 24), "Heiligabend", true));

		as(member);
		AvailabilityController.CapacityResponse week = availability.capacity(day(12, 21), day(12, 27), null);

		assertThat(week.scheduledMinutes()).isEqualTo(5 * 480);
		assertThat(week.holidayMinutes()).isEqualTo(480 + 240);
		assertThat(week.capacityMinutes()).isEqualTo(5 * 480 - 720);
		assertThat(week.holidays()).extracting(AvailabilityController.HolidayMarkResponse::name)
				.containsExactly("Heiligabend", "1. Weihnachtstag");
	}

	@Test
	void aNewDefaultReplacesTheOld_andDeletingACalendarSendsItsFollowersBackToTheDefault() {
		as(admin);
		HolidayController.CalendarResponse bavaria = holidayApi.createCalendar(
				new HolidayController.CalendarRequest("Bayern", null, null, true));
		HolidayController.CalendarResponse germany = holidayApi.createCalendar(
				new HolidayController.CalendarRequest("Deutschland", null, null, true));
		holidayApi.addHoliday(new HolidayController.HolidayRequest(bavaria.id(), day(12, 8), "Mariä Empfängnis", null));
		holidayApi.addHoliday(new HolidayController.HolidayRequest(germany.id(), day(12, 25), "1. Weihnachtstag",
				null));

		assertThat(mongo.findById(bavaria.id(), HolidayCalendar.class).getDefaultCalendar()).isNull();
		assertThat(mongo.findById(germany.id(), HolidayCalendar.class).getDefaultCalendar()).isTrue();

		as(member);
		availability.saveSchedule(null, new AvailabilityController.PatternRequest(day(12, 1),
				List.of(480, 480, 480, 480, 480, 0, 0), bavaria.id()));
		assertThat(availability.capacity(day(12, 1), day(12, 31), null).holidays())
				.extracting(AvailabilityController.HolidayMarkResponse::name).containsExactly("Mariä Empfängnis");

		as(admin);
		holidayApi.deleteCalendar(bavaria.id());

		as(member);
		assertThat(availability.schedule(null).current().holidayCalendarId()).isNull();
		assertThat(availability.capacity(day(12, 1), day(12, 31), null).holidays())
				.extracting(AvailabilityController.HolidayMarkResponse::name).containsExactly("1. Weihnachtstag");
	}

	// --- absences ---------------------------------------------------------------------

	@Test
	void anAbsenceNeedsNoReason_itsNoteIsShort_aHalfDayIsOneDay_andItLiesWithinTwoYears() {
		as(member);

		AvailabilityController.TimeOffResponse plain = availability.createTimeOff(
				new AvailabilityController.TimeOffRequest(null, TimeOff.Type.SICK, day(12, 14), day(12, 15), null, null));

		assertThat(plain.id()).isNotNull();
		assertThat(plain.note()).isNull();
		assertThatThrownBy(() -> availability.createTimeOff(new AvailabilityController.TimeOffRequest(null,
				TimeOff.Type.VACATION, day(12, 17), day(12, 17), null, "x".repeat(TimeOff.NOTE_MAX + 1))))
				.isInstanceOf(ApiException.class).hasMessage("error.availability.noteTooLong");
		assertThatThrownBy(() -> availability.createTimeOff(new AvailabilityController.TimeOffRequest(null,
				TimeOff.Type.OTHER, day(12, 17), day(12, 18), true, null)))
				.isInstanceOf(ApiException.class).hasMessage("error.availability.halfDaySingle");
		// Today is 16 December 2026: two years ahead ends on 16 December 2028.
		assertThatThrownBy(() -> availability.createTimeOff(new AvailabilityController.TimeOffRequest(null,
				TimeOff.Type.VACATION, LocalDate.of(2028, 12, 16), LocalDate.of(2028, 12, 17), null, null)))
				.isInstanceOf(ApiException.class).hasMessage("error.availability.timeOffOutOfRange");
	}

	@Test
	void aYearHoldsAtMostAHundredAbsences_countedByTheirFirstDay() {
		for (int index = 0; index < TimeOff.PER_YEAR_MAX; index++) {
			LocalDate first = LocalDate.of(2027, 1, 1).plusDays(index);
			mongo.insert(TimeOff.builder().userId(member.getId()).type(TimeOff.Type.OTHER).from(first).to(first).build());
		}
		as(member);

		assertThatThrownBy(() -> availability.createTimeOff(new AvailabilityController.TimeOffRequest(null,
				TimeOff.Type.VACATION, LocalDate.of(2027, 6, 1), LocalDate.of(2027, 6, 1), null, null)))
				.hasMessage("error.availability.timeOffPerYear");
		// Starting in December, it belongs to the year before, which has room.
		assertThat(availability.createTimeOff(new AvailabilityController.TimeOffRequest(null, TimeOff.Type.VACATION,
				day(12, 30), LocalDate.of(2027, 1, 2), null, null)).id()).isNotNull();
	}

	@Test
	void absencesAreSeenByTheOwnerAndAdmins_byLeadsOnlyWithThePolicyAndWithoutTheNote() {
		as(member);
		AvailabilityController.TimeOffResponse own = availability.createTimeOff(new AvailabilityController.TimeOffRequest(
				null, TimeOff.Type.VACATION, day(12, 21), day(12, 23), null, "Familie"));
		worked(member, issue(project, "HIN-1", List.of()), day(12, 1), WorkItem.Source.APP);
		LocalDate from = day(12, 1);
		LocalDate to = day(12, 31);
		assertThat(availability.timeOff(from, to, null, 0, 50).getContent())
				.singleElement().extracting(AvailabilityController.TimeOffResponse::note).isEqualTo("Familie");

		// Somebody who shares no project with the member.
		as(stranger);
		assertThatThrownBy(() -> availability.timeOff(from, to, member.getId(), 0, 50))
				.hasMessage("error.availability.forbidden");
		assertThatThrownBy(() -> availability.capacity(from, to, member.getId()))
				.hasMessage("error.availability.forbidden");

		// A lead, while the policy is off.
		as(lead);
		assertThatThrownBy(() -> availability.timeOff(from, to, member.getId(), 0, 50))
				.hasMessage("error.availability.forbidden");

		// A lead with the policy: type and span, never the note, and nobody outside the lead's projects.
		policy(true);
		as(lead);
		AvailabilityController.TimeOffResponse seen = availability.timeOff(from, to, member.getId(), 0, 50)
				.getContent().getFirst();
		assertThat(seen.type()).isEqualTo(TimeOff.Type.VACATION);
		assertThat(seen.from()).isEqualTo(day(12, 21));
		assertThat(seen.to()).isEqualTo(day(12, 23));
		assertThat(seen.note()).isNull();
		assertThat(seen.id()).isNull();
		assertThatThrownBy(() -> availability.timeOff(from, to, stranger.getId(), 0, 50))
				.hasMessage("error.availability.forbidden");
		// Reading absences is all a lead may do: no hours, no writes.
		assertThatThrownBy(() -> availability.capacity(from, to, member.getId()))
				.hasMessage("error.availability.forbidden");
		assertThatThrownBy(() -> availability.schedule(member.getId()))
				.hasMessage("error.availability.forbidden");
		assertThatThrownBy(() -> availability.createTimeOff(new AvailabilityController.TimeOffRequest(member.getId(),
				TimeOff.Type.OTHER, day(12, 28), day(12, 28), null, null)))
				.hasMessage("error.availability.forbidden");
		assertThatThrownBy(() -> availability.updateTimeOff(own.id(), new AvailabilityController.TimeOffPatchRequest(
				TimeOff.Type.SICK, null, null, null, null)))
				.hasMessage("error.notFound");

		// An administrator sees everything.
		as(admin);
		assertThat(availability.timeOff(from, to, member.getId(), 0, 50).getContent().getFirst().note())
				.isEqualTo("Familie");
	}

	@Test
	void aProjectSomebodyWasOnlyAddedToShowsItsLeadNothing_andALeadSeesASickDayAsAway() {
		as(member);
		availability.createTimeOff(new AvailabilityController.TimeOffRequest(null, TimeOff.Type.SICK, day(12, 14),
				day(12, 15), null, null));
		policy(true);
		LocalDate from = day(12, 1);
		LocalDate to = day(12, 31);

		// Anybody can create a project, lead it and add anybody to it. That alone shows nothing.
		Project mine = projects.save(Project.builder().key("MINE").name("Mine")
				.leadIds(new ArrayList<>(List.of(stranger.getId())))
				.memberIds(new ArrayList<>(List.of(stranger.getId(), member.getId()))).build());
		as(stranger);
		assertThatThrownBy(() -> availability.timeOff(from, to, member.getId(), 0, 50))
				.hasMessage("error.availability.forbidden");

		// Nor does time the member did not put there themselves: a commit can name anybody as its
		// author, a member of another project can move an issue with their hours into this one, and an
		// issue moved in and deleted leaves the hours on the project with no issue.
		worked(member, issue(mine, "MINE-1", List.of()), day(12, 2), WorkItem.Source.SMART_COMMIT);
		worked(member, issue(mine, "MINE-2", List.of("HIN-7")), day(12, 3), WorkItem.Source.APP);
		mongo.insert(WorkItem.builder().userId(member.getId()).projectId(mine.getId()).date(day(12, 4))
				.durationMinutes(60).activityType("Development").build());
		assertThatThrownBy(() -> availability.timeOff(from, to, member.getId(), 0, 50))
				.hasMessage("error.availability.forbidden");

		// Nor time on the lead's own project from more than a year ago.
		worked(member, issue(project, "HIN-2", List.of()),
				NOW.atZone(ZoneOffset.UTC).toLocalDate().minusYears(1).minusDays(1), WorkItem.Source.APP);
		as(lead);
		assertThatThrownBy(() -> availability.timeOff(from, to, member.getId(), 0, 50))
				.hasMessage("error.availability.forbidden");

		// Recent time the member recorded on an issue of the project does, and the sick day reads as a
		// day away.
		worked(member, issue(project, "HIN-1", List.of()), day(12, 1), WorkItem.Source.TIMER);
		assertThat(availability.timeOff(from, to, member.getId(), 0, 50).getContent()).singleElement()
				.extracting(AvailabilityController.TimeOffResponse::type).isEqualTo(TimeOff.Type.OTHER);
	}

	@Test
	void anAdministratorKeepingSomebodysAbsenceIsRecorded_withoutItsTypeOrNote() {
		as(member);
		availability.createTimeOff(new AvailabilityController.TimeOffRequest(null, TimeOff.Type.VACATION, day(12, 14),
				day(12, 14), null, "Eigene"));
		as(admin);
		availability.createTimeOff(new AvailabilityController.TimeOffRequest(member.getId(), TimeOff.Type.SICK,
				day(12, 15), day(12, 15), null, "Arzt"));

		List<AuditLog> records = mongo.find(Query.query(Criteria.where("action")
				.is(AuditAction.AVAILABILITY_TIME_OFF_CHANGED)), AuditLog.class);

		assertThat(records).singleElement().satisfies(record -> {
			assertThat(record.getMetadata()).containsEntry("change", "created").doesNotContainKey("type");
			assertThat(record.getMetadata().values()).doesNotContain("Arzt", "SICK");
		});
	}

	@Test
	void patternsAndAbsencesAreDeletedByTheirOwnerOrAnAdministrator_andNobodyElseLearnsTheyExist() {
		as(member);
		AvailabilityController.PatternResponse pattern = availability.saveSchedule(null,
				new AvailabilityController.PatternRequest(day(12, 1), List.of(480, 480, 480, 480, 480, 0, 0), null));
		AvailabilityController.TimeOffResponse absence = availability.createTimeOff(
				new AvailabilityController.TimeOffRequest(null, TimeOff.Type.VACATION, day(12, 21), day(12, 21), null,
						null));

		as(stranger);
		assertThatThrownBy(() -> availability.deleteSchedule(pattern.id())).hasMessage("error.notFound");
		assertThatThrownBy(() -> availability.deleteTimeOff(absence.id())).hasMessage("error.notFound");

		as(member);
		availability.deleteSchedule(pattern.id());
		as(admin);
		availability.deleteTimeOff(absence.id());

		Query ofMember = Query.query(Criteria.where("userId").is(member.getId()));
		assertThat(mongo.count(ofMember, WorkingSchedule.class)).isZero();
		assertThat(mongo.count(ofMember, TimeOff.class)).isZero();
		// The administrator's delete is on record; the owner's own is not.
		assertThat(mongo.count(Query.query(Criteria.where("action").is(AuditAction.AVAILABILITY_TIME_OFF_CHANGED)),
				AuditLog.class)).isOne();
	}

	// --- holidays -----------------------------------------------------------------------

	@Test
	void importingAFeedTwiceChangesNothing_andAYearHoldsAHundredHolidays() throws Exception {
		doReturn(CompletableFuture.completedFuture(new IcsFetchResult(IcsFetchResult.Outcome.FETCHED,
				feedOfDays(2026, 120), "\"v1\"", null, null, 200)))
				.when(fetcher).fetch(eq(FEED), any(), any());
		as(admin);
		HolidayController.CalendarResponse created = holidayApi.createCalendar(
				new HolidayController.CalendarRequest("Feed", null, FEED, false));

		assertThat(created.feedHost()).isEqualTo("feeds.example.org");
		assertThat(mongo.findById(created.id(), HolidayCalendar.class).getSource())
				.startsWith("v1:").doesNotContain("feeds.example.org");

		HolidayCalendar first = holidays.importYear(admin, created.id(), 2026).get(20, TimeUnit.SECONDS);
		assertThat(first.getImportState()).isEqualTo(HolidayCalendar.ImportState.DONE);
		assertThat(first.getLastImport()).isEqualTo(new HolidayCalendar.ImportSummary(2026, 100, 0, 0, 20, false));

		HolidayCalendar second = holidays.importYear(admin, created.id(), 2026).get(20, TimeUnit.SECONDS);
		assertThat(second.getLastImport()).isEqualTo(new HolidayCalendar.ImportSummary(2026, 0, 0, 100, 20, false));
		assertThat(holidayApi.holidays(created.id(), 2026)).hasSize(100);
		assertThat(mongo.count(Query.query(Criteria.where("calendarId").is(created.id())), Holiday.class))
				.isEqualTo(100);
	}

	@Test
	void aDayRenamedByHandKeepsItsName_whenTheFeedIsImportedAgain() throws Exception {
		doReturn(CompletableFuture.completedFuture(new IcsFetchResult(IcsFetchResult.Outcome.FETCHED,
				feedOfDays(2026, 3), null, null, null, 200)))
				.when(fetcher).fetch(eq(FEED), any(), any());
		as(admin);
		HolidayController.CalendarResponse created = holidayApi.createCalendar(
				new HolidayController.CalendarRequest("Feed", null, FEED, false));
		holidays.importYear(admin, created.id(), 2026).get(20, TimeUnit.SECONDS);
		String newYear = holidayApi.holidays(created.id(), 2026).getFirst().id();
		holidayApi.updateHoliday(newYear, new HolidayController.HolidayPatchRequest(null, "Neujahr", null));

		HolidayCalendar again = holidays.importYear(admin, created.id(), 2026).get(20, TimeUnit.SECONDS);

		assertThat(again.getLastImport()).isEqualTo(new HolidayCalendar.ImportSummary(2026, 0, 0, 3, 0, false));
		assertThat(holidayApi.holidays(created.id(), 2026)).extracting(HolidayController.HolidayResponse::name)
				.containsExactly("Neujahr", "Tag 1", "Tag 2");
	}

	@Test
	void aPrivateHostIsRefused_asAnAddressAndWhenFetched() throws Exception {
		as(admin);
		assertThatThrownBy(() -> holidayApi.createCalendar(
				new HolidayController.CalendarRequest("Literal", null, "https://127.0.0.1/feed.ics", false)))
				.hasMessage("error.ics.hostNotAllowed");

		HolidayController.CalendarResponse local = holidayApi.createCalendar(
				new HolidayController.CalendarRequest("Local", null, "https://localhost/feed.ics", false));
		HolidayCalendar result = holidays.importYear(admin, local.id(), 2026).get(30, TimeUnit.SECONDS);

		assertThat(result.getImportState()).isEqualTo(HolidayCalendar.ImportState.FAILED);
		assertThat(result.getLastImportError()).isEqualTo("error.ics.hostNotAllowed");
		assertThat(mongo.count(new Query(), Holiday.class)).isZero();
	}

	// --- the person's rights ----------------------------------------------------------------

	@Test
	void theExportCarriesPatternsAndAbsences_andDeletingTheAccountRemovesThem() {
		as(member);
		availability.saveSchedule(null, new AvailabilityController.PatternRequest(day(12, 1),
				List.of(480, 480, 480, 480, 0, 0, 0), null));
		availability.createTimeOff(new AvailabilityController.TimeOffRequest(null, TimeOff.Type.VACATION, day(12, 14),
				day(12, 14), null, "Reise"));

		Map<String, Object> part = partOf(me.exportData(users.findById(member.getId()).orElseThrow()),
				"availability");
		assertThat((List<?>) part.get("workingTimePatterns")).hasSize(1);
		assertThat((List<?>) part.get("absences")).hasSize(1);

		userService.delete(users.findById(member.getId()).orElseThrow());

		Query ofMember = Query.query(Criteria.where("userId").is(member.getId()));
		assertThat(mongo.count(ofMember, WorkingSchedule.class)).isZero();
		assertThat(mongo.count(ofMember, TimeOff.class)).isZero();
	}

	/** The module's part of an export, wherever the export nests the parts. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> partOf(Map<String, Object> export, String key) {
		if (export.get(key) instanceof Map<?, ?> part) {
			return (Map<String, Object>) part;
		}
		for (Object value : export.values()) {
			if (value instanceof Map<?, ?> nested && nested.get(key) instanceof Map<?, ?> part) {
				return (Map<String, Object>) part;
			}
		}
		throw new AssertionError("no " + key + " in the export: " + export.keySet());
	}

	private static byte[] feedOfDays(int year, int days) {
		StringBuilder ics = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:test\r\n");
		for (int index = 0; index < days; index++) {
			LocalDate date = LocalDate.of(year, 1, 1).plusDays(index);
			ics.append("BEGIN:VEVENT\r\nUID:day-").append(index)
					.append("\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;VALUE=DATE:")
					.append(date.format(DateTimeFormatter.BASIC_ISO_DATE))
					.append("\r\nDTEND;VALUE=DATE:").append(date.plusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE))
					.append("\r\nSUMMARY:Tag ").append(index).append("\r\nEND:VEVENT\r\n");
		}
		return ics.append("END:VCALENDAR\r\n").toString().getBytes(StandardCharsets.UTF_8);
	}
}
