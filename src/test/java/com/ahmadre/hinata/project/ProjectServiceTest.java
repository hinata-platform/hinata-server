package com.ahmadre.hinata.project;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectServiceTest {

	private ProjectRepository projects;
	private MongoTemplate mongo;
	private ProjectService service;

	@BeforeEach
	void setUp() {
		projects = mock(ProjectRepository.class);
		mongo = mock(MongoTemplate.class);
		TeamRepository teams = mock(TeamRepository.class);
		when(teams.findByMembersUserId(any())).thenReturn(List.of());
		when(projects.save(any(Project.class))).thenAnswer(i -> i.getArgument(0));
		NotificationService notifications = mock(NotificationService.class);
		service = new ProjectService(projects, mongo, teams, notifications);
	}

	private User user(String id, Role... roles) {
		return User.builder().id(id).roles(Set.of(roles.length == 0 ? Role.MEMBER : roles[0])).build();
	}

	private Project.WorkflowState ws(String id, String name, int hue) {
		return Project.WorkflowState.builder().id(id).name(name).hue(hue).build();
	}

	private Project sampleProject() {
		Project project = Project.builder()
				.id("p1").key("HIV").name("Hinata")
				.leadIds(new ArrayList<>(List.of("u1")))
				.memberIds(new ArrayList<>(List.of("u1", "u2")))
				.workflowStates(new ArrayList<>(List.of(ws("s1", "Open", 250), ws("s2", "Done", 155))))
				.resolvedStates(new ArrayList<>(List.of("Done")))
				.labels(new ArrayList<>())
				.build();
		when(projects.findById("p1")).thenReturn(Optional.of(project));
		return project;
	}

	private ProjectUpdateRequest req(List<Project.WorkflowState> states, List<String> resolved) {
		return new ProjectUpdateRequest(
				null, null, null, null, null, null, states, resolved, null, null, null, null);
	}

	@Test
	void renamingAStateCascadesToIssues() {
		sampleProject();
		Project saved = service.applyUpdate("p1",
				req(List.of(ws("s1", "In Progress", 250), ws("s2", "Done", 155)), null), user("u1"));

		assertThat(saved.workflowStateNames()).containsExactly("In Progress", "Done");
		// Open -> In Progress is cascaded to every issue carrying the old name.
		verify(mongo, atLeastOnce()).updateMulti(any(Query.class), any(UpdateDefinition.class), eq("issues"));
	}

	@Test
	void renamingAResolvedStateKeepsItResolvedUnderTheNewName() {
		sampleProject();
		Project saved = service.applyUpdate("p1",
				req(List.of(ws("s1", "Open", 250), ws("s2", "Closed", 155)), null), user("u1"));

		assertThat(saved.getResolvedStates()).containsExactly("Closed");
	}

	@Test
	void rejectsFewerThanTwoStates() {
		sampleProject();
		assertThatThrownBy(() -> service.applyUpdate("p1",
				req(List.of(ws("s1", "Open", 250)), null), user("u1")))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.project.minStates");
	}

	@Test
	void rejectsEmptyResolvedSet() {
		sampleProject();
		assertThatThrownBy(() -> service.applyUpdate("p1",
				req(null, List.of()), user("u1")))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.project.minResolved");
	}

	@Test
	void rejectsRemovingTheLastLead() {
		sampleProject();
		ProjectUpdateRequest update = new ProjectUpdateRequest(
				null, null, null, null, List.of(), null, null, null, null, null, null, null);
		assertThatThrownBy(() -> service.applyUpdate("p1", update, user("u1")))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.project.leadRequired");
	}

	@Test
	void rejectsDuplicateKey() {
		sampleProject();
		when(projects.findByKeyIgnoreCase("API"))
				.thenReturn(Optional.of(Project.builder().id("other").build()));
		ProjectUpdateRequest update = new ProjectUpdateRequest(
				"API", null, null, null, null, null, null, null, null, null, null, null);
		assertThatThrownBy(() -> service.applyUpdate("p1", update, user("u1")))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.project.keyExists");
	}

	@Test
	void nonLeadMemberCannotEditSettings() {
		sampleProject();
		ProjectUpdateRequest update = new ProjectUpdateRequest(
				null, "Renamed", null, null, null, null, null, null, null, null, null, null);
		assertThatThrownBy(() -> service.applyUpdate("p1", update, user("u2")))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.project.notLead");
		verify(projects, never()).save(any());
	}

	@Test
	void adminCanEditAndDeletingALabelCascades() {
		Project project = sampleProject();
		project.getLabels().add(Project.Label.builder().id("lb1").name("bug").hue(20).build());
		project.getLabels().add(Project.Label.builder().id("lb2").name("ux").hue(330).build());

		ProjectUpdateRequest update = new ProjectUpdateRequest(
				null, null, null, null, null, null, null, null,
				List.of(Project.Label.builder().id("lb1").name("bug").hue(20).build()),
				null, null, null);
		Project saved = service.applyUpdate("p1", update, user("admin", Role.ADMIN));

		assertThat(saved.labelNames()).containsExactly("bug");
		// "ux" was removed -> pulled from every issue's tags.
		verify(mongo, atLeastOnce()).updateMulti(any(Query.class), any(UpdateDefinition.class), eq("issues"));
	}

	@Test
	void deletingAStateWithIssuesRequiresMigrationAndReassigns() {
		Project project = Project.builder()
				.id("p1").key("HIV").name("Hinata")
				.leadIds(new ArrayList<>(List.of("u1")))
				.memberIds(new ArrayList<>(List.of("u1")))
				.workflowStates(new ArrayList<>(List.of(
						ws("s1", "Open", 250), ws("s2", "Doing", 70), ws("s3", "Done", 155))))
				.resolvedStates(new ArrayList<>(List.of("Done")))
				.labels(new ArrayList<>())
				.build();
		when(projects.findById("p1")).thenReturn(Optional.of(project));
		// The "Open" state still has issues assigned.
		when(mongo.count(any(Query.class), eq("issues"))).thenReturn(2L);

		List<Project.WorkflowState> withoutOpen =
				List.of(ws("s2", "Doing", 70), ws("s3", "Done", 155));

		// Deleting "Open" without naming a migration target is rejected.
		ProjectUpdateRequest noMigration = new ProjectUpdateRequest(
				null, null, null, null, null, null, withoutOpen, null, null, null, null, null);
		assertThatThrownBy(() -> service.applyUpdate("p1", noMigration, user("u1")))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.project.stateHasIssues");

		// Migrating Open (s1) -> Doing (s2) succeeds and reassigns the issues.
		ProjectUpdateRequest withMigration = new ProjectUpdateRequest(
				null, null, null, null, null, null, withoutOpen, null, null, null, null,
				java.util.Map.of("s1", "s2"));
		Project saved = service.applyUpdate("p1", withMigration, user("u1"));

		assertThat(saved.workflowStateNames()).containsExactly("Doing", "Done");
		verify(mongo, atLeastOnce())
				.updateMulti(any(Query.class), any(UpdateDefinition.class), eq("issues"));
	}

	@Test
	void defaultWorkflowMatchesSpec() {
		List<Project.WorkflowState> wf = Project.defaultWorkflow();
		assertThat(wf).extracting(Project.WorkflowState::getName)
				.containsExactly("Backlog", "Open", "In Progress", "In Review", "Done");
		assertThat(wf).extracting(Project.WorkflowState::getHue)
				.containsExactly(255, 250, 70, 300, 155);
		assertThat(wf).allSatisfy(s -> assertThat(s.getId()).isNotBlank());
	}
}
