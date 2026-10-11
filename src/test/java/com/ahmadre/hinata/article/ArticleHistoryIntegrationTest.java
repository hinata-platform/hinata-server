package com.ahmadre.hinata.article;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Versions, favourites, recently opened pages and sibling order — the parts of the
 * knowledge base that remember something about a page beyond its current text.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ArticleHistoryIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	private static final String DOC_A = doc("Erste Fassung");
	private static final String DOC_B = doc("Zweite Fassung");

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private UserRepository users;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private ArticleController articles;
	@Autowired
	private ArticleHistoryController history;
	@Autowired
	private ArticleReadingController reading;

	@MockitoBean
	private CurrentUser currentUser;

	private User alice;
	private User bob;
	private User carol;
	private Project project;

	@BeforeEach
	void setUp() {
		mongo.getDb().drop();
		alice = user("alice");
		bob = user("bob");
		carol = user("carol");
		project = projects.save(Project.builder().key("HIN").name("Hinata").leadId(alice.getId())
				.leadIds(new ArrayList<>(List.of(alice.getId())))
				.memberIds(new ArrayList<>(List.of(alice.getId(), bob.getId()))).build());
	}

	@Test
	void everyChangeOfTitleOrTextBecomesAVersion_andASaveWithoutChangeAddsNone() {
		as(alice);
		var page = create("Runbook", DOC_A);
		as(bob);
		articles.update(page.id(), edit("Runbook", DOC_B));
		articles.update(page.id(), edit("Runbook", DOC_B));

		var versions = history.versions(page.id(), 0, 20).getContent();
		assertThat(versions).extracting(ArticleHistory.Summary::number).containsExactly(2, 1);
		assertThat(versions).extracting(ArticleHistory.Summary::editorId).containsExactly(bob.getId(), alice.getId());
		assertThat(articles.get(page.id()).lastEditorId()).isEqualTo(bob.getId());
		assertThat(history.contributors(page.id())).containsExactly(bob.getId(), alice.getId());
	}

	@Test
	void aPageFromBeforeVersions_recordsWhatItReplacesOnItsFirstEdit() {
		Instant before = Instant.parse("2026-01-02T03:04:05Z");
		Article legacy = mongo.save(Article.builder().title("Alt").contentDoc(DOC_A).content("Erste Fassung")
				.projectId(project.getId()).authorId(alice.getId()).build());
		mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("_id").is(legacy.getId())),
				new org.springframework.data.mongodb.core.query.Update().set("updatedAt", before), Article.class);

		as(bob);
		articles.update(legacy.getId(), edit("Alt", DOC_B));

		var first = history.version(legacy.getId(), 1);
		assertThat(first.contentDoc()).isEqualTo(DOC_A);
		assertThat(first.editorId()).isEqualTo(alice.getId());
		assertThat(first.createdAt()).isEqualTo(before);
		assertThat(history.version(legacy.getId(), 2).contentDoc()).isEqualTo(DOC_B);
	}

	@Test
	void restoringBringsBackAnOldState_asTheNewestVersion() {
		as(alice);
		var page = create("Runbook", DOC_A);
		articles.update(page.id(), edit("Runbook neu", DOC_B));

		var restored = history.restore(page.id(), 1);

		assertThat(restored.title()).isEqualTo("Runbook");
		assertThat(restored.contentDoc()).isEqualTo(DOC_A);
		assertThat(history.versions(page.id(), 0, 20).getContent())
				.extracting(ArticleHistory.Summary::number).containsExactly(3, 2, 1);
	}

	@Test
	void somebodyWhoCannotReadThePage_seesNoneOfItsHistory() {
		as(alice);
		var page = create("Runbook", DOC_A);
		as(carol);
		assertNotFound(() -> history.versions(page.id(), 0, 20));
		assertNotFound(() -> history.version(page.id(), 1));
		assertNotFound(() -> history.restore(page.id(), 1));
		assertNotFound(() -> reading.star(page.id()));
	}

	@Test
	void favouritesAndRecentPages_nameOnlyPagesTheCallerStillReads() {
		as(alice);
		var shared = create("Geteilt", DOC_A);
		as(bob);
		reading.star(shared.id());
		reading.viewed(shared.id());
		assertThat(reading.marks().starred()).extracting(ArticleReading.Mark::id).containsExactly(shared.id());
		assertThat(reading.marks().recent()).extracting(ArticleReading.Mark::id).containsExactly(shared.id());

		// Bob leaves the project: the page is out of his reach and out of his lists.
		project.setMemberIds(new ArrayList<>(List.of(alice.getId())));
		projects.save(project);
		assertThat(reading.marks().starred()).isEmpty();
		assertThat(reading.marks().recent()).isEmpty();
	}

	@Test
	void unstarringKeepsThePageInTheRecentList() {
		as(alice);
		var page = create("Runbook", DOC_A);
		reading.viewed(page.id());
		reading.star(page.id());
		reading.unstar(page.id());
		assertThat(reading.marks().starred()).isEmpty();
		assertThat(reading.marks().recent()).extracting(ArticleReading.Mark::id).containsExactly(page.id());
	}

	@Test
	void theRecentListStaysShort() {
		as(alice);
		for (int i = 0; i < ArticleReading.RECENT + 5; i++) {
			reading.viewed(create("Seite " + i, DOC_A).id());
		}
		assertThat(reading.marks().recent()).hasSize(ArticleReading.RECENT);
		assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(), ArticleReaderState.class))
				.isEqualTo(ArticleReading.RECENT);
	}

	@Test
	void siblingsTakeTheGivenOrder_butPagesOfDifferentLevelsAreRefused() {
		as(alice);
		var a = create("A", DOC_A);
		var b = create("B", DOC_A);
		var child = articles.create(new ArticleController.ArticleRequest("Kind", null, DOC_A, null, null, a.id(),
				"Wissen", null, null, null));

		articles.order(new ArticleController.OrderRequest(List.of(b.id(), a.id())));
		assertThat(articles.get(b.id()).sortOrder()).isZero();
		assertThat(articles.get(a.id()).sortOrder()).isEqualTo(1);

		assertThatThrownBy(() -> articles.order(new ArticleController.OrderRequest(List.of(a.id(), child.id()))))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void deletingAPage_takesItsHistoryAndMarksAlong() {
		as(alice);
		var page = create("Runbook", DOC_A);
		reading.star(page.id());
		articles.delete(page.id());
		assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(), ArticleVersion.class)).isZero();
		assertThat(mongo.count(new org.springframework.data.mongodb.core.query.Query(), ArticleReaderState.class)).isZero();
	}

	private ArticleController.ArticleResponse create(String title, String doc) {
		return articles.create(new ArticleController.ArticleRequest(title, null, doc, project.getId(), null, null,
				"Wissen", null, null, null));
	}

	private static ArticleController.ArticleRequest edit(String title, String doc) {
		return new ArticleController.ArticleRequest(title, null, doc, null, null, null, null, null, null, null);
	}

	private static String doc(String text) {
		return "{\"root\":{\"type\":\"root\",\"version\":1,\"direction\":null,\"format\":\"\",\"indent\":0,"
				+ "\"children\":[{\"type\":\"paragraph\",\"version\":1,\"direction\":null,\"format\":\"\","
				+ "\"indent\":0,\"textFormat\":0,\"textStyle\":\"\",\"children\":[{\"type\":\"text\",\"version\":1,"
				+ "\"text\":\"" + text + "\",\"format\":0,\"style\":\"\",\"mode\":\"normal\",\"detail\":0}]}]}}";
	}

	private User user(String name) {
		return users.save(User.builder().email(name + "@example.org").username(name).displayName(name)
				.roles(Set.of(Role.MEMBER)).active(true).timezone("UTC").locale("en").build());
	}

	private void as(User user) {
		when(currentUser.require()).thenReturn(user);
	}

	private static void assertNotFound(Runnable call) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
	}
}
