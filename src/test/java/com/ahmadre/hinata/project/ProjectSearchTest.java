package com.ahmadre.hinata.project;

import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.team.ProjectAccess;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamMembership;
import com.ahmadre.hinata.team.TeamRepository;
import com.ahmadre.hinata.team.TeamRole;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The project pickers page through {@code searchVisible} instead of listing
 * every project, so the needle, the paging <em>and</em> the access rule all have
 * to travel into one query. The interesting part is that widening the search
 * must never widen what the caller may see: access stays an {@code AND} branch.
 */
class ProjectSearchTest {

	private MongoTemplate mongo;
	private TeamRepository teams;
	private ProjectService service;

	@BeforeEach
	void setUp() {
		mongo = mock(MongoTemplate.class);
		teams = mock(TeamRepository.class);
		when(teams.findByMembersUserId(any())).thenReturn(List.of());
		when(mongo.find(any(Query.class), eq(Project.class))).thenReturn(List.of());
		ProjectRepository projects = mock(ProjectRepository.class);
		service = new ProjectService(projects, mongo, teams,
				mock(NotificationService.class), new ProjectReach(projects, teams),
				mock(ModerationService.class));
	}

	private User member() {
		return User.builder().id("u1").roles(Set.of(Role.MEMBER)).build();
	}

	private User admin() {
		return User.builder().id("root").roles(Set.of(Role.ADMIN)).build();
	}

	private String capturedFind() {
		ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
		verify(mongo).find(captor.capture(), eq(Project.class));
		return captor.getValue().getQueryObject().toJson();
	}

	private Query capturedQuery() {
		ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
		verify(mongo).find(captor.capture(), eq(Project.class));
		return captor.getValue();
	}

	@Test
	void pagesInTheDatabaseRatherThanInMemory() {
		service.searchVisible(member(), null, false,
				PageRequest.of(2, 25, Sort.by(Sort.Direction.ASC, "name")));

		Query query = capturedQuery();
		assertThat(query.getLimit()).isEqualTo(25);
		assertThat(query.getSkip()).isEqualTo(50);
		assertThat(query.getSortObject().toJson()).contains("name");
	}

	@Test
	void restrictsAMemberToTheProjectsTheyBelongTo() {
		service.searchVisible(member(), null, false, PageRequest.of(0, 25));

		assertThat(capturedFind()).contains("memberIds").contains("u1").contains("archived");
	}

	@Test
	void keepsTheAccessBranchWhenANeedleNarrowsTheSearch() {
		service.searchVisible(member(), "ersti", false, PageRequest.of(0, 25));

		String query = capturedFind();
		assertThat(query).contains("name").contains("key").contains("ersti");
		// The needle is an AND-ed branch: searching must never reach past the
		// projects this user may see.
		assertThat(query).contains("memberIds");
	}

	@Test
	void treatsTheNeedleAsTextRatherThanAsAPattern() {
		service.searchVisible(member(), "a.*b", false, PageRequest.of(0, 25));

		// Quoted, so a user typing regex metacharacters searches for those
		// characters instead of matching every project.
		assertThat(capturedFind()).contains("\\\\Qa.*b\\\\E");
	}

	@Test
	void givesPlatformAdminsNoAccessBranchAtAll() {
		service.searchVisible(admin(), null, false, PageRequest.of(0, 25));

		assertThat(capturedFind()).doesNotContain("memberIds");
	}

	@Test
	void reachesProjectsGrantedThroughATeam() {
		Team team = Team.builder().id("t1").key("CORE").name("Core")
				.projectIds(new ArrayList<>(List.of("p9"))).build();
		team.getMembers().add(TeamMembership.builder().userId("u1").role(TeamRole.MEMBER)
				.access(ProjectAccess.all()).build());
		when(teams.findByMembersUserId("u1")).thenReturn(List.of(team));

		service.searchVisible(member(), null, false, PageRequest.of(0, 25));

		// Same reach as visibleTo(): direct membership OR a team grant.
		String query = capturedFind();
		assertThat(query).contains("memberIds").contains("p9").contains("$or");
	}

	@Test
	void separatesArchivedProjectsFromActiveOnes() {
		service.searchVisible(member(), null, true, PageRequest.of(0, 25));

		assertThat(capturedFind()).contains("\"archived\": true");
	}

	@Test
	void resolvesHeldIdsWithoutLeavingTheCallersReach() {
		service.resolveVisible(member(), List.of("p1", "p2"));

		String query = capturedFind();
		assertThat(query).contains("p1").contains("p2").contains("memberIds");
	}

	@Test
	void capsASingleResolveLookup() {
		List<String> ids = IntStream.rangeClosed(1, 150).mapToObj(i -> "p" + i)
				.collect(Collectors.toList());

		service.resolveVisible(member(), ids);

		assertThat(capturedFind()).contains("p100").doesNotContain("p150");
	}

	@Test
	void skipsTheQueryEntirelyWhenNoIdsAreHeld() {
		assertThat(service.resolveVisible(member(), List.of())).isEmpty();

		verify(mongo, never()).find(any(Query.class), eq(Project.class));
	}
}
