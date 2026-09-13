package com.ahmadre.hinata.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.project.Project;
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

	/** A global article that links to nothing — what the ordinary listing returns. */
	private static final Article GLOBAL = Article.builder()
			.id("a-global").title("Kontaktdaten").content("Keine Verweise")
			.build();

	/** A visible article that really links to an issue of a project key with digits. */
	private static final Article EP26 = Article.builder()
			.id("a-ep26").title("Erstiparty").projectId("p-mine")
			.content("Siehe EP26-2").referencedIssueKeys(List.of("EP26-2"))
			.build();

	@BeforeEach
	void setUp() {
		articles = mock(ArticleRepository.class);
		projects = mock(ProjectService.class);
		teams = mock(TeamService.class);
		currentUser = mock(CurrentUser.class);
		controller = new ArticleController(articles, new RichTextService(), currentUser,
				projects, teams);

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
	void aMalformedKeyAnswersWithNoBacklinksRatherThanTheOrdinaryListing() {
		member();
		when(articles.findByProjectIdIsNullOrderBySortOrderAsc()).thenReturn(List.of(GLOBAL));

		// A backlink question has exactly one honest answer for a value that
		// cannot be a key: nothing references it. Falling through to the listing
		// shows every global article under "documented in" on the issue.
		assertThat(controller.list(null, false, "not-a-key")).isEmpty();
		verify(articles, never()).findByProjectIdIsNullOrderBySortOrderAsc();
		verify(articles, never()).findByReferencedIssueKeysContains(anyString());
	}

	/**
	 * Project keys may contain digits ({@code EP26}). A key pattern of letters
	 * only rejected {@code EP26-2}, fell through to the ordinary listing, and every
	 * issue of such a project claimed to be documented in every global article.
	 */
	@Test
	void anIssueKeyWithDigitsInItsProjectKeyQueriesTheIndex() {
		member();
		when(articles.findByProjectIdIsNullOrderBySortOrderAsc()).thenReturn(List.of(GLOBAL));
		when(articles.findByReferencedIssueKeysContains("EP26-2")).thenReturn(List.of(EP26));

		assertThat(controller.list(null, false, "ep26-2"))
				.extracting(ArticleController.ArticleResponse::id)
				.containsExactly("a-ep26");
		verify(articles, never()).findByProjectIdIsNullOrderBySortOrderAsc();
	}

	@Test
	void anEmptyReferencesIssueParameterIsStillABacklinkQuery() {
		member();
		when(articles.findByProjectIdIsNullOrderBySortOrderAsc()).thenReturn(List.of(GLOBAL));

		assertThat(controller.list(null, false, "")).isEmpty();
		verify(articles, never()).findByProjectIdIsNullOrderBySortOrderAsc();
		verify(articles, never()).findByReferencedIssueKeysContains(anyString());
	}
}
