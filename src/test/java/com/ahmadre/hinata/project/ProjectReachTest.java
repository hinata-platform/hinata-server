package com.ahmadre.hinata.project;

import com.ahmadre.hinata.team.ProjectAccess;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.team.TeamRole;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectReach} exists so the "may this user see that project" rule lives
 * in exactly one place — and it now answers that question twice: once per user
 * ({@link ProjectReach#canSee(String, User)}) and once in bulk
 * ({@link ProjectReach#whoCanSee(String, Collection)}), because the notification
 * fan-out cannot afford a query per recipient.
 *
 * <p>Two implementations of one rule is the thing the class was written to
 * prevent, so this test is the seam that keeps them honest: whoever adds a
 * project visibility, a guest role or an archived-project rule to one of them
 * gets a red build instead of a quiet divergence in the path that decides who
 * receives mail about an issue they no longer have access to.
 */
class ProjectReachTest {

	private static final String PROJECT = "p1";
	private static final String OTHER_PROJECT = "p2";

	private ProjectRepository projects;
	private TeamRepository teams;
	private UserRepository users;
	private ProjectReach reach;

	/** Every kind of relationship a person can have to a project, in one table. */
	private Map<String, User> cast;

	@BeforeEach
	void setUp() {
		projects = mock(ProjectRepository.class);
		teams = mock(TeamRepository.class);
		users = mock(UserRepository.class);
		reach = new ProjectReach(projects, teams, users);

		Project project = Project.builder().id(PROJECT).key("HIN").name("hinata")
				.memberIds(new ArrayList<>(List.of("direct")))
				.build();
		when(projects.findById(PROJECT)).thenReturn(Optional.of(project));
		when(projects.findById(OTHER_PROJECT)).thenReturn(Optional.empty());
		when(teams.findByMembersUserId(any())).thenReturn(List.of());

		cast = Map.of(
				"direct", member("direct"),
				"granted", member("granted"),
				"grantedElsewhere", member("grantedElsewhere"),
				"admin", admin("admin"),
				"deactivatedAdmin", deactivated(admin("deactivatedAdmin")),
				"stranger", member("stranger"));
		cast.values().forEach(user -> when(users.findById(user.getId()))
				.thenReturn(Optional.of(user)));
		when(users.findAllById(any())).thenAnswer(invocation -> {
			Iterable<String> ids = invocation.getArgument(0);
			List<User> found = new ArrayList<>();
			ids.forEach(id -> {
				User user = cast.get(id);
				if (user != null) found.add(user);
			});
			return found;
		});

		// "granted" reaches p1 through a team that owns it; "grantedElsewhere" is on
		// a team whose grant covers a different project entirely.
		//
		// Both team lookups have to be stubbed, and that is the point rather than an
		// inconvenience: the two paths ask the team collection different questions —
		// canSee walks out from the user (findByMembersUserId), whoCanSee walks in
		// from the project (findByProjectIdsContains). Two queries answering one rule
		// is exactly the seam where they can drift.
		Team owningTeam = teamOwning(PROJECT, "granted");
		Team otherTeam = teamOwning(OTHER_PROJECT, "grantedElsewhere");
		when(teams.findByMembersUserId("granted")).thenReturn(List.of(owningTeam));
		when(teams.findByMembersUserId("grantedElsewhere")).thenReturn(List.of(otherTeam));
		when(teams.findByProjectIdsContains(any())).thenReturn(List.of());
		when(teams.findByProjectIdsContains(PROJECT)).thenReturn(List.of(owningTeam));
		when(teams.findByProjectIdsContains(OTHER_PROJECT)).thenReturn(List.of(otherTeam));
	}

	/**
	 * The agreement itself. Asserting set equality rather than checking a handful
	 * of names means a rule added to one side and not the other cannot slip
	 * through on a case nobody thought to enumerate.
	 */
	@Test
	void theBulkAndTheSingleAnswerAgree() {
		Set<String> everyone = cast.keySet();

		Set<String> singly = everyone.stream()
				.filter(id -> reach.canSee(PROJECT, cast.get(id)))
				.collect(Collectors.toSet());

		assertThat(reach.whoCanSee(PROJECT, everyone))
				.as("whoCanSee must answer exactly what canSee answers, person for person")
				.isEqualTo(singly);
	}

	/** The table above is only meaningful if it actually splits the cast. */
	@Test
	void theCastCoversBothOutcomes() {
		Set<String> allowed = reach.whoCanSee(PROJECT, cast.keySet());

		assertThat(allowed).contains("direct", "granted", "admin");
		assertThat(allowed).doesNotContain("stranger", "grantedElsewhere");
	}

	/**
	 * A project that no longer exists is visible to nobody — not even an admin.
	 * Both paths have to agree on that too, since a queued digest can outlive the
	 * project it points into.
	 */
	@Test
	void aProjectThatIsGoneIsVisibleToNobodyOnEitherPath() {
		assertThat(reach.whoCanSee(OTHER_PROJECT, cast.keySet())).isEmpty();
		cast.values().forEach(user ->
				assertThat(reach.canSee(OTHER_PROJECT, user))
						.as("canSee for %s", user.getId())
						.isFalse());
	}

	@Test
	void anEmptyOrUnknownAudienceIsAnsweredWithoutTouchingTheDatabase() {
		assertThat(reach.whoCanSee(PROJECT, List.of())).isEmpty();
		assertThat(reach.whoCanSee(PROJECT, List.of("nobody-by-that-id"))).isEmpty();
		assertThat(reach.whoCanSee(null, cast.keySet())).isEmpty();
	}

	// --- fixtures -------------------------------------------------------------

	private User member(String id) {
		return User.builder().id(id).email(id + "@example.test")
				.roles(Set.of(Role.MEMBER)).active(true).build();
	}

	private User admin(String id) {
		return User.builder().id(id).email(id + "@example.test")
				.roles(Set.of(Role.ADMIN)).active(true).build();
	}

	private User deactivated(User user) {
		user.setActive(false);
		return user;
	}

	private Team teamOwning(String projectId, String userId) {
		return Team.builder().id("t-" + projectId).name("Team " + projectId)
				.projectIds(new ArrayList<>(List.of(projectId)))
				.members(new ArrayList<>(List.of(TeamMembership.builder()
						.userId(userId)
						.role(TeamRole.MEMBER)
						.access(ProjectAccess.all())
						.build())))
				.build();
	}
}
