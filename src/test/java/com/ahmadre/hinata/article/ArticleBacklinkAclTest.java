package com.ahmadre.hinata.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * {@code ?referencesIssue} answers from a derived index rather than by scanning
 * article bodies, which makes it a second, cheaper way to reach an article — and
 * therefore a second place the visibility rule has to hold. An article in a
 * project the caller cannot see must be absent from a backlink result exactly as
 * it is absent from the ordinary list.
 */
class ArticleBacklinkAclTest {

	private ArticleRepository articles;
	private ProjectService projects;
	private TeamService teams;
	private CurrentUser currentUser;
	private ArticleController controller;

	private static final Article VISIBLE = Article.builder()
			.id("a-visible").title("Runbook").projectId("p-mine")
			.content("Siehe HIN-1").referencedIssueKeys(List.of("HIN-1"))
			.build();

	private static final Article HIDDEN = Article.builder()
			.id("a-hidden").title("Geheimes Runbook").projectId("p-theirs")
			.content("Siehe auch HIN-1").referencedIssueKeys(List.of("HIN-1"))
			.build();

	private static final Article TEAM_HIDDEN = Article.builder()
			.id("a-team").title("Team-Runbook").teamId("t-theirs")
			.content("HIN-1 auch hier").referencedIssueKeys(List.of("HIN-1"))
			.build();

	@BeforeEach
	void setUp() {
		articles = mock(ArticleRepository.class);
		projects = mock(ProjectService.class);
		teams = mock(TeamService.class);
		currentUser = mock(CurrentUser.class);
		controller = new ArticleController(articles, new RichTextService(), currentUser,
				projects, teams, mock(ModerationService.class),
				com.ahmadre.hinata.moderation.freeze.FreezeFixtures.nothingFrozen());

		when(articles.findByReferencedIssueKeysContains("HIN-1"))
				.thenReturn(List.of(VISIBLE, HIDDEN, TEAM_HIDDEN));
	}

	private User member() {
		User user = User.builder().id("u1").email("a@b.c").roles(Set.of(Role.MEMBER)).build();
		when(currentUser.require()).thenReturn(user);
		when(projects.visibleTo(user)).thenReturn(List.of(Project.builder().id("p-mine").build()));
		when(teams.visibleTo(user)).thenReturn(List.of());
		return user;
	}

	@Test
	void anArticleInAnInvisibleProjectIsExcludedFromABacklinkResult() {
		member();

		List<ArticleController.ArticleResponse> found = controller.list(null, false, "HIN-1");

		assertThat(found).extracting(ArticleController.ArticleResponse::id)
				.containsExactly("a-visible");
	}

	@Test
	void anArticleInAnInvisibleTeamIsExcludedToo() {
		member();

		assertThat(controller.list(null, false, "HIN-1"))
				.extracting(ArticleController.ArticleResponse::id)
				.doesNotContain("a-team");
	}

	@Test
	void anAdminSeesEveryBacklink() {
		User admin = User.builder().id("u0").email("root@b.c").roles(Set.of(Role.ADMIN)).build();
		when(currentUser.require()).thenReturn(admin);

		assertThat(controller.list(null, false, "HIN-1"))
				.extracting(ArticleController.ArticleResponse::id)
				.containsExactly("a-visible", "a-hidden", "a-team");
	}

	@Test
	void aMalformedKeyIsNotTreatedAsABacklinkQuery() {
		User user = member();
		when(articles.findByProjectIdIsNullOrderBySortOrderAsc()).thenReturn(List.of());

		// Falls through to the ordinary listing rather than querying the index.
		assertThat(controller.list(null, false, "not-a-key")).isEmpty();
		assertThat(user).isNotNull();
	}
}
