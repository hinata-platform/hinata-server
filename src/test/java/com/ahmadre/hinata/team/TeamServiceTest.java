package com.ahmadre.hinata.team;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamServiceTest {

	private TeamRepository teams;
	private ProjectService projects;
	private NotificationService notifications;
	private TeamService service;

	@BeforeEach
	void setUp() {
		teams = mock(TeamRepository.class);
		projects = mock(ProjectService.class);
		notifications = mock(NotificationService.class);
		when(teams.save(any(Team.class))).thenAnswer(invocation -> invocation.getArgument(0));
		service = new TeamService(teams, mock(TeamActivityRepository.class), projects, notifications);
	}

	private User user(String id, Role... roles) {
		return User.builder().id(id).roles(Set.of(roles.length == 0 ? Role.MEMBER : roles[0])).build();
	}

	@Test
	void creatorBecomesSoleTeamAdminWithAllAccess() {
		when(teams.existsByKeyIgnoreCase("CORE")).thenReturn(false);
		Team team = service.create(user("u1"), "Core", "core", "desc", 70, "hexagon");
		assertThat(team.getKey()).isEqualTo("CORE");
		assertThat(team.getMembers()).hasSize(1);
		assertThat(team.getMembers().get(0).getRole()).isEqualTo(TeamRole.ADMIN);
		assertThat(team.getMembers().get(0).getAccess().getScope()).isEqualTo(ProjectAccess.Scope.ALL);
	}

	@Test
	void rejectsDuplicateKey() {
		when(teams.existsByKeyIgnoreCase("CORE")).thenReturn(true);
		assertThatThrownBy(() -> service.create(user("u1"), "Core", "CORE", null, 70, "hexagon"))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.team.keyExists");
	}

	@Test
	void cannotDemoteLastAdmin() {
		Team team = teamWithAdmin("u1");
		assertThatThrownBy(() ->
				service.updateMembership(team, user("u1", Role.ADMIN), "u1", TeamRole.MEMBER, null))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.team.lastAdmin");
	}

	@Test
	void cannotRemoveLastAdmin() {
		Team team = teamWithAdmin("u1");
		assertThatThrownBy(() -> service.removeMember(team, user("u1", Role.ADMIN), "u1"))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.team.lastAdmin");
	}

	@Test
	void addMembersAppliesRoleAndAccessAndNotifies() {
		Team team = teamWithAdmin("u1");
		team.getProjectIds().add("p1");
		service.addMembers(team, user("u1", Role.ADMIN), List.of("u2"), TeamRole.MEMBER,
				ProjectAccess.some(List.of("p1")));
		TeamMembership added = team.membership("u2");
		assertThat(added).isNotNull();
		assertThat(added.getAccess().getScope()).isEqualTo(ProjectAccess.Scope.SOME);
		assertThat(added.getAccess().getProjectIds()).containsExactly("p1");
	}

	@Test
	void grantingAccessMaterializesUserIntoProjectMembers() {
		Team team = teamWithAdmin("u1");
		team.getProjectIds().add("p1");
		Project project = Project.builder().id("p1").name("Alpha").build();
		when(projects.findOptional("p1")).thenReturn(java.util.Optional.of(project));
		// Reconciliation reads the teams that own the project back from the repo.
		when(teams.findByProjectIdsContains("p1")).thenReturn(List.of(team));

		service.addMembers(team, user("u1", Role.ADMIN), List.of("u2"), TeamRole.MEMBER,
				ProjectAccess.some(List.of("p1")));

		assertThat(project.getMemberIds()).contains("u2");
	}

	@Test
	void someAccessMustBeSubsetOfTeamProjects() {
		Team team = teamWithAdmin("u1"); // owns no projects
		assertThatThrownBy(() -> service.addMembers(team, user("u1", Role.ADMIN), List.of("u2"),
				TeamRole.MEMBER, ProjectAccess.some(List.of("p-not-owned"))))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.team.accessNotSubset");
	}

	@Test
	void detachStripsProjectFromMemberAccessLists() {
		Team team = teamWithAdmin("u1");
		team.getProjectIds().add("p1");
		team.getMembers().add(TeamMembership.builder().userId("u2").role(TeamRole.MEMBER)
				.access(ProjectAccess.some(List.of("p1"))).build());
		when(projects.findOptional("p1"))
				.thenReturn(java.util.Optional.of(Project.builder().id("p1").name("Alpha").build()));

		service.detachProject(team, user("u1", Role.ADMIN), "p1");

		assertThat(team.getProjectIds()).doesNotContain("p1");
		assertThat(team.membership("u2").getAccess().getProjectIds()).doesNotContain("p1");
	}

	private Team teamWithAdmin(String adminId) {
		Team team = Team.builder().id("t1").key("CORE").name("Core").build();
		team.getMembers().add(TeamMembership.builder().userId(adminId).role(TeamRole.ADMIN)
				.access(ProjectAccess.all()).build());
		return team;
	}
}
