package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.issue.export.ExportFormat;
import com.ahmadre.hinata.issue.export.ExportWords;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.MessageSource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Report exports against a real database (HIN-93): streamed in chunks, capped with a marker,
 * neutralised against formulas, metered per person, audited, and never wider than the reader's
 * people scope.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"hinata.rate-limit.exports-per-minute=100",
		"management.health.mail.enabled=false",
		"hinata.time-tracking.advanced-enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class TimeReportExportIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimeReportExport exports;
	@Autowired
	private TimeReportService reports;
	@Autowired
	private UserRepository users;
	@Autowired
	private SettingsService settings;
	@Autowired
	private MessageSource messages;
	@Autowired
	private HinataProperties properties;

	private User admin;
	private User member;
	private Project project;

	@BeforeEach
	void seed() {
		for (Class<?> type : List.of(User.class, Project.class, WorkItem.class, AuditLog.class)) {
			mongo.remove(new Query(), type);
		}
		settings.save(new ServerSettings());
		admin = person("admin", Role.ADMIN);
		member = person("member", Role.MEMBER);
		project = mongo.insert(Project.builder().key("APO").name("Apollo").leadId(admin.getId())
				.leadIds(new ArrayList<>(List.of(admin.getId())))
				.memberIds(new ArrayList<>(List.of(admin.getId(), member.getId()))).build());
	}

	@Test
	void sixtyThousandEntriesStreamOutInChunksAndAPdfStopsAtItsCeiling() throws Exception {
		List<WorkItem> batch = new ArrayList<>();
		for (int i = 0; i < 60_000; i++) {
			batch.add(entry(member, 30, "entry " + i));
			if (batch.size() == 5_000) {
				mongo.insert(batch, WorkItem.class);
				batch.clear();
			}
		}

		CountingStream csv = new CountingStream();
		try (TimeReportExport.Plan plan = exports.plan(admin, filter(), TimeReportService.GroupBy.PROJECT, null)) {
			assertThat(plan.truncated()).isFalse();
			exports.writeCsv(plan, words(), csv);
		}
		// A flush per chunk: the file left while it was still being read.
		assertThat(csv.flushes).isGreaterThanOrEqualTo(60_000 / TimeReportExport.CHUNK);
		assertThat(csv.text().lines().count()).isEqualTo(60_001);

		ByteArrayOutputStream pdf = new ByteArrayOutputStream();
		try (TimeReportExport.Plan plan = exports.plan(admin, filter(), TimeReportService.GroupBy.PROJECT,
				ExportFormat.PDF)) {
			assertThat(plan.truncated()).isTrue();
			exports.writeDocument(plan, ExportFormat.PDF, words(), pdf);
		}
		try (PDDocument document = Loader.loadPDF(pdf.toByteArray())) {
			assertThat(new PDFTextStripper().getText(document)).contains("first 5000 entries");
		}
		assertThat(mongo.find(Query.query(Criteria.where("action").is(AuditAction.TIME_REPORT_EXPORTED)),
				AuditLog.class)).extracting(log -> log.getMetadata().get("rows"))
				.containsExactlyInAnyOrder("60000", "5000");
	}

	@Test
	void aWorkbookCarriesTheTotalsTheGroupsAndTheEntriesAsNumbers() throws Exception {
		mongo.insert(entry(member, 90, "=HYPERLINK(\"http://evil\")"));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (TimeReportExport.Plan plan = exports.plan(admin, filter(), TimeReportService.GroupBy.PROJECT,
				ExportFormat.XLSX)) {
			exports.writeDocument(plan, ExportFormat.XLSX, words(), out);
		}
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
			Sheet entries = workbook.getSheet("Entries");
			assertThat(entries).isNotNull();
			assertThat(entries.getRow(2).getCell(3).getNumericCellValue()).isEqualTo(1.5);
			assertThat(entries.getRow(2).getCell(11).getStringCellValue()).startsWith("'=HYPERLINK");
			assertThat(workbook.getSheet("By project")).isNotNull();
		}
	}

	@Test
	void aMembersFileHoldsTheirOwnEntriesOnly() throws Exception {
		mongo.insert(entry(member, 30, "mine"));
		mongo.insert(entry(admin, 45, "not yours"));
		CountingStream csv = new CountingStream();
		try (TimeReportExport.Plan plan = exports.plan(member, filter(), TimeReportService.GroupBy.PROJECT, null)) {
			exports.writeCsv(plan, words(), csv);
		}
		assertThat(csv.text()).contains("mine").doesNotContain("not yours");
	}

	@Test
	void theBudgetIsSpentBeforeTheScopeIsRead() {
		int before = properties.getRateLimit().getExportsPerMinute();
		properties.getRateLimit().setExportsPerMinute(1);
		User exporter = person("budget-" + System.nanoTime(), Role.MEMBER);
		try {
			exports.plan(exporter, filter(), TimeReportService.GroupBy.PROJECT, null).close();
			assertThatThrownBy(() -> exports.plan(exporter, filter(), TimeReportService.GroupBy.PROJECT, null))
					.isInstanceOfSatisfying(ApiException.class,
							ex -> assertThat(ex.getStatus().value()).isEqualTo(429));
		}
		finally {
			properties.getRateLimit().setExportsPerMinute(before);
		}
	}

	// --- fixtures -----------------------------------------------------------

	private TimeReportFilter filter() {
		return TimeReportFilter.of(DAY, DAY, null, null, null, null, null, null, null, Set.of(), null, null, null,
				null, ZoneOffset.UTC);
	}

	private ExportWords words() {
		return new ExportWords(messages, Locale.ENGLISH, ZoneOffset.UTC);
	}

	private WorkItem entry(User owner, int minutes, String description) {
		return WorkItem.builder().userId(owner.getId()).projectId(project.getId()).date(DAY)
				.durationMinutes(minutes).description(description).source(WorkItem.Source.APP).build();
	}

	private User person(String name, Role role) {
		return users.save(User.builder().username(name).displayName(name).email(name + "@example.test")
				.roles(Set.of(role)).active(true).locale("en").build());
	}

	/** Collects what is written and counts how often the writer let it go. */
	private static final class CountingStream extends FilterOutputStream {

		private int flushes;

		CountingStream() {
			super(new ByteArrayOutputStream());
		}

		@Override
		public void write(byte[] bytes, int offset, int length) throws IOException {
			out.write(bytes, offset, length);
		}

		@Override
		public void flush() throws IOException {
			flushes++;
			super.flush();
		}

		String text() {
			return ((ByteArrayOutputStream) out).toString(StandardCharsets.UTF_8);
		}
	}
}
