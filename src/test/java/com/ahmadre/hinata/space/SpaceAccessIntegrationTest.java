package com.ahmadre.hinata.space;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * A space is seen by its author and by whoever reads a page filed in it, and changed
 * only by its author. Spaces used to be listed to every account, so anybody saw the
 * names of other people's topics and could delete them. No role widens this: admins
 * read no more pages than anybody else and so see and manage no more spaces either.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class SpaceAccessIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private UserRepository users;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private SpaceRepository spaces;
	@Autowired
	private SpaceController controller;

	@MockitoBean
	private CurrentUser currentUser;

	private User alice;
	private User bob;
	private User admin;
	private User carol;
	private Space privateSpace;
	private Space projectSpace;
	private Space legacySpace;
	private Project project;

	@BeforeEach
	void setUp() {
		mongo.getDb().drop();
		alice = user("alice", Role.MEMBER);
		bob = user("bob", Role.MEMBER);
		admin = user("admin", Role.ADMIN, Role.ORG_ADMIN);
		carol = user("carol", Role.MEMBER);
		project = projects.save(Project.builder().key("HIN").name("Hinata").leadId(alice.getId())
				.leadIds(new ArrayList<>(List.of(alice.getId())))
				.memberIds(new ArrayList<>(List.of(alice.getId(), carol.getId()))).build());

		privateSpace = spaces.save(Space.builder().name("Kontaktdaten").authorId(alice.getId()).build());
		projectSpace = spaces.save(Space.builder().name("Projektwissen").authorId(alice.getId()).build());
		legacySpace = spaces.save(Space.builder().name("Altbestand").build());
		spaces.save(Space.builder().name("Leer").authorId(alice.getId()).build());

		// A private page of Alice's, a page of the project Carol works in, and one in
		// the space from before spaces had an author, filed with a lower-case name.
		mongo.save(Article.builder().title("Telefonliste").space("Kontaktdaten").authorId(alice.getId()).build());
		mongo.save(Article.builder().title("Runbook").space("Projektwissen").projectId(project.getId())
				.authorId(alice.getId()).build());
		mongo.save(Article.builder().title("Alt").space("altbestand").projectId(project.getId())
				.authorId(alice.getId()).build());
	}

	@Test
	void anAuthorSeesAndManagesTheirOwnSpaces_evenEmptyOnes() {
		as(alice);
		assertThat(controller.list()).extracting(SpaceController.SpaceResponse::name)
				.containsExactlyInAnyOrder("Kontaktdaten", "Projektwissen", "Altbestand", "Leer");
		assertThat(controller.list()).filteredOn(s -> s.name().equals("Kontaktdaten"))
				.allMatch(SpaceController.SpaceResponse::canManage);
	}

	@Test
	void somebodyWhoReadsNoPageInASpace_neitherSeesNorDeletesIt() {
		as(bob);
		assertThat(controller.list()).isEmpty();
		assertStatus(() -> controller.delete(privateSpace.getId()), HttpStatus.NOT_FOUND);
		assertStatus(() -> controller.update(privateSpace.getId(),
				new SpaceController.SpaceRequest("Meins", null, null, null, null)), HttpStatus.NOT_FOUND);
		assertThat(spaces.findById(privateSpace.getId())).isPresent();
	}

	@Test
	void anAdminSeesNoMoreSpacesThanThePagesTheyRead() {
		as(admin);
		assertThat(controller.list()).extracting(SpaceController.SpaceResponse::name)
				.doesNotContain("Kontaktdaten", "Leer");
		assertStatus(() -> controller.delete(privateSpace.getId()), HttpStatus.NOT_FOUND);
		assertThat(spaces.findById(privateSpace.getId())).isPresent();
	}

	@Test
	void aReaderOfOnePageSeesTheSpace_butMayNotChangeIt() {
		as(carol);
		assertThat(controller.list()).extracting(SpaceController.SpaceResponse::name)
				.containsExactlyInAnyOrder("Projektwissen", "Altbestand");
		assertThat(controller.list()).filteredOn(s -> s.name().equals("Projektwissen"))
				.singleElement().matches(s -> !s.canManage());
		assertStatus(() -> controller.delete(projectSpace.getId()), HttpStatus.FORBIDDEN);
		assertStatus(() -> controller.update(projectSpace.getId(),
				new SpaceController.SpaceRequest("Umbenannt", null, null, null, null)), HttpStatus.FORBIDDEN);
		assertThat(spaces.findById(projectSpace.getId()).orElseThrow().getName()).isEqualTo("Projektwissen");
	}

	@Test
	void aSpaceWithoutAuthor_isManagedByWhoeverReadsAllOfIt_neverByRole() {
		// An admin outside the project sees nothing of it and manages nothing.
		as(admin);
		assertThat(controller.list()).isEmpty();
		assertStatus(() -> controller.delete(legacySpace.getId()), HttpStatus.NOT_FOUND);

		// Carol reads the one page in it, so renaming it touches nothing she was not given.
		as(carol);
		assertThat(controller.list()).filteredOn(s -> s.name().equals("Altbestand"))
				.singleElement().matches(SpaceController.SpaceResponse::canManage);

		// A private page of somebody else in the same space takes that away again.
		mongo.save(Article.builder().title("Privat").space("Altbestand").authorId(bob.getId()).build());
		assertThat(controller.list()).filteredOn(s -> s.name().equals("Altbestand"))
				.singleElement().matches(s -> !s.canManage());
		assertStatus(() -> controller.update(legacySpace.getId(),
				new SpaceController.SpaceRequest("Neu", null, null, null, null)), HttpStatus.FORBIDDEN);
	}

	private void as(User user) {
		when(currentUser.require()).thenReturn(user);
		when(currentUser.requireId()).thenReturn(user.getId());
	}

	private User user(String name, Role... roles) {
		return users.save(User.builder().email(name + "@example.org").username(name).displayName(name)
				.roles(Set.of(roles)).active(true).timezone("UTC").locale("en").build());
	}

	private static void assertStatus(Runnable call, HttpStatus status) {
		assertThatThrownBy(call::run).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(status));
	}
}
