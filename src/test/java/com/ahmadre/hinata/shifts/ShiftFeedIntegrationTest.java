package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.TokenService;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.ics.IcsCalendar;
import com.ahmadre.hinata.ics.IcsEvent;
import com.ahmadre.hinata.ics.IcsParser;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The calendar feed of one's own shifts (HIN-47) against a real MongoDB replica set: the token's
 * life (made once, shown once, replaced, removed, gone with the account), what the anonymous route
 * lets through and what not, what a fetch contains and costs, and the per-feed limit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.mongodb.transactions-enabled=true",
		"hinata.shifts.enabled=true",
		"hinata.base-url=https://hinata.example.org",
		"hinata.web-base-url=https://app.example.org"
})
@Import(ShiftFeedIntegrationTest.Probes.class)
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class ShiftFeedIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();


	@TestConfiguration
	static class Probes {

		@Bean
		MongoClientSettingsBuilderCustomizer countFeedReads() {
			return builder -> builder.addCommandListener(ShiftReadCounter.listener());
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
	private SettingsService settings;
	@Autowired
	private TokenService tokens;
	@Autowired
	private ShiftService service;
	@Autowired
	private ShiftAssignments assignments;
	@Autowired
	private ShiftFeed feed;
	@Autowired
	private ShiftFeedLimiter limiter;
	@Autowired
	private ShiftPersonalData personalData;
	@Autowired
	private ApplicationEventPublisher publisher;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private User lead;
	private User ann;
	private User olga;
	private Project fair;
	private Project club;
	private Instant base;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "projects", "teams", "shift_events", "shifts", "shift_assignments",
				"shift_user_guards", "shift_planners", "shift_feed_tokens", "audit_log")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		lead = user("lead", "de");
		ann = user("ann", "de");
		olga = user("olga", "en");
		fair = project("FAIR", lead, ann);
		club = project("CLUB", olga, ann);
		base = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(3)).plus(Duration.ofHours(8));
	}

	@AfterEach
	void restore() {
		ShiftReadCounter.reset();
		ServerSettings current = settings.get();
		current.setShifts(null);
		if (current.getSecurity() != null) {
			current.getSecurity().setRateLimitEnabled(null);
		}
		settings.save(current);
	}

	// --- the token --------------------------------------------------------------------

	@Test
	@DisplayName("the address is shown once; afterwards only that it exists, and the token is stored nowhere")
	void theTokenIsShownOnceAndStoredNowhere(CapturedOutput output) throws Exception {
		String bearer = bearer(ann);
		assertThat(json(get("/api/v1/me/shifts/feed", bearer)).get("exists").asBoolean()).isFalse();

		HttpResponse<String> created = post("/api/v1/me/shifts/feed", bearer);
		assertThat(created.statusCode()).isEqualTo(201);
		JsonNode body = json(created);
		String url = body.get("url").asText();
		assertThat(url).matches("https://hinata\\.example\\.org/api/v1/shifts/feed/[A-Za-z0-9_-]{43}\\.ics");
		assertThat(body.get("webcalUrl").asText()).isEqualTo(url.replace("https://", "webcal://"));
		String token = token(url);

		assertThat(fetch(url).statusCode()).isEqualTo(200);

		JsonNode status = json(get("/api/v1/me/shifts/feed", bearer));
		assertThat(fields(status)).containsExactlyInAnyOrder("exists", "createdAt", "lastAccessedAt");
		assertThat(status.get("exists").asBoolean()).isTrue();
		assertThat(status.get("lastAccessedAt").isNull()).isFalse();
		assertThat(status.toString()).doesNotContain(token);

		Document stored = mongo.getCollection("shift_feed_tokens").find().first();
		assertThat(stored.toJson()).doesNotContain(token);
		assertThat(stored.getString("tokenHash")).isEqualTo(ShiftFeed.hash(token)).hasSize(64);
		for (Document record : mongo.getCollection("audit_log").find()) {
			assertThat(record.toJson()).doesNotContain(token);
		}
		assertThat(audits(AuditAction.SHIFT_FEED_TOKEN_CREATED)).singleElement()
				.satisfies(record -> assertThat(record.getActorId()).isEqualTo(ann.getId()));
		assertThat(JSON.writeValueAsString(personalData.data(ann))).doesNotContain(token).contains("lastUsedAt");
		assertThat(output.getAll()).doesNotContain(token);
	}

	@Test
	@DisplayName("with security logging at DEBUG the request path is logged, but never the token in it")
	void securityDebugLogsTheFeedPathWithoutTheToken(CapturedOutput output) {
		String url = feed.create(ann).url();
		String token = token(url);
		LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
		LogLevel before = logging.getLoggerConfiguration("org.springframework.security").getConfiguredLevel();
		logging.setLogLevel("org.springframework.security", LogLevel.DEBUG);
		try {
			assertThat(fetch(url).statusCode()).isEqualTo(200);
		} finally {
			logging.setLogLevel("org.springframework.security", before);
		}
		assertThat(output.getAll()).contains("/api/v1/shifts/feed/***").doesNotContain(token);
	}

	@Test
	@DisplayName("a new address ends the old one at once; removing it ends it too; a wrong one is 404")
	void replacedRemovedAndWrongTokensAre404() {
		String bearer = bearer(ann);
		String first = json(post("/api/v1/me/shifts/feed", bearer)).get("url").asText();
		String second = json(post("/api/v1/me/shifts/feed", bearer)).get("url").asText();

		assertThat(fetch(first).statusCode()).isEqualTo(404);
		assertThat(fetch(second).statusCode()).isEqualTo(200);
		assertThat(mongo.getCollection("shift_feed_tokens").countDocuments()).isEqualTo(1);
		assertThat(audits(AuditAction.SHIFT_FEED_TOKEN_REVOKED)).hasSize(1);

		assertThat(fetch(feedPath("A".repeat(43))).statusCode()).isEqualTo(404);
		assertThat(fetch(feedPath("short")).statusCode()).isEqualTo(404);

		assertThat(delete("/api/v1/me/shifts/feed", bearer).statusCode()).isEqualTo(204);
		assertThat(fetch(second).statusCode()).isEqualTo(404);
		assertThat(json(get("/api/v1/me/shifts/feed", bearer)).get("exists").asBoolean()).isFalse();
		assertThat(audits(AuditAction.SHIFT_FEED_TOKEN_REVOKED)).hasSize(2);
		assertThat(delete("/api/v1/me/shifts/feed", bearer).statusCode()).as("nothing there").isEqualTo(204);
	}

	@Test
	@DisplayName("a deactivated owner's feed is 404; a deleted owner's token is gone")
	void deactivatedAndDeletedOwners() {
		String url = feed.create(ann).url();
		ann.setActive(false);
		users.save(ann);
		assertThat(fetch(url).statusCode()).isEqualTo(404);

		ann.setActive(true);
		users.save(ann);
		assertThat(fetch(url).statusCode()).isEqualTo(200);

		publisher.publishEvent(new UserService.UserDeletedEvent(ann.getId()));
		assertThat(mongo.getCollection("shift_feed_tokens").countDocuments()).isZero();
		assertThat(fetch(url).statusCode()).isEqualTo(404);
	}

	// --- the anonymous route --------------------------------------------------------

	@Test
	@DisplayName("only a GET of one .ics segment goes without a login")
	void onlyTheGetIsOpen() {
		String url = feed.create(ann).url();
		String path = URI.create(url).getPath();
		assertThat(send(HttpRequest.newBuilder(local(path)).POST(HttpRequest.BodyPublishers.noBody()).build())
				.statusCode()).isEqualTo(401);
		assertThat(send(HttpRequest.newBuilder(local(path)).DELETE().build()).statusCode()).isEqualTo(401);
		assertThat(send(HttpRequest.newBuilder(local(path)).method("PUT", HttpRequest.BodyPublishers.noBody())
				.build()).statusCode()).isEqualTo(401);
		assertThat(fetch("/api/v1/shifts/feed/a/b.ics").statusCode()).isEqualTo(401);
		assertThat(fetch(path + "/foo").statusCode()).isEqualTo(401);
		assertThat(fetch("/api/v1/shifts/feed/").statusCode()).isEqualTo(401);
		assertThat(fetch("/api/v1/shifts/events").statusCode()).isEqualTo(401);
		assertThat(fetch("/api/v1/me/shifts/feed").statusCode()).isEqualTo(401);
		assertThat(raw("/api/v1/shifts/feed/a%2Fb.ics")).isIn(400, 401);
		assertThat(raw("/api/v1/shifts/feed/a%2fb.ics")).isIn(400, 401);
		assertThat(raw("/api/v1/shifts/feed/..%2F..%2Fevents.ics")).isIn(400, 401);
		assertThat(fetch(path).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("module off: 404 with a valid token; back on: the same address works again")
	void switchingTheModuleOffAndOnKeepsSubscriptions() {
		String url = feed.create(ann).url();
		switchModule(false);
		HttpResponse<String> off = send(HttpRequest.newBuilder(local(URI.create(url).getPath()))
				.header("Accept", "text/calendar").GET().build());
		assertThat(off.statusCode()).isEqualTo(404);
		assertThat(fetch(url).statusCode()).isEqualTo(404);
		assertThat(get("/api/v1/me/shifts/feed", bearer(ann)).statusCode()).isEqualTo(404);
		assertThat(mongo.getCollection("shift_feed_tokens").countDocuments()).isEqualTo(1);

		switchModule(true);
		assertThat(fetch(url).statusCode()).isEqualTo(200);
	}

	// --- what a fetch contains -----------------------------------------------------

	@Test
	@DisplayName("the feed holds the owner's own places with stable UIDs, titles, place, link and status")
	void theFeedHoldsTheOwnersPlaces() throws Exception {
		ShiftEvent fest = event(fair, lead, "Sommerfest", "Halle 3, Eingang Nord");
		Shift kasse = shift(fest, lead, "Kasse", base, 4);
		Shift theke = shift(fest, lead, "Theke", base.plus(Duration.ofHours(4)), 2);
		Shift others = shift(fest, lead, "Aufbau", base.plus(Duration.ofHours(6)), 2);
		ShiftAssignment mine = assignments.assign(lead, kasse.getId(), ann.getId(), false).assignment();
		assignments.assign(ann, theke.getId(), ann.getId(), true);
		assignments.assign(lead, others.getId(), lead.getId(), false);
		ShiftEvent cancelled = event(fair, lead, "Vorstandssitzung", null);
		Shift protokoll = shift(cancelled, lead, "Protokoll", base.plus(Duration.ofDays(1)), 2);
		assignments.assign(lead, protokoll.getId(), ann.getId(), false);
		service.cancelEvent(lead, cancelled.getId(), null);
		ShiftEvent past = event(fair, lead, "Messe", null, base.minus(Duration.ofDays(40)));
		Shift old = shift(past, lead, "Stand", base.minus(Duration.ofDays(40)), 2);
		assignments.assign(lead, old.getId(), ann.getId(), false);

		String url = feed.create(ann).url();
		HttpResponse<String> response = fetch(url);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("text/calendar;charset=utf-8");
		assertThat(response.headers().firstValue("Cache-Control")).hasValue("private, max-age=900");
		String ics = response.body();
		assertThat(ics).contains("X-WR-CALNAME:Hinata Schichten\r\n", "UID:" + mine.getId() + "@hinata.example.org\r\n",
				"SUMMARY:Kasse\\, Sommerfest\r\n", "LOCATION:Halle 3\\, Eingang Nord\r\n",
				"URL:https://app.example.org/shifts/events/" + fest.getId() + "\r\n",
				"DTSTART;TZID=Europe/Berlin:", "BEGIN:VTIMEZONE\r\nTZID:Europe/Berlin\r\n");

		IcsCalendar read = parse(ics);
		assertThat(read.events()).extracting(IcsEvent::summary)
				.containsExactlyInAnyOrder("Kasse, Sommerfest", "Theke, Sommerfest", "Protokoll, Vorstandssitzung");
		assertThat(read.events()).filteredOn(e -> e.summary().startsWith("Kasse")).singleElement()
				.satisfies(e -> {
					assertThat(e.status()).isEqualTo(IcsEvent.Status.TENTATIVE);
					assertThat(((IcsEvent.Timed) e).start()).isEqualTo(base);
					assertThat(e.description()).isEqualTo("In Hinata öffnen: https://app.example.org/shifts/events/"
							+ fest.getId());
				});
		assertThat(read.events()).filteredOn(e -> e.summary().startsWith("Theke")).singleElement()
				.extracting(IcsEvent::status).isEqualTo(IcsEvent.Status.CONFIRMED);
		assertThat(read.events()).filteredOn(e -> e.summary().startsWith("Protokoll")).singleElement()
				.extracting(IcsEvent::status).isEqualTo(IcsEvent.Status.CANCELLED);

		// Fetched again: the same events under the same UIDs, nothing doubled.
		IcsCalendar again = parse(fetch(url).body());
		assertThat(again.events()).extracting(IcsEvent::uid)
				.containsExactlyInAnyOrderElementsOf(read.events().stream().map(IcsEvent::uid).toList())
				.doesNotHaveDuplicates();

		// Given up: gone on the next fetch.
		assignments.release(ann, mine.getId());
		assertThat(parse(fetch(url).body()).events()).extracting(IcsEvent::summary)
				.doesNotContain("Kasse, Sommerfest");
	}

	@Test
	@DisplayName("after leaving a project its shifts are gone from the next fetch, at four reads whatever the size")
	void leavingAProjectEmptiesItsShiftsAtFourReads() {
		ShiftEvent fest = event(fair, lead, "Sommerfest", null);
		Instant later = base.plus(Duration.ofDays(4));
		ShiftEvent clubEvent = event(club, olga, "Vereinsabend", null, later);
		Shift one = shift(fest, lead, "Kasse", base, 2);
		assignments.assign(lead, one.getId(), ann.getId(), false);
		Shift clubShift = shift(clubEvent, olga, "Theke", later, 2);
		assignments.assign(olga, clubShift.getId(), ann.getId(), false);
		String token = token(feed.create(ann).url());

		int small = reads(() -> feed.fetch(token));
		assertThat(small).as("reads of one fetch: %s", ShiftReadCounter.commands).isLessThanOrEqualTo(4);

		for (int i = 1; i <= 30; i++) {
			Shift more = shift(fest, lead, "Schicht " + i, base.plus(Duration.ofHours(i * 2L)), 2);
			assignments.assign(lead, more.getId(), ann.getId(), false);
		}
		assertThat(reads(() -> feed.fetch(token))).as("reads with 31 places: %s", ShiftReadCounter.commands).isEqualTo(small);
		assertThat(summaries(feed.fetch(token))).contains("Theke, Vereinsabend").hasSize(32);

		// Out of the club's member list, without any event: the projects go into the query.
		club = projects.findById(club.getId()).orElseThrow();
		club.getMemberIds().remove(ann.getId());
		projects.save(club);
		assertThat(summaries(feed.fetch(token))).doesNotContain("Theke, Vereinsabend").hasSize(31);
	}

	@Test
	@DisplayName("lastUsedAt moves at most once an hour")
	void lastUsedAtIsWrittenHourly() {
		String token = token(feed.create(ann).url());
		feed.fetch(token);
		Instant first = stored().getLastUsedAt();
		assertThat(first).isNotNull();
		feed.fetch(token);
		assertThat(stored().getLastUsedAt()).isEqualTo(first);

		Instant earlier = Instant.now().minus(Duration.ofMinutes(61)).truncatedTo(ChronoUnit.MILLIS);
		mongo.updateFirst(new Query(), new Update().set("lastUsedAt", earlier), ShiftFeedToken.class);
		feed.fetch(token);
		assertThat(stored().getLastUsedAt()).isAfter(earlier.plus(Duration.ofMinutes(60)));
	}

	// --- the limit -------------------------------------------------------------------

	@Test
	@DisplayName("one fetch a minute per feed, then 429 with Retry-After; the admin switch turns it off")
	void oneFetchAMinutePerFeed() {
		switchRateLimit(true);
		String url = feed.create(ann).url();
		String other = feed.create(lead).url();
		assertThat(fetch(url).statusCode()).isEqualTo(200);
		HttpResponse<String> again = fetch(url);
		assertThat(again.statusCode()).isEqualTo(429);
		assertThat(Long.parseLong(again.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 61L);
		assertThat(fetch(other).statusCode()).as("another feed has its own minute").isEqualTo(200);
		// A wrong token is checked before the limit and spends nothing of it.
		assertThat(fetch(feedPath("B".repeat(43))).statusCode()).isEqualTo(404);

		switchRateLimit(false);
		assertThat(fetch(url).statusCode()).isEqualTo(200);
	}

	@Test
	@DisplayName("the limiter never tracks more feeds than its cap")
	void theLimiterStaysBounded() {
		switchRateLimit(true);
		for (int i = 0; i < ShiftFeedLimiter.MAX_TRACKED + 500; i++) {
			limiter.consume("feed-" + i);
			assertThat(limiter.tracked()).isLessThanOrEqualTo(ShiftFeedLimiter.MAX_TRACKED);
		}
		assertThat(limiter.consume("feed-new")).isEmpty();
		assertThat(limiter.consume("feed-new")).isPresent();
	}

	// --- helpers ---------------------------------------------------------------------

	private List<String> summaries(ShiftFeed.Fetch fetch) {
		assertThat(fetch).isInstanceOf(ShiftFeed.Calendar.class);
		return parse(new String(((ShiftFeed.Calendar) fetch).body(), StandardCharsets.UTF_8)).events().stream()
				.map(IcsEvent::summary).toList();
	}

	private static IcsCalendar parse(String ics) {
		return IcsParser.parse(ics.getBytes(StandardCharsets.UTF_8), Instant.now().minus(Duration.ofDays(400)),
				Instant.now().plus(Duration.ofDays(400)), ZoneOffset.UTC);
	}

	private ShiftFeedToken stored() {
		return mongo.findOne(new Query(), ShiftFeedToken.class);
	}

	private List<AuditLog> audits(AuditAction action) {
		return mongo.find(Query.query(Criteria.where("action").is(action)), AuditLog.class);
	}

	private void switchModule(boolean on) {
		ServerSettings current = settings.get();
		ServerSettings.Shifts block = new ServerSettings.Shifts();
		block.setEnabled(on);
		current.setShifts(block);
		settings.save(current);
	}

	private void switchRateLimit(boolean on) {
		ServerSettings current = settings.get();
		if (current.getSecurity() == null) {
			current.setSecurity(new ServerSettings.Security());
		}
		current.getSecurity().setRateLimitEnabled(on);
		settings.save(current);
	}

	private int reads(Runnable read) {
		return ShiftReadCounter.reads(read);
	}

	private ShiftEvent event(Project in, User planner, String title, String location) {
		return event(in, planner, title, location, base);
	}

	private ShiftEvent event(Project in, User planner, String title, String location, Instant start) {
		return service.createEvent(planner, in.getId(), new ShiftService.EventDraft(title, null, location,
				start.minus(Duration.ofHours(1)), start.plus(Duration.ofDays(3)), "Europe/Berlin", null));
	}

	private Shift shift(ShiftEvent event, User planner, String title, Instant start, int hours) {
		return service.createShift(planner, event.getId(), new ShiftService.ShiftDraft(title, start,
				start.plus(Duration.ofHours(hours)), 3, null), null);
	}

	private User user(String name, String locale) {
		return users.save(User.builder().username(name).email(name + "@feed.test")
				.displayName(Character.toUpperCase(name.charAt(0)) + name.substring(1)).locale(locale)
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

	private String bearer(User user) {
		return tokens.issue(user).accessToken();
	}

	private static String token(String url) {
		String path = URI.create(url).getPath();
		return path.substring(path.lastIndexOf('/') + 1, path.length() - ".ics".length());
	}

	private static String feedPath(String token) {
		return "/api/v1/shifts/feed/" + token + ".ics";
	}

	/** A feed address, absolute (as handed out) or a path, fetched the way a calendar app does: no login. */
	private HttpResponse<String> fetch(String address) {
		String path = address.startsWith("http") ? URI.create(address).getPath() : address;
		return send(HttpRequest.newBuilder(local(path)).GET().build());
	}

	/**
	 * The status for a path sent exactly as written. The HTTP client would normalise or refuse an
	 * encoded slash, so this one speaks HTTP/1.1 over a socket.
	 */
	private int raw(String path) {
		try (Socket socket = new Socket("localhost", port)) {
			OutputStream out = socket.getOutputStream();
			out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
					.getBytes(StandardCharsets.US_ASCII));
			out.flush();
			InputStream in = socket.getInputStream();
			String status = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
			return Integer.parseInt(status.substring(9, 12));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private HttpResponse<String> get(String path, String bearer) {
		return send(HttpRequest.newBuilder(local(path)).header("Authorization", "Bearer " + bearer).GET().build());
	}

	private HttpResponse<String> post(String path, String bearer) {
		return send(HttpRequest.newBuilder(local(path)).header("Authorization", "Bearer " + bearer)
				.POST(HttpRequest.BodyPublishers.noBody()).build());
	}

	private HttpResponse<String> delete(String path, String bearer) {
		return send(HttpRequest.newBuilder(local(path)).header("Authorization", "Bearer " + bearer).DELETE().build());
	}

	private URI local(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (Exception ex) {
			throw new IllegalStateException("request failed: " + request.uri(), ex);
		}
	}

	private static JsonNode json(HttpResponse<String> response) {
		try {
			return JSON.readTree(response.body());
		}
		catch (Exception ex) {
			throw new IllegalStateException(response.statusCode() + " " + response.body(), ex);
		}
	}

	private static List<String> fields(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}
}
