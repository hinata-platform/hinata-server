package com.ahmadre.hinata.project;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.me.SessionService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.ahmadre.hinata.issue.IssueWatcherCleanup;

/**
 * Who may reach a project's picture. Unlike a profile photo, a project logo is
 * internal: the read endpoint is authenticated and reach-scoped, so it can
 * neither be fetched anonymously nor used to find out that a project exists.
 *
 * <p>The anonymous case runs against a <em>real</em> {@link CurrentUser} over an
 * empty security context, so "no token" is genuinely asserted rather than
 * stubbed; the signed-in cases swap in a stub for the caller's identity only.
 */
class ProjectAvatarControllerTest {

	private ProjectService projectService;
	private ProjectAvatarService avatars;
	private ProjectRepository projects;
	private ProjectAvatarController controller;

	@BeforeEach
	void setUp() {
		projects = mock(ProjectRepository.class);
		avatars = mock(ProjectAvatarService.class);
		TeamRepository teams = mock(TeamRepository.class);
		when(teams.findByMembersUserId(any())).thenReturn(List.of());
		when(projects.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
		// A real ProjectService over a real ProjectReach: together they *are* the
		// reach and lead rules, so stubbing them out would stub out the behaviour
		// under test.
		projectService = new ProjectService(projects, mock(MongoTemplate.class), teams,
				mock(NotificationService.class), mock(IssueWatcherCleanup.class),
				new ProjectReach(projects, teams, mock(UserRepository.class)));
		controller = new ProjectAvatarController(projectService, avatars,
				new CurrentUser(mock(UserRepository.class), mock(SessionService.class)));
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private static User user(String id, Role... roles) {
		return User.builder().id(id).roles(Set.of(roles.length == 0 ? Role.MEMBER : roles[0])).build();
	}

	/** Runs the remaining assertions as [user] instead of anonymously. */
	private void signedInAs(User user) {
		CurrentUser stub = mock(CurrentUser.class);
		when(stub.require()).thenReturn(user);
		controller = new ProjectAvatarController(projectService, avatars, stub);
	}

	private Project project() {
		Project project = Project.builder()
				.id("p1").key("HIN").name("Hinata")
				.leadIds(new ArrayList<>(List.of("lead")))
				.memberIds(new ArrayList<>(List.of("lead", "member")))
				.build();
		when(projects.findById("p1")).thenReturn(Optional.of(project));
		return project;
	}

	@Test
	void anonymousCallersAreRejectedBeforeAnythingIsLookedUp() {
		SecurityContextHolder.clearContext();

		assertThatThrownBy(() -> controller.get("p1"))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.auth.required");

		verify(avatars, never()).load(any(Project.class));
	}

	/**
	 * A caller who cannot reach the project gets 403 — and never reaches storage,
	 * so the response cannot differ between "no avatar" and "not your project".
	 */
	@Test
	void someoneWithoutReachIsForbiddenAndNeverReachesStorage() {
		project();
		signedInAs(user("outsider"));

		assertThatThrownBy(() -> controller.get("p1"))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.project.notMember");

		verify(avatars, never()).load(any(Project.class));
	}

	@Test
	void aMemberGetsTheBytesPrivatelyCached() {
		Project project = project();
		signedInAs(user("member"));
		when(avatars.load(project)).thenReturn(Optional.of(new StorageService.StoredObject(
				"jpeg".getBytes(StandardCharsets.UTF_8), "image/jpeg")));

		ResponseEntity<byte[]> response = controller.get("p1");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		// private, not public: an authenticated resource must not sit in a shared
		// proxy where the next caller could pick it up.
		assertThat(response.getHeaders().getCacheControl()).contains("private").contains("604800");
	}

	@Test
	void aMemberOfAProjectWithoutAnAvatarGetsNotFound() {
		Project project = project();
		signedInAs(user("member"));
		when(avatars.load(project)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> controller.get("p1"))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.notFound");
	}

	@Test
	void aPlainMemberMayNotUploadAnAvatar() {
		project();
		signedInAs(user("member"));

		assertThatThrownBy(() -> controller.upload("p1",
				new MockMultipartFile("file", "l.png", "image/png", new byte[] { 1 })))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.project.notLead");

		verify(avatars, never()).store(any(Project.class), any());
	}

	@Test
	void aLeadUploadsAndGetsTheUrlBack() {
		Project project = project();
		signedInAs(user("lead"));
		MockMultipartFile file = new MockMultipartFile("file", "l.png", "image/png", new byte[] { 1 });
		when(avatars.store(project, file)).thenReturn("/api/v1/projects/p1/avatar?v=7&bh=LEHV");

		assertThat(controller.upload("p1", file))
				.containsEntry("avatarUrl", "/api/v1/projects/p1/avatar?v=7&bh=LEHV");
	}

	@Test
	void aPlainMemberMayNotRemoveTheAvatar() {
		project();
		signedInAs(user("member"));

		assertThatThrownBy(() -> controller.remove("p1"))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.project.notLead");

		verify(avatars, never()).remove(any(Project.class));
	}

	/**
	 * {@code avatarUrl} is written by the upload endpoint alone. If it ever became
	 * an update-DTO field, a client could point a project's picture at any URL the
	 * app would then render.
	 */
	@Test
	void theUpdateDtoHasNoAvatarUrlFieldToInjectThrough() {
		assertThat(ProjectUpdateRequest.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.doesNotContain("avatarUrl");
	}

	@Test
	void aPatchCarryingAnAvatarUrlLeavesTheStoredOneUntouched() {
		Project project = project();
		project.setAvatarUrl("/api/v1/projects/p1/avatar?v=1&bh=LEHV6nWB");

		// Every field the PATCH endpoint can carry, set at once.
		Project saved = projectService.applyUpdate("p1", new ProjectUpdateRequest(
				"HIN", "Renamed", "new description", "lead", List.of("lead"),
				List.of("lead", "member"), null, null, null, "#FFFFFF", false, null),
				user("lead"));

		assertThat(saved.getAvatarUrl()).isEqualTo("/api/v1/projects/p1/avatar?v=1&bh=LEHV6nWB");
		verify(avatars, never()).store(any(Project.class), any());
	}
}
