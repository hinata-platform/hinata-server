package com.ahmadre.hinata.template;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLog;
import com.ahmadre.hinata.audit.AuditLogRepository;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.RelativeDate;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueActivity;
import com.ahmadre.hinata.issue.IssueActivityRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Moving a project's event date against a real MongoDB.
 *
 * <p>The things worth asserting here are all properties of the write path. Whether exactly the
 * issues with an offset move and the ones somebody dated by hand stay put; whether the offsets
 * survive a project with no event date; whether a hand-set date really does take the rule with it,
 * so the next move leaves that issue alone. None of that can be observed against an object built
 * in memory, which is why this test carries a container.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		// The module is on for this file; the gate that keeps it off is a different test.
		"hinata.project-templates.enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class ProjectScheduleIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	/** A Thursday, so a working-day offset has a weekend to step over. */
	private static final LocalDate EVENT = LocalDate.of(2026, 11, 12);

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private ProjectScheduleService schedule;
	@Autowired
	private IssueService issues;
	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private IssueActivityRepository activities;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;
	@Autowired
	private AuditLogRepository auditLog;
	@Autowired
	private SettingsService settings;
	@Autowired
	private ProjectDeadlines deadlines;

	private User lead;
	private User member;
	private Project project;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "issue_activities", "projects", "users",
				"audit_log", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		lead = user("lead", Role.MEMBER);
		member = user("member", Role.MEMBER);
		project = projects.save(Project.builder().key("BFQ").name("Beers 4 Queers")
				.leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId())))
				.eventDate(EVENT)
				.build());
	}

	// --- the offset is the rule, the date is its result ----------------------

	@Test
	@DisplayName("an issue created with an offset arrives with the date already filled in")
	void theDateIsWrittenOnCreate() {
		Issue booked = issue("Book the room", weeks(-6));

		assertThat(booked.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(booked.getDueOffset()).isEqualTo(weeks(-6));
		// Written, not computed: this is the field the board, the reminder job and the
		// published store app read, and none of them know what an offset is.
		assertThat(issueRepository.findById(booked.getId()).orElseThrow().getDueDate())
				.isEqualTo(LocalDate.of(2026, 10, 1));
	}

	@Test
	@DisplayName("a working-day offset skips the weekend the calendar offset runs into")
	void workingDaysDiffer() {
		Issue calendar = issue("Posters", days(-4));
		Issue working = issue("Print run", new RelativeDate(-4, RelativeDate.Unit.DAYS,
				RelativeDate.Basis.WORKING));

		assertThat(calendar.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 8));
		assertThat(working.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 6));
	}

	// --- moving the event date -----------------------------------------------

	@Test
	@DisplayName("the preview names what would move and changes nothing")
	void previewChangesNothing() {
		Issue room = issue("Book the room", weeks(-6));
		Issue typed = typedDue("Pay the band", LocalDate.of(2026, 11, 2));

		ProjectScheduleService.Preview preview =
				schedule.preview(project.getId(), EVENT.plusDays(7), null, lead);

		assertThat(preview.shiftDays()).isEqualTo(7);
		// One deadline, and the count is of deadlines: an issue whose start and due both move
		// counts twice, because the sheet lists one row per date.
		assertThat(preview.moved()).isEqualTo(1);
		assertThat(preview.manual()).isEqualTo(1);
		assertThat(preview.moves()).singleElement().satisfies(move -> {
			assertThat(move.readableId()).isEqualTo(room.getReadableId());
			assertThat(move.from()).isEqualTo(LocalDate.of(2026, 10, 1));
			assertThat(move.to()).isEqualTo(LocalDate.of(2026, 10, 8));
		});
		// Nothing written: not the project's date, not either deadline.
		assertThat(projects.findById(project.getId()).orElseThrow().getEventDate()).isEqualTo(EVENT);
		assertThat(stored(room).getDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(stored(typed).getDueDate()).isEqualTo(LocalDate.of(2026, 11, 2));
	}

	@Test
	@DisplayName("applying moves exactly the deadlines that carry a rule")
	void applyMovesTheRuleBoundOnes() {
		Issue room = issue("Book the room", weeks(-6));
		Issue post = issue("Instagram post", weeks(-1));
		Issue typed = typedDue("Pay the band", LocalDate.of(2026, 11, 2));

		ProjectScheduleService.Result result =
				schedule.apply(project.getId(), EVENT.plusDays(7), lead);

		assertThat(result.deadlinesMoved()).isEqualTo(2);
		assertThat(result.leftAlone()).isEqualTo(1);
		assertThat(stored(room).getDueDate()).isEqualTo(LocalDate.of(2026, 10, 8));
		assertThat(stored(post).getDueDate()).isEqualTo(LocalDate.of(2026, 11, 12));
		// The one somebody typed is untouched, which is the promise the whole feature rests on.
		assertThat(stored(typed).getDueDate()).isEqualTo(LocalDate.of(2026, 11, 2));
		assertThat(projects.findById(project.getId()).orElseThrow().getEventDate())
				.isEqualTo(EVENT.plusDays(7));
	}

	@Test
	@DisplayName("a moved deadline says so in the issue's own history")
	void theIssueHistoryExplainsTheNewDate() {
		Issue room = issue("Book the room", weeks(-6));

		schedule.apply(project.getId(), EVENT.plusDays(7), lead);

		List<IssueActivity> history = activities.findByIssueIdOrderByCreatedAtDesc(room.getId());
		assertThat(history).anySatisfy(entry -> {
			assertThat(entry.getField()).isEqualTo(IssueActivity.Field.DUE_DATE);
			assertThat(entry.getFromValue()).isEqualTo("2026-10-01");
			assertThat(entry.getToValue()).isEqualTo("2026-10-08");
		});
	}

	@Test
	@DisplayName("the move is audited with the project and both counts")
	void theMoveIsAudited() {
		issue("Book the room", weeks(-6));
		typedDue("Pay the band", LocalDate.of(2026, 11, 2));

		schedule.apply(project.getId(), EVENT.plusDays(7), lead);

		assertThat(auditLog.findAll()).anySatisfy(entry -> {
			assertThat(entry.getAction()).isEqualTo(AuditAction.PROJECT_SCHEDULE_SHIFTED);
			assertThat(meta(entry, "project")).isEqualTo("BFQ");
			assertThat(meta(entry, "eventAfter")).isEqualTo("2026-11-19");
			assertThat(meta(entry, "deadlinesMoved")).isEqualTo("1");
			assertThat(meta(entry, "leftAlone")).isEqualTo("1");
		});
	}

	@Test
	@DisplayName("a reminder that already went out re-arms for the new day")
	void theReminderIsRearmed() {
		Issue room = issue("Book the room", weeks(-6));
		Issue reminded = stored(room);
		reminded.setDueReminderFor(reminded.getDueDate());
		issueRepository.save(reminded);

		schedule.apply(project.getId(), EVENT.plusDays(7), lead);

		// Otherwise the job stays silent about the new date because it once spoke about the old
		// one, and a deadline moves without anybody being told.
		assertThat(stored(room).getDueReminderFor()).isNull();
	}

	@Test
	@DisplayName("every moved deadline gets a history line, past the preview's own page size")
	void theHistoryIsNotPagedLikeThePreview() {
		// The preview names a handful of rows because a sheet is a decision, not a report. The
		// activity log is the opposite: one line per moved date, or an issue's date changes with
		// nothing anywhere saying who moved it. Clamping both with one number silently wrote
		// PREVIEW_LIMIT_MAX lines for however many dates actually moved.
		int count = ProjectScheduleService.PREVIEW_LIMIT_MAX + 25;
		for (int i = 0; i < count; i++) {
			issue("Bulk " + i, weeks(-2));
		}

		ProjectScheduleService.Result result =
				schedule.apply(project.getId(), EVENT.plusDays(7), lead);

		assertThat(result.deadlinesMoved()).isEqualTo(count);
		// Only the date lines: creating the issues wrote a CREATED entry each.
		assertThat(mongo.count(Query.query(
				Criteria.where("field").is(IssueActivity.Field.DUE_DATE)), IssueActivity.class))
				.isEqualTo(count);
		// And what a client asks for is still bounded.
		ProjectScheduleService.Preview preview = schedule.preview(project.getId(),
				EVENT.plusDays(14), Integer.MAX_VALUE, lead);
		assertThat(preview.moves()).hasSize(ProjectScheduleService.PREVIEW_LIMIT_MAX);
		assertThat(preview.moved()).isEqualTo(count);
	}

	@Test
	@DisplayName("a date nobody could have meant is refused rather than thrown out of")
	void anImpossibleDateIsRefused() {
		// Without the bound this reaches plusDays and throws out of the arithmetic: a 500 and a
		// stack trace per request, on a route any member may call in a loop.
		LocalDate absurd = LocalDate.of(999_999_999, 12, 31);

		assertThatThrownBy(() -> schedule.resolve(project.getId(), absurd,
				weeks(-1), member))
				.isInstanceOfSatisfying(ApiException.class, ex ->
						assertThat(ex.getMessageKey())
								.isEqualTo("error.project.eventDateOutOfRange"));
	}

	// --- the cases that must not lose anything -------------------------------

	@Test
	@DisplayName("clearing the event date leaves the deadlines and keeps the rules")
	void clearingTheDateKeepsEverything() {
		Issue room = issue("Book the room", weeks(-6));

		schedule.apply(project.getId(), null, lead);

		assertThat(projects.findById(project.getId()).orElseThrow().getEventDate()).isNull();
		assertThat(stored(room).getDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(stored(room).getDueOffset()).isEqualTo(weeks(-6));
	}

	@Test
	@DisplayName("a project without an event date keeps its rules and has no dates")
	void aTemplateHasRulesAndNoDates() {
		Project template = projects.save(Project.builder().key("TPL").name("Event template")
				.leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId())))
				.build());

		Issue room = issues.create(Issue.builder().projectId(template.getId())
				.title("Book the room").dueOffset(weeks(-6)).build(), lead);

		assertThat(room.getDueDate()).isNull();
		assertThat(stored(room).getDueOffset()).isEqualTo(weeks(-6));
	}

	@Test
	@DisplayName("a hand-set date takes the rule with it and is never moved again")
	void aTypedDateWins() {
		Issue room = issue("Book the room", weeks(-6));

		// What the PATCH route does when somebody picks a date: the date is set and the rule
		// behind it is dropped, visibly.
		issues.update(room.getId(), issue -> {
			issue.setDueDate(LocalDate.of(2026, 10, 20));
			issue.setDueOffset(null);
		}, lead);

		schedule.apply(project.getId(), EVENT.plusDays(7), lead);

		assertThat(stored(room).getDueDate()).isEqualTo(LocalDate.of(2026, 10, 20));
		assertThat(stored(room).getDueOffset()).isNull();
	}

	@Test
	@DisplayName("only a lead or an administrator may move the date")
	void amemberMayNotMoveIt() {
		issue("Book the room", weeks(-6));

		assertThatThrownBy(() -> schedule.apply(project.getId(), EVENT.plusDays(7), member))
				.isInstanceOfSatisfying(ApiException.class,
						ex -> assertThat(ex.getMessageKey()).isEqualTo("error.project.notLead"));
		assertThatThrownBy(() -> schedule.preview(project.getId(), EVENT.plusDays(7), null, member))
				.isInstanceOf(ApiException.class);
	}

	@Test
	@DisplayName("with the module off nothing recomputes a date, and the rule is kept")
	void theFlagClosesTheWritePathToo() {
		Issue room = issue("Book the room", weeks(-6));
		assertThat(room.getDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
		moduleOff();
		assertThat(deadlines.offsetsEnabled()).isFalse();

		// An ordinary edit of an issue that still carries a rule. Without the guard the write
		// path would go on resolving it for as long as the data exists, on an instance whose
		// administrator switched the feature off.
		issues.update(room.getId(), issue -> issue.setTitle("Book the room again"), lead);

		assertThat(stored(room).getDueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(stored(room).getDueOffset()).isEqualTo(weeks(-6));

		// And the schedule routes are closed to every caller, not only over HTTP.
		assertThatThrownBy(() -> schedule.apply(project.getId(), EVENT.plusDays(7), lead))
				.isInstanceOfSatisfying(ApiException.class, ex ->
						assertThat(ex.getMessageKey())
								.isEqualTo(ProjectTemplateGate.DISABLED_KEY));

		ServerSettings stored = settings.get();
		stored.setProjectTemplates(null);
		settings.save(stored);
		assertThat(deadlines.offsetsEnabled()).isTrue();
	}

	private void moduleOff() {
		ServerSettings stored = settings.get();
		ServerSettings.ProjectTemplates block = new ServerSettings.ProjectTemplates();
		block.setEnabled(false);
		stored.setProjectTemplates(block);
		settings.save(stored);
	}

	// --- helpers -------------------------------------------------------------

	private static RelativeDate weeks(int amount) {
		return new RelativeDate(amount, RelativeDate.Unit.WEEKS, RelativeDate.Basis.CALENDAR);
	}

	private static RelativeDate days(int amount) {
		return new RelativeDate(amount, RelativeDate.Unit.DAYS, RelativeDate.Basis.CALENDAR);
	}

	private Issue issue(String title, RelativeDate dueOffset) {
		return issues.create(Issue.builder().projectId(project.getId()).title(title)
				.dueOffset(dueOffset).build(), lead);
	}

	private Issue typedDue(String title, LocalDate due) {
		return issues.create(Issue.builder().projectId(project.getId()).title(title)
				.dueDate(due).build(), lead);
	}

	private Issue stored(Issue issue) {
		return issueRepository.findById(issue.getId()).orElseThrow();
	}

	private static String meta(AuditLog entry, String key) {
		return entry.getMetadata() == null ? null : entry.getMetadata().get(key);
	}

	private User user(String name, Role role) {
		return users.save(User.builder().username(name).email(name + "@example.org")
				.displayName(name).roles(Set.of(role)).build());
	}
}
