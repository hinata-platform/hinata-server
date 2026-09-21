package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.availability.TimeOff;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.common.TimePolicy;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.availability.Holiday;
import com.ahmadre.hinata.availability.HolidayCalendar;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamActivity;
import com.ahmadre.hinata.team.TeamAttachmentRepair;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.team.TeamRole;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.timetracking.WorkItem;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The team absence calendar and its capacity band against a real database (HIN-118).
 *
 * <p>The negative cases come first on purpose: a calendar of other people's absences that is on by
 * accident, names a sick day, or lists somebody through a project they never worked on is the
 * failure this stage exists to prevent.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Import(TeamAbsenceIntegrationTest.FrozenClock.class)
@Testcontainers(disabledWithoutDocker = true)
class TeamAbsenceIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	/** Monday, 15 June 2026. */
	static final Instant NOW = Instant.parse("2026-06-15T09:00:00Z");
	private static final LocalDate MON = LocalDate.of(2026, 6, 15);

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
	private UserRepository users;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private TeamService teams;
	@Autowired
	private TeamRepository teamRepository;
	@Autowired
	private TeamAttachmentRepair repair;
	@Autowired
	private TeamAbsenceService calendars;
	@Autowired
	private TimeOffTypeService types;
	@Autowired
	private TimeOffTypeRepository typeRepository;
	/** Stands in for the wire: every field a record has is written, null or not. */
	private static final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

	private User lead;
	private User ann;
	private User bob;
	private User eve;
	private User mallory;
	private User admin;
	private Project project;
	private TimeOffType vacation;
	private TimeOffType sick;
	private TimeOffType secret;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "projects", "issues", "work_items", "teams", "team_activity",
				"time_off_types", "time_off_requests", "time_off", "working_schedules", "holidays",
				"holiday_calendars", "server_settings", "migrations")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		lead = user("lead", Role.MEMBER);
		ann = user("ann", Role.MEMBER);
		bob = user("bob", Role.MEMBER);
		eve = user("eve", Role.MEMBER);
		mallory = user("mallory", Role.MEMBER);
		admin = user("admin", Role.ADMIN);
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadId(lead.getId()).leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), ann.getId(), bob.getId(), eve.getId()))).build());
		Issue issue = mongo.insert(Issue.builder().projectId(project.getId()).numberInProject(1).readableId("HIN-1")
				.title("HIN-1").formerReadableIds(new ArrayList<>()).build());
		// Ann and Bob worked on the project; Eve is a member who never recorded anything there.
		for (User worker : List.of(ann, bob, lead)) {
			mongo.insert(WorkItem.builder().userId(worker.getId()).projectId(project.getId()).issueId(issue.getId())
					.date(MON.minusDays(10)).durationMinutes(60).activityType("Development")
					.source(WorkItem.Source.APP).build());
		}
		policy(TimePolicy.AbsenceCalendar.OFF);
		vacation = visible(types.byKey(TimeOffType.SYSTEM_VACATION).orElseThrow(), TimeOffType.Visibility.TYPE);
		sick = visible(types.byKey(TimeOffType.SYSTEM_SICK).orElseThrow(), TimeOffType.Visibility.TYPE);
		secret = typeRepository.save(TimeOffType.builder().key("secret").name("Secret")
				.kind(TimeOffType.Kind.SPECIAL).visibility(TimeOffType.Visibility.SELF_ONLY).active(true).build());

		absence(ann, vacation, TimeOff.Type.VACATION, 1, 2);
		absence(bob, sick, TimeOff.Type.SICK, 3, 3);
		absence(ann, secret, TimeOff.Type.OTHER, 4, 4);
		mongo.insert(TimeOffRequest.builder().userId(bob.getId()).typeId(vacation.getId())
				.from(MON.plusDays(7)).to(MON.plusDays(8)).milliDays(2 * TimeOffType.DAY)
				.status(TimeOffRequest.Status.SUBMITTED).approverIds(new ArrayList<>(List.of(admin.getId())))
				.history(new ArrayList<>()).build());
	}

	// --- off by default ---------------------------------------------------------------

	@Test
	void withThePolicyOffNeitherReadAnswersForAnybody() {
		for (User reader : List.of(lead, ann, admin)) {
			assertThatThrownBy(() -> calendars.calendar(reader, null, project.getId(), MON, MON.plusDays(6), false, 0, 50))
					.isInstanceOfSatisfying(ApiException.class, error -> {
						assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
						assertThat(error.getMessage()).isEqualTo(AbsenceManagementGate.DISABLED_KEY);
					});
			assertThatThrownBy(() -> calendars.band(reader, null, project.getId(), MON, MON.plusDays(6), null))
					.hasMessage(AbsenceManagementGate.DISABLED_KEY);
		}
	}

	@Test
	void withTheModuleOffThePolicyIsNotInForceEither() {
		ServerSettings current = settings.get();
		current.getTimeTracking().setAbsenceCalendarVisibility(TimePolicy.AbsenceCalendar.TYPE);
		current.getTimeTracking().setAbsenceManagementEnabled(false);
		settings.save(current);

		assertThatThrownBy(() -> calendars.calendar(lead, null, project.getId(), MON, MON.plusDays(6), false, 0, 50))
				.hasMessage(AbsenceManagementGate.DISABLED_KEY);
	}

	// --- who is in a group -------------------------------------------------------------

	@Test
	void nobodyAppearsThroughAProjectTheyNeverWorkedOn() {
		policy(TimePolicy.AbsenceCalendar.BUSY_ONLY);

		assertThat(names(calendars.calendar(ann, null, project.getId(), MON, MON.plusDays(6), false, 0, 50)))
				.containsExactly("ann", "bob", "lead");

		// Mallory creates a project, leads it and adds Ann without asking: Ann is not in it.
		Project own = projects.save(Project.builder().key("MAL").name("Mine")
				.leadId(mallory.getId()).leadIds(new ArrayList<>(List.of(mallory.getId())))
				.memberIds(new ArrayList<>(List.of(mallory.getId(), ann.getId()))).build());
		assertThat(names(calendars.calendar(mallory, null, own.getId(), MON, MON.plusDays(6), false, 0, 50)))
				.containsExactly("mallory");

		// And a project she cannot see is not there at all.
		assertThatThrownBy(() -> calendars.calendar(mallory, null, project.getId(), MON, MON.plusDays(6), false, 0, 50))
				.isInstanceOfSatisfying(ApiException.class,
						error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

		// A keeper reads the whole project, the member who never worked there included.
		assertThat(names(calendars.calendar(admin, null, project.getId(), MON, MON.plusDays(6), false, 0, 50)))
				.containsExactly("ann", "bob", "eve", "lead");
	}

	@Test
	void aTeamManagerCannotAttachAProjectTheyDoNotLead() {
		Team own = teams.create(mallory, "Mallory", "MALT", null, 70, "hexagon");

		assertThatThrownBy(() -> teams.attachProjects(own, mallory, List.of(project.getId())))
				.hasMessage("error.project.notLead");
		assertThat(projects.findById(project.getId()).orElseThrow().getMemberIds()).doesNotContain(mallory.getId());
	}

	// --- how much of it -----------------------------------------------------------------

	@Test
	void busyOnlyNeverPutsATypeOnTheWire() throws Exception {
		policy(TimePolicy.AbsenceCalendar.BUSY_ONLY);

		TeamAbsenceService.CalendarPage page =
				calendars.calendar(lead, null, project.getId(), MON, MON.plusDays(13), false, 0, 50);

		TeamAbsenceService.Row annRow = row(page, "ann");
		assertThat(annRow.entries()).hasSize(1);
		assertThat(annRow.entries().getFirst().typeId()).isNull();
		String wire = json.writeValueAsString(page);
		assertThat(wire).doesNotContain(vacation.getId(), sick.getId(), secret.getId(), "\"vacation\"", "\"sick\"",
				"Secret");
	}

	@Test
	void typeNamesTheTypeButSicknessStaysAwayAndAPrivateTypeStaysPrivate() throws Exception {
		policy(TimePolicy.AbsenceCalendar.TYPE);

		TeamAbsenceService.CalendarPage page =
				calendars.calendar(lead, null, project.getId(), MON, MON.plusDays(13), false, 0, 50);

		assertThat(row(page, "ann").entries()).singleElement()
				.satisfies(entry -> assertThat(entry.typeId()).isEqualTo(vacation.getId()));
		TeamAbsenceService.Row bobRow = row(page, "bob");
		assertThat(bobRow.entries()).extracting(TeamAbsenceService.Entry::requested).containsExactly(false, true);
		assertThat(bobRow.entries().getFirst().typeId()).isNull();
		assertThat(bobRow.entries().get(1).typeId()).isEqualTo(vacation.getId());
		assertThat(json.writeValueAsString(page)).doesNotContain(sick.getId(), "\"sick\"", "Secret");

		// Ann's own row shows her private type — it is hers — and even she sees no sick day as one.
		TeamAbsenceService.CalendarPage own =
				calendars.calendar(ann, null, project.getId(), MON, MON.plusDays(13), false, 0, 50);
		assertThat(row(own, "ann").entries()).extracting(TeamAbsenceService.Entry::typeId)
				.containsExactly(vacation.getId(), secret.getId());
		assertThat(row(own, "bob").entries().getFirst().typeId()).isNull();
	}

	@Test
	void awayOnlyListsWhoHasSomethingToShowThatDay() {
		policy(TimePolicy.AbsenceCalendar.BUSY_ONLY);

		assertThat(names(calendars.calendar(lead, null, null, MON.plusDays(1), MON.plusDays(1), true, 0, 5)))
				.containsExactly("ann");
		// A day with only a private absence shows nobody to anybody else.
		assertThat(calendars.calendar(lead, null, null, MON.plusDays(4), MON.plusDays(4), true, 0, 5).content())
				.isEmpty();
	}

	// --- the band -------------------------------------------------------------------------

	@Test
	void approvedLowersCapacityRequestedOnlyShows() {
		policy(TimePolicy.AbsenceCalendar.BUSY_ONLY);

		TeamAbsenceService.Band band = calendars.band(lead, null, project.getId(), MON, MON.plusDays(13), null);

		// Three people who worked on the project, eight hours each on a weekday.
		assertThat(band.people()).isEqualTo(3);
		assertThat(band.buckets().get(0).capacityMinutes()).isEqualTo(3 * 480);
		assertThat(band.buckets().get(1).capacityMinutes()).isEqualTo(2 * 480);
		assertThat(band.buckets().get(1).away()).isEqualTo(1);
		// Bob's open request next Monday: counted as requested, and capacity untouched.
		assertThat(band.buckets().get(7).capacityMinutes()).isEqualTo(3 * 480);
		assertThat(band.buckets().get(7).requested()).isEqualTo(1);
		assertThat(band.buckets().get(7).away()).isZero();

		TeamAbsenceService.Band weeks =
				calendars.band(lead, null, project.getId(), MON, MON.plusDays(13), TeamAbsenceService.Resolution.WEEK);
		assertThat(weeks.buckets()).hasSize(2);
		assertThat(weeks.buckets().getFirst().scheduledMinutes()).isEqualTo(3 * 5 * 480);
	}

	@Test
	void withoutLeadsSeeingAbsencesTheBandIsForKeepersOnly() {
		// The band counts private and sick days into its capacity; a lead may read that sum only
		// where they may see their members' absences anyway.
		policy(TimePolicy.AbsenceCalendar.TYPE, false);

		assertThatThrownBy(() -> calendars.band(lead, null, project.getId(), MON, MON.plusDays(6), null))
				.hasMessage("error.availability.forbidden");
		assertThat(calendars.band(admin, null, project.getId(), MON, MON.plusDays(6), null).people()).isEqualTo(4);
	}

	@Test
	void aBandOverTooFewPeopleWouldBeOnePersonsHours() {
		policy(TimePolicy.AbsenceCalendar.TYPE);
		Project pair = projects.save(Project.builder().key("TWO").name("Two")
				.leadId(lead.getId()).leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), ann.getId()))).build());
		worked(ann, pair);

		assertThatThrownBy(() -> calendars.band(lead, null, pair.getId(), MON, MON.plusDays(6), null))
				.hasMessage("error.timeOff.bandTooSmall");
	}

	@Test
	void theBandCountsOnlyWhatTheCalendarShows() {
		policy(TimePolicy.AbsenceCalendar.BUSY_ONLY);

		TeamAbsenceService.Band band = calendars.band(lead, null, project.getId(), MON, MON.plusDays(6), null);

		// Friday: Ann's private type. The calendar shows nobody, so the band counts nobody away.
		assertThat(band.buckets().get(4).away()).isZero();
	}

	@Test
	void theBandIsForWhoPlans() {
		policy(TimePolicy.AbsenceCalendar.TYPE);

		assertThatThrownBy(() -> calendars.band(ann, null, project.getId(), MON, MON.plusDays(6), null))
				.hasMessage("error.availability.forbidden");
		assertThatThrownBy(() -> calendars.band(lead, null, project.getId(), MON, MON.plusDays(92), null))
				.hasMessage("error.availability.windowTooLong");
		assertThat(calendars.band(admin, null, project.getId(), MON, MON.plusDays(6), null).people()).isEqualTo(4);
	}

	@Test
	void sicknessIsOnlyAwayEvenOnOnesOwnRow() {
		policy(TimePolicy.AbsenceCalendar.TYPE);

		TeamAbsenceService.CalendarPage own =
				calendars.calendar(bob, null, project.getId(), MON, MON.plusDays(6), false, 0, 50);

		assertThat(row(own, "bob").entries()).singleElement()
				.satisfies(entry -> assertThat(entry.typeId()).isNull());
	}

	@Test
	void aHolidayNamesItselfOnlyOnOnesOwnRow() {
		policy(TimePolicy.AbsenceCalendar.TYPE);
		HolidayCalendar calendar = mongo.insert(HolidayCalendar.builder().name("NRW").defaultCalendar(true).build());
		mongo.insert(Holiday.builder().calendarId(calendar.getId()).date(MON.plusDays(3)).name("Fronleichnam")
				.halfDay(false).build());

		TeamAbsenceService.CalendarPage page =
				calendars.calendar(ann, null, project.getId(), MON, MON.plusDays(6), false, 0, 50);

		assertThat(row(page, "ann").holidays()).singleElement()
				.satisfies(holiday -> assertThat(holiday.name()).isEqualTo("Fronleichnam"));
		assertThat(row(page, "bob").holidays()).singleElement()
				.satisfies(holiday -> assertThat(holiday.name()).isNull());
	}

	@Test
	void somebodyWhoLeftAProjectIsNotBroughtBackThroughATeam() {
		policy(TimePolicy.AbsenceCalendar.BUSY_ONLY);
		User fred = user("fred", Role.MEMBER);
		worked(fred, project); // worked on HIN months ago, is no longer a member
		Team team = teams.create(lead, "Planning", "PLAN", null, 70, "hexagon");
		team = teams.attachProjects(team, lead, List.of(project.getId()));
		team = teams.addMembers(team, lead, List.of(fred.getId(), ann.getId()), TeamRole.MEMBER, null);

		assertThat(names(calendars.calendar(lead, team.getId(), null, MON, MON.plusDays(6), false, 0, 50)))
				.contains("ann", "lead").doesNotContain("fred");
		// Joining a team with no access to its project leaves a direct membership where it was.
		assertThat(projects.findById(project.getId()).orElseThrow().getMemberIds()).contains(ann.getId());
	}

	@Test
	void theRepairTakesBackAnAttachmentOnlyItsProjectsLeadCouldHaveMade() {
		Team own = teams.create(mallory, "Mallory", "MALT", null, 70, "hexagon");
		// What the old gap left behind: mallory's team holding HIN, and the activity row saying so.
		own.getProjectIds().add(project.getId());
		own = teamRepository.save(own);
		mongo.insert(TeamActivity.builder().teamId(own.getId()).actorId(mallory.getId())
				.verb(TeamActivity.Verb.ATTACHED_PROJECT).objectLabel("Hinata").build());
		Team legit = teams.create(lead, "Lead", "LEAD", null, 70, "hexagon");
		legit = teams.attachProjects(legit, lead, List.of(project.getId()));

		repair.run(null);

		assertThat(teamRepository.findById(own.getId()).orElseThrow().getProjectIds()).isEmpty();
		assertThat(teamRepository.findById(legit.getId()).orElseThrow().getProjectIds()).containsExactly(project.getId());
	}

	// --- fixtures ---------------------------------------------------------------------------

	private void worked(User who, Project on) {
		Issue issue = mongo.insert(Issue.builder().projectId(on.getId()).numberInProject(99)
				.readableId(on.getKey() + "-99").title("work").formerReadableIds(new ArrayList<>()).build());
		mongo.insert(WorkItem.builder().userId(who.getId()).projectId(on.getId()).issueId(issue.getId())
				.date(MON.minusDays(40)).durationMinutes(60).activityType("Development")
				.source(WorkItem.Source.APP).build());
	}

	/** The calendar at [level], with leads seeing their members' absences as the band requires. */
	private void policy(TimePolicy.AbsenceCalendar level) {
		policy(level, true);
	}

	private void policy(TimePolicy.AbsenceCalendar level, boolean leadsSee) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = new ServerSettings.TimeTracking();
		block.setAdvancedEnabled(true);
		block.setAbsenceManagementEnabled(true);
		block.setAbsenceCalendarVisibility(level);
		block.setLeadsSeeMemberEntries(leadsSee);
		current.setTimeTracking(block);
		settings.save(current);
	}

	private TimeOffType visible(TimeOffType type, TimeOffType.Visibility visibility) {
		type.setVisibility(visibility);
		return typeRepository.save(type);
	}

	private void absence(User who, TimeOffType type, TimeOff.Type kind, int from, int to) {
		mongo.insert(TimeOff.builder().userId(who.getId()).type(kind).typeId(type.getId())
				.from(MON.plusDays(from)).to(MON.plusDays(to)).createdBy(who.getId()).createdAt(NOW).build());
	}

	private User user(String name, Role role) {
		return users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(role)).active(true).timezone("UTC").locale("en")
				.build());
	}

	private static List<String> names(TeamAbsenceService.CalendarPage page) {
		return page.content().stream().map(TeamAbsenceService.Row::name).toList();
	}

	private static TeamAbsenceService.Row row(TeamAbsenceService.CalendarPage page, String name) {
		return page.content().stream().filter(row -> name.equals(row.name())).findFirst().orElseThrow();
	}
}
