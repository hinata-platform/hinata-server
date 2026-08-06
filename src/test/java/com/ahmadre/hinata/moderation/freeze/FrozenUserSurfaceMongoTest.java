package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.me.AvatarService;
import com.ahmadre.hinata.moderation.ModerationRecorder;
import com.ahmadre.hinata.moderation.ModerationService;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.search.SearchHit;
import com.ahmadre.hinata.search.SearchService;
import com.ahmadre.hinata.storage.StorageBackend;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.team.TeamService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserDirectoryService;
import com.ahmadre.hinata.user.UserRepository;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.TextIndexDefinition.TextIndexDefinitionBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FrozenTargetType#USER} actually restricts something.
 *
 * <p>It was checked from no read path in the product — the constant existed, the
 * admin endpoint accepted it, a row was written, and a frozen account's display
 * name, title and avatar carried on being served from four places. That is worse
 * than not having the constant: an operator who froze an account had every signal
 * that it had worked.
 *
 * <p>The constant was kept rather than deleted, and the avatar is why.
 * {@code GET /api/v1/users/*&#47;avatar} is one of only two unauthenticated content
 * routes in this product and it does not check whether the account is active — so
 * deactivating the user, otherwise the right answer to a person problem, leaves
 * their uploaded image served to the open internet. Nothing else here closes that.
 *
 * <p>Against a real MongoDB for the reason {@code SearchAccessMongoTest} gives: the
 * type-ahead is paged, so "frozen accounts are excluded" and "the total is honest"
 * are properties of a query document, and a mocked template would assert the shape
 * of something nobody ran.
 */
@Testcontainers(disabledWithoutDocker = true)
class FrozenUserSurfaceMongoTest {

	@Container
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	private MongoTemplate mongo;

	@BeforeEach
	void setUp() {
		mongo = new MongoTemplate(MongoClients.create(MONGO.getReplicaSetUrl()), "freeze-user");
		mongo.getDb().drop();
		mongo.indexOps(Issue.class).createIndex(new TextIndexDefinitionBuilder()
				.onField("title", 10F).onField("description", 2F).build());
		mongo.indexOps(Article.class).createIndex(new TextIndexDefinitionBuilder()
				.onField("title", 10F).onField("content", 2F).build());
		mongo.indexOps(User.class).createIndex(new TextIndexDefinitionBuilder()
				.onField("displayName", 10F).onField("title", 2F).build());

		mongo.save(user("u-frozen", "Frozen Person", false));
		mongo.save(user("u-open-1", "Frozen-adjacent Colleague", false));
		mongo.save(user("u-open-2", "Frozen-adjacent Other", false));
	}

	// --- the directory endpoints ------------------------------------------------

	@Test
	void aFrozenAccountIsAbsentFromTheDirectorySearch() {
		Page<User> found = directory(frozenUser()).search("Frozen", PageRequest.of(0, 25));

		assertThat(found.getContent()).extracting(User::getId)
				.containsExactlyInAnyOrder("u-open-1", "u-open-2");
	}

	/** The total, so the pager does not walk toward a row it can never show. */
	@Test
	void theDirectoryTotalDoesNotCountTheFrozenAccount() {
		assertThat(directory(frozenUser()).search("Frozen", PageRequest.of(0, 25))
				.getTotalElements()).isEqualTo(2);
	}

	/**
	 * The batch resolver is what every issue card and every avatar uses to turn a
	 * stored user id into a name — the most-called of the three, and the one that
	 * takes ids the caller already holds.
	 */
	@Test
	void aFrozenAccountDoesNotResolveThroughTheBatchIdLookup() {
		assertThat(directory(frozenUser()).byIds(List.of("u-frozen", "u-open-1")))
				.extracting(User::getId).containsExactly("u-open-1");
	}

	@Test
	void inactiveAccountsAreStillDroppedToo() {
		mongo.save(user("u-inactive", "Frozen-adjacent Retired", true));

		assertThat(directory(FreezeFixtures.nothingFrozen()).byIds(List.of("u-inactive")))
				.isEmpty();
	}

	@Test
	void withNothingFrozenTheDirectoryIsUnchanged() {
		assertThat(directory(FreezeFixtures.nothingFrozen())
				.search("Frozen", PageRequest.of(0, 25)).getTotalElements()).isEqualTo(3);
	}

	// --- global search ------------------------------------------------------------

	@Test
	void aFrozenAccountIsAbsentFromThePeopleCategoryForAnAdmin() {
		User admin = user("u-admin", "Admin", false);
		admin.setRoles(Set.of(Role.ADMIN));

		assertThat(search(frozenUser()).search("Frozen", "PEOPLE", false, admin).groups().stream()
				.flatMap(group -> group.items().stream())
				.map(SearchHit::getTitle))
				.doesNotContain("Frozen Person")
				.contains("Frozen-adjacent Colleague");
	}

	// --- the unauthenticated avatar route ------------------------------------------

	/**
	 * The route that has no viewer at all. Asserted at the service rather than
	 * through the byte chokepoint, because this guard is the one that holds even if
	 * the freeze failed to resolve the object key.
	 */
	@Test
	void aFrozenAccountsAvatarIsNotServedOnTheUnauthenticatedRoute() throws Exception {
		StorageBackend backend = mock(StorageBackend.class);
		when(backend.get(any())).thenReturn(Optional.of(
				new StorageService.StoredObject(new byte[] { 1 }, "image/jpeg")));
		AvatarService avatars = avatarService(frozenUser(), backend);

		assertThat(avatars.load("u-frozen")).isEmpty();
		verify(backend, never()).get(AvatarService.objectKeyFor("u-frozen"));
		assertThat(avatars.load("u-open-1")).isPresent();
	}

	// --- helpers ---------------------------------------------------------------------

	private FrozenContentService frozenUser() {
		return FreezeFixtures.frozen(FreezeFixtures.row(FrozenTargetType.USER, "u-frozen"));
	}

	private UserDirectoryService directory(FrozenContentService frozen) {
		return new UserDirectoryService(mongo, frozen);
	}

	private SearchService search(FrozenContentService frozen) {
		ProjectRepository projects = mock(ProjectRepository.class);
		when(projects.findByArchivedFalse()).thenReturn(List.of());
		ProjectService projectService = mock(ProjectService.class);
		when(projectService.visibleTo(any())).thenReturn(List.of());
		when(projectService.archivedVisibleTo(any())).thenReturn(List.of());
		TeamService teams = mock(TeamService.class);
		when(teams.visibleTo(any())).thenReturn(List.of());
		UserRepository users = mock(UserRepository.class);
		when(users.findAllById(any())).thenReturn(List.of());
		return new SearchService(mongo, users, projects, mock(IssueRepository.class),
				projectService, teams, frozen);
	}

	private AvatarService avatarService(FrozenContentService frozen, StorageBackend backend)
			throws Exception {
		HinataProperties properties = new HinataProperties();
		properties.getStorage().setProvider("s3");
		properties.getStorage().setAccessKey("key");
		properties.getStorage().setSecretKey("secret");
		properties.getStorage().setEndpoint("http://localhost:9000");
		StorageService storage = new StorageService(properties, null, null, frozen);
		ReflectionTestUtils.setField(storage, "backend", backend);
		return new AvatarService(storage, mock(UserRepository.class), mock(ModerationService.class),
				mock(ModerationRecorder.class), frozen);
	}

	private static User user(String id, String displayName, boolean retired) {
		return User.builder().id(id).username(id).displayName(displayName).title("Engineer")
				.active(!retired).roles(Set.of(Role.MEMBER)).updatedAt(Instant.now()).build();
	}
}
