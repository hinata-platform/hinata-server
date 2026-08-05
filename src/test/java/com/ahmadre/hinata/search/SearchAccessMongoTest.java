package com.ahmadre.hinata.search;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.team.Team;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.TextIndexDefinition.TextIndexDefinitionBuilder;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Global search used to return the whole organisation to anyone with an account.
 * The filter it did apply was on {@code archived}, not on membership, so a person
 * invited an hour ago and added to nothing could read back every issue title,
 * project name, sprint goal and knowledge-base title in the workspace — and, via
 * the Mongo {@code $text} index over descriptions and article bodies, confirm
 * whether a given word appeared inside documents they could not open.
 *
 * <p>Run against a real MongoDB, because the fix is a query and only a query can
 * be wrong in the ways that matter here: an {@code $in} that silently matches
 * nothing, an {@code $or} that widens instead of narrowing, a chained
 * {@code Criteria} that overwrites the clause before it. A mocked template would
 * assert the shape of a query nobody ever executed.
 *
 * <p>Membership resolution itself is stubbed. {@code ProjectService.visibleTo} is
 * pre-existing, separately tested logic; what is under test is whether search
 * <em>asks</em> it and honours the answer, which it did not.
 *
 * <p>Half of these tests assert that results still appear. A filter that returns
 * nothing is also "secure", and it is the failure this change could plausibly
 * introduce: search silently returning less is the kind of regression people work
 * around for months before reporting.
 */
@Testcontainers(disabledWithoutDocker = true)
class SearchAccessMongoTest {

	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static final String MINE = "p-mine";
	private static final String THEIRS = "p-theirs";
	private static final String MY_TEAM = "t-mine";
	private static final String THEIR_TEAM = "t-theirs";

	private MongoTemplate mongo;
	private SearchService search;
	private ProjectService projectService;
	private TeamService teamService;

	private final User member = user("u-member", false);
	private final User admin = user("u-admin", true);

	@BeforeEach
	void setUp() {
		mongo = new MongoTemplate(MongoClients.create(MONGO.getReplicaSetUrl()), "search-acl");
		mongo.getDb().drop();

		// The $text indexes have to be created by hand here. Spring Data builds them
		// from @TextIndexed at runtime, but not in a bare MongoTemplate — and without
		// them every TextQuery throws and is swallowed by the hybrid search's own
		// catch, which would leave the two tests about full-text leakage passing
		// because nothing ran. A vacuous security test is worse than none.
		mongo.indexOps(Issue.class).createIndex(new TextIndexDefinitionBuilder()
				.onField("title", 10F).onField("description", 2F).build());
		mongo.indexOps(Article.class).createIndex(new TextIndexDefinitionBuilder()
				.onField("title", 10F).onField("content", 2F).build());

		Project mine = project(MINE, "Aurora", "AUR", false);
		Project theirs = project(THEIRS, "Aurora Secret", "SEC", false);
		mongo.save(mine);
		mongo.save(theirs);

		// One matching document per category on each side of the fence, so every
		// category is asserted rather than the one that happens to be checked.
		mongo.save(issue("i-mine", MINE, "Aurora login fails"));
		mongo.save(issue("i-theirs", THEIRS, "Aurora payroll leak"));
		mongo.save(article("a-mine", MINE, null, "Aurora runbook"));
		mongo.save(article("a-theirs", THEIRS, null, "Aurora salary bands"));
		mongo.save(article("a-team-mine", null, MY_TEAM, "Aurora team notes"));
		mongo.save(article("a-team-theirs", null, THEIR_TEAM, "Aurora board minutes"));
		mongo.save(article("a-global", null, null, "Aurora handbook"));
		mongo.save(board("b-mine", MINE, "Aurora delivery"));
		mongo.save(board("b-theirs", THEIRS, "Aurora exec"));
		mongo.save(sprint("s-mine", "b-mine", "Aurora sprint 4"));
		mongo.save(sprint("s-theirs", "b-theirs", "Aurora layoffs planning"));

		projectService = mock(ProjectService.class);
		teamService = mock(TeamService.class);
		when(projectService.visibleTo(member)).thenReturn(List.of(mine));
		when(projectService.archivedVisibleTo(member)).thenReturn(List.of());
		when(teamService.visibleTo(member)).thenReturn(List.of(team(MY_TEAM)));

		ProjectRepository projects = mock(ProjectRepository.class);
		when(projects.findByArchivedFalse()).thenReturn(List.of(mine, theirs));

		IssueRepository issues = mock(IssueRepository.class);
		UserRepository users = mock(UserRepository.class);
		when(users.findAllById(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

		search = new SearchService(mongo, users, projects, issues, projectService, teamService);
	}

	// --- what must no longer leak -------------------------------------------

	@Test
	void aMemberOfOneProjectNeverSeesAnotherProjectsIssues() {
		assertThat(titles(member, "Aurora", "ISSUES"))
				.contains("Aurora login fails")
				.doesNotContain("Aurora payroll leak");
	}

	@Test
	void aMemberNeverSeesAnotherProjectItself() {
		assertThat(titles(member, "Aurora", "PROJECTS"))
				.contains("Aurora")
				.doesNotContain("Aurora Secret");
	}

	/**
	 * Knowledge articles had no filter of any kind — not even the archived one the
	 * other categories applied — so every title in the organisation was listed.
	 */
	@Test
	void aMemberSeesOnlyTheArticlesTheirProjectsAndTeamsReach() {
		assertThat(titles(member, "Aurora", "DOCS"))
				.contains("Aurora runbook", "Aurora team notes", "Aurora handbook")
				.doesNotContain("Aurora salary bands", "Aurora board minutes");
	}

	/**
	 * Boards were filtered to non-archived projects; sprints were forwarded
	 * untouched, which put every sprint goal in the workspace into the palette.
	 */
	@Test
	void aMemberSeesOnlyTheirOwnBoardsAndSprints() {
		assertThat(titles(member, "Aurora", "BOARDS"))
				.contains("Aurora delivery", "Aurora sprint 4")
				.doesNotContain("Aurora exec", "Aurora layoffs planning");
	}

	@Test
	void aUserWhoReachesNothingGetsNothingButGlobalArticles() {
		when(projectService.visibleTo(member)).thenReturn(List.of());
		when(teamService.visibleTo(member)).thenReturn(List.of());

		assertThat(titles(member, "Aurora", "ISSUES")).isEmpty();
		assertThat(titles(member, "Aurora", "PROJECTS")).isEmpty();
		assertThat(titles(member, "Aurora", "BOARDS")).isEmpty();
		// The org-wide space stays readable — that is what "global" means.
		assertThat(titles(member, "Aurora", "DOCS")).containsExactly("Aurora handbook");
	}

	/**
	 * The body-content oracle. The {@code $text} index covers descriptions and
	 * article bodies, so a hit used to confirm a word inside a document the caller
	 * could not open, even though the body itself was never returned.
	 */
	@Test
	void theFullTextIndexNoLongerConfirmsWordsInsideForeignDocuments() {
		assertThat(titles(member, "redundancies", "ISSUES")).isEmpty();
		assertThat(titles(member, "redundancies", "DOCS")).isEmpty();
	}

	// --- what must still work ------------------------------------------------

	@Test
	void anAdminStillSeesEverything() {
		assertThat(titles(admin, "Aurora", "ISSUES"))
				.contains("Aurora login fails", "Aurora payroll leak");
		assertThat(titles(admin, "Aurora", "DOCS"))
				.contains("Aurora salary bands", "Aurora board minutes");
		assertThat(titles(admin, "Aurora", "BOARDS"))
				.contains("Aurora exec", "Aurora layoffs planning");
	}

	@Test
	void aMemberStillFindsTheirOwnWorkByFullText() {
		assertThat(titles(member, "outage", "ISSUES")).containsExactly("Aurora login fails");
	}

	/**
	 * The empty-query suggestion path is a second entry point into the same data and
	 * was scoped separately; a fix that covered only the query path would leave it
	 * leaking.
	 */
	@Test
	void theEmptyQuerySuggestionsAreScopedToo() {
		assertThat(titles(member, "", "ISSUES")).containsExactly("Aurora login fails");
		assertThat(titles(member, "", "DOCS"))
				.doesNotContain("Aurora salary bands", "Aurora board minutes");
	}

	// --- the counts behind the category chips ---------------------------------

	@Test
	void theCategoryCountsReflectWhatTheViewerCanSee() {
		SearchResponse response = search.search("Aurora", "all", false, member);

		assertThat(response.counts().get("ISSUES")).isEqualTo(1L);
		assertThat(response.counts().get("PROJECTS")).isEqualTo(1L);
		// mine + my team + global
		assertThat(response.counts().get("DOCS")).isEqualTo(3L);
	}

	@Test
	void anAdminsCountsCoverTheWholeWorkspace() {
		SearchResponse response = search.search("Aurora", "all", false, admin);

		assertThat(response.counts().get("ISSUES")).isEqualTo(2L);
		assertThat(response.counts().get("DOCS")).isEqualTo(5L);
	}

	// --- helpers ---------------------------------------------------------------

	/** Hit titles for [who] in one category, or empty when the group is absent. */
	private List<String> titles(User who, String query, String category) {
		return search.search(query, category, false, who).groups().stream()
				.filter(group -> group.category().equals(category))
				.flatMap(group -> group.items().stream())
				.map(SearchHit::getTitle)
				.toList();
	}

	private static User user(String id, boolean admin) {
		return User.builder().id(id).displayName(id).active(true)
				.roles(admin ? Set.of(Role.ADMIN) : Set.of(Role.MEMBER)).build();
	}

	private static Project project(String id, String name, String key, boolean archived) {
		return Project.builder().id(id).name(name).key(key).archived(archived)
				.memberIds(List.of()).updatedAt(Instant.now()).build();
	}

	private static Issue issue(String id, String projectId, String title) {
		return Issue.builder().id(id).projectId(projectId).title(title)
				.readableId(id.toUpperCase())
				// Only the foreign issue mentions the sensitive word, so a hit on it
				// from the other side is unambiguously a leak through the text index.
				.description("i-theirs".equals(id) ? "planned redundancies" : "login outage")
				.archived(false).updatedAt(Instant.now()).build();
	}

	private static Article article(String id, String projectId, String teamId, String title) {
		return Article.builder().id(id).projectId(projectId).teamId(teamId).title(title)
				.content("a-theirs".equals(id) ? "planned redundancies" : "notes")
				.updatedAt(Instant.now()).build();
	}

	private static AgileBoard board(String id, String projectId, String name) {
		return AgileBoard.builder().id(id).name(name).projectIds(List.of(projectId))
				.createdAt(Instant.now()).build();
	}

	private static Sprint sprint(String id, String boardId, String name) {
		return Sprint.builder().id(id).boardId(boardId).name(name).createdAt(Instant.now()).build();
	}

	private static Team team(String id) {
		return Team.builder().id(id).name(id).build();
	}
}
