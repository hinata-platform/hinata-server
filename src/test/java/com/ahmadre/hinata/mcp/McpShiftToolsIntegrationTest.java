package com.ahmadre.hinata.mcp;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.TokenService;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.notification.MailService;
import com.ahmadre.hinata.notification.PushService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.shifts.Shift;
import com.ahmadre.hinata.shifts.ShiftAssignment;
import com.ahmadre.hinata.shifts.ShiftAssignments;
import com.ahmadre.hinata.shifts.ShiftEvent;
import com.ahmadre.hinata.shifts.ShiftService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shift tools of HIN-164 over the real {@code /mcp} transport, with shift planning on and a
 * MongoDB replica set: what a helper's assistant reads and does, what a planner's token may and may
 * not do, the size and window limits, the waiting list in the tools' words, and the audit records.
 * The module switched off and the bare scope gates are in {@link McpSecurityIntegrationTest}.
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
class McpShiftToolsIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String EVENT_TITLE = "Sommerfest am Vereinsheim";
	private static final String REDIRECT_URI = "https://example.com/callback";

	@MockitoBean
	private MailService mail;
	@MockitoBean
	private PushService push;

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
	private ShiftAssignments assignments;
	@Autowired
	private SettingsService settings;

	private User lead;
	private User ann;
	private User bob;
	private User carl;
	private User erin;
	private Project project;
	/** Ten days ahead on the hour, so every default window of 30 days holds it. */
	private Instant base;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "projects", "teams", "shift_events", "shifts", "shift_assignments",
				"shift_user_guards", "shift_planners", "personal_access_tokens", "notifications", "audit_log",
				"server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		lead = user("lead");
		ann = user("ann");
		bob = user("bob");
		carl = user("carl");
		erin = user("erin");
		project = projects.save(Project.builder().key("FAIR").name("FAIR").leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), ann.getId(), bob.getId(), carl.getId()))).build());
		base = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofDays(10));
	}

	// --- reading -----------------------------------------------------------------------------

	@Test
	@DisplayName("next_shift names the caller's next place; list_my_shifts holds places and waiting entries")
	void nextShiftAndTheCallersOwnList() {
		ShiftEvent event = event(base, true);
		Shift first = shift(event, base, 1, 1);
		Shift later = shift(event, base.plus(Duration.ofHours(3)), 1, 1);
		assignments.assign(ann, first.getId(), null, true);
		assignments.assign(bob, later.getId(), null, true);
		ShiftAssignment waiting = assignments.assign(ann, later.getId(), null, true).assignment();
		assertThat(waiting.getStatus()).isEqualTo(ShiftAssignment.Status.WAITING);

		McpTestClient agent = client(ann, "shifts:read");
		JsonNode next = body(ok(agent.call("next_shift", "{}")));
		assertThat(next.path("found").asBoolean()).isTrue();
		assertThat(next.path("shift").path("shiftId").asText()).isEqualTo(first.getId());
		assertThat(next.path("shift").path("timezone").asText()).isEqualTo("Europe/Berlin");
		assertThat(next.path("shift").path("eventTitle").asText()).isEqualTo(EVENT_TITLE);

		JsonNode mine = body(ok(agent.call("list_my_shifts", "{}")));
		assertThat(mine.path("shifts")).hasSize(1);
		assertThat(mine.path("shifts").get(0).path("status").asText()).isEqualTo("CONFIRMED");
		assertThat(mine.path("waiting")).hasSize(1);
		assertThat(mine.path("waiting").get(0).path("assignmentId").asText()).isEqualTo(waiting.getId());
		assertThat(mine.path("waiting").get(0).path("waitPosition").asInt()).isEqualTo(1);
		assertThat(mine.path("truncated").asBoolean()).isFalse();

		JsonNode none = body(ok(client(carl, "shifts:read").call("next_shift", "{}")));
		assertThat(none.path("found").asBoolean(true)).isFalse();
	}

	@Test
	@DisplayName("an outsider asking for an event is refused without its title")
	void anOutsiderLearnsNothing() {
		ShiftEvent event = event(base, true);
		Shift shift = shift(event, base, 1, null);

		McpTestClient outsider = client(erin, "shifts:read", "shifts:write");
		for (JsonNode refused : List.of(outsider.call("get_shift_event", "{\"eventId\":\"" + event.getId() + "\"}"),
				outsider.call("get_shift", "{\"shiftId\":\"" + shift.getId() + "\"}"),
				outsider.call("self_assign_shift", "{\"shiftId\":\"" + shift.getId() + "\"}"))) {
			assertThat(refused.path("isError").asBoolean(false)).isTrue();
			assertThat(McpTestClient.text(refused)).contains("nicht gefunden").doesNotContain("Sommerfest")
					.doesNotContain("Festwiese");
		}
		JsonNode list = body(ok(outsider.call("list_shift_events", "{}")));
		assertThat(list.path("events")).isEmpty();
		JsonNode byKey = outsider.call("list_shift_events", "{\"projectKey\":\"FAIR\"}");
		assertThat(byKey.path("isError").asBoolean(false)).isTrue();
		assertThat(McpTestClient.text(byKey)).doesNotContain("Sommerfest");
	}

	@Test
	@DisplayName("an event with 200 shifts stays under 32 KB and carries numbers, no names")
	void aBigEventStaysSmall() {
		ShiftEvent event = service.createEvent(lead, project.getId(), new ShiftService.EventDraft(EVENT_TITLE, null,
				"Festwiese", base, base.plus(Duration.ofHours(20)), "Europe/Berlin", true));
		for (int i = 0; i < 200; i++) {
			Instant start = base.plus(Duration.ofMinutes(5L * i));
			service.createShift(lead, event.getId(), new ShiftService.ShiftDraft("Stand Halle 3, Schicht " + i, start,
					start.plus(Duration.ofHours(2)), 3, i % 2 == 0 ? null : 5), null);
		}
		Shift some = mongo.findOne(Query.query(Criteria.where("eventId").is(event.getId())), Shift.class);
		assignments.assign(ann, some.getId(), null, true);

		String text = McpTestClient.text(ok(client(ann, "shifts:read")
				.call("get_shift_event", "{\"eventId\":\"" + event.getId() + "\"}")));
		assertThat(text.getBytes(StandardCharsets.UTF_8).length).as("bytes").isLessThan(32 * 1024);
		JsonNode detail = body(text);
		int rows = 0;
		for (JsonNode day : detail.path("days")) {
			assertThat(day.path("date").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
			rows += day.path("shifts").size();
		}
		assertThat(rows).isEqualTo(200);
		assertThat(detail.path("shiftCount").asInt()).isEqualTo(200);
		assertThat(detail.path("timezone").asText()).isEqualTo("Europe/Berlin");
		assertThat(detail.path("placesRequired").asInt()).isEqualTo(600);
		assertThat(detail.path("shiftsUnderstaffed").asInt()).isEqualTo(200);
		assertThat(text).doesNotContain("Ann").doesNotContain(ann.getId());
		JsonNode row = detail.path("days").get(0).path("shifts").get(0);
		assertThat(row.path("understaffed").asBoolean()).isTrue();
		assertThat(row.path("starts").asText()).matches("\\d{2}:\\d{2}");
		assertThat(row.has("full")).as("false flags are left out").isFalse();
	}

	@Test
	@DisplayName("lists stop at 100 and say so; a window over 62 days is refused as a sentence")
	void listsAreCappedAndWindowsBounded() {
		for (int i = 0; i < 101; i++) {
			event(base.plus(Duration.ofMinutes(i)), true);
		}
		McpTestClient agent = client(ann, "shifts:read");
		JsonNode page = body(ok(agent.call("list_shift_events", "{\"size\":500}")));
		assertThat(page.path("size").asInt()).isEqualTo(100);
		assertThat(page.path("events")).hasSize(100);
		assertThat(page.path("total").asLong()).isEqualTo(101);
		assertThat(page.path("truncated").asBoolean()).isTrue();
		JsonNode rest = body(ok(agent.call("list_shift_events", "{\"size\":100,\"page\":1}")));
		assertThat(rest.path("events")).hasSize(1);
		assertThat(rest.path("truncated").asBoolean()).isFalse();

		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		String wide = "{\"from\":\"" + today + "\",\"to\":\"" + today.plusDays(62) + "\"}";
		for (String tool : List.of("list_shift_events", "list_my_shifts")) {
			JsonNode refused = agent.call(tool, wide);
			assertThat(refused.path("isError").asBoolean(false)).as(tool).isTrue();
			assertThat(McpTestClient.text(refused)).as(tool).endsWith("Der Zeitraum darf höchstens 62 Tage lang sein.");
		}
		JsonNode exact = agent.call("list_my_shifts", "{\"from\":\"" + today + "\",\"to\":\"" + today.plusDays(61) + "\"}");
		assertThat(exact.path("isError").asBoolean(false)).as("62 days are fine").isFalse();
	}

	@Test
	@DisplayName("understaffedOnly keeps the events where people are still missing")
	void understaffedOnly() {
		ShiftEvent missing = event(base, true);
		shift(missing, base, 2, null);
		ShiftEvent staffed = event(base.plus(Duration.ofHours(1)), true);
		Shift full = shift(staffed, base.plus(Duration.ofHours(1)), 1, 1);
		assignments.assign(ann, full.getId(), null, true);
		// Cancelled: its shift needs nobody any more, so it is not where people are missing.
		ShiftEvent called = event(base.plus(Duration.ofHours(2)), true);
		shift(called, base.plus(Duration.ofHours(2)), 2, null);
		service.cancelEvent(lead, called.getId(), null);

		JsonNode all = body(ok(client(bob, "shifts:read").call("list_shift_events", "{}")));
		assertThat(all.path("events")).hasSize(3);
		JsonNode onlyShort = body(ok(client(bob, "shifts:read").call("list_shift_events",
				"{\"understaffedOnly\":true,\"projectKey\":\"fair\"}")));
		assertThat(onlyShort.path("events")).hasSize(1);
		assertThat(onlyShort.path("events").get(0).path("id").asText()).isEqualTo(missing.getId());
		assertThat(onlyShort.path("total").asLong()).isEqualTo(1);
	}

	/**
	 * HIN-162 through MCP: nobody waiting is ever named, not to a planner, not to the person
	 * waiting, not to a colleague. Counts and the caller's own position are fine.
	 */
	@Test
	@DisplayName("people on a waiting list are never named by a tool, not even to a planner")
	void waitingPeopleAreNeverNamed() {
		ShiftEvent event = event(base, true);
		Shift full = shift(event, base, 1, 1);
		assignments.assign(ann, full.getId(), null, true);
		assignments.assign(bob, full.getId(), null, true);
		assignments.assign(carl, full.getId(), null, true);

		for (User reader : List.of(lead, ann, bob, carl)) {
			McpTestClient agent = client(reader, "shifts:read");
			String shiftText = McpTestClient.text(ok(agent.call("get_shift", "{\"shiftId\":\"" + full.getId() + "\"}")));
			JsonNode shift = body(shiftText);
			assertThat(shift.path("people")).as(reader.getUsername()).hasSize(1);
			assertThat(shift.path("people").get(0).path("userId").asText()).isEqualTo(ann.getId());
			assertThat(shift.path("waiting").asInt()).isEqualTo(2);
			assertThat(shiftText).as(reader.getUsername()).doesNotContain("Bob").doesNotContain("Carl")
					.doesNotContain(bob.getId()).doesNotContain(carl.getId());

			String eventText = McpTestClient.text(ok(agent.call("get_shift_event",
					"{\"eventId\":\"" + event.getId() + "\"}")));
			assertThat(eventText).as(reader.getUsername()).doesNotContain("Ann").doesNotContain("Bob")
					.doesNotContain("Carl").doesNotContain(bob.getId()).doesNotContain(carl.getId());
			String listText = McpTestClient.text(ok(agent.call("list_shift_events", "{}")));
			assertThat(listText).doesNotContain("Bob").doesNotContain(bob.getId());
		}
		JsonNode carls = body(ok(client(carl, "shifts:read").call("get_shift", "{\"shiftId\":\"" + full.getId() + "\"}")));
		assertThat(carls.path("myWaitPosition").asInt()).isEqualTo(2);
		assertThat(carls.has("myAssignmentId")).isFalse();
	}

	// --- writing -----------------------------------------------------------------------------

	@Test
	@DisplayName("signing up for a full shift lands on the waiting list and says so; an overlap is a sentence")
	void selfAssignWaitsOrExplains() {
		ShiftEvent event = event(base, true);
		Shift full = shift(event, base, 1, 1);
		assignments.assign(ann, full.getId(), null, true);

		JsonNode waiting = body(ok(client(bob, "shifts:read", "shifts:write")
				.call("self_assign_shift", "{\"shiftId\":\"" + full.getId() + "\"}")));
		assertThat(waiting.path("status").asText()).isEqualTo("WAITING");
		assertThat(waiting.path("waitPosition").asInt()).isEqualTo(1);
		assertThat(waiting.path("note").asText()).contains("Warteliste").contains("Platz 1");

		Shift open = shift(event, base.plus(Duration.ofHours(4)), 1, null);
		Shift sameTime = shift(event, base.plus(Duration.ofHours(4)), 1, null);
		McpTestClient carls = client(carl, "shifts:write");
		JsonNode signedUp = body(ok(carls.call("self_assign_shift", "{\"shiftId\":\"" + open.getId() + "\"}")));
		assertThat(signedUp.path("status").asText()).isEqualTo("CONFIRMED");
		assertThat(signedUp.path("note").asText()).isEqualTo("Du bist jetzt auf dieser Schicht.");

		JsonNode overlap = carls.call("self_assign_shift", "{\"shiftId\":\"" + sameTime.getId() + "\"}");
		assertThat(overlap.path("isError").asBoolean(false)).isTrue();
		assertThat(McpTestClient.text(overlap)).contains("Diese Person hat zur selben Zeit schon eine Schicht.");
	}

	@Test
	@DisplayName("a planner's token with only shifts:write cannot free somebody else's place; shifts:plan can")
	void releasingSomebodyElsesPlaceNeedsThePlanScope() {
		ShiftEvent event = event(base, true);
		Shift shift = shift(event, base, 2, null);
		ShiftAssignment anns = assignments.assign(ann, shift.getId(), null, true).assignment();

		JsonNode refused = client(lead, "shifts:read", "shifts:write")
				.call("release_shift", "{\"assignmentId\":\"" + anns.getId() + "\"}");
		assertThat(refused.path("isError").asBoolean(false)).isTrue();
		assertThat(McpTestClient.text(refused)).contains("remove_assignment");
		assertThat(statusOf(anns)).isEqualTo(ShiftAssignment.Status.CONFIRMED);

		JsonNode notAPlanner = client(bob, "shifts:plan").call("remove_assignment",
				"{\"assignmentId\":\"" + anns.getId() + "\"}");
		assertThat(notAPlanner.path("isError").asBoolean(false)).isTrue();
		assertThat(McpTestClient.text(notAPlanner)).contains("Nur wer dieses Projekt plant");
		assertThat(statusOf(anns)).isEqualTo(ShiftAssignment.Status.CONFIRMED);

		// The planner finds the id where the tool description says it is: in get_shift.
		JsonNode asPlanner = body(ok(client(lead, "shifts:read", "shifts:plan").call("get_shift",
				"{\"shiftId\":\"" + shift.getId() + "\"}")));
		JsonNode annOnShift = asPlanner.path("people").get(0);
		assertThat(annOnShift.path("userId").asText()).isEqualTo(ann.getId());
		assertThat(annOnShift.path("status").asText()).isEqualTo("CONFIRMED");
		String fromTool = annOnShift.path("assignmentId").asText();
		assertThat(fromTool).isEqualTo(anns.getId());
		JsonNode asMember = body(ok(client(bob, "shifts:read").call("get_shift",
				"{\"shiftId\":\"" + shift.getId() + "\"}")));
		assertThat(asMember.path("people").get(0).has("assignmentId")).as("only for planners").isFalse();
		assertThat(asMember.path("people").get(0).has("status")).isFalse();

		JsonNode removed = body(ok(client(lead, "shifts:plan").call("remove_assignment",
				"{\"assignmentId\":\"" + fromTool + "\"}")));
		assertThat(removed.path("status").asText()).isEqualTo("RELEASED");
		assertThat(statusOf(anns)).isEqualTo(ShiftAssignment.Status.RELEASED);
	}

	@Test
	@DisplayName("every write leaves an MCP_SHIFT_* record, off by default, ids only")
	void everyWriteIsAudited() {
		ShiftEvent event = event(base, true);
		Shift first = shift(event, base, 2, null);
		Shift second = shift(event, base.plus(Duration.ofHours(3)), 2, null);
		Shift third = shift(event, base.plus(Duration.ofHours(6)), 2, null);

		McpTestClient anns = client(ann, "shifts:read", "shifts:write");
		ok(anns.call("self_assign_shift", "{\"shiftId\":\"" + first.getId() + "\"}"));
		assertThat(records(AuditAction.MCP_SHIFT_SELF_ASSIGNED)).as("off by default").isEmpty();
		assertThat(records(AuditAction.SHIFT_ASSIGNMENT_CREATED)).as("the module's own record").hasSize(1);
		for (AuditAction action : mcpShiftActions()) {
			assertThat(action.defaultEnabled()).as(action.name()).isFalse();
			assertThat(action.content()).as(action.name()).isTrue();
			assertThat(action.organisational()).as(action.name()).isTrue();
		}

		enable(mcpShiftActions());
		McpTestClient planner = client(lead, "shifts:plan");
		String own = body(ok(anns.call("self_assign_shift", "{\"shiftId\":\"" + second.getId() + "\"}")))
				.path("assignmentId").asText();
		ok(anns.call("release_shift", "{\"assignmentId\":\"" + own + "\"}"));
		String toConfirm = body(ok(planner.call("assign_shift",
				"{\"shiftId\":\"" + second.getId() + "\",\"userId\":\"" + ann.getId() + "\"}"))).path("assignmentId").asText();
		ok(anns.call("confirm_shift", "{\"assignmentId\":\"" + toConfirm + "\"}"));
		String toDecline = body(ok(planner.call("assign_shift",
				"{\"shiftId\":\"" + third.getId() + "\",\"userId\":\"" + ann.getId() + "\"}"))).path("assignmentId").asText();
		ok(anns.call("decline_shift", "{\"assignmentId\":\"" + toDecline + "\"}"));
		ok(planner.call("remove_assignment", "{\"assignmentId\":\"" + toConfirm + "\"}"));

		assertThat(records(AuditAction.MCP_SHIFT_SELF_ASSIGNED)).hasSize(1);
		assertThat(records(AuditAction.MCP_SHIFT_RELEASED)).hasSize(1);
		assertThat(records(AuditAction.MCP_SHIFT_ASSIGNED_BY_PLANNER)).hasSize(2);
		assertThat(records(AuditAction.MCP_SHIFT_CONFIRMED)).hasSize(1);
		assertThat(records(AuditAction.MCP_SHIFT_DECLINED)).hasSize(1);
		List<AuditLog> removedRecords = records(AuditAction.MCP_SHIFT_REMOVED);
		assertThat(removedRecords).singleElement().satisfies(record -> {
			assertThat(record.getActorId()).isEqualTo(lead.getId());
			assertThat(record.getTargetId()).isEqualTo(ann.getId());
			assertThat(record.getMetadata()).containsEntry("assignmentId", toConfirm)
					.containsEntry("shiftId", second.getId()).containsEntry("eventId", event.getId())
					.containsEntry("projectId", project.getId()).containsEntry("status", "RELEASED");
		});
		for (AuditLog record : mongo.findAll(AuditLog.class)) {
			assertThat(String.valueOf(record.getMetadata())).doesNotContain("Sommerfest").doesNotContain("Aufbau");
		}
	}

	// --- consent -----------------------------------------------------------------------------

	@Test
	@DisplayName("with the module on, consent offers shifts:read and shifts:write by default and shifts:plan only by name")
	void consentOffersTheShiftScopesWhileTheModuleIsOn() throws Exception {
		String jwt = tokens.issue(lead).accessToken();
		HttpResponse<String> registered = http(HttpRequest.newBuilder(url("/oauth/register"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"client_name\":\"Test AI\",\"redirect_uris\":[\""
						+ REDIRECT_URI + "\"],\"token_endpoint_auth_method\":\"none\"}")).build());
		assertThat(registered.statusCode()).as("register").isEqualTo(201);
		String clientId = JSON.readTree(registered.body()).path("client_id").asText();

		JsonNode byDefault = consentScopes(jwt, clientId, null);
		assertThat(byDefault.toString()).contains("shifts:read").contains("shifts:write").doesNotContain("shifts:plan");
		JsonNode byName = consentScopes(jwt, clientId, "shifts:read shifts:plan");
		assertThat(byName.toString()).contains("shifts:plan");
	}

	/** The scopes the consent screen offers for an authorization asking for [scope] (none when null). */
	private JsonNode consentScopes(String jwt, String clientId, String scope) throws Exception {
		String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
				MessageDigest.getInstance("SHA-256").digest("a-verifier-of-enough-length-for-pkce-0123456789"
						.getBytes(StandardCharsets.US_ASCII)));
		String authorize = "/oauth/authorize?response_type=code&client_id=" + enc(clientId)
				+ "&redirect_uri=" + enc(REDIRECT_URI) + (scope == null ? "" : "&scope=" + enc(scope))
				+ "&state=s&code_challenge=" + enc(challenge) + "&code_challenge_method=S256";
		HttpResponse<String> redirect = http(HttpRequest.newBuilder(url(authorize)).GET().build());
		assertThat(redirect.statusCode()).as("authorize").isEqualTo(302);
		String location = redirect.headers().firstValue("Location").orElseThrow();
		String requestId = location.substring(location.indexOf("request_id=") + "request_id=".length()).split("&")[0];
		HttpResponse<String> consent = http(HttpRequest.newBuilder(url("/api/v1/oauth/consent/" + requestId))
				.header("Authorization", "Bearer " + jwt).GET().build());
		assertThat(consent.statusCode()).as("consent").isEqualTo(200);
		return JSON.readTree(consent.body()).path("scopes");
	}

	private URI url(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private static String enc(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static HttpResponse<String> http(HttpRequest request) throws Exception {
		return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
				.send(request, HttpResponse.BodyHandlers.ofString());
	}

	// --- helpers -----------------------------------------------------------------------------

	private McpTestClient client(User user, String... scopes) {
		return McpTestClient.withSession(port, tokens.issue(user).accessToken(), scopes);
	}

	private ShiftEvent event(Instant start, boolean open) {
		return service.createEvent(lead, project.getId(), new ShiftService.EventDraft(EVENT_TITLE, null, "Festwiese",
				start, start.plus(Duration.ofHours(10)), "Europe/Berlin", open));
	}

	private Shift shift(ShiftEvent event, Instant start, int required, Integer max) {
		return service.createShift(lead, event.getId(), new ShiftService.ShiftDraft("Aufbau", start,
				start.plus(Duration.ofHours(2)), required, max), null);
	}

	private ShiftAssignment.Status statusOf(ShiftAssignment assignment) {
		return mongo.findById(assignment.getId(), ShiftAssignment.class).getStatus();
	}

	private List<AuditLog> records(AuditAction action) {
		return mongo.find(Query.query(Criteria.where("action").is(action.name())), AuditLog.class);
	}

	private static List<AuditAction> mcpShiftActions() {
		return List.of(AuditAction.MCP_SHIFT_SELF_ASSIGNED, AuditAction.MCP_SHIFT_ASSIGNED_BY_PLANNER,
				AuditAction.MCP_SHIFT_RELEASED, AuditAction.MCP_SHIFT_CONFIRMED, AuditAction.MCP_SHIFT_DECLINED,
				AuditAction.MCP_SHIFT_REMOVED);
	}

	private void enable(List<AuditAction> actions) {
		ServerSettings current = settings.get();
		if (current.getAudit() == null) {
			current.setAudit(new ServerSettings.Audit());
		}
		actions.forEach(action -> current.getAudit().getEvents().put(action.name(), true));
		settings.save(current);
	}

	/** The result, after checking it is no refusal. */
	private static JsonNode ok(JsonNode result) {
		assertThat(result.path("isError").asBoolean(false)).as(McpTestClient.text(result)).isFalse();
		return result;
	}

	private static JsonNode body(JsonNode result) {
		return body(McpTestClient.text(result));
	}

	private static JsonNode body(String text) {
		try {
			return JSON.readTree(text);
		}
		catch (Exception e) {
			throw new AssertionError("Tool result was not JSON: " + text, e);
		}
	}

	private User user(String name) {
		return users.save(User.builder().username(name).email(name + "@mcp-shifts.test")
				.displayName(Character.toUpperCase(name.charAt(0)) + name.substring(1)).locale("de")
				.roles(new HashSet<>(Set.of(Role.MEMBER))).active(true).build());
	}
}
