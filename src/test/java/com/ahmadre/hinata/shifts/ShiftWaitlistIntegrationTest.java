package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.TokenService;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.me.NotificationPreferences;
import com.ahmadre.hinata.notification.MailService;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.PushService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
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
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;

/**
 * The waiting list of HIN-162 against a real MongoDB replica set: who waits in which order, who
 * moves up when a place opens and when nobody does, the deadline to confirm, who sees what, what
 * clears a waiting entry, and that every notice goes out once and only after the commit.
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
@Testcontainers(disabledWithoutDocker = true)
class ShiftWaitlistIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

	@MockitoBean
	private MailService mail;
	@MockitoBean
	private PushService push;

	@LocalServerPort
	private int port;
	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private MongoClient client;
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
	private TokenService tokens;
	@Autowired
	private ShiftService service;
	@Autowired
	private ShiftAssignments assignments;
	@Autowired
	private ShiftReads reads;
	@Autowired
	private ShiftTx tx;
	@Autowired
	private ShiftWaitlistJob job;
	@Autowired
	private ShiftReminderJob reminders;
	@Autowired
	private ShiftCounterReconcile reconcile;
	@Autowired
	private ShiftRetentionJob retention;
	@Autowired
	private ShiftPersonalData personalData;
	@Autowired
	private ShiftFeed feed;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private User lead;
	private User ann;
	private User bob;
	private User carl;
	private User dana;
	private User erin;
	private Project project;
	/** Ten days ahead on the hour: far from every cutoff, deadline and reminder window. */
	private Instant base;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "projects", "teams", "shift_events", "shifts", "shift_assignments",
				"shift_user_guards", "shift_planners", "shift_feed_tokens", "notifications", "audit_log",
				"server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		lead = user("lead");
		ann = user("ann");
		bob = user("bob");
		carl = user("carl");
		dana = user("dana");
		erin = user("erin");
		project = projects.save(Project.builder().key("FAIR").name("FAIR").leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), ann.getId(), bob.getId(), carl.getId(), dana.getId(),
						erin.getId()))).build());
		base = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofDays(10));
	}

	// --- joining and the order -----------------------------------------------------------

	@Test
	@DisplayName("a full shift puts sign-ups on the list in order; the counter stays; the rank comes from the order")
	void fullShiftPutsSignUpsOnTheList() {
		Shift shift = fullShift(1);
		ShiftAssignments.Assigned bobs = assignments.assign(bob, shift.getId(), null, true);
		ShiftAssignments.Assigned carls = assignments.assign(carl, shift.getId(), null, true);

		assertThat(bobs.assignment().getStatus()).isEqualTo(ShiftAssignment.Status.WAITING);
		assertThat(carls.assignment().getStatus()).isEqualTo(ShiftAssignment.Status.WAITING);
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isEqualTo(1);
		assertThat(rank(bob, shift)).isEqualTo(1);
		assertThat(rank(carl, shift)).isEqualTo(2);
		assertThat(reads.shift(bob, shift.getId()).shift().myWaitAssignmentId()).isEqualTo(bobs.assignment().getId());
		assertThat(reads.shift(bob, shift.getId()).shift().mine()).isFalse();
		assertThat(reads.shift(bob, shift.getId()).shift().myAssignment()).isNull();

		// Signing up again while waiting is a 409 of its own.
		assertThat(statusOfRefusal(() -> assignments.assign(bob, shift.getId(), null, true)))
				.isEqualTo("error.shifts.alreadyWaiting");
		// Waiting holds no place: not in "my shifts", not next, in the waiting list of one's own.
		assertThat(reads.mine(bob, null, null, 0, 25).getContent()).isEmpty();
		assertThat(reads.next(bob)).isEmpty();
		assertThat(reads.waiting(bob)).singleElement().satisfies(row -> {
			assertThat(row.status()).isEqualTo(ShiftAssignment.Status.WAITING);
			assertThat(row.shift().myWaitPosition()).isEqualTo(1);
			assertThat(row.shift().waitingCount()).isEqualTo(2);
			assertThat(row.shift().mine()).isFalse();
			assertThat(row.confirmBy()).isNull();
		});
	}

	@Test
	@DisplayName("twenty parallel sign-ups on a full shift all land on the list, each with its own rank")
	void twentyParallelJoins() throws Exception {
		Shift shift = fullShift(1);
		List<User> crowd = crowd(20);
		List<Supplier<Integer>> calls = new ArrayList<>();
		for (User person : crowd) {
			String token = token(person);
			calls.add(() -> post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", token).statusCode());
		}

		assertThat(race(calls)).containsOnly(201);

		assertThat(waitingOn(shift)).isEqualTo(20);
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isEqualTo(1);
		Set<Integer> ranks = new HashSet<>();
		crowd.forEach(person -> ranks.add(rank(person, shift)));
		assertThat(ranks).hasSize(20).containsExactlyInAnyOrderElementsOf(range(1, 20));
	}

	@Test
	@DisplayName("the list holds fifty at most")
	void listIsCapped() {
		Shift shift = fullShift(1);
		List<User> crowd = crowd(ShiftWaitlist.WAITING_MAX);
		crowd.forEach(person -> assignments.assign(person, shift.getId(), null, true));
		assertThat(statusOfRefusal(() -> assignments.assign(bob, shift.getId(), null, true)))
				.isEqualTo("error.shifts.waitlistFull");
	}

	@Test
	@DisplayName("ninety minutes before the start nobody moves up, nobody hears it, and a new sign-up queues behind")
	void cutoffStopsMovingUpAndNobodyOvertakes() {
		Instant soon = Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(90));
		ShiftEvent event = event(soon.minus(Duration.ofMinutes(30)), Duration.ofHours(6), true);
		Shift shift = shift(event, soon, Duration.ofHours(2), 1, 1);
		String anns = assignments.assign(ann, shift.getId(), null, true).assignment().getId();
		assignments.assign(bob, shift.getId(), null, true);

		assignments.release(ann, anns);

		assertThat(statusOf(shift, bob)).isEqualTo(ShiftAssignment.Status.WAITING);
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isZero();
		// A free place with somebody waiting: the next sign-up goes behind them.
		assertThat(assignments.assign(carl, shift.getId(), null, true).assignment().getStatus())
				.isEqualTo(ShiftAssignment.Status.WAITING);
		assertThat(rank(carl, shift)).isEqualTo(2);
		assertThat(job.run(Instant.now()).promoted()).isZero();
		assertThat(notices(null, Notification.Type.SHIFT_PROMOTED)).isZero();
		assertThat(notices(bob, null)).isZero();
		assertThat(notices(carl, null)).isZero();
	}

	// --- moving up -----------------------------------------------------------------------

	@Test
	@DisplayName("giving up a place moves the first person up after the commit, with a deadline and one notice")
	void releaseMovesTheFirstUp() {
		Shift shift = fullShift(1);
		String anns = mongo.findOne(Query.query(Criteria.where("userId").is(ann.getId())), ShiftAssignment.class).getId();
		String bobs = assignments.assign(bob, shift.getId(), null, true).assignment().getId();
		assignments.assign(carl, shift.getId(), null, true);
		Instant before = Instant.now();

		ShiftAssignment released = assignments.release(ann, anns);

		assertThat(released.getStatus()).isEqualTo(ShiftAssignment.Status.RELEASED);
		ShiftAssignment moved = mongo.findById(bobs, ShiftAssignment.class);
		assertThat(moved.getStatus()).isEqualTo(ShiftAssignment.Status.ASSIGNED);
		assertThat(moved.getAssignedBy()).isNull();
		assertThat(moved.getPromotedAt()).isNotNull();
		// Ten days ahead: a day to confirm.
		assertThat(moved.getConfirmBy()).isBetween(before.plus(Duration.ofHours(24)), Instant.now().plus(Duration.ofHours(24)));
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isEqualTo(1);
		assertThat(rank(carl, shift)).isEqualTo(1);
		assertCountersAgree();

		assertThat(notices(bob, Notification.Type.SHIFT_PROMOTED)).isEqualTo(1);
		Notification bell = one(bob, Notification.Type.SHIFT_PROMOTED);
		assertThat(bell.getLink()).isEqualTo("/shifts/events/" + shift.getEventId());
		assertThat(bell.getBody()).contains("Aufbau");
		List<Invocation> pushes = mockingDetails(push).getInvocations().stream()
				.filter(call -> call.getMethod().getName().equals("sendToUser") && bob.getId().equals(call.getArgument(0)))
				.toList();
		assertThat(pushes).singleElement().satisfies(sent ->
				assertThat(sent.getArgument(1) + " " + sent.getArgument(2)).isEqualTo("Schichten Ein Platz ist frei geworden.")
						.doesNotContain("Aufbau", "Sommerfest"));
		AuditLog record = mongo.findOne(Query.query(Criteria.where("action").is(AuditAction.SHIFT_ASSIGNMENT_CREATED)
				.and("metadata.promoted").is("true")), AuditLog.class);
		assertThat(record.getActorId()).isNull();

		// The person who moved up sees the deadline, confirms, and it is gone.
		ShiftReads.MyShiftResponse mine = reads.mine(bob, null, null, 0, 25).getContent().getFirst();
		assertThat(mine.confirmBy()).isEqualTo(moved.getConfirmBy());
		assertThat(mine.confirmByLocal()).isNotBlank();
		// A sheet opened from the event page, not from "Meine", shows the same deadline.
		ShiftReads.MyAssignment own = reads.shift(bob, shift.getId()).shift().myAssignment();
		assertThat(own.confirmBy()).isEqualTo(moved.getConfirmBy());
		assertThat(own.confirmByLocal()).isEqualTo(mine.confirmByLocal());
		assertThat(assignments.confirm(bob, bobs).getConfirmBy()).isNull();
		assertThat(mongo.findById(bobs, ShiftAssignment.class).getStatus()).isEqualTo(ShiftAssignment.Status.CONFIRMED);
	}

	@Test
	@DisplayName("a freed place and the first person's own sign-up elsewhere at once: exactly one place is theirs")
	void releaseRacesTheCandidatesOwnSignUp() throws Exception {
		ShiftEvent event = event(base, Duration.ofHours(10), true);
		Shift full = shift(event, base, Duration.ofHours(2), 1, 1);
		Shift other = shift(event, base.plus(Duration.ofHours(1)), Duration.ofHours(2), 1, null);
		String anns = assignments.assign(ann, full.getId(), null, true).assignment().getId();
		assignments.assign(bob, full.getId(), null, true);
		String annToken = token(ann);
		String bobToken = token(bob);

		List<Integer> codes = race(List.of(
				() -> post("/api/v1/shifts/assignments/" + anns + "/release", annToken).statusCode(),
				() -> post("/api/v1/shifts/shifts/" + other.getId() + "/self-assign", bobToken).statusCode()));

		assertThat(codes.get(0)).isEqualTo(200);
		long active = mongo.count(Query.query(Criteria.where("userId").is(bob.getId())
				.and("status").in(ShiftAssignment.Status.ACTIVE)), ShiftAssignment.class);
		assertThat(active).isEqualTo(1);
		assertCountersAgree();
	}

	@Test
	@DisplayName("an inactive or no longer seeing candidate is passed silently, a colliding one with a notice, the next moves up")
	void passedOverCandidates() {
		Shift shift = fullShift(1);
		String anns = mongo.findOne(Query.query(Criteria.where("userId").is(ann.getId())), ShiftAssignment.class).getId();
		for (User person : List.of(bob, carl, dana, erin)) {
			assignments.assign(person, shift.getId(), null, true);
		}
		bob.setActive(false);
		users.save(bob);
		// Carl now holds another shift at that time.
		Shift elsewhere = shift(event(base, Duration.ofHours(10), true), base, Duration.ofHours(2), 1, null);
		assignments.assign(lead, elsewhere.getId(), carl.getId(), false);
		// Dana loses sight without the membership event, so only the promotion's own check catches it.
		project = projects.findById(project.getId()).orElseThrow();
		project.getMemberIds().remove(dana.getId());
		project = projects.save(project);

		assignments.release(ann, anns);

		assertThat(statusOf(shift, erin)).isEqualTo(ShiftAssignment.Status.ASSIGNED);
		for (User passed : List.of(bob, carl, dana)) {
			assertThat(statusOf(shift, passed)).as(passed.getUsername()).isEqualTo(ShiftAssignment.Status.RELEASED);
		}
		assertThat(notices(carl, Notification.Type.SHIFT_WAITLIST_SKIPPED)).isEqualTo(1);
		assertThat(notices(bob, null)).isZero();
		assertThat(notices(dana, null)).isZero();
		assertThat(notices(erin, Notification.Type.SHIFT_PROMOTED)).isEqualTo(1);
		assertCountersAgree();
	}

	// --- the deadline --------------------------------------------------------------------

	@Test
	@DisplayName("no answer by the deadline: the place goes back, the person hears it, the next one moves up; once only")
	void deadlinePasses() {
		Shift shift = fullShift(1);
		String anns = mongo.findOne(Query.query(Criteria.where("userId").is(ann.getId())), ShiftAssignment.class).getId();
		String bobs = assignments.assign(bob, shift.getId(), null, true).assignment().getId();
		assignments.assign(carl, shift.getId(), null, true);
		assignments.release(ann, anns);
		overdue(bobs);

		ShiftWaitlistJob.Result first = job.run(Instant.now());

		assertThat(first.expired()).isEqualTo(1);
		assertThat(mongo.findById(bobs, ShiftAssignment.class).getStatus()).isEqualTo(ShiftAssignment.Status.RELEASED);
		assertThat(notices(bob, Notification.Type.SHIFT_WAITLIST_EXPIRED)).isEqualTo(1);
		assertThat(one(bob, Notification.Type.SHIFT_WAITLIST_EXPIRED).getLink()).isEqualTo("/shifts");
		assertThat(statusOf(shift, carl)).isEqualTo(ShiftAssignment.Status.ASSIGNED);
		assertThat(notices(carl, Notification.Type.SHIFT_PROMOTED)).isEqualTo(1);
		assertThat(job.run(Instant.now()).expired()).isZero();
		assertCountersAgree();
	}

	@Test
	@DisplayName("a confirmation before the job wins; the job then takes nothing back")
	void confirmationWins() {
		Shift shift = fullShift(1);
		String anns = mongo.findOne(Query.query(Criteria.where("userId").is(ann.getId())), ShiftAssignment.class).getId();
		String bobs = assignments.assign(bob, shift.getId(), null, true).assignment().getId();
		assignments.assign(carl, shift.getId(), null, true);
		assignments.release(ann, anns);
		overdue(bobs);

		assignments.confirm(bob, bobs);

		assertThat(job.run(Instant.now()).expired()).isZero();
		assertThat(mongo.findById(bobs, ShiftAssignment.class).getStatus()).isEqualTo(ShiftAssignment.Status.CONFIRMED);
		assertThat(statusOf(shift, carl)).isEqualTo(ShiftAssignment.Status.WAITING);
	}

	@Test
	@DisplayName("confirming and the job at once, and two job instances at once: one outcome, nobody moves up twice")
	void racesAroundTheDeadline() throws Exception {
		Shift shift = fullShift(1);
		String anns = mongo.findOne(Query.query(Criteria.where("userId").is(ann.getId())), ShiftAssignment.class).getId();
		String bobs = assignments.assign(bob, shift.getId(), null, true).assignment().getId();
		assignments.assign(carl, shift.getId(), null, true);
		assignments.assign(dana, shift.getId(), null, true);
		assignments.release(ann, anns);
		overdue(bobs);
		String bobToken = token(bob);
		Instant t = Instant.now();

		List<Integer> results = race(List.of(
				() -> post("/api/v1/shifts/assignments/" + bobs + "/confirm", bobToken).statusCode(),
				() -> job.run(t).expired(),
				() -> job.run(t).expired()));

		ShiftAssignment.Status bobNow = mongo.findById(bobs, ShiftAssignment.class).getStatus();
		int expired = results.get(1) + results.get(2);
		if (bobNow == ShiftAssignment.Status.CONFIRMED) {
			assertThat(results.get(0)).isEqualTo(200);
			assertThat(expired).isZero();
			assertThat(statusOf(shift, carl)).isEqualTo(ShiftAssignment.Status.WAITING);
		}
		else {
			assertThat(bobNow).isEqualTo(ShiftAssignment.Status.RELEASED);
			assertThat(expired).isEqualTo(1);
			assertThat(statusOf(shift, carl)).isEqualTo(ShiftAssignment.Status.ASSIGNED);
			assertThat(notices(carl, Notification.Type.SHIFT_PROMOTED)).isEqualTo(1);
		}
		assertThat(statusOf(shift, dana)).isEqualTo(ShiftAssignment.Status.WAITING);
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isEqualTo(1);
		assertCountersAgree();
	}

	// --- more places, closed sign-up ------------------------------------------------------

	@Test
	@DisplayName("raising the upper limit moves that many up; removing it moves up everybody")
	void raisingTheLimit() {
		Shift shift = fullShift(1);
		for (User person : List.of(bob, carl, dana, erin)) {
			assignments.assign(person, shift.getId(), null, true);
		}

		service.updateShift(lead, shift.getId(), new ShiftService.ShiftPatch(null, null, null, null, 3, null));

		assertThat(statusOf(shift, bob)).isEqualTo(ShiftAssignment.Status.ASSIGNED);
		assertThat(statusOf(shift, carl)).isEqualTo(ShiftAssignment.Status.ASSIGNED);
		assertThat(statusOf(shift, dana)).isEqualTo(ShiftAssignment.Status.WAITING);
		assertThat(rank(dana, shift)).isEqualTo(1);

		service.updateShift(lead, shift.getId(), new ShiftService.ShiftPatch(null, null, null, null, null, true));
		assertThat(waitingOn(shift)).isZero();
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isEqualTo(5);
		assertCountersAgree();
	}

	@Test
	@DisplayName("closed sign-up leaves the list for the planners; 'assign now' lifts the entry; opening again moves up")
	void closedSignUpKeepsTheList() {
		ShiftEvent event = event(base, Duration.ofHours(10), true);
		Shift shift = shift(event, base, Duration.ofHours(2), 2, 2);
		String anns = assignments.assign(ann, shift.getId(), null, true).assignment().getId();
		assignments.assign(lead, shift.getId(), erin.getId(), false);
		String bobs = assignments.assign(bob, shift.getId(), null, true).assignment().getId();
		assignments.assign(carl, shift.getId(), null, true);
		service.updateEvent(lead, event.getId(), new ShiftService.EventPatch(null, null, null, null, null, null, false));

		assignments.release(ann, anns);
		assertThat(statusOf(shift, bob)).isEqualTo(ShiftAssignment.Status.WAITING);

		// The planner's "assign now" turns the waiting entry on: the same document, no duplicate key.
		ShiftAssignments.Assigned now = assignments.assign(lead, shift.getId(), bob.getId(), false);
		assertThat(now.assignment().getId()).isEqualTo(bobs);
		assertThat(now.assignment().getStatus()).isEqualTo(ShiftAssignment.Status.ASSIGNED);
		assertThat(now.assignment().getAssignedBy()).isEqualTo(lead.getId());
		assertThat(notices(bob, Notification.Type.SHIFT_ASSIGNED)).isEqualTo(1);
		// Full again: a planner's assignment past the limit is still refused.
		assertThat(statusOfRefusal(() -> assignments.assign(lead, shift.getId(), dana.getId(), false)))
				.isEqualTo("error.shifts.shiftFull");

		String erins = mongo.findOne(Query.query(Criteria.where("userId").is(erin.getId())), ShiftAssignment.class).getId();
		assignments.release(lead, erins);
		assertThat(statusOf(shift, carl)).isEqualTo(ShiftAssignment.Status.WAITING);
		service.updateEvent(lead, event.getId(), new ShiftService.EventPatch(null, null, null, null, null, null, true));
		assertThat(statusOf(shift, carl)).isEqualTo(ShiftAssignment.Status.ASSIGNED);
		assertCountersAgree();
	}

	// --- who sees what -------------------------------------------------------------------

	@Test
	@DisplayName("waiting people see their rank, members the number, planners the names; users never names a waiting person to others")
	void visibilityPerRole() throws Exception {
		Shift shift = fullShift(1);
		assignments.assign(bob, shift.getId(), null, true);
		assignments.assign(carl, shift.getId(), null, true);

		ShiftReads.ShiftDetail forBob = reads.shift(bob, shift.getId());
		assertThat(forBob.shift().myWaitPosition()).isEqualTo(1);
		assertThat(forBob.shift().waitingIds()).isEmpty();
		assertThat(forBob.users()).containsOnlyKeys(ann.getId());

		ShiftReads.ShiftDetail forDana = reads.shift(dana, shift.getId());
		assertThat(forDana.shift().waitingCount()).isEqualTo(2);
		assertThat(forDana.shift().myWaitPosition()).isNull();
		assertThat(forDana.shift().waitingIds()).isEmpty();
		assertThat(forDana.shift().assigneeIds()).containsExactly(ann.getId());
		assertThat(forDana.users()).doesNotContainKeys(bob.getId(), carl.getId());
		ShiftReads.EventResponse eventForDana = reads.event(dana, shift.getEventId());
		assertThat(eventForDana.users()).doesNotContainKeys(bob.getId(), carl.getId());
		assertThat(eventForDana.shifts().getFirst().waitingIds()).isEmpty();
		assertThat(eventForDana.shifts().getFirst().waitingCount()).isEqualTo(2);

		ShiftReads.ShiftDetail forLead = reads.shift(lead, shift.getId());
		assertThat(forLead.shift().waitingIds()).containsExactly(bob.getId(), carl.getId());
		assertThat(forLead.users()).containsKeys(bob.getId(), carl.getId());
		ShiftReads.EventResponse eventForLead = reads.event(lead, shift.getEventId());
		assertThat(eventForLead.shifts().getFirst().waitingIds()).containsExactly(bob.getId(), carl.getId());
		assertThat(eventForLead.shifts().getFirst().assigneeIds()).containsExactly(ann.getId());
		// Lists name nobody, planner or not.
		assertThat(reads.calendar(lead, base.minus(Duration.ofHours(1)), base.plus(Duration.ofHours(12)), null, null,
				null, null).shifts()).singleElement().satisfies(row -> {
			assertThat(row.waitingIds()).isEmpty();
			assertThat(row.waitingCount()).isEqualTo(2);
		});

		// The wire shape, and the caller's own waiting entries.
		JsonNode wire = json(get("/api/v1/shifts/shifts/" + shift.getId(), token(carl))).get("shift");
		assertThat(wire.get("waitingCount").asInt()).isEqualTo(2);
		assertThat(wire.get("myWaitPosition").asInt()).isEqualTo(2);
		assertThat(wire.get("waitingIds")).isEmpty();
		JsonNode waiting = json(get("/api/v1/me/shifts/waiting", token(carl)));
		assertThat(waiting.isArray()).isTrue();
		assertThat(waiting.get(0).get("status").asText()).isEqualTo("WAITING");
		assertThat(waiting.get(0).get("shift").get("myWaitPosition").asInt()).isEqualTo(2);
		assertThat(waiting.get(0).has("confirmBy")).isTrue();
		assertThat(waiting.get(0).has("confirmByLocal")).isTrue();
	}

	// --- what clears a waiting entry -----------------------------------------------------

	@Test
	@DisplayName("leaving the project, losing the team grant and deleting the account all end a waiting entry")
	void membershipAndAccountEndWaiting() {
		Shift shift = fullShift(1);
		Team team = teams.save(Team.builder().key("OPS").name("Ops").createdBy(lead.getId())
				.projectIds(new ArrayList<>(List.of(project.getId())))
				.members(new ArrayList<>(List.of(
						TeamMembership.builder().userId(lead.getId()).role(TeamRole.ADMIN).access(ProjectAccess.all()).build(),
						TeamMembership.builder().userId(dana.getId()).role(TeamRole.MEMBER).access(ProjectAccess.all())
								.build())))
				.build());
		project = projects.findById(project.getId()).orElseThrow();
		project.getMemberIds().remove(dana.getId());
		project = projects.save(project);
		for (User person : List.of(bob, dana, erin)) {
			assignments.assign(person, shift.getId(), null, true);
		}

		HttpResponse<String> removed = patch("/api/v1/projects/" + project.getId(), "{\"memberIds\":[\""
				+ lead.getId() + "\",\"" + ann.getId() + "\",\"" + carl.getId() + "\",\"" + erin.getId() + "\"]}",
				token(lead));
		assertThat(removed.statusCode()).as(removed.body()).isEqualTo(200);
		assertThat(statusOf(shift, bob)).isEqualTo(ShiftAssignment.Status.RELEASED);

		teamService.removeMember(team, lead, dana.getId());
		assertThat(statusOf(shift, dana)).isEqualTo(ShiftAssignment.Status.RELEASED);

		userService.delete(erin);
		assertThat(mongo.count(Query.query(Criteria.where("userId").is(erin.getId())), ShiftAssignment.class)).isZero();
		assertThat(waitingOn(shift)).isZero();
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isEqualTo(1);
	}

	@Test
	@DisplayName("a cancelled event ends its lists and tells the people on them; a deleted shift takes them along")
	void cancelledEventAndDeletedShift() {
		Shift shift = fullShift(1);
		assignments.assign(bob, shift.getId(), null, true);
		ShiftEvent event = mongo.findById(shift.getEventId(), ShiftEvent.class);

		service.cancelEvent(lead, event.getId());

		assertThat(statusOf(shift, bob)).isEqualTo(ShiftAssignment.Status.RELEASED);
		assertThat(statusOf(shift, ann)).isEqualTo(ShiftAssignment.Status.CONFIRMED);
		assertThat(notices(bob, Notification.Type.SHIFT_EVENT_CHANGED)).isEqualTo(1);
		assertThat(notices(ann, Notification.Type.SHIFT_EVENT_CHANGED)).isEqualTo(1);

		Shift second = fullShift(1);
		assignments.assign(carl, second.getId(), null, true);
		service.deleteShift(lead, second.getId());
		assertThat(mongo.count(Query.query(Criteria.where("shiftId").is(second.getId())), ShiftAssignment.class)).isZero();
	}

	@Test
	@DisplayName("reminder, overlap check, calendar feed and counter reconciliation all ignore waiting entries")
	void waitingHoldsNothing() {
		// A shift in the reminder window of the run planned for the coming hour.
		Instant plannedHour = Instant.now().truncatedTo(ChronoUnit.HOURS);
		Instant start = plannedHour.plus(Duration.ofHours(23));
		ShiftEvent event = event(start, Duration.ofHours(6), true);
		Shift full = shift(event, start, Duration.ofHours(2), 1, 1);
		Shift beside = shift(event, start.plus(Duration.ofMinutes(30)), Duration.ofHours(2), 1, null);
		assignments.assign(ann, full.getId(), null, true);
		ShiftAssignment bobs = assignments.assign(bob, full.getId(), null, true).assignment();

		// Waiting does not block a place at the same time.
		assertThat(assignments.assign(bob, beside.getId(), null, true).assignment().getStatus())
				.isEqualTo(ShiftAssignment.Status.CONFIRMED);

		reminders.run(plannedHour);
		assertThat(mongo.findById(bobs.getId(), ShiftAssignment.class).getReminderSentAt()).isNull();
		assertThat(notices(bob, Notification.Type.SHIFT_REMINDER)).isEqualTo(1);

		String url = feed.create(bob).url();
		String token = url.substring(url.lastIndexOf('/') + 1, url.length() - ".ics".length());
		ShiftFeed.Fetch fetched = feed.fetch(token);
		String ics = new String(((ShiftFeed.Calendar) fetched).body(), StandardCharsets.UTF_8);
		assertThat(ics).contains("Beiseite").doesNotContain("UID:" + bobs.getId());

		mongo.updateFirst(Query.query(Criteria.where("_id").is(full.getId())),
				new Update().set("assignedCount", 0).set("updatedAt", Instant.now().minus(Duration.ofHours(1))),
				Shift.class);
		reconcile.run();
		assertThat(mongo.findById(full.getId(), Shift.class).assigned()).isEqualTo(1);
	}

	// --- delivery after the commit ---------------------------------------------------------

	@Test
	@DisplayName("a forced write conflict on the release is retried; the person who moves up hears it once")
	void notifiedOnceAfterTheCommit() {
		Shift shift = fullShift(1);
		ShiftAssignment anns = mongo.findOne(Query.query(Criteria.where("userId").is(ann.getId())), ShiftAssignment.class);
		String bobs = assignments.assign(bob, shift.getId(), null, true).assignment().getId();
		AtomicInteger attempts = new AtomicInteger();

		tx.run(() -> {
			if (attempts.incrementAndGet() == 1) {
				return conflictingOn("shift_assignments", new ObjectId(anns.getId()),
						() -> assignments.release(ann, anns.getId()));
			}
			return assignments.release(ann, anns.getId());
		});

		assertThat(attempts.get()).isEqualTo(2);
		assertThat(mongo.findById(bobs, ShiftAssignment.class).getStatus()).isEqualTo(ShiftAssignment.Status.ASSIGNED);
		assertThat(notices(bob, Notification.Type.SHIFT_PROMOTED)).isEqualTo(1);
		assertThat(mongo.count(Query.query(Criteria.where("action").is(AuditAction.SHIFT_ASSIGNMENT_CREATED)
				.and("metadata.promoted").is("true")), AuditLog.class)).isEqualTo(1);
		assertCountersAgree();
	}

	// --- retention and the copy of one's data --------------------------------------------

	@Test
	@DisplayName("waiting entries that never became a place go a day after the shift; the copy of one's data has them")
	void retentionAndPersonalData() {
		Instant ended = Instant.now().minus(Duration.ofDays(3));
		insertEntry(bob, ended, ShiftAssignment.Status.WAITING, true, false);
		insertEntry(carl, ended, ShiftAssignment.Status.RELEASED, true, false);
		insertEntry(dana, ended, ShiftAssignment.Status.RELEASED, true, true);
		insertEntry(erin, ended, ShiftAssignment.Status.RELEASED, false, false);
		Shift shift = fullShift(1);
		assignments.assign(bob, shift.getId(), null, true);

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<String, Object>) personalData.data(bob))
				.get("assignments");
		assertThat(rows).anySatisfy(row -> {
			assertThat(row.get("status")).isEqualTo(ShiftAssignment.Status.WAITING);
			assertThat(row.get("waitlistedAt")).isNotNull();
		});

		assertThat(retention.run()).isEqualTo(2);
		assertThat(mongo.count(Query.query(Criteria.where("userId").is(bob.getId())), ShiftAssignment.class)).isEqualTo(1);
		assertThat(mongo.count(Query.query(Criteria.where("userId").is(carl.getId())), ShiftAssignment.class)).isZero();
		assertThat(mongo.count(Query.query(Criteria.where("userId").is(dana.getId())), ShiftAssignment.class)).isEqualTo(1);
		assertThat(mongo.count(Query.query(Criteria.where("userId").is(erin.getId())), ShiftAssignment.class)).isEqualTo(1);
	}

	// --- helpers -------------------------------------------------------------------------

	/** A shift at [base] with an upper limit of [max], Ann on it and full when [max] is 1. */
	private Shift fullShift(int max) {
		Shift shift = shift(event(base, Duration.ofHours(10), true), base, Duration.ofHours(2), 1, max);
		assignments.assign(ann, shift.getId(), null, true);
		return shift;
	}

	private ShiftEvent event(Instant start, Duration length, boolean open) {
		return service.createEvent(lead, project.getId(), new ShiftService.EventDraft("Sommerfest", null, "Festwiese",
				start, start.plus(length), "Europe/Berlin", open));
	}

	private Shift shift(ShiftEvent event, Instant start, Duration length, int required, Integer max) {
		String title = mongo.count(Query.query(Criteria.where("eventId").is(event.getId())), Shift.class) == 0
				? "Aufbau" : "Beiseite";
		return service.createShift(lead, event.getId(), new ShiftService.ShiftDraft(title, start, start.plus(length),
				required, max));
	}

	private Integer rank(User person, Shift shift) {
		return reads.shift(person, shift.getId()).shift().myWaitPosition();
	}

	private ShiftAssignment.Status statusOf(Shift shift, User person) {
		ShiftAssignment found = mongo.findOne(Query.query(Criteria.where("shiftId").is(shift.getId())
				.and("userId").is(person.getId())), ShiftAssignment.class);
		return found == null ? null : found.getStatus();
	}

	private long waitingOn(Shift shift) {
		return mongo.count(Query.query(Criteria.where("shiftId").is(shift.getId())
				.and("status").is(ShiftAssignment.Status.WAITING)), ShiftAssignment.class);
	}

	private void overdue(String assignmentId) {
		mongo.updateFirst(Query.query(Criteria.where("_id").is(assignmentId)),
				new Update().set("confirmBy", Instant.now().minus(Duration.ofMinutes(1))), ShiftAssignment.class);
	}

	private void insertEntry(User person, Instant endedAt, ShiftAssignment.Status status, boolean waitlisted,
			boolean promoted) {
		mongo.insert(ShiftAssignment.builder().shiftId("old-" + person.getUsername()).eventId("old")
				.projectId(project.getId()).userId(person.getId()).startsAt(endedAt.minus(Duration.ofHours(2)))
				.endsAt(endedAt).status(status).waitlistedAt(waitlisted ? endedAt.minus(Duration.ofDays(5)) : null)
				.promotedAt(promoted ? endedAt.minus(Duration.ofDays(2)) : null).build());
	}

	private static String statusOfRefusal(Runnable call) {
		try {
			call.run();
		}
		catch (com.ahmadre.hinata.common.ApiException refused) {
			return refused.getMessageKey();
		}
		throw new AssertionError("expected a refusal");
	}

	private long notices(User user, Notification.Type type) {
		Criteria criteria = user == null ? new Criteria() : Criteria.where("userId").is(user.getId());
		if (type != null) {
			criteria = criteria.and("type").is(type);
		}
		return mongo.count(Query.query(criteria), Notification.class);
	}

	private Notification one(User user, Notification.Type type) {
		return mongo.findOne(Query.query(Criteria.where("userId").is(user.getId()).and("type").is(type)),
				Notification.class);
	}

	private void assertCountersAgree() {
		for (Shift shift : mongo.findAll(Shift.class)) {
			long active = mongo.count(Query.query(Criteria.where("shiftId").is(shift.getId())
					.and("status").in(ShiftAssignment.Status.ACTIVE)), ShiftAssignment.class);
			assertThat(shift.assigned()).as("counter of %s", shift.getId()).isEqualTo((int) active);
		}
	}

	private <T> T conflictingOn(String collection, ObjectId id, Supplier<T> work) {
		try (ClientSession other = client.startSession()) {
			other.startTransaction();
			client.getDatabase(mongo.getDb().getName()).getCollection(collection)
					.updateOne(other, Filters.eq("_id", id), Updates.inc("conflictProbe", 1));
			try {
				return work.get();
			}
			finally {
				other.abortTransaction();
			}
		}
	}

	private List<Integer> race(List<? extends Supplier<Integer>> calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.size());
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		for (Supplier<Integer> call : calls) {
			Callable<Integer> task = () -> {
				go.await();
				return call.get();
			};
			futures.add(pool.submit(task));
		}
		go.countDown();
		List<Integer> results = new ArrayList<>();
		for (Future<Integer> future : futures) {
			results.add(future.get(60, TimeUnit.SECONDS));
		}
		pool.shutdown();
		return results;
	}

	private static List<Integer> range(int from, int to) {
		List<Integer> out = new ArrayList<>();
		for (int i = from; i <= to; i++) {
			out.add(i);
		}
		return out;
	}

	private List<User> crowd(int size) {
		List<User> crowd = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			crowd.add(user("helper" + i));
		}
		project = projects.findById(project.getId()).orElseThrow();
		crowd.forEach(person -> project.getMemberIds().add(person.getId()));
		project = projects.save(project);
		return crowd;
	}

	/** Mail and push at any hour, so the test does not depend on the weekday it runs on. */
	private User user(String name) {
		NotificationPreferences prefs = NotificationPreferences.defaults();
		prefs.setSchedule(NotificationPreferences.Schedule.ALWAYS);
		return users.save(User.builder().username(name).email(name + "@waitlist.test")
				.displayName(Character.toUpperCase(name.charAt(0)) + name.substring(1)).locale("de")
				.notificationPreferences(prefs)
				.roles(new HashSet<>(Set.of(Role.MEMBER))).active(true).build());
	}

	private String token(User user) {
		return tokens.issue(user).accessToken();
	}

	private static JsonNode json(HttpResponse<String> response) throws Exception {
		return JSON.readTree(response.body());
	}

	private HttpResponse<String> get(String path, String token) {
		return send(HttpRequest.newBuilder(url(path)).header("Authorization", "Bearer " + token).GET().build());
	}

	private HttpResponse<String> post(String path, String token) {
		return send(HttpRequest.newBuilder(url(path)).header("Authorization", "Bearer " + token)
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.POST(HttpRequest.BodyPublishers.ofString("")).build());
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
