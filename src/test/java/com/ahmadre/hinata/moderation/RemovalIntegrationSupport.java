package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.CopiesScrub;
import com.ahmadre.hinata.content.ContentSurface;
import com.ahmadre.hinata.content.Placeholders;
import com.ahmadre.hinata.content.Removal;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.TargetRef;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What every moderation test needs (HIN-169, HIN-170): a real MongoDB, an object store in memory,
 * people, a project, the surfaces as moderation reaches them, and somebody signed in. All of them
 * share one context.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
abstract class RemovalIntegrationSupport {

	/**
	 * Started once and never stopped by a test class: the removal tests share one context, and a
	 * container stopped after the first class would leave the second talking to nothing.
	 */
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	static {
		MONGO.start();
	}

	static final String CASE = "case-1";

	@Autowired
	MongoTemplate mongo;
	@Autowired
	List<ContentSurface> surfaces;
	@Autowired
	ModerationFollowUps followUps;
	@Autowired
	IssueService issues;
	@Autowired
	ProjectRepository projects;
	@Autowired
	UserRepository users;

	@MockitoBean
	StorageService storage;
	@MockitoBean
	CurrentUser currentUser;

	/** Watched, not replaced: counting how often a request scrubs is the point of some tests. */
	@MockitoSpyBean
	CopiesScrub copiesScrub;
	/** Watched: what a notice mails, and where a reply goes (HIN-171). */
	@MockitoSpyBean
	com.ahmadre.hinata.notification.MailService mail;
	@MockitoSpyBean
	com.ahmadre.hinata.notification.PushService push;

	@Autowired
	@org.springframework.beans.factory.annotation.Qualifier(ModerationFollowUps.EXECUTOR)
	java.util.concurrent.Executor followUpThread;

	final MemoryObjects store = new MemoryObjects();

	User owner;
	User author;
	User reader;
	Project project;
	Placeholders placeholders;

	@BeforeEach
	void seedPeopleAndProject() {
		for (String collection : List.of("issues", "issue_comments", "issue_activities", "issue_mail_digests",
				"notifications", "projects", "users", "articles", "article_versions", "moderation_quarantine",
				"moderation_quarantine_items", "audit_log", "moderation_cases", "moderation_reports",
				"moderation_stats", "user_blocks")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		store.installOn(storage);
		SIGNED_IN.remove();
		org.mockito.Mockito.when(currentUser.require()).thenAnswer(call -> SIGNED_IN.get());
		org.mockito.Mockito.when(currentUser.requireId()).thenAnswer(call -> SIGNED_IN.get().getId());
		org.mockito.Mockito.clearInvocations(copiesScrub, mail, push);
		owner = user("owner");
		author = user("jonas");
		reader = user("lena");
		project = projects.save(Project.builder().key("ACME").name("Messe")
				.leadId(owner.getId())
				.leadIds(new ArrayList<>(List.of(owner.getId())))
				.memberIds(new ArrayList<>(List.of(owner.getId(), author.getId(), reader.getId())))
				.workflowStates(new ArrayList<>(List.of(state("Open"), state("Done"))))
				.resolvedStates(new ArrayList<>(List.of("Done")))
				.build());
		placeholders = followUps.placeholders();
	}

	User user(String name, Role... roles) {
		Set<Role> held = new java.util.HashSet<>(Set.of(Role.MEMBER));
		held.addAll(List.of(roles));
		return users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(held).active(true).build());
	}

	/** The instance's moderation contact is [email]: where a reply that is an appeal goes. */
	void contactIs(com.ahmadre.hinata.setup.SettingsService settings, String email) {
		mongo.getCollection("server_settings").deleteMany(new Document());
		com.ahmadre.hinata.setup.ServerSettings stored = new com.ahmadre.hinata.setup.ServerSettings();
		com.ahmadre.hinata.setup.ServerSettings.Moderation contact = new com.ahmadre.hinata.setup.ServerSettings.Moderation();
		contact.setContactEmail(email);
		stored.setModeration(contact);
		settings.save(stored);
	}

	/** Waits for what the follow-up thread was handed: one thread, so a step queued now runs last. */
	void followUpsDone() {
		java.util.concurrent.CompletableFuture.runAsync(() -> { }, followUpThread).join();
	}

	/** Who is signed in, per thread: two moderators can click at once. */
	private static final ThreadLocal<User> SIGNED_IN = new ThreadLocal<>();

	/** Signs [person] in, on this thread, for what follows. */
	void actingAs(User person) {
		SIGNED_IN.set(person);
	}

	private static Project.WorkflowState state(String name) {
		return Project.WorkflowState.builder().id(name.toLowerCase()).name(name).hue(200).build();
	}

	Issue issue(String title, String descriptionDoc) {
		return issues.create(Issue.builder().projectId(project.getId()).title(title)
				.description("").descriptionDoc(descriptionDoc).type(Issue.Type.TASK).build(), owner);
	}

	ContentSurface surface(ContentKind kind) {
		return surfaces.stream().filter(surface -> surface.kind() == kind).findFirst().orElseThrow();
	}

	Removal remove(ContentKind kind, String id, String parentId, RemovalScope scope, ReportCategory category) {
		return surface(kind).remove(new TargetRef(kind, id, parentId), scope, CASE, category, placeholders);
	}

	/** A decision as HIN-171 writes it: whole, with [outcome] on [ground] by [decidedBy]. */
	static Decision decision(Decision.Outcome outcome, Decision.Ground ground, String decidedBy) {
		return new Decision(outcome, null, ground, ReportCategory.HARASSMENT, "Beleidigung im Kommentar", null, null,
				null, false, null, null, false, Decision.Basis.NOTICE, false, null, decidedBy,
				java.time.Instant.now(), null);
	}

	/** A Lexical document of one paragraph. */
	static String doc(String text) {
		return """
				{"root":{"type":"root","children":[{"type":"paragraph","children":[\
				{"type":"text","text":"%s","format":0,"detail":0,"mode":"normal","style":"","version":1}]}]}}"""
				.formatted(text);
	}

	/** A Lexical document showing one inline image. */
	static String docWithImage(String text, String mediaId) {
		return """
				{"root":{"type":"root","children":[{"type":"paragraph","children":[\
				{"type":"text","text":"%s","format":0,"detail":0,"mode":"normal","style":"","version":1}]},\
				{"type":"image","src":"/api/v1/media/%s","version":1}]}}""".formatted(text, mediaId);
	}
}
