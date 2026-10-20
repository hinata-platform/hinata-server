package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.TokenService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectDeleted;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Series (HIN-161) against a real MongoDB replica set: creating and materialising, the nightly
 * run racing itself and a split, "only this one" and "all future ones" on events and shifts,
 * deleting a series, the limits, and what a list pays for the labels.
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
@Import(ShiftSeriesIntegrationTest.Probes.class)
@Testcontainers(disabledWithoutDocker = true)
class ShiftSeriesIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
	private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
	private static final Set<String> READS = Set.of("find", "aggregate", "count", "getMore", "distinct");

	static final AtomicBoolean counting = new AtomicBoolean();
	static final List<String> commands = new CopyOnWriteArrayList<>();

	@TestConfiguration
	static class Probes {

		@Bean
		MongoClientSettingsBuilderCustomizer countSeriesReads() {
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
	private ProjectRepository projects;
	@Autowired
	private TokenService tokens;
	@Autowired
	private ShiftService service;
	@Autowired
	private ShiftSeriesService series;
	@Autowired
	private ShiftAssignments assignments;
	@Autowired
	private ShiftReads reads;
	@Autowired
	private ShiftPersonalData personalData;
	@Autowired
	private ApplicationEventPublisher publisher;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private User lead;
	private User ann;
	private User bob;
	private User outsider;
	private Project project;
	private LocalDate today;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "projects", "teams", "shift_events", "shifts", "shift_assignments",
				"shift_user_guards", "shift_planners", "shift_series", "notifications", "audit_log",
				"server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		lead = user("lead");
		ann = user("ann");
		bob = user("bob");
		outsider = user("outsider");
		project = projects.save(Project.builder().key("DUTY").name("DUTY").leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), ann.getId(), bob.getId()))).build());
		today = LocalDate.now(BERLIN);
	}

	@AfterEach
	void stopCounting() {
		counting.set(false);
		commands.clear();
	}

	// --- creating -----------------------------------------------------------------------

	@Test
	@DisplayName("wall-clock times and days are stored without a zone: a server in another zone reads the same series")
	void wallClockSurvivesAServerZoneChange() {
		java.util.TimeZone original = java.util.TimeZone.getDefault();
		LocalDate monday = today.plusDays(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
		try {
			// Fourteen hours ahead of UTC: midnight there is the day before in UTC.
			java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"));
			ShiftSeriesService.Created created = series.create(lead, project.getId(), new ShiftSeriesService.SeriesDraft(
					"Sprechstunde", null, null, "Europe/Berlin", monday.atTime(18, 0), 240, "FREQ=WEEKLY;BYDAY=MO", true,
					List.of(new ShiftSeriesService.TemplateDraft(null, "Beratung", 0, LocalTime.of(18, 0),
							LocalTime.of(20, 0), 1, null))));
			String id = created.series().getId();
			ShiftEvent second = occurrence(id, monday.plusWeeks(1));
			service.cancelEvent(lead, second.getId(), "this");
			int occurrences = (int) mongo.count(Query.query(Criteria.where("seriesId").is(id)), ShiftEvent.class);

			Document raw = mongo.getCollection("shift_series").find(new Document("_id", new org.bson.types.ObjectId(id)))
					.first();
			assertThat(raw.get("firstStart")).isEqualTo(monday.atTime(18, 0).toString());
			assertThat(raw.getList("shifts", Document.class).getFirst().get("start")).isEqualTo("18:00");
			assertThat(raw.getList("exdates", Object.class)).containsExactly(monday.plusWeeks(1).toString());
			assertThat(raw.get("materializedUntil")).isInstanceOf(String.class);
			assertThat(mongo.getCollection("shift_events").find(new Document("seriesId", id)).first()
					.get("occurrenceDate")).isInstanceOf(String.class);

			// Eleven hours behind UTC: the same instants are a different local day here.
			java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Pago_Pago"));
			ShiftSeries read = mongo.findById(id, ShiftSeries.class);
			assertThat(read.getFirstStart()).isEqualTo(monday.atTime(18, 0));
			assertThat(read.getShifts().getFirst().getStart()).isEqualTo(LocalTime.of(18, 0));
			assertThat(read.getShifts().getFirst().getEnd()).isEqualTo(LocalTime.of(20, 0));
			assertThat(read.getExdates()).containsExactly(monday.plusWeeks(1));
			assertThat(read.getMaterializedUntil()).isEqualTo(created.series().getMaterializedUntil());
			assertThat(occurrence(id, monday)).isNotNull();
			assertThat(occurrences(id)).allSatisfy(event ->
					assertThat(event.getOccurrenceDate().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY));

			// Pulled along again from an earlier horizon: the days that exist are found by date, none twice.
			mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
					new Update().set("materializedUntil", monday.plusWeeks(2)), ShiftSeries.class);
			series.materialize(id);
			List<ShiftEvent> after = mongo.find(Query.query(Criteria.where("seriesId").is(id)), ShiftEvent.class);
			assertThat(after).hasSize(occurrences);
			assertThat(after.stream().map(ShiftEvent::getOccurrenceDate).distinct().count()).isEqualTo(occurrences);
			assertThat(series.upcoming(read, 2)).extracting(ShiftSeriesService.Upcoming::date)
					.doesNotContain(monday.plusWeeks(1));
		}
		finally {
			java.util.TimeZone.setDefault(original);
		}
	}

	@Test
	@DisplayName("a weekly series on two weekdays materialises its events with their shifts")
	void weeklyOnTwoDays() throws Exception {
		LocalDate monday = today.plusDays(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
		String body = JSON.writeValueAsString(Map.of(
				"title", "Thekendienst", "location", "Vereinsheim", "timezone", "Europe/Berlin",
				"firstStart", monday.atTime(18, 0).toString(), "durationMinutes", 240,
				"rrule", "FREQ=WEEKLY;BYDAY=MO,WE",
				"shifts", List.of(Map.of("title", "Theke", "start", "18:00", "end", "20:00", "requiredHelpers", 2),
						Map.of("title", "Abbau", "start", "20:00", "end", "22:00", "requiredHelpers", 1, "maxHelpers", 3))));
		HttpResponse<String> created = post("/api/v1/projects/" + project.getId() + "/shifts/series", body, lead);
		assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
		JsonNode json = JSON.readTree(created.body());
		assertThat(json.get("recurrenceLabel").asText()).isEqualTo("wöchentlich Mo, Mi");
		assertThat(json.get("next")).hasSize(5);
		assertThat(json.get("next").get(0).get("startsLocal").asText()).isEqualTo(monday.atTime(18, 0) + ":00");
		assertThat(json.get("shifts").get(0).get("key").asText()).isNotBlank();
		int occurrences = json.get("occurrencesCreated").asInt();
		String seriesId = json.get("id").asText();

		List<ShiftEvent> events = occurrences(seriesId);
		assertThat(events).hasSize(occurrences);
		assertThat(events).allSatisfy(event -> {
			assertThat(event.getOccurrenceDate().getDayOfWeek()).isIn(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
			assertThat(ShiftRules.local(event.getStartsAt(), "Europe/Berlin")).endsWith("T18:00:00");
		});
		assertThat(events.get(events.size() - 1).getOccurrenceDate()).isBeforeOrEqualTo(today.plusDays(90));
		assertThat(mongo.count(Query.query(Criteria.where("eventId").is(events.get(0).getId())), Shift.class))
				.isEqualTo(2);
		assertThat(auditCount(AuditAction.SHIFT_SERIES_CREATED)).isEqualTo(1);

		// The event detail and the list carry the series and its label.
		ShiftReads.EventResponse detail = reads.event(ann, events.get(0).getId());
		assertThat(detail.seriesId()).isEqualTo(seriesId);
		assertThat(detail.recurrenceLabel()).isEqualTo("wöchentlich Mo, Mi");
		assertThat(detail.detached()).isFalse();
		HttpResponse<String> read = get("/api/v1/shifts/series/" + seriesId, ann);
		assertThat(read.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(read.body()).get("canPlan").asBoolean()).isFalse();
		assertThat(get("/api/v1/shifts/series/" + seriesId, outsider).statusCode()).isEqualTo(404);
	}

	@Test
	void dailyMonthlyCountAndUntil() {
		ShiftSeriesService.Created daily = create("FREQ=DAILY", today.atTime(9, 0), 60, List.of());
		assertThat(daily.occurrences()).isEqualTo(ShiftSeriesPlan.HORIZON_OCCURRENCES);

		ShiftSeriesService.Created monthly = create("FREQ=MONTHLY;BYMONTHDAY=15", today.atTime(9, 0), 60, List.of());
		assertThat(occurrences(monthly.series().getId())).allSatisfy(
				event -> assertThat(event.getOccurrenceDate().getDayOfMonth()).isIn(15, today.getDayOfMonth()));

		ShiftSeriesService.Created counted = create("FREQ=WEEKLY;COUNT=4", today.atTime(9, 0), 60, List.of());
		assertThat(counted.occurrences()).isEqualTo(4);
		assertThat(counted.series().isFinished()).isTrue();

		LocalDate end = today.plusDays(9);
		ShiftSeriesService.Created until = create("FREQ=DAILY;UNTIL=" + end.toString().replace("-", ""),
				today.atTime(9, 0), 60, List.of());
		assertThat(until.occurrences()).isEqualTo(10);
		assertThat(until.series().isFinished()).isTrue();
	}

	@Test
	void refusals() throws Exception {
		for (String rule : List.of("FREQ=YEARLY", "FREQ=WEEKLY;INTERVAL=101", "FREQ=HOURLY", "FREQ=MONTHLY;BYDAY=1MO")) {
			assertThatThrownBy(() -> create(rule, today.atTime(9, 0), 60, List.of())).as(rule)
					.hasMessage("error.shifts.rruleUnsupported");
		}
		assertThatThrownBy(() -> create("FREQ=WEEKLY", today.minusYears(2).atTime(9, 0), 60, List.of()))
				.hasMessage("error.shifts.seriesStartOutOfRange");
		List<ShiftSeriesService.TemplateDraft> fiftyOne = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			fiftyOne.add(template("S" + i, 9, 10, 1, null));
		}
		assertThatThrownBy(() -> create("FREQ=WEEKLY", today.atTime(9, 0), 60, fiftyOne))
				.hasMessage("error.shifts.seriesTooManyShifts");
		List<ShiftSeriesService.TemplateDraft> forty = new ArrayList<>(fiftyOne.subList(0, 40));
		assertThatThrownBy(() -> create("FREQ=DAILY", today.atTime(9, 0), 60, forty))
				.hasMessage("error.shifts.seriesTooLarge");
		assertThatThrownBy(() -> create("FREQ=WEEKLY", today.atTime(9, 0), 60, List.of(template("Spät", 11, 12, 1, null))))
				.hasMessage("error.shifts.shiftOutsideEvent");
		assertThatThrownBy(() -> series.create(ann, project.getId(), draft("FREQ=WEEKLY", today.atTime(9, 0), 60,
				List.of()))).isInstanceOfSatisfying(ApiException.class,
						error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
		assertThat(mongo.count(new Query(), ShiftSeries.class)).isZero();
		assertThat(mongo.count(new Query(), ShiftEvent.class)).isZero();

		HttpResponse<String> preview = post("/api/v1/projects/" + project.getId() + "/shifts/series:preview",
				JSON.writeValueAsString(Map.of("title", "Sprechstunde", "timezone", "Europe/Berlin",
						"firstStart", today.atTime(10, 0).toString(), "durationMinutes", 60,
						"rrule", "FREQ=MONTHLY;INTERVAL=2;COUNT=6")), lead);
		assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
		JsonNode previewJson = JSON.readTree(preview.body());
		assertThat(previewJson.get("recurrenceLabel").asText())
				.isEqualTo("alle 2 Monate am " + today.getDayOfMonth() + "., 6 Mal");
		assertThat(previewJson.get("next")).hasSize(5);
		assertThat(mongo.count(new Query(), ShiftSeries.class)).isZero();
	}

	// --- the nightly run ----------------------------------------------------------------

	@Test
	@DisplayName("the nightly run pulls a daily series to 90 days; parallel and repeated runs create nothing twice")
	void nightlyRunsDoNotDuplicate() throws Exception {
		ShiftSeriesService.Created daily = create("FREQ=DAILY", today.atTime(9, 0), 60,
				List.of(template("Dienst", 9, 10, 1, null)));
		String id = daily.series().getId();
		List<Integer> created = parallel(() -> series.materialize(id), () -> series.materialize(id));
		assertThat(created.stream().mapToInt(Integer::intValue).sum()).isEqualTo(39);
		assertThat(series.materialize(id)).isZero();
		assertThat(series.nightly()).isZero();
		List<ShiftEvent> events = occurrences(id);
		assertThat(events).hasSize(91);
		assertThat(events.stream().map(ShiftEvent::getOccurrenceDate).distinct()).hasSize(91);
		assertThat(mongo.count(new Query(), Shift.class)).isEqualTo(91);
		assertThat(mongo.findById(id, ShiftSeries.class).getMaterializedUntil()).isEqualTo(today.plusDays(90));
	}

	@Test
	@DisplayName("a project gone: the series goes with it and the nightly run creates nothing; months off: no past")
	void projectDeletedAndModulePaused() {
		ShiftSeriesService.Created weekly = create("FREQ=WEEKLY", today.atTime(9, 0), 60, List.of());
		mongo.updateFirst(Query.query(Criteria.where("_id").is(weekly.series().getId())),
				new Update().set("materializedUntil", today.minusMonths(5)), ShiftSeries.class);
		mongo.remove(new Query(), ShiftEvent.class);
		series.nightly();
		assertThat(occurrences(weekly.series().getId())).isNotEmpty()
				.allSatisfy(event -> assertThat(event.getOccurrenceDate()).isAfterOrEqualTo(today));

		publisher.publishEvent(new ProjectDeleted(project.getId()));
		assertThat(mongo.count(new Query(), ShiftSeries.class)).isZero();
		assertThat(series.nightly()).isZero();
		assertThat(mongo.count(new Query(), ShiftEvent.class)).isZero();

		// A series left behind without its project is finished by the run, not filled.
		ShiftSeries orphan = mongo.insert(ShiftSeries.builder().projectId("gone").title("X").timezone("Europe/Berlin")
				.firstStart(today.atTime(9, 0)).durationMinutes(60).rrule("FREQ=DAILY")
				.materializedUntil(today).finished(false).build());
		assertThat(series.nightly()).isZero();
		assertThat(mongo.findById(orphan.getId(), ShiftSeries.class).isFinished()).isTrue();
	}

	@Test
	@DisplayName("the nightly run beside \"all future ones\": never two occurrences on one day")
	void nightlyRunBesideASplit() throws Exception {
		ShiftSeriesService.Created daily = create("FREQ=DAILY", today.atTime(9, 0), 60,
				List.of(template("Dienst", 9, 10, 1, null)));
		String id = daily.series().getId();
		ShiftEvent pivot = occurrence(id, today.plusDays(7));
		parallel(() -> series.materialize(id),
				() -> {
					series.changeEventFuture(lead, pivot, new ShiftService.EventPatch("Frühdienst", null, null,
							pivot.getStartsAt().minus(Duration.ofHours(1)), null, null, null));
					return 0;
				});
		series.nightly();
		List<ShiftEvent> all = mongo.find(Query.query(Criteria.where("projectId").is(project.getId())),
				ShiftEvent.class);
		Map<LocalDate, Long> perDay = all.stream()
				.collect(Collectors.groupingBy(ShiftEvent::getOccurrenceDate, Collectors.counting()));
		assertThat(perDay.values()).containsOnly(1L);
		assertThat(perDay).containsKeys(today, today.plusDays(7), today.plusDays(90));
		assertThat(all.stream().filter(event -> !event.getOccurrenceDate().isBefore(today.plusDays(7))))
				.allSatisfy(event -> {
					assertThat(event.getTitle()).isEqualTo("Frühdienst");
					assertThat(ShiftRules.local(event.getStartsAt(), "Europe/Berlin")).endsWith("T08:00:00");
				});
	}

	// --- "only this one" ----------------------------------------------------------------

	@Test
	void writesToAnOccurrenceNeedAScope() throws Exception {
		ShiftSeriesService.Created weekly = create("FREQ=WEEKLY", today.plusDays(1).atTime(9, 0), 120,
				List.of(template("Dienst", 9, 10, 1, null)));
		ShiftEvent first = occurrences(weekly.series().getId()).get(0);
		assertThatThrownBy(() -> service.updateEvent(lead, first.getId(),
				new ShiftService.EventPatch("Neu", null, null, null, null, null, null)))
				.hasMessage("error.shifts.seriesScopeRequired");
		HttpResponse<String> refused = patch("/api/v1/shifts/events/" + first.getId(), "{\"title\":\"Neu\"}", lead);
		assertThat(refused.statusCode()).isEqualTo(400);
		assertThat(JSON.readTree(refused.body()).get("details").get("code").asText())
				.isEqualTo("error.shifts.seriesScopeRequired");
		assertThat(patch("/api/v1/shifts/events/" + first.getId() + "?scope=sometimes", "{\"title\":\"Neu\"}", lead)
				.statusCode()).isEqualTo(400);
		Shift shift = shiftsOf(first).get(0);
		assertThat(delete("/api/v1/shifts/shifts/" + shift.getId(), lead).statusCode()).isEqualTo(400);
		assertThat(post("/api/v1/shifts/events/" + first.getId() + "/cancel", "", lead).statusCode()).isEqualTo(400);
		HttpResponse<String> done = patch("/api/v1/shifts/events/" + first.getId() + "?scope=this",
				"{\"title\":\"Neu\"}", lead);
		assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
		assertThat(JSON.readTree(done.body()).get("event").get("detached").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("\"only this one\" detaches the occurrence, also for a shift change; later series changes pass it by")
	void onlyThisOne() {
		ShiftSeriesService.Created weekly = create("FREQ=WEEKLY", today.plusDays(1).atTime(9, 0), 180,
				List.of(template("Dienst", 9, 10, 1, null)));
		List<ShiftEvent> events = occurrences(weekly.series().getId());
		ShiftEvent first = events.get(0);
		ShiftEvent second = events.get(1);
		ShiftEvent third = events.get(2);

		service.updateEvent(lead, first.getId(), new ShiftService.EventPatch("Sonderdienst", null, null, null, null,
				null, null), "this");
		Shift shiftOfSecond = shiftsOf(second).get(0);
		service.updateShift(lead, shiftOfSecond.getId(), new ShiftService.ShiftPatch(null,
				shiftOfSecond.getStartsAt().plus(Duration.ofHours(1)), shiftOfSecond.getEndsAt().plus(Duration.ofHours(1)),
				null, null, null), "this");
		assertThat(mongo.findById(first.getId(), ShiftEvent.class).isDetached()).isTrue();
		assertThat(mongo.findById(second.getId(), ShiftEvent.class).isDetached()).isTrue();

		// A change of the whole series from the first occurrence on leaves both detached ones alone.
		ShiftSeriesService.FutureChange change = series.changeEventFuture(lead, third,
				new ShiftService.EventPatch("Spätdienst", null, null, null, null, null, null));
		assertThat(mongo.findById(first.getId(), ShiftEvent.class).getTitle()).isEqualTo("Sonderdienst");
		assertThat(mongo.findById(second.getId(), ShiftEvent.class).getSeriesId()).isEqualTo(weekly.series().getId());
		assertThat(shiftsOf(second).get(0).getStartsAt()).isEqualTo(shiftOfSecond.getStartsAt().plus(Duration.ofHours(1)));
		assertThat(mongo.findById(third.getId(), ShiftEvent.class).getSeriesId()).isEqualTo(change.series().getId());
	}

	@Test
	@DisplayName("cancelling and deleting one occurrence leave its day out of the series")
	void cancellingAndDeletingOneLeaveAGap() {
		ShiftSeriesService.Created weekly = create("FREQ=WEEKLY", today.plusDays(1).atTime(9, 0), 120,
				List.of(template("Dienst", 9, 10, 1, null)));
		List<ShiftEvent> events = occurrences(weekly.series().getId());
		service.cancelEvent(lead, events.get(1).getId(), "this");
		service.deleteEvent(lead, events.get(2).getId(), "this");
		ShiftSeries stored = mongo.findById(weekly.series().getId(), ShiftSeries.class);
		assertThat(stored.getExdates()).containsExactlyInAnyOrder(events.get(1).getOccurrenceDate(),
				events.get(2).getOccurrenceDate());
		assertThat(mongo.findById(events.get(1).getId(), ShiftEvent.class).isCancelled()).isTrue();
		assertThat(mongo.findById(events.get(2).getId(), ShiftEvent.class)).isNull();

		// A split carries the gaps over; the deleted day is not filled again.
		ShiftSeriesService.FutureChange change = series.changeEventFuture(lead, events.get(0),
				new ShiftService.EventPatch("Neu", null, null, null, null, null, null));
		assertThat(change.series().getExdates()).contains(events.get(1).getOccurrenceDate(),
				events.get(2).getOccurrenceDate());
		assertThat(mongo.count(Query.query(Criteria.where("occurrenceDate").is(events.get(2).getOccurrenceDate())),
				ShiftEvent.class)).isZero();

		// At most 366 left-out days.
		List<LocalDate> full = new ArrayList<>();
		for (int i = 0; i < ShiftSeriesPlan.EXDATES_MAX; i++) {
			full.add(today.plusYears(1).plusDays(i));
		}
		mongo.updateFirst(Query.query(Criteria.where("_id").is(change.series().getId())),
				new Update().set("exdates", full), ShiftSeries.class);
		ShiftEvent next = occurrences(change.series().getId()).get(1);
		assertThatThrownBy(() -> service.deleteEvent(lead, next.getId(), "this"))
				.hasMessage("error.shifts.seriesTooManyExdates");
		assertThat(mongo.findById(next.getId(), ShiftEvent.class)).isNotNull();
	}

	// --- "all future ones" --------------------------------------------------------------

	@Test
	@DisplayName("\"all future ones\" splits the series, counts COUNT on, recomputes times and moves people along")
	void allFutureOnes() {
		ShiftSeriesService.Created weekly = create("FREQ=WEEKLY;COUNT=10", today.plusDays(1).atTime(18, 0), 240,
				List.of(template("Theke", 18, 20, 2, null), template("Abbau", 20, 22, 1, null)));
		String oldId = weekly.series().getId();
		List<ShiftEvent> events = occurrences(oldId);
		assertThat(events).hasSize(10);
		ShiftEvent detachedOne = events.get(5);
		service.updateEvent(lead, detachedOne.getId(), new ShiftService.EventPatch("Anders", null, null, null, null,
				null, null), "this");
		service.deleteEvent(lead, events.get(6).getId(), "this");
		Shift annOnFourth = shiftsOf(events.get(3)).get(0);
		assignments.assign(lead, annOnFourth.getId(), ann.getId(), false);
		// Bob holds something at the new time already, so moving Ann's evening is fine, Bob's collides.
		Shift bobOnFourth = shiftsOf(events.get(3)).get(1);
		assignments.assign(lead, bobOnFourth.getId(), bob.getId(), false);
		ShiftEvent own = service.createEvent(lead, project.getId(), new ShiftService.EventDraft("Eigenes", null, null,
				bobOnFourth.getStartsAt().minus(Duration.ofHours(1)), bobOnFourth.getStartsAt(), "Europe/Berlin", null));
		Shift ownShift = service.createShift(lead, own.getId(), new ShiftService.ShiftDraft("x",
				own.getStartsAt(), own.getEndsAt(), 1, null));
		assignments.assign(lead, ownShift.getId(), bob.getId(), false);

		ShiftEvent pivot = events.get(2);
		ShiftService.Change<ShiftEvent> change = service.updateEvent(lead, pivot.getId(), new ShiftService.EventPatch(
				"Spätschicht", null, null, pivot.getStartsAt().minus(Duration.ofHours(1)), null, null, null), "future");

		ShiftSeries old = mongo.findById(oldId, ShiftSeries.class);
		assertThat(old.getRrule()).isEqualTo("FREQ=WEEKLY;UNTIL="
				+ pivot.getOccurrenceDate().minusDays(1).toString().replace("-", ""));
		assertThat(old.isFinished()).isTrue();
		ShiftSeries next = mongo.findById(change.item().getSeriesId(), ShiftSeries.class);
		assertThat(next.getRrule()).isEqualTo("FREQ=WEEKLY;COUNT=8");
		assertThat(next.getFirstStart()).isEqualTo(pivot.getOccurrenceDate().atTime(17, 0));
		assertThat(next.getExdates()).contains(detachedOne.getOccurrenceDate(), events.get(6).getOccurrenceDate());
		assertThat(next.getShifts()).extracting(ShiftTemplate::getStart)
				.containsExactly(LocalTime.of(17, 0), LocalTime.of(19, 0));

		// The past ones stay with the old series, the detached one too; the rest moved and was recomputed.
		assertThat(mongo.findById(events.get(0).getId(), ShiftEvent.class).getSeriesId()).isEqualTo(oldId);
		assertThat(mongo.findById(detachedOne.getId(), ShiftEvent.class).getSeriesId()).isEqualTo(oldId);
		for (int i : List.of(2, 3, 4, 7, 8, 9)) {
			ShiftEvent moved = mongo.findById(events.get(i).getId(), ShiftEvent.class);
			assertThat(moved.getSeriesId()).as("occurrence %s", i).isEqualTo(next.getId());
			assertThat(moved.getTitle()).isEqualTo("Spätschicht");
			assertThat(ShiftRules.local(moved.getStartsAt(), "Europe/Berlin")).endsWith("T17:00:00");
			assertThat(shiftsOf(moved)).extracting(Shift::getTemplateKey).doesNotContainNull().hasSize(2);
		}
		ShiftAssignment annMoved = mongo.findOne(Query.query(Criteria.where("shiftId").is(annOnFourth.getId())),
				ShiftAssignment.class);
		assertThat(ShiftRules.local(annMoved.getStartsAt(), "Europe/Berlin")).endsWith("T17:00:00");
		assertThat(change.warnings()).extracting(ShiftService.Warning::userId).containsExactly(bob.getId());
		// Each person on a moved place hears once.
		assertThat(notifications(ann, Notification.Type.SHIFT_SERIES_CHANGED)).isEqualTo(1);
		assertThat(notifications(bob, Notification.Type.SHIFT_SERIES_CHANGED)).isEqualTo(1);
		assertThat(notifications(lead, Notification.Type.SHIFT_SERIES_CHANGED)).isZero();
		assertThat(auditCount(AuditAction.SHIFT_SERIES_UPDATED)).isEqualTo(1);
		// COUNT holds over both series: two before, eight from the pivot on, the two left out included.
		assertThat(mongo.count(Query.query(Criteria.where("seriesId").is(next.getId())), ShiftEvent.class))
				.isEqualTo(6);
	}

	@Test
	@DisplayName("shift changes for all future ones go through the template key; a held template cannot go")
	void shiftTemplatesForAllFutureOnes() {
		ShiftSeriesService.Created weekly = create("FREQ=WEEKLY", today.plusDays(1).atTime(9, 0), 240,
				List.of(template("Aufbau", 9, 10, 1, null), template("Kasse", 10, 12, 2, null)));
		List<ShiftEvent> events = occurrences(weekly.series().getId());
		ShiftEvent pivot = events.get(1);
		Shift kasse = shiftsOf(pivot).get(1);

		Shift changed = service.updateShift(lead, kasse.getId(), new ShiftService.ShiftPatch("Kasse groß", null, null,
				3, null, null), "future").item();
		assertThat(changed.getTitle()).isEqualTo("Kasse groß");
		for (ShiftEvent later : occurrences(weekly.series().getId()).subList(0, 1)) {
			assertThat(shiftsOf(later).get(1).getTitle()).isEqualTo("Kasse");
		}
		String nextId = mongo.findById(pivot.getId(), ShiftEvent.class).getSeriesId();
		assertThat(occurrences(nextId)).allSatisfy(later -> {
			assertThat(shiftsOf(later)).extracting(Shift::getTitle).containsExactly("Aufbau", "Kasse groß");
			assertThat(shiftsOf(later).get(1).getRequiredHelpers()).isEqualTo(3);
		});

		// A shift added to all future ones lands on every later occurrence.
		ShiftEvent third = occurrences(nextId).get(1);
		service.createShift(lead, third.getId(), new ShiftService.ShiftDraft("Abbau",
				third.getStartsAt().plus(Duration.ofHours(3)), third.getEndsAt(), 1, null), "future");
		String thirdSeries = mongo.findById(third.getId(), ShiftEvent.class).getSeriesId();
		assertThat(occurrences(thirdSeries)).allSatisfy(later -> assertThat(shiftsOf(later)).hasSize(3));

		// Removing a template somebody holds on a later occurrence is refused, and nothing changes.
		ShiftEvent fourth = occurrences(thirdSeries).get(1);
		Shift aufbauLater = shiftsOf(fourth).get(0);
		assignments.assign(lead, aufbauLater.getId(), ann.getId(), false);
		Shift aufbauThird = shiftsOf(mongo.findById(third.getId(), ShiftEvent.class)).get(0);
		assertThatThrownBy(() -> service.deleteShift(lead, aufbauThird.getId(), "future"))
				.isInstanceOfSatisfying(ApiException.class, error -> {
					assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(error.getMessageKey()).isEqualTo("error.shifts.seriesShiftHasAssignments");
					assertThat(error.getDetails().get("shiftIds")).contains(aufbauLater.getId());
				});
		assertThat(mongo.findById(third.getId(), ShiftEvent.class).getSeriesId()).isEqualTo(thirdSeries);
		assertThat(mongo.findById(aufbauThird.getId(), Shift.class)).isNotNull();

		// Without the person it goes, from that occurrence on.
		assignments.release(lead, mongo.findOne(Query.query(Criteria.where("shiftId").is(aufbauLater.getId())),
				ShiftAssignment.class).getId());
		service.deleteShift(lead, aufbauThird.getId(), "future");
		String lastSeries = mongo.findById(third.getId(), ShiftEvent.class).getSeriesId();
		assertThat(occurrences(lastSeries)).allSatisfy(later ->
				assertThat(shiftsOf(later)).extracting(Shift::getTitle).containsExactly("Kasse groß", "Abbau"));
	}

	@Test
	@DisplayName("deleting a series from an occurrence on: unheld ones go, held ones are cancelled, the past stays")
	void deleteAllFutureOnes() {
		ShiftSeriesService.Created weekly = create("FREQ=WEEKLY", today.plusDays(1).atTime(9, 0), 120,
				List.of(template("Dienst", 9, 10, 2, null)));
		String id = weekly.series().getId();
		List<ShiftEvent> events = occurrences(id);
		ShiftEvent past = mongo.insert(ShiftEvent.builder().projectId(project.getId()).title("Früher")
				.startsAt(Instant.now().minus(Duration.ofDays(7))).endsAt(Instant.now().minus(Duration.ofDays(7))
						.plus(Duration.ofHours(1))).timezone("Europe/Berlin").cancelled(false).selfAssignable(true)
				.seriesId(id).occurrenceDate(today.minusDays(7)).detached(false).build());
		Shift held = shiftsOf(events.get(3)).get(0);
		assignments.assign(lead, held.getId(), ann.getId(), false);
		assignments.assign(lead, held.getId(), bob.getId(), false);
		Shift heldToo = shiftsOf(events.get(4)).get(0);
		assignments.assign(lead, heldToo.getId(), ann.getId(), false);

		assertThatThrownBy(() -> service.deleteEvent(lead, past.getId(), "future"))
				.hasMessage("error.shifts.seriesFromInPast");

		service.deleteEvent(lead, events.get(1).getId(), "future");
		assertThat(mongo.findById(past.getId(), ShiftEvent.class)).isNotNull();
		assertThat(mongo.findById(events.get(0).getId(), ShiftEvent.class)).isNotNull();
		assertThat(mongo.findById(events.get(1).getId(), ShiftEvent.class)).isNull();
		assertThat(mongo.findById(events.get(2).getId(), ShiftEvent.class)).isNull();
		assertThat(mongo.findById(events.get(3).getId(), ShiftEvent.class).isCancelled()).isTrue();
		assertThat(mongo.findById(held.getId(), Shift.class).isCancelled()).isTrue();
		assertThat(mongo.findById(held.getId(), Shift.class).isDeleted()).isFalse();
		assertThat(mongo.findById(events.get(4).getId(), ShiftEvent.class).isCancelled()).isTrue();
		assertThat(mongo.count(Query.query(Criteria.where("seriesId").is(id).and("cancelled").is(false)
				.and("occurrenceDate").gte(events.get(1).getOccurrenceDate())), ShiftEvent.class)).isZero();
		assertThat(notifications(ann, Notification.Type.SHIFT_SERIES_CHANGED)).isEqualTo(1);
		assertThat(notifications(bob, Notification.Type.SHIFT_SERIES_CHANGED)).isEqualTo(1);
		Notification bell = mongo.findOne(Query.query(Criteria.where("userId").is(ann.getId())
				.and("type").is(Notification.Type.SHIFT_SERIES_CHANGED)), Notification.class);
		assertThat(bell.getBody()).startsWith("2 deiner Schichten");
		assertThat(bell.getLink()).isEqualTo("/shifts");
		assertThat(auditCount(AuditAction.SHIFT_SERIES_DELETED)).isEqualTo(1);
		ShiftSeries closed = mongo.findById(id, ShiftSeries.class);
		assertThat(closed.isFinished()).isTrue();
		assertThat(series.nightly()).isZero();
	}

	// --- reads --------------------------------------------------------------------------

	@Test
	@DisplayName("occurrences behave as events: sign-up, calendar; a list pays one read for all labels")
	void occurrencesAreEvents() {
		ShiftSeriesService.Created a = create("FREQ=DAILY", today.plusDays(1).atTime(9, 0), 60,
				List.of(template("Dienst", 9, 10, 1, null)));
		ShiftSeriesService.Created b = create("FREQ=WEEKLY;INTERVAL=2", today.plusDays(1).atTime(12, 0), 60,
				List.of(template("Dienst", 12, 13, 1, null)));
		ShiftEvent first = occurrences(a.series().getId()).get(0);
		assignments.assign(ann, shiftsOf(first).get(0).getId(), null, true);
		assertThat(reads.next(ann)).hasValueSatisfying(mine -> {
			assertThat(mine.event().seriesId()).isEqualTo(a.series().getId());
			assertThat(mine.event().recurrenceLabel()).isEqualTo("täglich");
		});

		Instant from = today.plusDays(1).atStartOfDay(BERLIN).toInstant();
		Instant to = from.plus(Duration.ofDays(20));
		ShiftReads.CalendarResponse calendar = reads.calendar(ann, from, to, null, null, null, null);
		assertThat(calendar.events().values()).extracting(ShiftReads.EventHead::recurrenceLabel)
				.containsOnly("täglich", "alle 2 Wochen " + today.plusDays(1).getDayOfWeek()
						.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, java.util.Locale.GERMAN));

		commands.clear();
		counting.set(true);
		List<ShiftReads.EventSummary> page = reads.events(ann, from, to, null, 0, 100).getContent();
		counting.set(false);
		assertThat(page).hasSizeGreaterThan(20).allSatisfy(row -> assertThat(row.recurrenceLabel()).isNotBlank());
		assertThat(commands.stream().filter(command -> command.contains("shift_series"))).hasSize(1);

		// The person's data export names the series they created.
		@SuppressWarnings("unchecked")
		Map<String, Object> export = (Map<String, Object>) personalData.data(lead);
		assertThat((List<?>) export.get("createdSeries")).hasSize(2);
	}

	// --- helpers ------------------------------------------------------------------------

	private ShiftSeriesService.Created create(String rrule, LocalDateTime firstStart, int minutes,
			List<ShiftSeriesService.TemplateDraft> templates) {
		return series.create(lead, project.getId(), draft(rrule, firstStart, minutes, templates));
	}

	private static ShiftSeriesService.SeriesDraft draft(String rrule, LocalDateTime firstStart, int minutes,
			List<ShiftSeriesService.TemplateDraft> templates) {
		return new ShiftSeriesService.SeriesDraft("Bereitschaft", null, "Werkstatt", "Europe/Berlin", firstStart,
				minutes, rrule, null, templates);
	}

	private static ShiftSeriesService.TemplateDraft template(String title, int from, int to, int required, Integer max) {
		return new ShiftSeriesService.TemplateDraft(null, title, 0, LocalTime.of(from, 0), LocalTime.of(to, 0),
				required, max);
	}

	private List<ShiftEvent> occurrences(String seriesId) {
		return mongo.find(Query.query(Criteria.where("seriesId").is(seriesId)).with(Sort.by("occurrenceDate")),
				ShiftEvent.class).stream().filter(event -> !event.getOccurrenceDate().isBefore(today)).toList();
	}

	private ShiftEvent occurrence(String seriesId, LocalDate day) {
		return mongo.findOne(Query.query(Criteria.where("seriesId").is(seriesId).and("occurrenceDate").is(day)),
				ShiftEvent.class);
	}

	private List<Shift> shiftsOf(ShiftEvent event) {
		return mongo.find(Query.query(Criteria.where("eventId").is(event.getId()).and("deleted").is(false))
				.with(Sort.by("startsAt")), Shift.class);
	}

	private long notifications(User user, Notification.Type type) {
		return mongo.count(Query.query(Criteria.where("userId").is(user.getId()).and("type").is(type)),
				Notification.class);
	}

	private long auditCount(AuditAction action) {
		return mongo.count(Query.query(Criteria.where("action").is(action)), AuditLog.class);
	}

	@SafeVarargs
	private List<Integer> parallel(Callable<Integer>... calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.length);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		for (Callable<Integer> call : calls) {
			futures.add(pool.submit(() -> {
				go.await();
				return call.call();
			}));
		}
		go.countDown();
		List<Integer> out = new ArrayList<>();
		for (Future<Integer> future : futures) {
			out.add(future.get(60, TimeUnit.SECONDS));
		}
		pool.shutdown();
		return out;
	}

	private User user(String name) {
		return users.save(User.builder().username(name).email(name + "@series.test")
				.displayName(Character.toUpperCase(name.charAt(0)) + name.substring(1))
				.roles(new java.util.HashSet<>(Set.of(Role.MEMBER))).active(true).locale("de").build());
	}

	private HttpResponse<String> get(String path, User user) {
		return send(HttpRequest.newBuilder(url(path)).header("Authorization", "Bearer " + token(user)).GET().build());
	}

	private HttpResponse<String> delete(String path, User user) {
		return send(HttpRequest.newBuilder(url(path)).header("Authorization", "Bearer " + token(user)).DELETE().build());
	}

	private HttpResponse<String> post(String path, String json, User user) {
		return send(HttpRequest.newBuilder(url(path)).header("Authorization", "Bearer " + token(user))
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.POST(HttpRequest.BodyPublishers.ofString(json)).build());
	}

	private HttpResponse<String> patch(String path, String json, User user) {
		return send(HttpRequest.newBuilder(url(path)).header("Authorization", "Bearer " + token(user))
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.method("PATCH", HttpRequest.BodyPublishers.ofString(json)).build());
	}

	private String token(User user) {
		return tokens.issue(user).accessToken();
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
