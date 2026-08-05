package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.search.SearchHit;
import com.ahmadre.hinata.search.SearchResponse;
import com.ahmadre.hinata.search.SearchService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Frozen content does not appear in search — for anybody, including an admin —
 * and everything else still does.
 *
 * <p>Against a real MongoDB, for the reason {@code SearchAccessMongoTest} gives
 * about the access filter and which applies with more force here: the freeze
 * filter is composed <em>alongside</em> an access filter that answers {@code null}
 * for an admin, and {@code null} is dropped by the composer. Whether the
 * {@code $and} that results narrows or evaporates is a property of the query
 * document, not of the code that builds it — a mocked template would assert the
 * shape of something nobody executed.
 *
 * <p>The {@code $text} half matters as much as the regex half. Both are asserted,
 * because the index covers descriptions and article bodies, so a freeze that only
 * reached the regex query would still let anyone confirm which words appear inside
 * frozen material.
 *
 * <p>Half of these tests assert that results still appear, mirroring the discipline
 * of the test this one is modelled on: a filter that returns nothing is also
 * "secure", and quietly emptying search is the regression this change could
 * plausibly introduce in every install that has nothing frozen at all.
 */
@Testcontainers(disabledWithoutDocker = true)
class FrozenSearchMongoTest {

	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private static final String PROJECT = "p-mine";

	private MongoTemplate mongo;
	private ProjectService projectService;
	private TeamService teamService;
	private ProjectRepository projects;
	private UserRepository users;
	private IssueRepository issueRepo;

	private final User member = user("u-member", false);
	private final User admin = user("u-admin", true);

	@BeforeEach
	void setUp() {
		mongo = new MongoTemplate(MongoClients.create(MONGO.getReplicaSetUrl()), "freeze-search");
		mongo.getDb().drop();

		// Built by hand for the reason SearchAccessMongoTest documents: a bare
		// MongoTemplate does not create them from @TextIndexed, every TextQuery then
		// throws, and the hybrid search's own catch swallows it — leaving the two
		// full-text assertions below passing because nothing ran.
		mongo.indexOps(Issue.class).createIndex(new TextIndexDefinitionBuilder()
				.onField("title", 10F).onField("description", 2F).build());
		mongo.indexOps(Article.class).createIndex(new TextIndexDefinitionBuilder()
				.onField("title", 10F).onField("content", 2F).build());

		Project project = project(PROJECT, "Aurora");
		mongo.save(project);
		mongo.save(issue("i-frozen", "Aurora frozen report", "unspeakable"));
		mongo.save(issue("i-open", "Aurora login fails", "login outage"));
		mongo.save(article("a-frozen", "Aurora frozen note", "unspeakable"));
		mongo.save(article("a-open", "Aurora runbook", "notes"));

		projectService = mock(ProjectService.class);
		teamService = mock(TeamService.class);
		when(projectService.visibleTo(member)).thenReturn(List.of(project));
		when(projectService.archivedVisibleTo(member)).thenReturn(List.of());
		when(teamService.visibleTo(member)).thenReturn(List.of());

		projects = mock(ProjectRepository.class);
		when(projects.findByArchivedFalse()).thenReturn(List.of(project));
		issueRepo = mock(IssueRepository.class);
		users = mock(UserRepository.class);
		when(users.findAllById(any())).thenReturn(List.of());
	}

	// --- what must not appear ---------------------------------------------------

	@Test
	void aFrozenIssueIsAbsentFromSearchForAMember() {
		assertThat(titles(search(frozenRegistry()), member, "Aurora", "ISSUES"))
				.contains("Aurora login fails")
				.doesNotContain("Aurora frozen report");
	}

	/** The account every other filter in this service lets straight through. */
	@Test
	void aFrozenIssueIsAbsentFromSearchForAnAdminToo() {
		assertThat(titles(search(frozenRegistry()), admin, "Aurora", "ISSUES"))
				.contains("Aurora login fails")
				.doesNotContain("Aurora frozen report");
	}

	@Test
	void aFrozenArticleIsAbsentForAMemberAndAnAdmin() {
		SearchService search = search(frozenRegistry());

		assertThat(titles(search, member, "Aurora", "DOCS"))
				.contains("Aurora runbook")
				.doesNotContain("Aurora frozen note");
		assertThat(titles(search, admin, "Aurora", "DOCS"))
				.contains("Aurora runbook")
				.doesNotContain("Aurora frozen note");
	}

	/**
	 * The body oracle. The {@code $text} index covers descriptions and article
	 * bodies, so a hit confirms a word inside a document even though the body is
	 * never returned — which for frozen material is the one thing nobody may learn.
	 */
	@Test
	void theFullTextIndexDoesNotConfirmWordsInsideFrozenDocuments() {
		SearchService search = search(frozenRegistry());

		assertThat(titles(search, admin, "unspeakable", "ISSUES")).isEmpty();
		assertThat(titles(search, admin, "unspeakable", "DOCS")).isEmpty();
	}

	/** The empty-query suggestion path is a second entry point into the same data. */
	@Test
	void theEmptyQuerySuggestionsExcludeFrozenContentToo() {
		SearchService search = search(frozenRegistry());

		assertThat(titles(search, admin, "", "ISSUES")).doesNotContain("Aurora frozen report");
		assertThat(titles(search, admin, "", "DOCS")).doesNotContain("Aurora frozen note");
	}

	/**
	 * The per-category chip has to agree with the group under it. {@code counts}
	 * builds its own filters, parallel to the query path — structurally the shape
	 * that leaked before the access fix — so it is asserted separately.
	 */
	@Test
	void theCategoryCountsAgreeWithTheGroupsForAnAdmin() {
		SearchResponse response = search(frozenRegistry()).search("Aurora", "all", false, admin);

		assertThat(response.counts().get("ISSUES")).isEqualTo(1L);
		assertThat(response.counts().get("DOCS")).isEqualTo(1L);
	}

	// --- what must still work ----------------------------------------------------

	@Test
	void withNothingFrozenSearchReturnsExactlyWhatItAlwaysDid() {
		SearchService search = search(FreezeFixtures.nothingFrozen());

		assertThat(titles(search, member, "Aurora", "ISSUES"))
				.containsExactlyInAnyOrder("Aurora frozen report", "Aurora login fails");
		assertThat(titles(search, member, "Aurora", "DOCS"))
				.containsExactlyInAnyOrder("Aurora frozen note", "Aurora runbook");
	}

	@Test
	void withNothingFrozenTheCountsAreUnchanged() {
		SearchResponse response =
				search(FreezeFixtures.nothingFrozen()).search("Aurora", "all", false, admin);

		assertThat(response.counts().get("ISSUES")).isEqualTo(2L);
		assertThat(response.counts().get("DOCS")).isEqualTo(2L);
	}

	@Test
	void aMemberStillFindsTheirOwnWorkByFullText() {
		assertThat(titles(search(frozenRegistry()), member, "outage", "ISSUES"))
				.containsExactly("Aurora login fails");
	}

	// --- helpers -----------------------------------------------------------------

	private FrozenContentService frozenRegistry() {
		return FreezeFixtures.frozen(
				FreezeFixtures.row(FrozenTargetType.ISSUE, "i-frozen"),
				FreezeFixtures.row(FrozenTargetType.ARTICLE, "a-frozen"));
	}

	private SearchService search(FrozenContentService frozen) {
		return new SearchService(mongo, users, projects, issueRepo, projectService, teamService,
				frozen);
	}

	private static List<String> titles(SearchService search, User who, String query, String category) {
		return search.search(query, category, false, who).groups().stream()
				.filter(group -> group.category().equals(category))
				.flatMap(group -> group.items().stream())
				.map(SearchHit::getTitle)
				.toList();
	}

	private static User user(String id, boolean isAdmin) {
		return User.builder().id(id).displayName(id).active(true)
				.roles(Set.of(isAdmin ? Role.ADMIN : Role.MEMBER)).build();
	}

	private static Project project(String id, String name) {
		return Project.builder().id(id).name(name).key("AUR").archived(false)
				.memberIds(List.of("u-member")).updatedAt(Instant.now()).build();
	}

	private static Issue issue(String id, String title, String description) {
		return Issue.builder().id(id).projectId(PROJECT).title(title).readableId(id.toUpperCase())
				.description(description).archived(false).updatedAt(Instant.now()).build();
	}

	private static Article article(String id, String title, String content) {
		return Article.builder().id(id).projectId(PROJECT).title(title).content(content)
				.updatedAt(Instant.now()).build();
	}
}
