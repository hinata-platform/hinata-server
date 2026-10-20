package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.TokenService;
import com.ahmadre.hinata.availability.TimeOff;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.team.ProjectAccess;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.team.TeamRole;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import org.bson.Document;
import org.bson.types.ObjectId;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shift planning's backend against a real MongoDB replica set (HIN-43): the rules, the assignment
 * path under real parallel requests, delivery after the commit, the read models and what they
 * cost, the rights per route, and the data a person is owed or loses after the retention period.
 *
 * <p>The races run as parallel requests against the sign-up route of HIN-45.
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
@Import(ShiftPlanningIntegrationTest.Probes.class)
@Testcontainers(disabledWithoutDocker = true)
class ShiftPlanningIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
	private static final Set<String> READS = Set.of("find", "aggregate", "count", "getMore", "distinct");

	/** Counts the read commands sent while {@link #counting} is on. */
	static final AtomicBoolean counting = new AtomicBoolean();
	static final List<String> commands = new CopyOnWriteArrayList<>();

	@TestConfiguration
	static class Probes {

		@Bean
		MongoClientSettingsBuilderCustomizer countReads() {
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

		@Bean
		AssignedCounter assignedCounter() {
			return new AssignedCounter();
		}

	}

	/** Counts the assignment events that reached an after-commit listener. */
	static class AssignedCounter {
		final AtomicInteger delivered = new AtomicInteger();

		@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
		void on(ShiftDomainEvents.Assigned event) {
			delivered.incrementAndGet();
		}
	}

	@LocalServerPort
	private int port;
	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private MongoClient client;
	@Autowired
	private UserRepository users;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private TeamRepository teams;
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
	private ShiftTx tx;
	@Autowired
	private ShiftCounterReconcile reconcile;
	@Autowired
	private ShiftRetentionJob retention;
	@Autowired
	private ShiftPersonalData personalData;
	@Autowired
	private AssignedCounter assignedCounter;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private User lead;
	private User ann;
	private User bob;
	private User carl;
	private User outsider;
	private User otherLead;
	private Project project;
	private Project other;
	/** Ten days ahead at 08:00 UTC, so nothing here is in the past or due a reminder. */
	private Instant base;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "projects", "teams", "shift_events", "shifts", "shift_assignments",
				"shift_user_guards", "shift_planners", "shift_feed_tokens", "audit_log", "time_off", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		lead = user("lead");
		ann = user("ann");
		bob = user("bob");
		carl = user("carl");
		outsider = user("outsider");
		otherLead = user("olga");
		project = project("FAIR", lead, ann, bob, carl);
		other = project("CLUB", otherLead, ann);
		base = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(10)).plus(Duration.ofHours(8));
		assignedCounter.delivered.set(0);
	}

	@AfterEach
	void stopCounting() {
		counting.set(false);
		commands.clear();
	}

	// --- rules 1 to 7 --------------------------------------------------------------------

	@Test
	@DisplayName("an event takes several shifts, each with its own need and upper limit")
	void anEventTakesSeveralShifts() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift a = shift(event, base, hours(4), 3, null);
		Shift b = shift(event, base.plus(hours(4)), hours(4), 2, 5);

		ShiftReads.EventResponse detail = reads.event(ann, event.getId());
		assertThat(detail.shifts()).extracting(ShiftReads.ShiftResponse::id).containsExactly(a.getId(), b.getId());
		assertThat(detail.shifts().get(0).maxHelpers()).isNull();
		assertThat(detail.shifts().get(1).maxHelpers()).isEqualTo(5);
		assertThat(detail.placesRequired()).isEqualTo(5);
		assertThat(detail.shiftsUnderstaffed()).isEqualTo(2);
		assertThat(detail.timezone()).isEqualTo("Europe/Berlin");
		assertThat(detail.shifts().get(0).startsLocal()).isEqualTo(ShiftRules.local(base, "Europe/Berlin"));
	}

	@Test
	void rule1EndBeforeStartIsRefused() {
		assertThatThrownBy(() -> service.createEvent(lead, project.getId(), draft("E", base, base, null)))
				.hasMessage("error.shifts.endBeforeStart");
		ShiftEvent event = event(project, lead, base, hours(10));
		assertThatThrownBy(() -> service.createShift(lead, event.getId(),
				new ShiftService.ShiftDraft("S", base.plus(hours(2)), base.plus(hours(1)), 1, null)))
				.hasMessage("error.shifts.endBeforeStart");
	}

	@Test
	void rule2AShiftOutsideItsEventIsRefusedAlsoWhenOnlyTheEndMoves() {
		ShiftEvent event = event(project, lead, base, hours(10));
		assertThatThrownBy(() -> service.createShift(lead, event.getId(),
				new ShiftService.ShiftDraft("S", base.minus(hours(1)), base.plus(hours(1)), 1, null)))
				.hasMessage("error.shifts.shiftOutsideEvent");
		Shift shift = shift(event, base, hours(2), 1, null);
		assertThatThrownBy(() -> service.updateShift(lead, shift.getId(),
				new ShiftService.ShiftPatch(null, null, base.plus(hours(11)), null, null, null)))
				.hasMessage("error.shifts.shiftOutsideEvent");
		// Shortening the event under a shift is the same rule from the other side.
		assertThatThrownBy(() -> service.updateEvent(lead, event.getId(),
				new ShiftService.EventPatch(null, null, null, null, base.plus(hours(1)), null, null)))
				.hasMessage("error.shifts.shiftOutsideEvent");
	}

	@Test
	void rules3And4CountPeople() {
		ShiftEvent event = event(project, lead, base, hours(10));
		assertThatThrownBy(() -> shift(event, base, hours(2), 3, 2)).hasMessage("error.shifts.maxBelowRequired");
		assertThatThrownBy(() -> shift(event, base, hours(2), 0, null)).hasMessage("error.shifts.helpersOutOfRange");
		assertThatThrownBy(() -> shift(event, base, hours(2), 501, null)).hasMessage("error.shifts.helpersOutOfRange");
		assertThatThrownBy(() -> shift(event, base, hours(2), 1, 501)).hasMessage("error.shifts.helpersOutOfRange");
	}

	@Test
	void rule5LoweringTheLimitBelowWhoIsThereIsAConflictAndRemovesNobody() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift shift = shift(event, base, hours(2), 1, 5);
		for (User person : List.of(ann, bob, carl)) {
			assignments.assign(lead, shift.getId(), person.getId(), false);
		}
		assertThatThrownBy(() -> service.updateShift(lead, shift.getId(),
				new ShiftService.ShiftPatch(null, null, null, null, 2, null)))
				.isInstanceOfSatisfying(ApiException.class, error -> {
					assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(error.getMessageKey()).isEqualTo("error.shifts.maxBelowAssigned");
					assertThat(error.getDetails()).containsEntry("assigned", "3");
				});
		assertThat(activeOn(shift)).isEqualTo(3);
		assertThat(service.updateShift(lead, shift.getId(),
				new ShiftService.ShiftPatch(null, null, null, null, 3, null)).item().getMaxHelpers()).isEqualTo(3);
		assertThat(service.updateShift(lead, shift.getId(),
				new ShiftService.ShiftPatch(null, null, null, null, null, true)).item().getMaxHelpers()).isNull();
	}

	@Test
	void rule6AnInvalidZoneIsRefused() {
		assertThatThrownBy(() -> service.createEvent(lead, project.getId(), draft("E", base, base.plus(hours(2)),
				"Mars/Olympus"))).hasMessage("error.user.invalidTimezone");
	}

	@Test
	void rule7Limits() {
		assertThatThrownBy(() -> service.createEvent(lead, project.getId(),
				draft("E", base, base.plus(Duration.ofDays(15)), null))).hasMessage("error.shifts.eventTooLong");
		ShiftEvent event = event(project, lead, base, Duration.ofDays(3));
		assertThatThrownBy(() -> shift(event, base, hours(25), 1, null)).hasMessage("error.shifts.shiftTooLong");
		assertThatThrownBy(() -> service.createEvent(lead, project.getId(),
				draft("x".repeat(121), base, base.plus(hours(1)), null))).hasMessage("error.shifts.textTooLong");
		List<Shift> many = new ArrayList<>();
		for (int i = 0; i < ShiftRules.SHIFTS_PER_EVENT_MAX; i++) {
			many.add(Shift.builder().eventId(event.getId()).projectId(project.getId()).startsAt(base)
					.endsAt(base.plus(hours(1))).requiredHelpers(1).assignedCount(0).cancelled(false).deleted(false).build());
		}
		mongo.insertAll(many);
		assertThatThrownBy(() -> shift(event, base, hours(1), 1, null)).hasMessage("error.shifts.tooManyShifts");
	}

	@Test
	@DisplayName("a batch creates every day or none, and names the days that failed")
	void batchIsAllOrNothing() {
		ShiftEvent event = event(project, lead, base, Duration.ofDays(3));
		LocalDate first = LocalDate.ofInstant(base, ZoneId.of("Europe/Berlin"));
		ShiftService.ShiftDraft template = new ShiftService.ShiftDraft("Stand", base.plus(hours(1)), base.plus(hours(5)), 2, 3);

		assertThatThrownBy(() -> service.createShifts(lead, event.getId(),
				new ShiftService.BatchDraft(template, List.of(first, first.plusDays(1), first.plusDays(5)))))
				.isInstanceOfSatisfying(ApiException.class, error -> {
					assertThat(error.getMessageKey()).isEqualTo("error.shifts.batchInvalid");
					assertThat(error.getDetails()).containsEntry(first.plusDays(5).toString(), "error.shifts.shiftOutsideEvent");
				});
		assertThat(mongo.count(new Query(), Shift.class)).isZero();

		List<Shift> created = service.createShifts(lead, event.getId(),
				new ShiftService.BatchDraft(template, List.of(first, first.plusDays(1), first.plusDays(2))));
		assertThat(created).hasSize(3);
		assertThat(created).extracting(shift -> ShiftRules.local(shift.getStartsAt(), "Europe/Berlin").substring(11))
				.containsOnly(ShiftRules.local(base.plus(hours(1)), "Europe/Berlin").substring(11));
	}

	// --- rules 8 to 12 -------------------------------------------------------------------

	@Test
	@DisplayName("moving an event carries shifts and assignments along, reports collisions and owes a new reminder")
	void rule8MovingAnEvent() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift shift = shift(event, base, hours(2), 1, null);
		assignments.assign(lead, shift.getId(), ann.getId(), false);
		mongo.updateMulti(Query.query(Criteria.where("shiftId").is(shift.getId())),
				new Update().set("reminderSentAt", Instant.now()), ShiftAssignment.class);
		// Ann also covers a shift in the other project, three hours after this one.
		ShiftEvent club = event(other, otherLead, base, hours(10));
		Shift clubShift = shift(club, base.plus(hours(3)), hours(2), 1, null);
		assignments.assign(otherLead, clubShift.getId(), ann.getId(), false);

		ShiftService.Change<ShiftEvent> moved = service.updateEvent(lead, event.getId(),
				new ShiftService.EventPatch(null, null, null, base.plus(hours(3)), null, null, null));

		assertThat(moved.item().getStartsAt()).isEqualTo(base.plus(hours(3)));
		assertThat(moved.item().getEndsAt()).isEqualTo(base.plus(hours(13)));
		Shift after = mongo.findById(shift.getId(), Shift.class);
		assertThat(after.getStartsAt()).isEqualTo(base.plus(hours(3)));
		ShiftAssignment carried = mongo.findOne(Query.query(Criteria.where("shiftId").is(shift.getId())),
				ShiftAssignment.class);
		assertThat(carried.getStartsAt()).isEqualTo(base.plus(hours(3)));
		assertThat(carried.getEndsAt()).isEqualTo(base.plus(hours(5)));
		assertThat(carried.getReminderSentAt()).isNull();
		// The collision is reported, not refused; the lead cannot see the club, so its shift is unnamed.
		assertThat(moved.warnings()).containsExactly(new ShiftService.Warning(ann.getId(), shift.getId(), null));
	}

	@Test
	void rule8AReminderInsideTheNextDayIsKept() {
		Instant soon = Instant.now().plus(hours(2)).truncatedTo(ChronoUnit.SECONDS);
		ShiftEvent event = event(project, lead, soon, hours(6));
		Shift shift = shift(event, soon, hours(1), 1, null);
		assignments.assign(lead, shift.getId(), ann.getId(), false);
		Instant sent = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		mongo.updateMulti(new Query(), new Update().set("reminderSentAt", sent), ShiftAssignment.class);

		service.updateEvent(lead, event.getId(), new ShiftService.EventPatch(null, null, null, soon.plus(hours(1)), null, null, null));

		assertThat(mongo.findOne(new Query(), ShiftAssignment.class).getReminderSentAt()).isEqualTo(sent);
	}

	@Test
	void rule9MovingAShiftCarriesItsAssignmentsAndWarns() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift early = shift(event, base, hours(2), 1, null);
		Shift late = shift(event, base.plus(hours(4)), hours(2), 1, null);
		assignments.assign(lead, early.getId(), ann.getId(), false);
		assignments.assign(lead, late.getId(), ann.getId(), false);

		ShiftService.Change<Shift> moved = service.updateShift(lead, late.getId(),
				new ShiftService.ShiftPatch(null, base.plus(hours(1)), base.plus(hours(3)), null, null, null));

		assertThat(mongo.findOne(Query.query(Criteria.where("shiftId").is(late.getId())), ShiftAssignment.class)
				.getStartsAt()).isEqualTo(base.plus(hours(1)));
		assertThat(moved.warnings()).containsExactly(new ShiftService.Warning(ann.getId(), late.getId(), early.getId()));
	}

	@Test
	@DisplayName("a cancelled event blocks no replacement, reminds nobody, is nobody's next shift and can be deleted")
	void rule10Cancelling() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift shift = shift(event, base, hours(2), 1, null);
		assignments.assign(ann, shift.getId(), ann.getId(), true);

		service.cancelEvent(lead, event.getId());

		assertThat(mongo.findById(shift.getId(), Shift.class).isCancelled()).isTrue();
		ShiftAssignment held = mongo.findOne(Query.query(Criteria.where("shiftId").is(shift.getId())), ShiftAssignment.class);
		assertThat(held.isCancelled()).isTrue();
		assertThat(held.active()).isTrue();
		assertThatThrownBy(() -> assignments.assign(bob, shift.getId(), bob.getId(), true))
				.hasMessage("error.shifts.shiftUnavailable");
		ShiftEvent replacement = event(project, lead, base, hours(10));
		Shift instead = shift(replacement, base, hours(2), 1, null);
		assertThat(assignments.assign(ann, instead.getId(), ann.getId(), true).assignment().active()).isTrue();
		assertThat(reads.next(ann)).get().extracting(row -> row.shift().id()).isEqualTo(instead.getId());
		// The reminder index (HIN-46) holds only what is still owed one.
		assertThat(mongo.count(Query.query(Criteria.where("cancelled").is(false).and("reminderSentAt").is(null)
				.and("status").in(ShiftAssignment.Status.ACTIVE)), ShiftAssignment.class)).isEqualTo(1);

		service.deleteEvent(lead, event.getId());
		assertThat(mongo.findById(event.getId(), ShiftEvent.class)).isNull();
		assertThat(mongo.count(Query.query(Criteria.where("eventId").is(event.getId())), ShiftAssignment.class)).isZero();
	}

	@Test
	void rule11DeletingAShiftDeletesItsAssignmentsAndSaysHowMany() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift shift = shift(event, base, hours(2), 1, null);
		assignments.assign(lead, shift.getId(), ann.getId(), false);
		assignments.assign(lead, shift.getId(), bob.getId(), false);

		service.deleteShift(lead, shift.getId());

		assertThat(mongo.findById(shift.getId(), Shift.class)).isNull();
		assertThat(mongo.count(Query.query(Criteria.where("shiftId").is(shift.getId())), ShiftAssignment.class)).isZero();
		AuditLog record = mongo.findOne(Query.query(Criteria.where("action").is(AuditAction.SHIFT_DELETED)), AuditLog.class);
		assertThat(record.getMetadata()).containsEntry("assignmentsRemoved", "2").doesNotContainValue("S");
	}

	@Test
	@DisplayName("deleting a shift against parallel sign-ups leaves no assignment behind (tombstone)")
	void rule11TombstoneAgainstAParallelSignUp() throws Exception {
		ShiftEvent event = event(project, lead, base, hours(10));
		List<User> crowd = crowd(12);
		for (int round = 0; round < 3; round++) {
			Shift shift = shift(event, base, hours(2), 1, null);
			ExecutorService pool = Executors.newFixedThreadPool(crowd.size() + 1);
			CountDownLatch go = new CountDownLatch(1);
			List<Future<?>> done = new ArrayList<>();
			for (User person : crowd) {
				done.add(pool.submit(() -> {
					go.await();
					return post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", token(person)).statusCode();
				}));
			}
			done.add(pool.submit(() -> {
				go.await();
				Thread.sleep(3);
				return delete("/api/v1/shifts/shifts/" + shift.getId(), token(lead)).statusCode();
			}));
			go.countDown();
			for (Future<?> future : done) {
				future.get(60, TimeUnit.SECONDS);
			}
			pool.shutdown();
			assertThat(mongo.findById(shift.getId(), Shift.class)).isNull();
			assertThat(mongo.count(Query.query(Criteria.where("shiftId").is(shift.getId())), ShiftAssignment.class))
					.as("orphans of round %d", round).isZero();
		}
	}

	@Test
	void rule12AnEventWithPeopleOnItIsDeletedOnlyAfterCancelling() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift shift = shift(event, base, hours(2), 1, null);
		assignments.assign(lead, shift.getId(), ann.getId(), false);

		assertThatThrownBy(() -> service.deleteEvent(lead, event.getId())).hasMessage("error.shifts.eventHasAssignments");
		// The tombstones of the refused attempt were rolled back with it.
		assertThat(mongo.findById(shift.getId(), Shift.class).isDeleted()).isFalse();
		assertThat(auditCount(AuditAction.SHIFT_EVENT_DELETED)).isZero();

		service.cancelEvent(lead, event.getId());
		service.deleteEvent(lead, event.getId());
		assertThat(mongo.count(new Query(), Shift.class)).isZero();
		assertThat(auditCount(AuditAction.SHIFT_EVENT_DELETED)).isEqualTo(1);
	}

	@Test
	@DisplayName("rule 13: an unseen id is 404, a non-member under /projects/{id} gets 403")
	void rule13RightsPerRoute() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift shift = shift(event, base, hours(2), 1, null);
		String stranger = token(outsider);
		String member = token(ann);

		assertThat(get("/api/v1/shifts/events/" + event.getId(), stranger).statusCode()).isEqualTo(404);
		assertThat(get("/api/v1/shifts/shifts/" + shift.getId(), stranger).statusCode()).isEqualTo(404);
		assertThat(patch("/api/v1/shifts/events/" + event.getId(), "{\"title\":\"x\"}", stranger).statusCode()).isEqualTo(404);
		assertThat(delete("/api/v1/shifts/shifts/" + shift.getId(), stranger).statusCode()).isEqualTo(404);
		assertThat(get("/api/v1/shifts/events/" + new ObjectId(), member).statusCode()).isEqualTo(404);
		String window = "?from=" + base.minus(hours(1)) + "&to=" + base.plus(hours(20));
		assertThat(get("/api/v1/projects/" + project.getId() + "/shifts/events" + window, stranger).statusCode()).isEqualTo(403);
		assertThat(post("/api/v1/projects/" + project.getId() + "/shifts/events",
				body(draft("E", base, base.plus(hours(1)), null)), stranger).statusCode()).isEqualTo(403);
		assertThat(get("/api/v1/shifts/calendar" + window + "&projectId=" + project.getId(), stranger).statusCode())
				.isEqualTo(403);

		// A member reads but does not plan.
		assertThat(get("/api/v1/shifts/events/" + event.getId(), member).statusCode()).isEqualTo(200);
		assertThat(patch("/api/v1/shifts/events/" + event.getId(), "{\"title\":\"x\"}", member).statusCode()).isEqualTo(403);
		assertThat(post("/api/v1/projects/" + project.getId() + "/shifts/events",
				body(draft("E", base, base.plus(hours(1)), null)), member).statusCode()).isEqualTo(403);
		assertThat(post("/api/v1/shifts/events/" + event.getId() + "/shifts",
				"{\"startsAt\":\"" + base + "\",\"endsAt\":\"" + base.plus(hours(1)) + "\"}", member).statusCode()).isEqualTo(403);
	}

	// --- the assignment path -------------------------------------------------------------

	@Test
	@DisplayName("a shift needing three takes three, and a fourth only while there is no limit reached")
	void needAndLimit() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift open = shift(event, base, hours(2), 3, null);
		Shift capped = shift(event, base.plus(hours(3)), hours(2), 3, 3);
		List<User> crowd = crowd(4);
		for (User person : crowd) {
			assignments.assign(lead, open.getId(), person.getId(), false);
		}
		assertThat(mongo.findById(open.getId(), Shift.class).assigned()).isEqualTo(4);
		for (User person : crowd.subList(0, 3)) {
			assignments.assign(lead, capped.getId(), person.getId(), false);
		}
		assertThatThrownBy(() -> assignments.assign(lead, capped.getId(), crowd.get(3).getId(), false))
				.isInstanceOfSatisfying(ApiException.class, error -> {
					assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(error.getMessageKey()).isEqualTo("error.shifts.shiftFull");
				});
		assertCountersAgree();
	}

	@Test
	void nonMembersAndInactiveAccountsCannotBeAssigned() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift shift = shift(event, base, hours(2), 1, null);
		User asleep = user("asleep");
		addMember(asleep);
		asleep.setActive(false);
		users.save(asleep);

		for (User target : List.of(outsider, asleep)) {
			assertThatThrownBy(() -> assignments.assign(lead, shift.getId(), target.getId(), false))
					.isInstanceOfSatisfying(ApiException.class, error -> {
						assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
						assertThat(error.getMessageKey()).isEqualTo("error.shifts.assigneeNotMember");
					});
		}
		// A member cannot assign anybody else.
		assertThatThrownBy(() -> assignments.assign(ann, shift.getId(), bob.getId(), false))
				.hasMessage("error.shifts.notPlanner");
	}

	@Test
	@DisplayName("signing up again after declining or giving up reuses the one document per person and shift")
	void reEnteringKeepsOneDocument() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift shift = shift(event, base, hours(2), 1, 1);
		ShiftAssignment first = assignments.assign(lead, shift.getId(), ann.getId(), false).assignment();
		assignments.decline(ann, first.getId());
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isZero();
		ShiftAssignment again = assignments.assign(ann, shift.getId(), ann.getId(), true).assignment();
		assertThat(again.getId()).isEqualTo(first.getId());
		assertThat(again.getStatus()).isEqualTo(ShiftAssignment.Status.CONFIRMED);
		assignments.release(ann, first.getId());
		// Releasing twice gives the place back once.
		assignments.release(ann, first.getId());
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isZero();
		assignments.assign(lead, shift.getId(), ann.getId(), false);

		assertThat(mongo.count(Query.query(Criteria.where("shiftId").is(shift.getId())), ShiftAssignment.class)).isEqualTo(1);
		assertThatThrownBy(() -> assignments.assign(lead, shift.getId(), ann.getId(), false))
				.hasMessage("error.shifts.alreadyAssigned");
		assertCountersAgree();
	}

	@Test
	@DisplayName("an overlap is found across projects and named only to who may see the other one")
	void overlapAcrossProjects() {
		ShiftEvent club = event(other, otherLead, base, hours(10));
		Shift clubShift = shift(club, base, hours(3), 1, null);
		assignments.assign(otherLead, clubShift.getId(), ann.getId(), false);
		ShiftEvent fair = event(project, lead, base, hours(10));
		Shift fairShift = shift(fair, base.plus(hours(2)), hours(2), 1, null);

		assertThatThrownBy(() -> assignments.assign(lead, fairShift.getId(), ann.getId(), false))
				.isInstanceOfSatisfying(ApiException.class, error -> {
					assertThat(error.getMessageKey()).isEqualTo("error.shifts.overlappingAssignment");
					assertThat(error.getDetails()).doesNotContainKey("shiftId").doesNotContainKey("eventId");
				});
		assertThatThrownBy(() -> assignments.assign(ann, fairShift.getId(), ann.getId(), true))
				.isInstanceOfSatisfying(ApiException.class, error ->
						assertThat(error.getDetails()).containsEntry("shiftId", clubShift.getId()));
		// Back to back is fine.
		Shift after = shift(fair, base.plus(hours(3)), hours(2), 1, null);
		assertThat(assignments.assign(ann, after.getId(), ann.getId(), true).assignment().active()).isTrue();
	}

	@Test
	void signUpClosedOnlyStopsSigningUp() {
		ShiftEvent event = service.createEvent(lead, project.getId(), new ShiftService.EventDraft("Closed", null, null,
				base, base.plus(hours(10)), "Europe/Berlin", false));
		Shift shift = shift(event, base, hours(2), 1, null);
		assertThatThrownBy(() -> assignments.assign(ann, shift.getId(), ann.getId(), true))
				.hasMessage("error.shifts.selfAssignDisabled");
		assertThat(assignments.assign(lead, shift.getId(), ann.getId(), false).assignment().getStatus())
				.isEqualTo(ShiftAssignment.Status.ASSIGNED);
	}

	@Test
	@DisplayName("the absence hint goes to the person and to who may see it, and a free Saturday is no absence")
	void absenceHint() {
		ServerSettings current = settings.get();
		if (current.getTimeTracking() == null) {
			current.setTimeTracking(new ServerSettings.TimeTracking());
		}
		current.getTimeTracking().setAdvancedEnabled(true);
		current.getTimeTracking().setAbsenceManagementEnabled(true);
		settings.save(current);
		ZoneId berlin = ZoneId.of("Europe/Berlin");
		LocalDate day = LocalDate.ofInstant(base, berlin);
		mongo.insert(TimeOff.builder().userId(ann.getId()).type(TimeOff.Type.VACATION).from(day).to(day)
				.halfDay(false).build());
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift a = shift(event, base, hours(1), 1, null);
		Shift b = shift(event, base.plus(hours(2)), hours(1), 1, null);

		assertThat(assignments.assign(ann, a.getId(), ann.getId(), true).warnings()).containsExactly("absent");
		// The lead may not see Ann's absences under the default policy: no field, no hint.
		assertThat(assignments.assign(lead, b.getId(), ann.getId(), false).warnings()).isNull();
		// An organisation admin plans with absences (type and span): the same lead, now one, is told.
		lead.getRoles().add(Role.ORG_ADMIN);
		lead = users.save(lead);
		Shift e = shift(event, base.plus(hours(6)), hours(1), 1, null);
		assertThat(assignments.assign(lead, e.getId(), ann.getId(), false).warnings()).containsExactly("absent");

		// Bob has no working pattern at all; a Saturday is still no absence.
		LocalDate saturday = day.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.SATURDAY));
		Instant saturdayNine = saturday.atTime(9, 0).atZone(berlin).toInstant();
		ShiftEvent weekend = event(project, lead, saturdayNine, hours(8));
		Shift c = shift(weekend, saturdayNine, hours(2), 1, null);
		assertThat(assignments.assign(bob, c.getId(), bob.getId(), true).warnings()).isNull();

		// With absence management off the hint is never computed.
		current = settings.get();
		current.getTimeTracking().setAbsenceManagementEnabled(false);
		settings.save(current);
		Shift d = shift(event, base.plus(hours(4)), hours(1), 1, null);
		assertThat(assignments.assign(ann, d.getId(), ann.getId(), true).warnings()).isNull();
	}

	// --- concurrency over HTTP -----------------------------------------------------------

	@Test
	@DisplayName("twenty people race for the last place: exactly one gets it, the others wait (HIN-162)")
	void twentyForTheLastPlace() throws Exception {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift shift = shift(event, base, hours(2), 3, 3);
		assignments.assign(lead, shift.getId(), ann.getId(), false);
		assignments.assign(lead, shift.getId(), bob.getId(), false);
		List<User> crowd = crowd(20);

		List<Integer> codes = race(crowd.stream().map(person ->
				(Supplier<Integer>) () -> post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", token(person))
						.statusCode()).toList());

		assertThat(codes).containsOnly(201);
		assertThat(mongo.count(Query.query(Criteria.where("shiftId").is(shift.getId())
				.and("status").is(ShiftAssignment.Status.CONFIRMED)), ShiftAssignment.class)).isEqualTo(1);
		assertThat(mongo.count(Query.query(Criteria.where("shiftId").is(shift.getId())
				.and("status").is(ShiftAssignment.Status.WAITING)), ShiftAssignment.class)).isEqualTo(19);
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isEqualTo(3);
		assertCountersAgree();
	}

	@Test
	@DisplayName("one person on twenty overlapping shifts at once: exactly one sticks")
	void onePersonTwentyOverlaps() throws Exception {
		ShiftEvent event = event(project, lead, base, hours(10));
		List<Shift> overlapping = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			overlapping.add(shift(event, base.plus(Duration.ofMinutes(i)), hours(2), 1, null));
		}
		String annToken = token(ann);

		List<Integer> codes = race(overlapping.stream().map(shift ->
				(Supplier<Integer>) () -> post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", annToken)
						.statusCode()).toList());

		assertThat(codes.stream().filter(code -> code == 201).count()).isEqualTo(1);
		assertThat(mongo.count(Query.query(Criteria.where("userId").is(ann.getId())
				.and("status").in(ShiftAssignment.Status.ACTIVE)), ShiftAssignment.class)).isEqualTo(1);
		assertCountersAgree();
	}

	@Test
	@DisplayName("the same person twice on the same shift at once: one 201, one 409")
	void samePersonTwiceAtOnce() throws Exception {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift shift = shift(event, base, hours(2), 5, null);
		String annToken = token(ann);
		Supplier<Integer> signUp = () -> post("/api/v1/shifts/shifts/" + shift.getId() + "/self-assign", annToken).statusCode();

		List<Integer> codes = race(List.of(signUp, signUp));

		assertThat(codes).containsExactlyInAnyOrder(201, 409);
		assertThat(mongo.count(Query.query(Criteria.where("shiftId").is(shift.getId())), ShiftAssignment.class)).isEqualTo(1);
		assertCountersAgree();
	}

	// --- delivery after the commit -------------------------------------------------------

	@Test
	@DisplayName("a forced write conflict is retried and delivers once; the failed attempt delivers nothing")
	void deliveryAfterCommitAndNeverTwice() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift shift = shift(event, base, hours(2), 2, null);
		AtomicInteger attempts = new AtomicInteger();
		// The guard exists, as it does for anybody who signed up before.
		mongo.getCollection("shift_user_guards").insertOne(new Document("_id", new ObjectId(ann.getId())).append("seq", 0L));

		ShiftAssignments.Assigned done = tx.run(() -> {
			if (attempts.incrementAndGet() == 1) {
				return conflictingOn("shift_user_guards", new ObjectId(ann.getId()),
						() -> assignments.assign(lead, shift.getId(), ann.getId(), false));
			}
			return assignments.assign(lead, shift.getId(), ann.getId(), false);
		});

		assertThat(attempts.get()).isEqualTo(2);
		assertThat(done.assignment().active()).isTrue();
		assertThat(assignedCounter.delivered.get()).isEqualTo(1);
		assertCountersAgree();

		AtomicInteger moves = new AtomicInteger();
		tx.run(() -> {
			if (moves.incrementAndGet() == 1) {
				return conflictingOn("shift_events", new ObjectId(event.getId()), () -> service.updateEvent(lead,
						event.getId(), new ShiftService.EventPatch("Renamed", null, null, null, null, null, null)));
			}
			return service.updateEvent(lead, event.getId(),
					new ShiftService.EventPatch("Renamed", null, null, null, null, null, null));
		});
		assertThat(moves.get()).isEqualTo(2);
		assertThat(auditCount(AuditAction.SHIFT_EVENT_UPDATED)).isEqualTo(1);
		AuditLog record = mongo.findOne(Query.query(Criteria.where("action").is(AuditAction.SHIFT_EVENT_UPDATED)),
				AuditLog.class);
		assertThat(record.getMetadata().values()).noneMatch(value -> value.contains("Renamed"));
	}

	// --- read models ---------------------------------------------------------------------

	@Test
	@DisplayName("every read model costs the same number of queries for one row as for many")
	void readModelsHaveNoNPlusOne() {
		ShiftEvent first = populatedEvent(base, 1);
		Instant from = base.minus(hours(1));
		Instant to = base.plus(Duration.ofDays(20));
		int eventsOne = reads(() -> reads.events(ann, from, to, null, 0, 25));
		int detailOne = reads(() -> reads.event(ann, first.getId()));
		int calendarOne = reads(() -> reads.calendar(ann, from, to, null, null, true, null));
		int mineOne = reads(() -> reads.mine(ann, from, to, 0, 25));
		String someShift = mongo.findOne(Query.query(Criteria.where("eventId").is(first.getId())), Shift.class).getId();
		int shiftOne = reads(() -> reads.shift(ann, someShift));

		for (int i = 1; i < 6; i++) {
			populatedEvent(base.plus(Duration.ofDays(i * 2L)), 4);
		}
		ShiftEvent big = populatedEvent(base.plus(Duration.ofDays(15)), 8);

		assertThat(reads(() -> reads.events(ann, from, to, null, 0, 25))).isEqualTo(eventsOne);
		assertThat(reads(() -> reads.event(ann, big.getId()))).isEqualTo(detailOne);
		assertThat(reads(() -> reads.calendar(ann, from, to, null, null, true, null))).isEqualTo(calendarOne);
		assertThat(reads(() -> reads.mine(ann, from, to, 0, 25))).isEqualTo(mineOne);
		String bigShift = mongo.findOne(Query.query(Criteria.where("eventId").is(big.getId())), Shift.class).getId();
		assertThat(reads(() -> reads.shift(ann, bigShift))).isEqualTo(shiftOne);
	}

	@Test
	@DisplayName("users named once, mine and canPlan right, the calendar capped and saying so")
	void readModelsSayTheRightThings() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift a = shift(event, base, hours(2), 2, null);
		Shift b = shift(event, base.plus(hours(3)), hours(2), 1, 1);
		Shift c = shift(event, base.plus(hours(6)), hours(2), 1, null);
		assignments.assign(ann, a.getId(), ann.getId(), true);
		assignments.assign(lead, b.getId(), bob.getId(), false);
		assignments.assign(lead, c.getId(), ann.getId(), false);

		ShiftReads.EventResponse forAnn = reads.event(ann, event.getId());
		assertThat(forAnn.users()).containsOnlyKeys(ann.getId(), bob.getId());
		assertThat(forAnn.users().get(ann.getId()).name()).isEqualTo("Ann");
		assertThat(forAnn.mineCount()).isEqualTo(2);
		assertThat(forAnn.canPlan()).isFalse();
		assertThat(forAnn.shifts()).extracting(ShiftReads.ShiftResponse::mine).containsExactly(true, false, true);
		// The caller's own place rides along, from the read that already decides "mine".
		assertThat(forAnn.shifts()).extracting(row -> row.myAssignment() == null ? null : row.myAssignment().status())
				.containsExactly(ShiftAssignment.Status.CONFIRMED, null, ShiftAssignment.Status.ASSIGNED);
		ShiftAssignment annOnC = mongo.findOne(Query.query(Criteria.where("shiftId").is(c.getId())
				.and("userId").is(ann.getId())), ShiftAssignment.class);
		assertThat(forAnn.shifts().get(2).myAssignment().id()).isEqualTo(annOnC.getId());
		assertThat(reads.shift(ann, c.getId()).shift().myAssignment().id()).isEqualTo(annOnC.getId());
		assertThat(reads.shift(bob, a.getId()).shift().myAssignment()).isNull();
		assertThat(reads.calendar(ann, base.minus(hours(1)), base.plus(hours(20)), null, null, null, null).shifts())
				.extracting(row -> row.myAssignment() == null ? null : row.myAssignment().id())
				.containsExactly(mongo.findOne(Query.query(Criteria.where("shiftId").is(a.getId())
						.and("userId").is(ann.getId())), ShiftAssignment.class).getId(), null, annOnC.getId());
		assertThat(forAnn.shifts()).extracting(ShiftReads.ShiftResponse::understaffed).containsExactly(true, false, false);
		assertThat(forAnn.shifts()).extracting(ShiftReads.ShiftResponse::full).containsExactly(false, true, false);
		assertThat(forAnn.placesRequired()).isEqualTo(4);
		assertThat(forAnn.placesTaken()).isEqualTo(3);
		assertThat(reads.event(lead, event.getId()).canPlan()).isTrue();

		Page<ShiftReads.EventSummary> list = reads.events(lead, base.minus(hours(1)), base.plus(hours(20)), null, 0, 25);
		assertThat(list.getContent()).singleElement().satisfies(summary -> {
			assertThat(summary.canPlan()).isTrue();
			assertThat(summary.shiftsUnderstaffed()).isEqualTo(1);
			assertThat(summary.mineCount()).isZero();
		});

		ShiftReads.CalendarResponse capped = reads.calendar(ann, base.minus(hours(1)), base.plus(hours(20)), null, null,
				null, 2);
		assertThat(capped.shifts()).hasSize(2);
		assertThat(capped.truncated()).isTrue();
		assertThat(capped.events()).containsOnlyKeys(event.getId());
		assertThat(reads.calendar(ann, base.minus(hours(1)), base.plus(hours(20)), null, true, null, null).shifts())
				.extracting(ShiftReads.ShiftResponse::id).containsExactly(a.getId());
		assertThat(reads.calendar(ann, base.minus(hours(1)), base.plus(hours(20)), null, null, false, null).shifts())
				.isEmpty();
	}

	@Test
	@DisplayName("my shifts: only my own, only where I still see the project, gone after leaving or losing the grant")
	void myShiftsFollowVisibility() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift a = shift(event, base, hours(2), 2, null);
		assignments.assign(lead, a.getId(), ann.getId(), false);
		assignments.assign(lead, a.getId(), bob.getId(), false);
		Instant from = base.minus(hours(1));
		Instant to = base.plus(hours(20));

		assertThat(reads.mine(ann, from, to, 0, 25).getContent()).singleElement()
				.satisfies(row -> assertThat(row.status()).isEqualTo(ShiftAssignment.Status.ASSIGNED));
		assertThat(reads.next(ann)).isPresent();

		project.getMemberIds().remove(ann.getId());
		projects.save(project);
		assertThat(reads.mine(ann, from, to, 0, 25).getContent()).isEmpty();
		assertThat(reads.next(ann)).isEmpty();

		// Dana reaches the project through a team only.
		User dana = user("dana");
		Team team = teams.save(Team.builder().key("OPS").name("Ops").createdBy(lead.getId())
				.projectIds(new ArrayList<>(List.of(project.getId())))
				.members(new ArrayList<>(List.of(TeamMembership.builder().userId(dana.getId()).role(TeamRole.MEMBER)
						.access(ProjectAccess.all()).build())))
				.build());
		assignments.assign(lead, a.getId(), dana.getId(), false);
		assertThat(reads.mine(dana, from, to, 0, 25).getContent()).hasSize(1);
		team.getMembers().getFirst().setAccess(ProjectAccess.none());
		teams.save(team);
		assertThat(reads.mine(dana, from, to, 0, 25).getContent()).isEmpty();
	}

	@Test
	@DisplayName("the wire shapes the app builds on")
	void wireShapes() throws Exception {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift shift = shift(event, base, hours(2), 2, 3);
		assignments.assign(ann, shift.getId(), ann.getId(), true);
		String window = "?from=" + base.minus(hours(1)) + "&to=" + base.plus(hours(20));
		String annToken = token(ann);

		JsonNode detail = json(get("/api/v1/shifts/events/" + event.getId(), annToken));
		assertThat(fields(detail)).containsExactly("id", "projectId", "title", "description", "location", "startsAt",
				"endsAt", "startsLocal", "endsLocal", "timezone", "cancelled", "selfAssignable", "placesRequired",
				"placesTaken", "shiftsUnderstaffed", "mineCount", "canPlan", "seriesId", "recurrenceLabel", "detached",
				"shifts", "users");
		assertThat(fields(detail.path("shifts").get(0))).containsExactly("id", "eventId", "projectId", "title",
				"startsAt", "endsAt", "startsLocal", "endsLocal", "requiredHelpers", "maxHelpers", "assignedCount",
				"understaffed", "full", "mine", "myAssignment", "cancelled", "assigneeIds", "waitingCount",
				"myWaitPosition", "myWaitAssignmentId", "waitingIds");
		assertThat(fields(detail.path("shifts").get(0).path("myAssignment"))).containsExactly("id", "status");
		assertThat(detail.path("shifts").get(0).path("myAssignment").path("status").asText()).isEqualTo("CONFIRMED");
		assertThat(json(get("/api/v1/shifts/events/" + event.getId(), token(bob))).path("shifts").get(0)
				.path("myAssignment").isNull()).isTrue();
		assertThat(fields(detail.path("users").path(ann.getId()))).containsExactly("name", "avatarUrl");
		JsonNode list = json(get("/api/v1/shifts/events" + window, annToken));
		assertThat(list.path("content").get(0).has("shifts")).isFalse();
		assertThat(list.path("totalElements").asInt()).isEqualTo(1);
		JsonNode calendar = json(get("/api/v1/shifts/calendar" + window, annToken));
		assertThat(fields(calendar)).containsExactly("shifts", "events", "truncated", "plannableProjectIds");
		assertThat(fields(calendar.path("events").path(event.getId()))).containsExactly("id", "title", "location",
				"timezone", "selfAssignable", "cancelled", "seriesId", "recurrenceLabel", "detached");
		JsonNode mine = json(get("/api/v1/me/shifts" + window, annToken));
		assertThat(fields(mine.path("content").get(0))).containsExactly("assignmentId", "status", "assignedBy",
				"shift", "event", "confirmBy", "confirmByLocal");
		assertThat(get("/api/v1/me/shifts/next", annToken).statusCode()).isEqualTo(200);
		assertThat(get("/api/v1/me/shifts/next", token(carl)).statusCode()).isEqualTo(204);
		JsonNode one = json(get("/api/v1/shifts/shifts/" + shift.getId(), annToken));
		assertThat(fields(one)).containsExactly("shift", "event", "users", "canPlan");

		String leadToken = token(lead);
		// A planner also gets the places to act on, each with the id "take off" needs.
		JsonNode planned = json(get("/api/v1/shifts/shifts/" + shift.getId(), leadToken));
		assertThat(fields(planned)).containsExactly("shift", "event", "users", "canPlan", "assignments");
		assertThat(fields(planned.path("assignments").get(0))).containsExactly("id", "userId", "status");
		ShiftAssignment annsPlace = mongo.findOne(Query.query(Criteria.where("shiftId").is(shift.getId())
				.and("userId").is(ann.getId())), ShiftAssignment.class);
		assertThat(planned.path("assignments").get(0).path("id").asText()).isEqualTo(annsPlace.getId());
		assertThat(planned.path("assignments").get(0).path("userId").asText()).isEqualTo(ann.getId());
		assertThat(planned.path("assignments").get(0).path("status").asText()).isEqualTo("CONFIRMED");
		assertThat(post("/api/v1/shifts/assignments/" + annsPlace.getId() + "/release", leadToken).statusCode())
				.isEqualTo(200);
		assertThat(json(get("/api/v1/shifts/shifts/" + shift.getId(), leadToken)).path("assignments")).isEmpty();
		assignments.assign(ann, shift.getId(), ann.getId(), true);
		HttpResponse<String> created = post("/api/v1/projects/" + project.getId() + "/shifts/events",
				body(draft("Open day", base, base.plus(hours(6)), null)), leadToken);
		assertThat(created.statusCode()).isEqualTo(201);
		String newId = json(created).path("id").asText();
		HttpResponse<String> batch = post("/api/v1/shifts/events/" + newId + "/shifts:batch",
				"{\"template\":{\"title\":\"Door\",\"startsAt\":\"" + base + "\",\"endsAt\":\"" + base.plus(hours(2))
						+ "\",\"requiredHelpers\":2},\"days\":[\"" + LocalDate.ofInstant(base, ZoneId.of("Europe/Berlin")) + "\"]}",
				leadToken);
		assertThat(batch.statusCode()).as(batch.body()).isEqualTo(201);
		assertThat(json(batch).isArray()).isTrue();
		HttpResponse<String> changed = patch("/api/v1/shifts/events/" + newId, "{\"startsAt\":\"" + base.plus(hours(1)) + "\"}",
				leadToken);
		assertThat(fields(json(changed))).containsExactly("event", "warnings");
		assignments.assign(lead, shift.getId(), bob.getId(), false);
		HttpResponse<String> shiftChanged = patch("/api/v1/shifts/shifts/" + shift.getId(),
				"{\"requiredHelpers\":1,\"maxHelpers\":1}", leadToken);
		assertThat(shiftChanged.statusCode()).isEqualTo(409);
		assertThat(json(shiftChanged).path("details").path("code").asText()).isEqualTo("error.shifts.maxBelowAssigned");
		assertThat(post("/api/v1/shifts/events/" + newId + "/cancel", "", leadToken).statusCode()).isEqualTo(200);
		assertThat(delete("/api/v1/shifts/events/" + newId, leadToken).statusCode()).isEqualTo(204);
	}

	@Test
	@DisplayName("with the module off every route of this stage is 404")
	void moduleOff() {
		ShiftEvent event = event(project, lead, base, hours(10));
		ServerSettings current = settings.get();
		ServerSettings.Shifts off = new ServerSettings.Shifts();
		off.setEnabled(false);
		current.setShifts(off);
		settings.save(current);
		try {
			String leadToken = token(lead);
			String window = "?from=" + base.minus(hours(1)) + "&to=" + base.plus(hours(20));
			for (String path : List.of("/api/v1/shifts/events" + window, "/api/v1/shifts/events/" + event.getId(),
					"/api/v1/shifts/calendar" + window, "/api/v1/me/shifts", "/api/v1/me/shifts/next",
					"/api/v1/projects/" + project.getId() + "/shifts/events" + window)) {
				assertThat(get(path, leadToken).statusCode()).as(path).isEqualTo(404);
			}
			assertThat(delete("/api/v1/shifts/events/" + event.getId(), leadToken).statusCode()).isEqualTo(404);
			assertThatThrownBy(() -> service.cancelEvent(lead, event.getId())).hasMessage("error.feature.disabled");
		}
		finally {
			current = settings.get();
			current.setShifts(null);
			settings.save(current);
		}
	}

	@Test
	void theIndexesTheReadsRelyOnExist() {
		List<String> assignments = new ArrayList<>();
		mongo.getCollection("shift_assignments").listIndexes().forEach(index -> assignments.add(index.getString("name")));
		assertThat(assignments).contains("user_shift", "user_start", "shift_status", "event", "reminder_due", "start_end");
		List<String> shifts = new ArrayList<>();
		mongo.getCollection("shifts").listIndexes().forEach(index -> shifts.add(index.getString("name")));
		assertThat(shifts).contains("event_start", "project_start", "live_start");
		List<String> events = new ArrayList<>();
		mongo.getCollection("shift_events").listIndexes().forEach(index -> events.add(index.getString("name")));
		assertThat(events).contains("project_start");
	}

	// --- personal data, retention, reconciliation ----------------------------------------

	@Test
	@SuppressWarnings("unchecked")
	void theExportHoldsAssignmentsPlannerRolesAndTheFeedWithoutItsToken() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift shift = shift(event, base, hours(2), 1, null);
		assignments.assign(lead, shift.getId(), ann.getId(), false);
		mongo.getCollection("shift_planners").insertOne(new Document("projectId", project.getId())
				.append("userId", ann.getId()).append("grantedBy", lead.getId()).append("createdAt", new java.util.Date()));
		mongo.getCollection("shift_feed_tokens").insertOne(new Document("userId", ann.getId())
				.append("tokenHash", "secret-hash").append("createdAt", new java.util.Date()));

		Map<String, Object> data = (Map<String, Object>) personalData.data(ann);
		List<Map<String, Object>> held = (List<Map<String, Object>>) data.get("assignments");
		assertThat(held).singleElement().satisfies(row -> {
			assertThat(row.get("shiftTitle")).isEqualTo("S");
			assertThat(row.get("eventTitle")).isEqualTo("Event");
			assertThat(row.get("assignedBy")).isEqualTo(lead.getId());
		});
		assertThat((List<?>) data.get("plannerRoles")).hasSize(1);
		assertThat(data.get("feedSubscription").toString()).doesNotContain("secret");
		assertThat(personalData.tables(ann, java.util.Locale.GERMAN)).hasSize(2);
	}

	@Test
	void retentionDeletesOldAssignmentsAndLeavesCountersAlone() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift shift = shift(event, base, hours(2), 1, null);
		assignments.assign(lead, shift.getId(), ann.getId(), false);
		Instant longAgo = Instant.now().minus(Duration.ofDays(400));
		mongo.insert(ShiftAssignment.builder().shiftId("gone").eventId("gone").projectId(project.getId())
				.userId(bob.getId()).startsAt(longAgo).endsAt(longAgo.plus(hours(2)))
				.status(ShiftAssignment.Status.CONFIRMED).build());

		assertThat(retention.run()).isEqualTo(1);
		assertThat(mongo.count(new Query(), ShiftAssignment.class)).isEqualTo(1);
		assertThat(mongo.findById(shift.getId(), Shift.class).assigned()).isEqualTo(1);
	}

	@Test
	void reconciliationCorrectsADriftedCounterAndLeavesARecentOneAlone() {
		ShiftEvent event = event(project, lead, base, hours(10));
		Shift drifted = shift(event, base, hours(2), 1, null);
		Shift busy = shift(event, base.plus(hours(3)), hours(2), 1, null);
		assignments.assign(lead, drifted.getId(), ann.getId(), false);
		mongo.updateFirst(Query.query(Criteria.where("_id").is(drifted.getId())),
				new Update().set("assignedCount", 5).set("updatedAt", Instant.now().minus(Duration.ofMinutes(10))), Shift.class);
		mongo.updateFirst(Query.query(Criteria.where("_id").is(busy.getId())),
				new Update().set("assignedCount", 2).set("updatedAt", Instant.now()), Shift.class);

		assertThat(reconcile.run()).isEqualTo(1);
		assertThat(mongo.findById(drifted.getId(), Shift.class).assigned()).isEqualTo(1);
		assertThat(mongo.findById(busy.getId(), Shift.class).assigned()).isEqualTo(2);
	}

	// --- helpers -------------------------------------------------------------------------

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

	private void assertCountersAgree() {
		for (Shift shift : mongo.findAll(Shift.class)) {
			assertThat(shift.assigned()).as("counter of %s", shift.getId()).isEqualTo(activeOn(shift));
		}
	}

	private int activeOn(Shift shift) {
		return (int) mongo.count(Query.query(Criteria.where("shiftId").is(shift.getId())
				.and("status").in(ShiftAssignment.Status.ACTIVE)), ShiftAssignment.class);
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

	/** An event with [shifts] shifts, Ann on every other one and Bob on the rest. */
	private ShiftEvent populatedEvent(Instant start, int shifts) {
		ShiftEvent event = event(project, lead, start, Duration.ofDays(1));
		for (int i = 0; i < shifts; i++) {
			Shift shift = shift(event, start.plus(hours(2L * i)), hours(1), 2, null);
			assignments.assign(lead, shift.getId(), (i % 2 == 0 ? ann : bob).getId(), false);
			assignments.assign(lead, shift.getId(), carl.getId(), false);
		}
		return event;
	}

	private List<User> crowd(int size) {
		List<User> crowd = new ArrayList<>();
		for (int i = 0; i < size; i++) {
			User person = user("helper" + i);
			crowd.add(person);
		}
		project = projects.findById(project.getId()).orElseThrow();
		crowd.forEach(person -> project.getMemberIds().add(person.getId()));
		project = projects.save(project);
		return crowd;
	}

	private void addMember(User person) {
		project = projects.findById(project.getId()).orElseThrow();
		project.getMemberIds().add(person.getId());
		project = projects.save(project);
	}

	private ShiftEvent event(Project in, User planner, Instant start, Duration length) {
		return service.createEvent(planner, in.getId(), draft("Event", start, start.plus(length), "Europe/Berlin"));
	}

	private Shift shift(ShiftEvent event, Instant start, Duration length, int required, Integer max) {
		User planner = event.getProjectId().equals(project.getId()) ? lead : otherLead;
		return service.createShift(planner, event.getId(), new ShiftService.ShiftDraft("S", start, start.plus(length),
				required, max));
	}

	private static ShiftService.EventDraft draft(String title, Instant start, Instant end, String zone) {
		return new ShiftService.EventDraft(title, null, null, start, end, zone, null);
	}

	private static Duration hours(long hours) {
		return Duration.ofHours(hours);
	}

	private User user(String name) {
		return users.save(User.builder().username(name).email(name + "@shifts.test")
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

	private String body(Object value) {
		try {
			return JSON.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
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
