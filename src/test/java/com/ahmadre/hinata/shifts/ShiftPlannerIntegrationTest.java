package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.TokenService;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectDeleted;
import com.ahmadre.hinata.project.ProjectMemberRemoved;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.team.ProjectAccess;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.team.TeamRole;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planner role and the assignment routes of HIN-45 against a real MongoDB replica set, over
 * real HTTP: who may plan, assign, sign up, confirm, decline and give up; what a membership, a
 * project or an account ending clears; and what the audit log records.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.mongodb.transactions-enabled=true",
		"hinata.shifts.enabled=true"
})
@Import(ShiftPlannerIntegrationTest.Probes.class)
@Testcontainers(disabledWithoutDocker = true)
class ShiftPlannerIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
	private static final Set<String> READS = Set.of("find", "aggregate", "count", "getMore", "distinct");

	static final AtomicBoolean counting = new AtomicBoolean();
	static final List<String> commands = new CopyOnWriteArrayList<>();

	@TestConfiguration
	static class Probes {

		@Bean
		MongoClientSettingsBuilderCustomizer countPlannerReads() {
			CommandListener listener = new CommandListener() {
				@Override
				public void commandStarted(CommandStartedEvent event) {
					if (counting.get() && READS.contains(event.getCommandName())) {
						commands.add(event.getCommandName() + " " + event.getCommand().get(event.getCommandName()));
					}
				}
			};
			return builder -> builder.addCommandListener(listener);
		}
	}

	@LocalServerPort
	private int port;
	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private UserRepository users;
	@Autowired
	private UserService userService;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private TeamRepository teams;
	@Autowired
	private TeamService teamService;
	@Autowired
	private SettingsService settings;
	@Autowired
	private TokenService tokens;
	@Autowired
	private ShiftService service;
	@Autowired
	private ShiftAssignments assignments;
	@Autowired
	private ShiftReads reads;
	@Autowired
	private ApplicationEventPublisher publisher;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private User lead;
	private User planner;
	private User ann;
	private User bob;
	private User outsider;
	private Project project;
	private Instant base;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "projects", "teams", "shift_events", "shifts", "shift_assignments",
				"shift_user_guards", "shift_planners", "shift_feed_tokens", "audit_log", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		lead = user("lead");
		planner = user("pia");
		ann = user("ann");
		bob = user("bob");
		outsider = user("outsider");
		project = project("FEST", lead, planner, ann, bob);
		base = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(10)).plus(Duration.ofHours(8));
	}

	@AfterEach
	void stopCounting() {
		counting.set(false);
		commands.clear();
	}

	// --- the planner role ----------------------------------------------------------------

	@Test
	@DisplayName("Project carries no shift field; the role lives in shift_planners")
	void projectStaysUntouched() {
		assertThat(Arrays.stream(Project.class.getDeclaredFields()).map(Field::getName))
				.noneMatch(name -> name.toLowerCase().contains("shift") || name.toLowerCase().contains("planner"));
		grantPlanner();
		assertThat(mongo.getCollection("shift_planners").countDocuments(new Document("projectId", project.getId())
				.append("userId", planner.getId()))).isEqualTo(1);
	}

	@Test
	@DisplayName("a lead grants and revokes; granting twice keeps one entry; the list names everybody once")
	void leadGrantsAndRevokes() throws Exception {
		String path = "/api/v1/projects/" + project.getId() + "/shifts/planners";
		HttpResponse<String> granted = post(path, "{\"userId\":\"" + planner.getId() + "\"}", token(lead));
		assertThat(granted.statusCode()).as(granted.body()).isEqualTo(201);
		assertThat(post(path, "{\"userId\":\"" + planner.getId() + "\"}", token(lead)).statusCode()).isEqualTo(201);

		JsonNode list = json(get(path, token(ann)));
		assertThat(fields(list)).containsExactly("planners", "users");
		assertThat(list.path("planners")).hasSize(1);
		assertThat(fields(list.path("planners").get(0))).containsExactly("userId", "grantedBy", "createdAt");
		assertThat(list.path("planners").get(0).path("userId").asText()).isEqualTo(planner.getId());
		assertThat(list.path("planners").get(0).path("grantedBy").asText()).isEqualTo(lead.getId());
		assertThat(fields(list.path("users"))).containsExactlyInAnyOrder(planner.getId(), lead.getId());
		assertThat(auditCount(AuditAction.SHIFT_PLANNER_GRANTED)).isEqualTo(1);

		assertThat(delete(path + "/" + planner.getId(), token(lead)).statusCode()).isEqualTo(204);
		assertThat(delete(path + "/" + planner.getId(), token(lead)).statusCode()).isEqualTo(204);
		assertThat(json(get(path, token(ann))).path("planners")).isEmpty();
		assertThat(auditCount(AuditAction.SHIFT_PLANNER_REVOKED)).isEqualTo(1);
	}

	@Test
	@DisplayName("only leads and team admins give the role; members and planners get 403, strangers 403")
	void onlyManagersGrant() {
		grantPlanner();
		String path = "/api/v1/projects/" + project.getId() + "/shifts/planners";
		String body = "{\"userId\":\"" + bob.getId() + "\"}";
		assertThat(post(path, body, token(ann)).statusCode()).isEqualTo(403);
		assertThat(post(path, body, token(planner)).statusCode()).isEqualTo(403);
		assertThat(delete(path + "/" + planner.getId(), token(planner)).statusCode()).isEqualTo(403);
		assertThat(get(path, token(outsider)).statusCode()).isEqualTo(403);
		assertThat(post(path, body, token(outsider)).statusCode()).isEqualTo(403);

		// A team admin of a team that owns the project runs it, so grants.
		User admin = user("tara");
		teams.save(Team.builder().key("CREW").name("Crew").createdBy(admin.getId())
				.projectIds(new ArrayList<>(List.of(project.getId())))
				.members(new ArrayList<>(List.of(TeamMembership.builder().userId(admin.getId()).role(TeamRole.ADMIN)
						.access(ProjectAccess.all()).build())))
				.build());
		assertThat(post(path, body, token(admin)).statusCode()).isEqualTo(201);
	}

	@Test
	@DisplayName("a non-member or an inactive account becomes neither planner nor assignee (400)")
	void strangersAndSleepersAreRefused() throws Exception {
		User asleep = user("asleep");
		addMember(asleep);
		asleep.setActive(false);
		users.save(asleep);
		Shift shift = shift(event(base, hours(10), true), base, hours(2), 1, null);
		String path = "/api/v1/projects/" + project.getId() + "/shifts/planners";
		for (User target : List.of(outsider, asleep)) {
			HttpResponse<String> refused = post(path, "{\"userId\":\"" + target.getId() + "\"}", token(lead));
			assertThat(refused.statusCode()).isEqualTo(400);
			assertThat(json(refused).path("details").path("code").asText()).isEqualTo("error.shifts.plannerNotMember");
			HttpResponse<String> notAssigned = post("/api/v1/shifts/shifts/" + shift.getId() + "/assignments",
					"{\"userId\":\"" + target.getId() + "\"}", token(lead));
			assertThat(notAssigned.statusCode()).isEqualTo(400);
		}
		assertThat(post(path, "{}", token(lead)).statusCode()).isEqualTo(400);
		assertThat(mongo.getCollection("shift_planners").countDocuments()).isZero();
	}

	@Test
	@DisplayName("a planner plans shifts but cannot change settings, members or workflow states")
	void plannerPlansButDoesNotManage() {
		grantPlanner();
		String pia = token(planner);
		HttpResponse<String> created = post("/api/v1/projects/" + project.getId() + "/shifts/events",
				"{\"title\":\"Summer fair\",\"startsAt\":\"" + base + "\",\"endsAt\":\"" + base.plus(hours(8)) + "\"}",
				pia);
		assertThat(created.statusCode()).as(created.body()).isEqualTo(201);

		String projectPath = "/api/v1/projects/" + project.getId();
		assertThat(patch(projectPath, "{\"name\":\"Renamed\"}", pia).statusCode()).isEqualTo(403);
		assertThat(patch(projectPath, "{\"memberIds\":[\"" + lead.getId() + "\",\"" + planner.getId() + "\"]}", pia)
				.statusCode()).isEqualTo(403);
		assertThat(patch(projectPath, "{\"workflowStates\":[{\"name\":\"Open\"}]}", pia).statusCode()).isEqualTo(403);
		Project after = projects.findById(project.getId()).orElseThrow();
		assertThat(after.getName()).isEqualTo("FEST");
		assertThat(after.getMemberIds()).contains(ann.getId(), bob.getId());
		assertThat(after.getWorkflowStates()).isEqualTo(project.getWorkflowStates());
	}

	// --- assignment routes ---------------------------------------------------------------

	@Test
	@DisplayName("a planner assigns and takes back; the person confirms or declines; the answer shapes")
	void plannerAssignsAndThePersonAnswers() throws Exception {
		grantPlanner();
		Shift shift = shift(event(base, hours(10), true), base, hours(2), 2, null);
		String pia = token(planner);

		HttpResponse<String> assigned = post("/api/v1/shifts/shifts/" + shift.getId() + "/assignments",
				"{\"userId\":\"" + ann.getId() + "\"}", pia);
		assertThat(assigned.statusCode()).as(assigned.body()).isEqualTo(201);
		JsonNode change = json(assigned);
		assertThat(fields(change)).containsExactly("shift", "assignment", "warnings");
		assertThat(fields(change.path("assignment"))).containsExactly("id", "shiftId", "userId", "status",
				"assignedBy", "createdAt");
		assertThat(change.path("assignment").path("status").asText()).isEqualTo("ASSIGNED");
		assertThat(change.path("assignment").path("assignedBy").asText()).isEqualTo(planner.getId());
		assertThat(change.path("shift").path("assignedCount").asInt()).isEqualTo(1);
		String annAssignment = change.path("assignment").path("id").asText();

		// Only Ann answers her assignment.
		assertThat(post("/api/v1/shifts/assignments/" + annAssignment + "/confirm", pia).statusCode()).isEqualTo(404);
		assertThat(post("/api/v1/shifts/assignments/" + annAssignment + "/decline", token(bob)).statusCode())
				.isEqualTo(404);
		HttpResponse<String> confirmed = post("/api/v1/shifts/assignments/" + annAssignment + "/confirm", token(ann));
		assertThat(confirmed.statusCode()).isEqualTo(200);
		assertThat(fields(json(confirmed))).containsExactly("assignmentId", "status", "assignedBy", "shift", "event");
		assertThat(json(confirmed).path("status").asText()).isEqualTo("CONFIRMED");
		assertThat(post("/api/v1/shifts/assignments/" + annAssignment + "/confirm", token(ann)).statusCode())
				.isEqualTo(200);
		// A confirmed place is given up, not declined.
		HttpResponse<String> late = post("/api/v1/shifts/assignments/" + annAssignment + "/decline", token(ann));
		assertThat(late.statusCode()).isEqualTo(409);
		assertThat(json(late).path("details").path("code").asText()).isEqualTo("error.shifts.assignmentNotPending");

		// Bob declines his.
		String bobAssignment = json(post("/api/v1/shifts/shifts/" + shift.getId() + "/assignments",
				"{\"userId\":\"" + bob.getId() + "\"}", pia)).path("assignment").path("id").asText();
		HttpResponse<String> declined = post("/api/v1/shifts/assignments/" + bobAssignment + "/decline", token(bob));
		assertThat(json(declined).path("assignment").path("status").asText()).isEqualTo("DECLINED");
		assertThat(json(declined).path("shift").path("assignedCount").asInt()).isEqualTo(1);
		assertThat(post("/api/v1/shifts/assignments/" + bobAssignment + "/confirm", token(bob)).statusCode())
				.isEqualTo(409);

		// The planner takes Ann off.
		HttpResponse<String> released = post("/api/v1/shifts/assignments/" + annAssignment + "/release", pia);
		assertThat(json(released).path("assignment").path("status").asText()).isEqualTo("RELEASED");
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isZero();

		for (AuditAction action : List.of(AuditAction.SHIFT_ASSIGNMENT_CREATED, AuditAction.SHIFT_ASSIGNMENT_CONFIRMED,
				AuditAction.SHIFT_ASSIGNMENT_DECLINED, AuditAction.SHIFT_ASSIGNMENT_RELEASED)) {
			assertThat(auditCount(action)).as(action.name()).isPositive();
		}
		assertThat(auditCount(AuditAction.SHIFT_ASSIGNMENT_CONFIRMED)).isEqualTo(1);
		assertCountersAgree();
	}

	@Test
	@DisplayName("a member signs up and gives up, cannot assign anybody, cannot take others off")
	void memberSignsUpOnly() throws Exception {
		Shift shift = shift(event(base, hours(10), true), base, hours(2), 2, null);
		String annToken = token(ann);
		HttpResponse<String> signedUp = post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", annToken);
		assertThat(signedUp.statusCode()).isEqualTo(201);
		JsonNode change = json(signedUp);
		assertThat(change.path("assignment").path("status").asText()).isEqualTo("CONFIRMED");
		assertThat(change.path("assignment").path("assignedBy").isNull()).isTrue();
		assertThat(change.path("shift").path("mine").asBoolean()).isTrue();
		assertThat(change.path("shift").path("myAssignment").path("id").asText())
				.isEqualTo(change.path("assignment").path("id").asText());

		HttpResponse<String> assignOther = post("/api/v1/shifts/shifts/" + shift.getId() + "/assignments",
				"{\"userId\":\"" + bob.getId() + "\"}", annToken);
		assertThat(assignOther.statusCode()).isEqualTo(403);
		String bobAssignment = json(post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", token(bob)))
				.path("assignment").path("id").asText();
		assertThat(post("/api/v1/shifts/assignments/" + bobAssignment + "/release", annToken).statusCode())
				.isEqualTo(403);
		// A stranger sees nothing at all.
		assertThat(post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", token(outsider)).statusCode())
				.isEqualTo(404);
		assertThat(post("/api/v1/shifts/shifts/" + shift.getId() + "/assignments",
				"{\"userId\":\"" + ann.getId() + "\"}", token(outsider)).statusCode()).isEqualTo(404);
		assertThat(post("/api/v1/shifts/assignments/" + bobAssignment + "/release", token(outsider)).statusCode())
				.isEqualTo(404);

		String annAssignment = change.path("assignment").path("id").asText();
		assertThat(json(post("/api/v1/shifts/assignments/" + annAssignment + "/release", annToken))
				.path("assignment").path("status").asText()).isEqualTo("RELEASED");
		// Signing up again turns the same document back on.
		assertThat(json(post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", annToken))
				.path("assignment").path("id").asText()).isEqualTo(annAssignment);
		assertThat(mongo.count(Query.query(Criteria.where("shiftId").is(shift.getId()).and("userId").is(ann.getId())),
				ShiftAssignment.class)).isEqualTo(1);
		assertCountersAgree();
	}

	@Test
	@DisplayName("three on a shift needing three, a fourth below the limit, 409 at the limit on both paths")
	void capacityOnBothPaths() throws Exception {
		grantPlanner();
		User carl = user("carl");
		User dora = user("dora");
		User emil = user("emil");
		addMember(carl);
		addMember(dora);
		addMember(emil);
		Shift shift = shift(event(base, hours(10), true), base, hours(2), 3, 4);
		String pia = token(planner);
		assertThat(post("/api/v1/shifts/shifts/" + shift.getId() + "/assignments",
				"{\"userId\":\"" + ann.getId() + "\"}", pia).statusCode()).isEqualTo(201);
		assertThat(post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", token(bob)).statusCode())
				.isEqualTo(201);
		assertThat(post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", token(carl)).statusCode())
				.isEqualTo(201);
		// Need met; over it and under the limit is still taken.
		assertThat(post("/api/v1/shifts/shifts/" + shift.getId() + "/assignments",
				"{\"userId\":\"" + dora.getId() + "\"}", pia).statusCode()).isEqualTo(201);
		HttpResponse<String> full = post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", token(emil));
		assertThat(full.statusCode()).isEqualTo(409);
		HttpResponse<String> fullToo = post("/api/v1/shifts/shifts/" + shift.getId() + "/assignments",
				"{\"userId\":\"" + emil.getId() + "\"}", pia);
		assertThat(fullToo.statusCode()).isEqualTo(409);
		assertThat(json(fullToo).path("details").path("code").asText()).isEqualTo("error.shifts.shiftFull");
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isEqualTo(4);
		assertCountersAgree();
	}

	@Test
	@DisplayName("a planner and ten sign-ups race for the last place: exactly one wins")
	void mixedRaceForTheLastPlace() throws Exception {
		grantPlanner();
		Shift shift = shift(event(base, hours(10), true), base, hours(2), 2, 2);
		assignments.assign(lead, shift.getId(), ann.getId(), false);
		List<User> crowd = crowd(11);
		String pia = token(planner);
		List<Supplier<Integer>> calls = new ArrayList<>();
		calls.add(() -> post("/api/v1/shifts/shifts/" + shift.getId() + "/assignments",
				"{\"userId\":\"" + crowd.getFirst().getId() + "\"}", pia).statusCode());
		for (User person : crowd.subList(1, crowd.size())) {
			String personal = token(person);
			calls.add(() -> post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", personal).statusCode());
		}

		List<Integer> codes = race(calls);

		assertThat(codes.stream().filter(code -> code == 201).count()).isEqualTo(1);
		assertThat(codes).allMatch(code -> code == 201 || code == 409);
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isEqualTo(2);
		assertCountersAgree();
	}

	@Test
	@DisplayName("sign-up closed stops only signing up; a cancelled event stops both")
	void closedAndCancelled() throws Exception {
		grantPlanner();
		ShiftEvent closed = event(base, hours(10), false);
		Shift shift = shift(closed, base, hours(2), 2, null);
		HttpResponse<String> refused = post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", token(ann));
		assertThat(refused.statusCode()).isEqualTo(403);
		assertThat(json(refused).path("details").path("code").asText()).isEqualTo("error.shifts.selfAssignDisabled");
		assertThat(post("/api/v1/shifts/shifts/" + shift.getId() + "/assignments",
				"{\"userId\":\"" + ann.getId() + "\"}", token(planner)).statusCode()).isEqualTo(201);

		ShiftEvent open = event(base.plus(Duration.ofDays(1)), hours(10), true);
		Shift other = shift(open, base.plus(Duration.ofDays(1)), hours(2), 2, null);
		service.cancelEvent(lead, open.getId());
		assertThat(post("/api/v1/shifts/shifts/" + other.getId() + "/self-assign", token(bob)).statusCode())
				.isEqualTo(409);
		assertThat(post("/api/v1/shifts/shifts/" + other.getId() + "/assignments",
				"{\"userId\":\"" + bob.getId() + "\"}", token(planner)).statusCode()).isEqualTo(409);
	}

	// --- read models ---------------------------------------------------------------------

	@Test
	@DisplayName("canPlan and plannableProjectIds follow the role, at one planner query per request")
	void readModelsKnowPlanners() {
		ShiftEvent event = event(base, hours(10), true);
		Shift shift = shift(event, base, hours(2), 1, null);
		Instant from = base.minus(hours(1));
		Instant to = base.plus(Duration.ofDays(20));
		assertThat(reads.event(planner, event.getId()).canPlan()).isFalse();
		assertThat(reads.calendar(planner, from, to, null, null, null, null).plannableProjectIds()).isEmpty();

		grantPlanner();
		assertThat(reads.event(planner, event.getId()).canPlan()).isTrue();
		assertThat(reads.shift(planner, shift.getId()).canPlan()).isTrue();
		assertThat(reads.events(planner, from, to, null, 0, 25).getContent()).allMatch(ShiftReads.EventSummary::canPlan);
		assertThat(reads.calendar(planner, from, to, null, null, null, null).plannableProjectIds())
				.containsExactly(project.getId());
		assertThat(reads.calendar(lead, from, to, null, null, null, null).plannableProjectIds())
				.containsExactly(project.getId());
		assertThat(reads.calendar(ann, from, to, null, null, null, null).plannableProjectIds()).isEmpty();
		assertThat(reads.events(ann, from, to, null, 0, 25).getContent()).noneMatch(ShiftReads.EventSummary::canPlan);

		int eventsOne = reads(() -> reads.events(planner, from, to, null, 0, 25));
		int calendarOne = reads(() -> reads.calendar(planner, from, to, null, null, null, null));
		assertThat(plannerQueries()).isEqualTo(1);
		for (int i = 1; i < 6; i++) {
			ShiftEvent more = event(base.plus(Duration.ofDays(i * 2L)), hours(10), true);
			shift(more, base.plus(Duration.ofDays(i * 2L)), hours(2), 1, null);
		}
		assertThat(reads(() -> reads.events(planner, from, to, null, 0, 25))).isEqualTo(eventsOne);
		assertThat(plannerQueries()).isEqualTo(1);
		assertThat(reads(() -> reads.calendar(planner, from, to, null, null, null, null))).isEqualTo(calendarOne);
		assertThat(plannerQueries()).isEqualTo(1);
	}

	// --- when a membership, a project or an account ends ---------------------------------

	@Test
	@DisplayName("leaving the project ends the role and frees the places ahead; twice changes nothing more")
	void leavingTheProject() {
		grantPlanner();
		Shift ahead = shift(event(base, hours(10), true), base, hours(2), 2, null);
		assignments.assign(lead, ahead.getId(), planner.getId(), false);
		assignments.assign(lead, ahead.getId(), ann.getId(), false);

		HttpResponse<String> removed = patch("/api/v1/projects/" + project.getId(), "{\"memberIds\":[\""
				+ lead.getId() + "\",\"" + ann.getId() + "\",\"" + bob.getId() + "\"]}", token(lead));
		assertThat(removed.statusCode()).as(removed.body()).isEqualTo(200);

		assertThat(mongo.getCollection("shift_planners").countDocuments()).isZero();
		assertThat(statusOf(ahead, planner)).isEqualTo(ShiftAssignment.Status.RELEASED);
		assertThat(statusOf(ahead, ann)).isEqualTo(ShiftAssignment.Status.ASSIGNED);
		assertThat(mongo.findById(ahead.getId(), Shift.class).assigned()).isEqualTo(1);

		publisher.publishEvent(new ProjectMemberRemoved(project.getId(), planner.getId()));
		assertThat(mongo.findById(ahead.getId(), Shift.class).assigned()).isEqualTo(1);
		assertThat(auditCount(AuditAction.SHIFT_PLANNER_REVOKED)).isEqualTo(1);
		AuditLog freed = mongo.findOne(Query.query(Criteria.where("action").is(AuditAction.SHIFT_ASSIGNMENT_RELEASED)),
				AuditLog.class);
		assertThat(freed.getActorId()).isNull();
		assertThat(freed.getMetadata()).containsEntry("reason", "membershipEnded");
		assertCountersAgree();

		// Rejoining brings the role back only when a lead grants it again.
		addMember(planner);
		assertThat(reads.event(planner, ahead.getEventId()).canPlan()).isFalse();
	}

	@Test
	@DisplayName("a direct member who still reaches the project through a team keeps role and places")
	void aTeamGrantKeepsThePlace() {
		grantPlanner();
		Shift shift = shift(event(base, hours(10), true), base, hours(2), 2, null);
		assignments.assign(lead, shift.getId(), planner.getId(), false);
		teams.save(Team.builder().key("CREW").name("Crew").createdBy(lead.getId())
				.projectIds(new ArrayList<>(List.of(project.getId())))
				.members(new ArrayList<>(List.of(TeamMembership.builder().userId(planner.getId()).role(TeamRole.MEMBER)
						.access(ProjectAccess.all()).build())))
				.build());

		assertThat(patch("/api/v1/projects/" + project.getId(), "{\"memberIds\":[\"" + lead.getId() + "\",\""
				+ ann.getId() + "\",\"" + bob.getId() + "\"]}", token(lead)).statusCode()).isEqualTo(200);

		assertThat(mongo.getCollection("shift_planners").countDocuments()).isEqualTo(1);
		assertThat(statusOf(shift, planner)).isEqualTo(ShiftAssignment.Status.ASSIGNED);
	}

	@Test
	@DisplayName("losing the team grant ends the role and frees the places, counter right")
	void losingTheTeamGrant() {
		User dana = user("dana");
		Team team = teams.save(Team.builder().key("OPS").name("Ops").createdBy(lead.getId())
				.projectIds(new ArrayList<>(List.of(project.getId())))
				.members(new ArrayList<>(List.of(
						TeamMembership.builder().userId(lead.getId()).role(TeamRole.ADMIN).access(ProjectAccess.all()).build(),
						TeamMembership.builder().userId(dana.getId()).role(TeamRole.MEMBER).access(ProjectAccess.all())
								.build())))
				.build());
		assertThat(post("/api/v1/projects/" + project.getId() + "/shifts/planners",
				"{\"userId\":\"" + dana.getId() + "\"}", token(lead)).statusCode()).isEqualTo(201);
		Shift shift = shift(event(base, hours(10), true), base, hours(2), 2, null);
		assignments.assign(lead, shift.getId(), dana.getId(), false);

		teamService.removeMember(team, lead, dana.getId());

		assertThat(mongo.getCollection("shift_planners").countDocuments()).isZero();
		assertThat(statusOf(shift, dana)).isEqualTo(ShiftAssignment.Status.RELEASED);
		publisher.publishEvent(new ProjectMemberRemoved(project.getId(), dana.getId()));
		assertCountersAgree();
	}

	@Test
	@DisplayName("a deleted project takes the module's documents with it")
	void deletedProject() {
		grantPlanner();
		Shift shift = shift(event(base, hours(10), true), base, hours(2), 2, null);
		assignments.assign(lead, shift.getId(), ann.getId(), false);

		publisher.publishEvent(new ProjectDeleted(project.getId()));

		for (String collection : List.of("shift_events", "shifts", "shift_assignments", "shift_planners")) {
			assertThat(mongo.getCollection(collection).countDocuments(new Document("projectId", project.getId())))
					.as(collection).isZero();
		}
	}

	@Test
	@DisplayName("a deleted account leaves no assignment, planner entry, guard or feed token")
	void deletedAccount() {
		grantPlanner();
		Shift shift = shift(event(base, hours(10), true), base, hours(2), 2, null);
		assignments.assign(lead, shift.getId(), planner.getId(), false);
		assignments.assign(lead, shift.getId(), ann.getId(), false);
		mongo.getCollection("shift_feed_tokens").insertOne(new Document("userId", planner.getId())
				.append("tokenHash", "h").append("createdAt", new java.util.Date()));

		userService.delete(planner);

		assertThat(mongo.count(Query.query(Criteria.where("userId").is(planner.getId())), ShiftAssignment.class)).isZero();
		assertThat(mongo.getCollection("shift_planners").countDocuments(new Document("userId", planner.getId()))).isZero();
		assertThat(mongo.getCollection("shift_feed_tokens").countDocuments(new Document("userId", planner.getId())))
				.isZero();
		assertThat(mongo.findById(planner.getId(), ShiftUserGuard.class)).isNull();
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isEqualTo(1);
		assertCountersAgree();
	}

	// --- audit and the switch ------------------------------------------------------------

	@Test
	@DisplayName("each grant is audited with actor and target, never a title")
	void grantsAreAudited() {
		grantPlanner();
		ShiftEvent event = event(base, hours(10), true);
		Shift shift = shift(event, base, hours(2), 1, null);
		assignments.assign(planner, shift.getId(), ann.getId(), false);

		AuditLog granted = mongo.findOne(Query.query(Criteria.where("action").is(AuditAction.SHIFT_PLANNER_GRANTED)),
				AuditLog.class);
		assertThat(granted.getActorId()).isEqualTo(lead.getId());
		assertThat(granted.getTargetId()).isEqualTo(planner.getId());
		assertThat(granted.getMetadata()).containsEntry("projectId", project.getId());
		AuditLog assigned = mongo.findOne(Query.query(Criteria.where("action").is(AuditAction.SHIFT_ASSIGNMENT_CREATED)),
				AuditLog.class);
		assertThat(assigned.getActorId()).isEqualTo(planner.getId());
		assertThat(assigned.getTargetId()).isEqualTo(ann.getId());
		assertThat(assigned.getMetadata()).containsEntry("shiftId", shift.getId()).containsEntry("self", "false");
		for (AuditLog record : mongo.findAll(AuditLog.class)) {
			if (record.getMetadata() != null) {
				assertThat(record.getMetadata().values()).as(record.getAction().name())
						.noneMatch(value -> value.contains("Event") || value.equals("S"));
			}
		}
	}

	@Test
	@DisplayName("with the module off every route of this stage is 404")
	void moduleOff() {
		grantPlanner();
		Shift shift = shift(event(base, hours(10), true), base, hours(2), 1, null);
		String assignment = assignments.assign(lead, shift.getId(), ann.getId(), false).assignment().getId();
		ServerSettings current = settings.get();
		ServerSettings.Shifts off = new ServerSettings.Shifts();
		off.setEnabled(false);
		current.setShifts(off);
		settings.save(current);
		try {
			String leadToken = token(lead);
			String annToken = token(ann);
			String planners = "/api/v1/projects/" + project.getId() + "/shifts/planners";
			assertThat(get(planners, leadToken).statusCode()).isEqualTo(404);
			assertThat(post(planners, "{\"userId\":\"" + bob.getId() + "\"}", leadToken).statusCode()).isEqualTo(404);
			assertThat(delete(planners + "/" + planner.getId(), leadToken).statusCode()).isEqualTo(404);
			assertThat(post("/api/v1/shifts/shifts/" + shift.getId() + "/assignments",
					"{\"userId\":\"" + bob.getId() + "\"}", leadToken).statusCode()).isEqualTo(404);
			assertThat(post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", annToken).statusCode())
					.isEqualTo(404);
			for (String action : List.of("confirm", "decline", "release")) {
				assertThat(post("/api/v1/shifts/assignments/" + assignment + "/" + action, annToken).statusCode())
						.as(action).isEqualTo(404);
			}
		}
		finally {
			current = settings.get();
			current.setShifts(null);
			settings.save(current);
		}
	}

	// --- helpers -------------------------------------------------------------------------

	private void grantPlanner() {
		HttpResponse<String> granted = post("/api/v1/projects/" + project.getId() + "/shifts/planners",
				"{\"userId\":\"" + planner.getId() + "\"}", token(lead));
		assertThat(granted.statusCode()).as(granted.body()).isEqualTo(201);
	}

	private ShiftAssignment.Status statusOf(Shift shift, User person) {
		return mongo.findOne(Query.query(Criteria.where("shiftId").is(shift.getId()).and("userId").is(person.getId())),
				ShiftAssignment.class).getStatus();
	}

	private long plannerQueries() {
		return commands.stream().filter(command -> command.startsWith("find ") && command.contains("shift_planners")).count();
	}

	private void assertCountersAgree() {
		for (Shift shift : mongo.findAll(Shift.class)) {
			long active = mongo.count(Query.query(Criteria.where("shiftId").is(shift.getId())
					.and("status").in(ShiftAssignment.Status.ACTIVE)), ShiftAssignment.class);
			assertThat(shift.assigned()).as("counter of %s", shift.getId()).isEqualTo((int) active);
		}
	}

	private long auditCount(AuditAction action) {
		return mongo.count(Query.query(Criteria.where("action").is(action)), AuditLog.class);
	}

	private int reads(Runnable read) {
		commands.clear();
		counting.set(true);
		try {
			read.run();
		}
		finally {
			counting.set(false);
		}
		return commands.size();
	}

	private List<Integer> race(List<Supplier<Integer>> calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.size());
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		for (Supplier<Integer> call : calls) {
			futures.add(pool.submit(() -> {
				go.await();
				return call.get();
			}));
		}
		go.countDown();
		List<Integer> codes = new ArrayList<>();
		for (Future<Integer> future : futures) {
			codes.add(future.get(60, TimeUnit.SECONDS));
		}
		pool.shutdown();
		return codes;
	}

	private List<User> crowd(int size) {
		List<User> crowd = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			User person = user("helper" + i);
			crowd.add(person);
			addMember(person);
		}
		return crowd;
	}

	private void addMember(User person) {
		project = projects.findById(project.getId()).orElseThrow();
		if (!project.getMemberIds().contains(person.getId())) {
			project.getMemberIds().add(person.getId());
		}
		project = projects.save(project);
	}

	private ShiftEvent event(Instant start, Duration length, boolean selfAssignable) {
		return service.createEvent(lead, project.getId(), new ShiftService.EventDraft("Event", null, null, start,
				start.plus(length), "Europe/Berlin", selfAssignable));
	}

	private Shift shift(ShiftEvent event, Instant start, Duration length, int required, Integer max) {
		return service.createShift(lead, event.getId(), new ShiftService.ShiftDraft("S", start, start.plus(length),
				required, max));
	}

	private static Duration hours(long hours) {
		return Duration.ofHours(hours);
	}

	private User user(String name) {
		return users.save(User.builder().username(name).email(name + "@planners.test")
				.displayName(Character.toUpperCase(name.charAt(0)) + name.substring(1))
				.roles(new java.util.HashSet<>(Set.of(Role.MEMBER))).active(true).build());
	}

	private Project project(String key, User leader, User... members) {
		List<String> ids = new ArrayList<>(List.of(leader.getId()));
		for (User member : members) {
			ids.add(member.getId());
		}
		return projects.save(Project.builder().key(key).name(key).leadId(leader.getId())
				.leadIds(new ArrayList<>(List.of(leader.getId()))).memberIds(ids).build());
	}

	private String token(User user) {
		return tokens.issue(user).accessToken();
	}

	private static JsonNode json(HttpResponse<String> response) throws Exception {
		return JSON.readTree(response.body());
	}

	private static List<String> fields(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private HttpResponse<String> get(String path, String token) {
		return send(HttpRequest.newBuilder(url(path)).header("Authorization", "Bearer " + token).GET().build());
	}

	private HttpResponse<String> delete(String path, String token) {
		return send(HttpRequest.newBuilder(url(path)).header("Authorization", "Bearer " + token).DELETE().build());
	}

	private HttpResponse<String> post(String path, String token) {
		return post(path, "", token);
	}

	private HttpResponse<String> post(String path, String json, String token) {
		return send(HttpRequest.newBuilder(url(path)).header("Authorization", "Bearer " + token)
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.POST(HttpRequest.BodyPublishers.ofString(json)).build());
	}

	private HttpResponse<String> patch(String path, String json, String token) {
		return send(HttpRequest.newBuilder(url(path)).header("Authorization", "Bearer " + token)
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.method("PATCH", HttpRequest.BodyPublishers.ofString(json)).build());
	}

	private URI url(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (Exception ex) {
			throw new IllegalStateException("request failed: " + request.uri(), ex);
		}
	}
}
