package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CSV import against a real database (HIN-93): a preview that writes nothing and names every row
 * that fails, a commit that writes all rows or none, a second commit that writes nothing, the lock
 * date and the required fields on every row, and imports for others only by an administrator.
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
class TimeImportIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final LocalDate YESTERDAY = LocalDate.now(ZoneOffset.UTC).minusDays(1);
	private static final String DAY = YESTERDAY.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimeImportService imports;
	@Autowired
	private UserRepository users;
	@Autowired
	private SettingsService settings;

	private User admin;
	private User member;
	private Issue issue;

	@BeforeEach
	void seed() {
		for (Class<?> type : List.of(User.class, Project.class, Issue.class, WorkItem.class, TimeImport.class,
				AuditLog.class)) {
			mongo.remove(new Query(), type);
		}
		settings.save(new ServerSettings());
		admin = person("admin", Role.ADMIN);
		member = person("member", Role.MEMBER);
		Project project = mongo.insert(Project.builder().key("APO").name("Apollo").leadId(admin.getId())
				.leadIds(new ArrayList<>(List.of(admin.getId())))
				.memberIds(new ArrayList<>(List.of(admin.getId(), member.getId()))).build());
		issue = mongo.insert(Issue.builder().projectId(project.getId()).readableId("APO-1").numberInProject(1)
				.title("Work").state("Open").build());
	}

	@Test
	void thePreviewChecksEveryRowAndWritesNothing() {
		TimeImportService.Preview preview = preview(member, """
				Datum;Beginn;Ende;Projekt;Vorgang;Beschreibung;Tags
				%1$s;09:00;10:30;APO;APO-1;Planung;design, research
				31.02.2026;09:00;10:00;APO;;Kein Tag;
				%1$s;;;NOPE;;Unbekannt;
				""".formatted(DAY), null);

		assertThat(preview.mapping()).containsEntry(TimeImportService.Column.DATE, 0)
				.containsEntry(TimeImportService.Column.START, 1).containsEntry(TimeImportService.Column.TAGS, 6);
		assertThat(preview.validRows()).isEqualTo(1);
		assertThat(preview.errors().getContent()).extracting(TimeImport.RowError::line).containsExactly(3, 4);
		assertThat(preview.errors().getContent().get(1).message()).contains("NOPE");
		assertThat(preview.rows().get(0).minutes()).isEqualTo(90);
		assertThat(preview.rows().get(0).tags()).containsExactly("design", "research");
		assertThat(mongo.count(new Query(), WorkItem.class)).isZero();
	}

	@Test
	void aCommitWritesEveryRowOnceMarkedAsCsv() {
		TimeImportService.Preview preview = preview(member, """
				date,minutes,issue,description
				%1$s,30,APO-1,one
				%1$s,45,APO-1,two
				""".formatted(YESTERDAY), null);

		TimeImportService.Result first = imports.commit(member, preview.importId());
		TimeImportService.Result again = imports.commit(member, preview.importId());

		assertThat(first.inserted()).isEqualTo(2);
		assertThat(again).isEqualTo(first);
		List<WorkItem> written = mongo.findAll(WorkItem.class);
		assertThat(written).hasSize(2).allSatisfy(item -> {
			assertThat(item.getSource()).isEqualTo(WorkItem.Source.CSV);
			assertThat(item.getUserId()).isEqualTo(member.getId());
			assertThat(item.getImportId()).isEqualTo(preview.importId());
		});
		assertThat(mongo.findById(issue.getId(), Issue.class).getSpentMinutes()).isEqualTo(75);
	}

	@Test
	void aLockThatMovesBeforeTheCommitLeavesNothingBehind() {
		TimeImportService.Preview preview = preview(member, """
				date,minutes,issue
				%1$s,30,APO-1
				%1$s,30,APO-1
				""".formatted(YESTERDAY), null);
		policy(block -> block.setLockBefore(LocalDate.now(ZoneOffset.UTC)));

		assertThatThrownBy(() -> imports.commit(member, preview.importId()))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getMessageKey()).isEqualTo("error.time.import.rowChanged"));
		assertThat(mongo.count(new Query(), WorkItem.class)).isZero();
		assertThat(mongo.findById(preview.importId(), TimeImport.class).getStatus())
				.isEqualTo(TimeImport.Status.FAILED);
		assertThat(mongo.findById(issue.getId(), Issue.class).getSpentMinutes()).isZero();
	}

	@Test
	void requiredFieldsHoldForEveryRow() {
		policy(block -> {
			ServerSettings.TimeTracking.RequiredFields required = new ServerSettings.TimeTracking.RequiredFields();
			required.setDescription(true);
			block.setRequiredFields(required);
		});
		TimeImportService.Preview preview = preview(member, """
				date,minutes,issue,description
				%1$s,30,APO-1,
				""".formatted(YESTERDAY), null);

		assertThat(preview.validRows()).isZero();
		assertThat(preview.errorCount()).isEqualTo(1);
	}

	@Test
	void onlyAnAdministratorImportsForSomebodyElseAndThatIsAudited() {
		String file = """
				date,minutes,issue
				%s,30,APO-1
				""".formatted(YESTERDAY);
		assertThatThrownBy(() -> preview(member, file, admin.getId())).isInstanceOf(ApiException.class);

		TimeImportService.Preview preview = preview(admin, file, member.getId());
		imports.commit(admin, preview.importId());

		assertThat(mongo.findAll(WorkItem.class)).singleElement()
				.extracting(WorkItem::getUserId).isEqualTo(member.getId());
		assertThat(mongo.find(Query.query(Criteria.where("action").is(AuditAction.TIME_ENTRIES_IMPORTED)),
				AuditLog.class)).hasSize(1);
	}

	@Test
	void anInterruptedCommitIsTakenBackByTheSweep() {
		TimeImportService.Preview preview = preview(member, """
				date,minutes,issue
				%s,30,APO-1
				""".formatted(YESTERDAY), null);
		mongo.insert(WorkItem.builder().userId(member.getId()).projectId(issue.getProjectId()).issueId(issue.getId())
				.date(YESTERDAY).durationMinutes(30).source(WorkItem.Source.CSV).importId(preview.importId()).build());
		mongo.updateFirst(Query.query(Criteria.where("_id").is(preview.importId())), new Update()
				.set("status", TimeImport.Status.COMMITTING).set("commitStartedAt", Instant.now().minusSeconds(7200)),
				TimeImport.class);

		assertThat(imports.sweep()).isEqualTo(1);
		assertThat(mongo.count(new Query(), WorkItem.class)).isZero();
	}

	// --- fixtures -----------------------------------------------------------

	private TimeImportService.Preview preview(User actor, String csv, String target) {
		return imports.preview(actor, new MockMultipartFile("file", "entries.csv", "text/csv",
				csv.getBytes(StandardCharsets.UTF_8)), Map.of(), target, Locale.ENGLISH);
	}

	private void policy(java.util.function.Consumer<ServerSettings.TimeTracking> change) {
		ServerSettings current = settings.get();
		ServerSettings.TimeTracking block = current.getTimeTracking() != null ? current.getTimeTracking()
				: new ServerSettings.TimeTracking();
		change.accept(block);
		current.setTimeTracking(block);
		settings.save(current);
	}

	private User person(String name, Role role) {
		return users.save(User.builder().username(name).displayName(name).email(name + "@example.test")
				.roles(Set.of(role)).active(true).locale("en").build());
	}
}
