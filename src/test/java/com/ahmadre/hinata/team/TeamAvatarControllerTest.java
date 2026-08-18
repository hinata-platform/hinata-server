package com.ahmadre.hinata.team;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.me.SessionService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may reach a team's picture. Unlike a profile photo, a team crest is
 * internal: the read endpoint is authenticated and membership-scoped, so it can
 * neither be fetched anonymously nor used to find out that a team exists.
 *
 * <p>The anonymous case runs against a <em>real</em> {@link CurrentUser} over an
 * empty security context, so "no token" is genuinely asserted rather than
 * stubbed; the signed-in cases swap in a stub for the caller's identity only.
 */
class TeamAvatarControllerTest {

	private TeamService teamService;
	private TeamAvatarService avatars;
	private TeamRepository teams;
	private TeamAvatarController controller;

	@BeforeEach
	void setUp() {
		teams = mock(TeamRepository.class);
		avatars = mock(TeamAvatarService.class);
		// A real TeamService over mocked repositories: it *is* the visibility and
		// manage rule, so stubbing it out would stub out the behaviour under test.
		teamService = new TeamService(teams, mock(TeamActivityRepository.class),
				mock(ProjectService.class), mock(NotificationService.class), avatars);
		controller = new TeamAvatarController(teamService, avatars,
				new CurrentUser(mock(UserRepository.class), mock(SessionService.class)));
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private static User user(String id, Role... roles) {
		return User.builder().id(id).roles(Set.of(roles.length == 0 ? Role.MEMBER : roles[0])).build();
	}

	/** Puts [user] in the security context the way the JWT filter would. */
	private void signedInAs(User user) {
		CurrentUser stub = mock(CurrentUser.class);
		when(stub.require()).thenReturn(user);
		controller = new TeamAvatarController(teamService, avatars, stub);
	}

	private Team teamWith(String adminId, String memberId) {
		Team team = Team.builder().id("t1").key("CORE").name("Core").build();
		team.getMembers().add(TeamMembership.builder().userId(adminId).role(TeamRole.ADMIN)
				.access(ProjectAccess.all()).build());
		team.getMembers().add(TeamMembership.builder().userId(memberId).role(TeamRole.MEMBER)
				.access(ProjectAccess.none()).build());
		when(teams.findById("t1")).thenReturn(Optional.of(team));
		return team;
	}

	@Test
	void anonymousCallersAreRejectedBeforeAnythingIsLookedUp() {
		SecurityContextHolder.clearContext();

		assertThatThrownBy(() -> controller.get("t1"))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.auth.required");

		verify(avatars, never()).load(any(Team.class));
	}

	/**
	 * A caller outside the team gets 403 — and never reaches storage, so the
	 * response cannot differ between "no avatar" and "not your team".
	 */
	@Test
	void aNonMemberIsForbiddenAndNeverReachesStorage() {
		teamWith("admin", "member");
		signedInAs(user("outsider"));

		assertThatThrownBy(() -> controller.get("t1"))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.team.notMember");

		verify(avatars, never()).load(any(Team.class));
	}

	@Test
	void aMemberGetsTheBytesPrivatelyCached() {
		Team team = teamWith("admin", "member");
		signedInAs(user("member"));
		when(avatars.load(team)).thenReturn(Optional.of(new StorageService.StoredObject(
				"jpeg".getBytes(StandardCharsets.UTF_8), "image/jpeg")));

		ResponseEntity<byte[]> response = controller.get("t1");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		// private, not public: an authenticated resource must not sit in a shared
		// proxy where the next caller could pick it up.
		assertThat(response.getHeaders().getCacheControl()).contains("private").contains("604800");
	}

	@Test
	void aMemberOfATeamWithoutAnAvatarGetsNotFound() {
		Team team = teamWith("admin", "member");
		signedInAs(user("member"));
		when(avatars.load(team)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> controller.get("t1"))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.notFound");
	}

	@Test
	void aPlainMemberMayNotUploadAnAvatar() {
		teamWith("admin", "member");
		signedInAs(user("member"));

		assertThatThrownBy(() -> controller.upload("t1",
				new MockMultipartFile("file", "c.png", "image/png", new byte[] { 1 })))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.team.notManager");

		verify(avatars, never()).store(any(Team.class), any());
	}

	@Test
	void aTeamAdminUploadsAndGetsTheUrlBack() {
		Team team = teamWith("admin", "member");
		signedInAs(user("admin"));
		MockMultipartFile file = new MockMultipartFile("file", "c.png", "image/png", new byte[] { 1 });
		when(avatars.store(team, file)).thenReturn("/api/v1/teams/t1/avatar?v=7&bh=LEHV");

		assertThat(controller.upload("t1", file))
				.containsEntry("avatarUrl", "/api/v1/teams/t1/avatar?v=7&bh=LEHV");
	}

	@Test
	void aPlainMemberMayNotRemoveTheAvatar() {
		teamWith("admin", "member");
		signedInAs(user("member"));

		assertThatThrownBy(() -> controller.remove("t1"))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.team.notManager");

		verify(avatars, never()).remove(any(Team.class));
	}

	/**
	 * {@code avatarUrl} is written by the upload endpoint alone. If it ever became
	 * an update-DTO field, a client could point a team's picture at any URL the
	 * app would then render.
	 */
	@Test
	void theUpdateDtoHasNoAvatarUrlFieldToInjectThrough() {
		assertThat(TeamController.UpdateTeamRequest.class.getRecordComponents())
				.extracting(java.lang.reflect.RecordComponent::getName)
				.doesNotContain("avatarUrl");
	}

	@Test
	void aPatchCarryingAnAvatarUrlLeavesTheStoredOneUntouched() {
		Team team = teamWith("admin", "member");
		team.setAvatarUrl("/api/v1/teams/t1/avatar?v=1&bh=LEHV6nWB");
		when(teams.save(any(Team.class))).thenAnswer(invocation -> invocation.getArgument(0));

		// Every field the PATCH endpoint can carry, set at once.
		teamService.update(team, user("admin", Role.ADMIN), "Renamed", "new description", "CORE2",
				200, "star");

		assertThat(team.getAvatarUrl()).isEqualTo("/api/v1/teams/t1/avatar?v=1&bh=LEHV6nWB");
		verify(avatars, never()).store(any(Team.class), any());
	}
}
