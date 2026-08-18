package com.ahmadre.hinata.deletion;

import com.ahmadre.hinata.board.AgileBoardRepository;
import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectAvatarService;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamActivityRepository;
import com.ahmadre.hinata.team.TeamAvatarService;
import com.ahmadre.hinata.team.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A deleted team or project takes its avatar with it. Nothing else references
 * the stored object, so an entity removed without this step leaves a picture in
 * the bucket that no request could ever reach again and no sweep would reap —
 * the inline-media collector only walks the {@code media/} prefix.
 */
class DeletionAvatarCleanupTest {

	private IssueRepository issues;
	private ProjectAvatarService projectAvatars;
	private TeamAvatarService teamAvatars;
	private DeletionService deletion;

	@BeforeEach
	void setUp() {
		issues = mock(IssueRepository.class);
		projectAvatars = mock(ProjectAvatarService.class);
		teamAvatars = mock(TeamAvatarService.class);
		when(issues.findByProjectId(anyString(), any())).thenReturn(Page.empty());
		deletion = new DeletionService(
				mock(AgileBoardRepository.class), mock(SprintRepository.class), issues,
				mock(ProjectRepository.class), mock(ProjectService.class), mock(TeamRepository.class),
				mock(TeamActivityRepository.class), projectAvatars, teamAvatars,
				mock(StorageService.class), mock(MongoTemplate.class), mock(MessageSource.class));
	}

	@Test
	void theProjectCascadeDropsTheStoredAvatar() {
		Project project = Project.builder().id("p1").key("HIN").name("Hinata").build();

		deletion.deleteProject(project,
				new DeletionService.ProjectDeleteOptions(DeletionService.IssueStrategy.NONE, null, 0),
				Locale.ENGLISH, new SseEmitter());

		verify(projectAvatars).deleteStoredObject("p1");
	}

	@Test
	void theTeamCascadeDropsTheStoredAvatar() {
		Team team = Team.builder().id("t1").key("CORE").name("Core").build();

		deletion.deleteTeam(team, Locale.ENGLISH, new SseEmitter());

		verify(teamAvatars).deleteStoredObject("t1");
	}
}
