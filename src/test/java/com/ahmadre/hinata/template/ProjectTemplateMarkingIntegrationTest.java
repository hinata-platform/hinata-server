package com.ahmadre.hinata.template;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLogRepository;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.RelativeDate;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.project.ProjectUpdateRequest;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
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
 * The template marker and the one-step way from a template to a project.
 *
 * <p>The marker is deliberately nothing more than a marker: same rights, same search, same boards,
 * a different section in the list. What is worth testing is therefore not what a template <em>is</em>
 * but the two things around it — that the field cannot be set on an instance where templates do not
 * exist, and that instantiating produces a project with a timeline that is already right.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false",
		"hinata.project-templates.enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
class ProjectTemplateMarkingIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private ProjectService projectService;
	@Autowired
	private ProjectCopyService copies;
	@Autowired
	private IssueService issues;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;
	@Autowired
	private AuditLogRepository auditLog;
	@Autowired
	private SettingsService settings;

	private User lead;
	private User outsider;
	private Project template;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "issue_links", "projects", "users",
				"audit_log", "server_settings")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		lead = user("lead");
		outsider = user("outsider");
		template = projects.save(Project.builder().key("TPL").name("Event template")
				.leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId())))
				.template(true)
				.build());
		issue("Book the room", weeks(-6));
		issue("Posters", weeks(-3));
		issue("Instagram post", weeks(-1));
		issue("Settle the bill", new RelativeDate(3, RelativeDate.Unit.DAYS,
				RelativeDate.Basis.CALENDAR));
	}

	@AfterEach
	void clearOverride() {
		ServerSettings current = settings.get();
		current.setProjectTemplates(null);
		settings.save(current);
	}

	// --- the marker -----------------------------------------------------------

	@Test
	@DisplayName("a project can be marked as a template and marked back")
	void theMarkerGoesBothWays() {
		Project plain = projects.save(Project.builder().key("PLAIN").name("An ordinary project")
				.leadId(lead.getId()).leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId()))).build());

		Project marked = projectService.applyUpdate(plain.getId(), update(true), lead);
		assertThat(marked.isTemplate()).isTrue();

		Project unmarked = projectService.applyUpdate(plain.getId(), update(false), lead);
		assertThat(unmarked.isTemplate()).isFalse();

		assertThat(auditLog.findAll()).anySatisfy(entry ->
				assertThat(entry.getAction()).isEqualTo(AuditAction.PROJECT_TEMPLATE_MARKED));
	}

	@Test
	@DisplayName("with the module off the marker is refused and the rest of the save goes through")
	void theMarkerNeedsTheModule() {
		moduleOff();

		assertThatThrownBy(() -> projectService.applyUpdate(template.getId(), update(false), lead))
				.isInstanceOfSatisfying(ApiException.class, ex ->
						assertThat(ex.getMessageKey()).isEqualTo("error.feature.disabled"));

		// A project nobody can rename would be a broken product, not a disabled feature: only
		// the one field that needs the module is refused.
		Project renamed = projectService.applyUpdate(template.getId(), new ProjectUpdateRequest(
				null, "Renamed anyway", null, null, null, null, null, null, null, null, null,
				null, null, null), lead);
		assertThat(renamed.getName()).isEqualTo("Renamed anyway");
		assertThat(renamed.isTemplate()).isTrue();
	}

	@Test
	@DisplayName("a save that does not mention the marker leaves it where it was")
	void anOmittedMarkerChangesNothing() {
		moduleOff();

		Project saved = projectService.applyUpdate(template.getId(), new ProjectUpdateRequest(
				null, "Still a template", null, null, null, null, null, null, null, null, null,
				null, null, null), lead);

		assertThat(saved.isTemplate()).isTrue();
	}

	// --- one step from template to project ------------------------------------

	@Test
	@DisplayName("instantiating gives a project whose deadlines are already right")
	void instantiatingSetsTheTimeline() {
		ProjectCopyService.Result result = copies.copy(template.getId(),
				new ProjectCopyService.Options("Beers 4 Queers SoSe 27", "BFQ27",
						LocalDate.of(2027, 5, 14), true, false, true, false, false), lead);

		assertThat(result.issuesCopied()).isEqualTo(4);
		assertThat(result.deadlinesSet()).isEqualTo(4);
		assertThat(result.project().isTemplate()).isFalse();
		assertThat(dueOf(result.project(), "Book the room")).isEqualTo(LocalDate.of(2027, 4, 2));
		assertThat(dueOf(result.project(), "Settle the bill")).isEqualTo(LocalDate.of(2027, 5, 17));
	}

	@Test
	@DisplayName("instantiating without a date keeps the rules and leaves the deadlines empty")
	void instantiatingWithoutADateKeepsTheRules() {
		ProjectCopyService.Result result = copies.copy(template.getId(),
				new ProjectCopyService.Options("Undated", "UND", null, true, false, true, false,
						false), lead);

		assertThat(result.deadlinesSet()).isZero();
		assertThat(dueOf(result.project(), "Book the room")).isNull();
		assertThat(offsetOf(result.project(), "Book the room")).isEqualTo(weeks(-6));
	}

	@Test
	@DisplayName("somebody who cannot see the template cannot instantiate it")
	void anOutsiderIsRefused() {
		assertThatThrownBy(() -> copies.copy(template.getId(), new ProjectCopyService.Options(
				"Mine now", "MINE", LocalDate.of(2027, 5, 14), true, false, true, false, false),
				outsider))
				.isInstanceOfSatisfying(ApiException.class, ex ->
						assertThat(ex.getMessageKey()).isEqualTo("error.project.notMember"));
		assertThat(projects.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("with the module off there is no way to copy at all")
	void copyingNeedsTheModule() {
		moduleOff();

		assertThatThrownBy(() -> copies.copy(template.getId(), new ProjectCopyService.Options(
				"Nope", "NOPE", null, true, false, true, false, false), lead))
				.isInstanceOfSatisfying(ApiException.class, ex ->
						assertThat(ex.getMessageKey())
								.isEqualTo(ProjectTemplateGate.DISABLED_KEY));
		assertThat(projects.count()).isEqualTo(1);
	}

	// --- helpers --------------------------------------------------------------

	private void moduleOff() {
		ServerSettings current = settings.get();
		ServerSettings.ProjectTemplates block = new ServerSettings.ProjectTemplates();
		block.setEnabled(false);
		current.setProjectTemplates(block);
		settings.save(current);
	}

	private static ProjectUpdateRequest update(Boolean template) {
		return new ProjectUpdateRequest(null, null, null, null, null, null, null, null, null,
				null, null, null, template, null);
	}

	private static RelativeDate weeks(int amount) {
		return new RelativeDate(amount, RelativeDate.Unit.WEEKS, RelativeDate.Basis.CALENDAR);
	}

	private void issue(String title, RelativeDate dueOffset) {
		issues.create(Issue.builder().projectId(template.getId()).title(title)
				.dueOffset(dueOffset).build(), lead);
	}

	private Issue copied(Project project, String title) {
		return mongo.find(Query.query(Criteria.where("projectId").is(project.getId())), Issue.class)
				.stream().filter(issue -> title.equals(issue.getTitle())).findFirst()
				.orElseThrow(() -> new AssertionError("no copied issue titled " + title));
	}

	private LocalDate dueOf(Project project, String title) {
		return copied(project, title).getDueDate();
	}

	private RelativeDate offsetOf(Project project, String title) {
		return copied(project, title).getDueOffset();
	}

	private User user(String name) {
		return users.save(User.builder().username(name).email(name + "@example.org")
				.displayName(name).roles(Set.of(Role.MEMBER)).build());
	}
}
