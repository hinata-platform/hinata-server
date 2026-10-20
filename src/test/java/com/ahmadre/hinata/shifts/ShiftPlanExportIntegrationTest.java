package com.ahmadre.hinata.shifts;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.auth.TokenService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.issue.export.ExportFormat;
import com.ahmadre.hinata.issue.export.ExportSlots;
import com.ahmadre.hinata.notification.MailService;
import com.ahmadre.hinata.notification.PushService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shift plan as a file (HIN-163) against a real MongoDB replica set: what each format says, in
 * the event's zone and the caller's language; that a file without names holds none, read back as
 * text; that names are a planner's, the waiting list nobody's; the shared slots, the budget, the
 * audit record, and a Word document of two hundred shifts within a fixed allocation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"hinata.rate-limit.exports-per-minute=1000",
		"management.health.mail.enabled=false",
		"hinata.mongodb.transactions-enabled=true",
		"hinata.shifts.enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class ShiftPlanExportIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
	/** Names nobody else in a document would spell, so finding one means a person was named. */
	private static final List<String> HELD = List.of("Annabell Quist", "Bertram Ylvi", "Dagmar Zoll");
	private static final String WAITING = "Carlotta Wex";

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
	private ShiftPlanExport exports;
	@Autowired
	private ExportSlots slots;
	@Autowired
	private HinataProperties properties;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private User lead;
	private User member;
	private User stranger;
	private Project project;
	private ShiftEvent event;
	private int budget;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "projects", "teams", "shift_events", "shifts", "shift_assignments",
				"shift_user_guards", "shift_planners", "shift_series", "notifications", "audit_log",
				"server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		budget = properties.getRateLimit().getExportsPerMinute();
		lead = user("lead", "Lea Leitner");
		member = user("member", "Mia Mitglied");
		stranger = user("stranger", "Sven Fremd");
		User ann = user("ann", HELD.get(0));
		User bob = user("bob", HELD.get(1));
		User dana = user("dana", HELD.get(2));
		User carl = user("carl", WAITING);
		project = projects.save(Project.builder().key("FAIR").name("Fair").leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId(), ann.getId(), bob.getId(),
						dana.getId(), carl.getId()))).build());

		LocalDateTime day1 = LocalDateTime.now(BERLIN).plusDays(20).withHour(0).withMinute(0).withSecond(0).withNano(0);
		event = service.createEvent(lead, project.getId(), new ShiftService.EventDraft("Sommerfest am See", null,
				"Festwiese", at(day1.withHour(8)), at(day1.plusDays(1).withHour(20)), "Europe/Berlin", true));
		Shift setup = shift("Aufbau", day1.withHour(9), day1.withHour(13), 3, null);
		Shift till = shift("Kasse", day1.withHour(14), day1.withHour(18), 1, 1);
		shift("Abbau", day1.plusDays(1).withHour(16), day1.plusDays(1).withHour(19), 2, null);
		assignments.assign(ann, setup.getId(), null, true);
		assignments.assign(bob, setup.getId(), null, true);
		assignments.assign(dana, till.getId(), null, true);
		// The till is full: Carlotta waits, and a waiting person is in no file.
		assertThat(assignments.assign(carl, till.getId(), null, true).assignment().getStatus())
				.isEqualTo(ShiftAssignment.Status.WAITING);
	}

	@AfterEach
	void restoreBudget() {
		properties.getRateLimit().setExportsPerMinute(budget);
	}

	@Test
	@DisplayName("every format: the head, a section per day, wall-clock times in the event's zone, what is missing as text")
	void everyFormatCarriesThePlan() throws Exception {
		for (ExportFormat format : ExportFormat.values()) {
			String text = text(format, export(lead, format, false, Locale.GERMAN));
			assertThat(text).as(format.name())
					.contains("Sommerfest am See", "Festwiese", "3 von 6 Plätzen")
					.contains("09:00 – 13:00", "14:00 – 18:00", "16:00 – 19:00")
					.contains("Aufbau", "Kasse", "Abbau")
					.contains("2, fehlt 1", "0, fehlt 2")
					.contains("Der Stand kann sich ändern")
					.doesNotContain("Wiederholung");
			if (format != ExportFormat.XLSX) {
				// A workbook has no eyebrow; its first sheet is named after the event.
				assertThat(text).contains("Schichtplan");
			}
		}
	}

	@Test
	@DisplayName("a cancelled event's shifts miss nobody: the file says how many hold a place, not who is missing")
	void cancelledShiftsMissNobody() throws Exception {
		service.cancelEvent(lead, event.getId(), null);
		String text = text(ExportFormat.XLSX, export(lead, ExportFormat.XLSX, false, Locale.GERMAN));
		assertThat(text).contains("3 von 6 Plätzen").doesNotContain("fehlt");
	}

	@Test
	@DisplayName("without names no person is in the file, read back as text; with names the people holding a place are")
	void namesOnlyWhenAsked() throws Exception {
		for (ExportFormat format : ExportFormat.values()) {
			String without = text(format, export(lead, format, false, Locale.ENGLISH));
			for (String name : HELD) {
				assertThat(without).as(format + " without names").doesNotContain(name);
			}
			assertThat(without).doesNotContain(WAITING).doesNotContain("People");

			String with = text(format, export(lead, format, true, Locale.ENGLISH));
			assertThat(with).as(format + " with names").contains(HELD).contains("People", "1 missing");
			assertThat(with).as(format + ": the waiting list is never in the file").doesNotContain(WAITING);
		}
	}

	@Test
	@DisplayName("a member exports without names; with names it is 403, a stranger gets 404, a bad format 400")
	void rightsOverHttp() {
		HttpResponse<byte[]> plain = get("/api/v1/shifts/events/" + event.getId() + "/export?format=pdf",
				member, "de");
		assertThat(plain.statusCode()).isEqualTo(200);
		assertThat(plain.headers().firstValue("Content-Type")).hasValue("application/pdf");
		assertThat(plain.headers().firstValue("Content-Disposition").orElseThrow())
				.startsWith("attachment").contains("schichtplan-Sommerfest-am-See-");
		assertThat(plain.headers().firstValue("X-Export-Truncated")).hasValue("false");

		HttpResponse<byte[]> english = get("/api/v1/shifts/events/" + event.getId() + "/export?format=xlsx",
				member, "en");
		assertThat(english.statusCode()).isEqualTo(200);
		assertThat(english.headers().firstValue("Content-Disposition").orElseThrow())
				.contains("shift-plan-Sommerfest-am-See-").contains(".xlsx");

		assertThat(get("/api/v1/shifts/events/" + event.getId() + "/export?format=docx&names=true", member, "de")
				.statusCode()).isEqualTo(403);
		assertThat(get("/api/v1/shifts/events/" + event.getId() + "/export?format=docx&names=true", lead, "de")
				.statusCode()).isEqualTo(200);
		assertThat(get("/api/v1/shifts/events/" + event.getId() + "/export?format=pdf", stranger, "de")
				.statusCode()).isEqualTo(404);
		assertThat(get("/api/v1/shifts/events/" + event.getId() + "/export?format=odt", lead, "de")
				.statusCode()).isEqualTo(400);
	}

	@Test
	@DisplayName("each export is audited with the event, the format and names, never the title")
	void auditedWithoutTheTitle() {
		export(lead, ExportFormat.XLSX, true, Locale.GERMAN);
		export(member, ExportFormat.PDF, false, Locale.GERMAN);

		List<AuditLog> logs = mongo.find(Query.query(Criteria.where("action").is(AuditAction.SHIFT_PLAN_EXPORTED)),
				AuditLog.class);
		assertThat(logs).hasSize(2).allSatisfy(log -> {
			assertThat(log.getTargetId()).isEqualTo(event.getId());
			assertThat(log.getMetadata()).containsEntry("eventId", event.getId())
					.containsOnlyKeys("eventId", "format", "names");
			assertThat(log.getMetadata().values()).noneMatch(value -> value.contains("Sommerfest"));
		});
		assertThat(logs).extracting(log -> log.getMetadata().get("format") + "/" + log.getMetadata().get("names"))
				.containsExactlyInAnyOrder("xlsx/true", "pdf/false");
	}

	@Test
	@DisplayName("a busy instance turns the export away before the budget; the budget turns it away after")
	void slotsAndBudget() {
		List<ExportSlots.Slot> taken = new ArrayList<>();
		try {
			for (int i = 0; i < ExportSlots.MAX_RUNNING; i++) {
				taken.add(slots.tryAcquire());
			}
			assertThatThrownBy(() -> exports.plan(lead, event.getId(), ExportFormat.PDF, false, Locale.GERMAN))
					.isInstanceOfSatisfying(ApiException.class, ex -> {
						assertThat(ex.getStatus().value()).isEqualTo(429);
						assertThat(ex.getMessage()).isEqualTo("error.rateLimited");
					});
		}
		finally {
			taken.forEach(ExportSlots.Slot::close);
		}

		properties.getRateLimit().setExportsPerMinute(1);
		User exporter = user("budget-" + System.nanoTime(), "Budget");
		mongo.updateFirst(Query.query(Criteria.where("_id").is(project.getId())),
				new Update().push("memberIds", exporter.getId()), Project.class);
		exports.plan(exporter, event.getId(), ExportFormat.PDF, false, Locale.GERMAN).close();
		assertThatThrownBy(() -> exports.plan(exporter, event.getId(), ExportFormat.PDF, false, Locale.GERMAN))
				.isInstanceOfSatisfying(ApiException.class, ex -> {
					assertThat(ex.getStatus().value()).isEqualTo(429);
					assertThat(ex.getMessage()).isEqualTo("error.issue.exportRateLimited");
				});
		// Refused or written, every plan gave its slot back.
		ExportSlots.Slot probe = slots.tryAcquire();
		probe.close();
	}

	@Test
	@DisplayName("an occurrence of a series says how it repeats, in the file's language")
	void seriesLabel() throws Exception {
		ShiftSeries series = mongo.insert(ShiftSeries.builder().projectId(project.getId()).title("Sommerfest am See")
				.timezone("Europe/Berlin").rrule("FREQ=WEEKLY;BYDAY=SA")
				.firstStart(LocalDateTime.ofInstant(event.getStartsAt(), BERLIN)).durationMinutes(60).build());
		mongo.updateFirst(Query.query(Criteria.where("_id").is(event.getId())),
				new Update().set("seriesId", series.getId()), ShiftEvent.class);

		String text = text(ExportFormat.DOCX, export(lead, ExportFormat.DOCX, false, Locale.GERMAN));
		assertThat(text).contains("Wiederholung", "wöchentlich");
	}

	@Test
	@DisplayName("a Word document of two hundred named shifts stays under a fixed allocation")
	void twoHundredShiftsInWordStayWithinAFixedHeap() throws Exception {
		ShiftEvent big = service.createEvent(lead, project.getId(), new ShiftService.EventDraft("Messe", null, null,
				event.getStartsAt(), event.getStartsAt().plus(Duration.ofDays(10)), "Europe/Berlin", true));
		List<Document> shifts = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			java.time.Instant start = big.getStartsAt().plus(Duration.ofMinutes(70L * i));
			shifts.add(new Document("eventId", big.getId()).append("projectId", project.getId())
					.append("title", "Stand " + i).append("startsAt", java.util.Date.from(start))
					.append("endsAt", java.util.Date.from(start.plus(Duration.ofHours(1))))
					.append("requiredHelpers", 3).append("assignedCount", 0).append("cancelled", false)
					.append("deleted", false));
		}
		mongo.getCollection("shifts").insertMany(shifts);

		// Once to load the renderer's classes and fonts, which are not the document's cost.
		export(lead, ExportFormat.DOCX, true, Locale.GERMAN);
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		long before = threads.getCurrentThreadAllocatedBytes();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ShiftPlanExport.Plan plan = exports.plan(lead, big.getId(), ExportFormat.DOCX, true, Locale.GERMAN)) {
			exports.write(plan, out);
		}
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;

		assertThat(text(ExportFormat.DOCX, out.toByteArray())).contains("Stand 0", "Stand 199");
		// Measured at about 11 MB once warm; the ceiling leaves room for another JVM, not for a
		// document held twice or a plan read whole per row.
		assertThat(allocated).as("bytes allocated rendering 200 shifts").isLessThan(64L * 1024 * 1024);
	}

	// --- fixtures -----------------------------------------------------------

	private byte[] export(User caller, ExportFormat format, boolean names, Locale locale) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ShiftPlanExport.Plan plan = exports.plan(caller, event.getId(), format, names, locale)) {
			exports.write(plan, out);
		}
		return out.toByteArray();
	}

	/** The words of a file as a reader would copy them out: PDFBox for a PDF, POI for the rest. */
	private static String text(ExportFormat format, byte[] bytes) throws Exception {
		switch (format) {
			case PDF -> {
				try (PDDocument document = Loader.loadPDF(bytes)) {
					return new PDFTextStripper().getText(document);
				}
			}
			case DOCX -> {
				try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
						XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
					return extractor.getText();
				}
			}
			default -> {
				StringBuilder out = new StringBuilder();
				try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
					for (Sheet sheet : workbook) {
						out.append(sheet.getSheetName()).append('\n');
						for (Row row : sheet) {
							for (Cell cell : row) {
								out.append(cell.toString()).append('\t');
							}
							out.append('\n');
						}
					}
				}
				return out.toString();
			}
		}
	}

	private Shift shift(String title, LocalDateTime start, LocalDateTime end, int required, Integer max) {
		return service.createShift(lead, event.getId(), new ShiftService.ShiftDraft(title, at(start), at(end),
				required, max));
	}

	private static java.time.Instant at(LocalDateTime wallClock) {
		return ZonedDateTime.of(wallClock, BERLIN).toInstant();
	}

	private User user(String name, String displayName) {
		return users.save(User.builder().username(name).email(name + "@plan.test").displayName(displayName)
				.locale("de").roles(new HashSet<>(Set.of(Role.MEMBER))).active(true).build());
	}

	private HttpResponse<byte[]> get(String path, User caller, String language) {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Authorization", "Bearer " + tokens.issue(caller).accessToken())
				.header("Accept-Language", language).GET().build();
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		}
		catch (Exception ex) {
			throw new IllegalStateException("request failed: " + path, ex);
		}
	}
}
