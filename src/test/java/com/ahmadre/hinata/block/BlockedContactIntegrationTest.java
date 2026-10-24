package com.ahmadre.hinata.block;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueParticipantService;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.weeklysummary.WeeklySummaryService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What blocking stops and what it leaves alone (HIN-26, HIN-168), in both directions: Anna
 * blocks Ben, Cleo blocks nobody. Ben's comments, mentions, replies and edits stop reaching
 * Anna and still reach Cleo; assigning Anna still tells her; sharing an issue with Anna is
 * refused exactly as for an account that cannot take part.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class BlockedContactIntegrationTest {

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
	private IssueRepository issueRepository;
	@Autowired
	private IssueService issues;
	@Autowired
	private IssueParticipantService participants;
	@MockitoSpyBean
	private BlockService blocks;
	@Autowired
	private WeeklySummaryService weekly;

	private User anna;
	private User ben;
	private User cleo;
	private Project project;
	private Issue issue;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "projects", "issues", "issue_comments", "issue_activities",
				"notifications", "user_blocks", "issue_mail_digests", "audit_log")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		anna = user("anna");
		ben = user("ben");
		cleo = user("cleo");
		project = projects.save(Project.builder().key("APO").name("Apollo").leadId(ben.getId())
				.leadIds(new ArrayList<>(List.of(ben.getId())))
				.memberIds(new ArrayList<>(List.of(anna.getId(), ben.getId(), cleo.getId())))
				.workflowStates(new ArrayList<>(List.of(
						Project.WorkflowState.builder().id("s1").name("Open").build(),
						Project.WorkflowState.builder().id("s2").name("Done").build())))
				.resolvedStates(new ArrayList<>(List.of("Done")))
				.build());
		issue = issueRepository.save(Issue.builder().projectId(project.getId()).title("Launch")
				.readableId("APO-1").numberInProject(1).state("Open").type(Issue.Type.TASK)
				// Anna and Cleo only watch, so their change mails are bundled.
				.reporterId(ben.getId())
				.watcherIds(new ArrayList<>(List.of(anna.getId(), cleo.getId())))
				.build());
		blocks.block(anna, ben.getId());
	}

	@Test
	@DisplayName("a comment of the blocked person mentioning and answering both reaches only the other")
	void commentsMentionsAndRepliesStop() {
		IssueComment annas = issues.addComment(issue.getId(), text("first"), anna);
		IssueComment cleos = issues.addComment(issue.getId(), text("second"), cleo);
		clearNotifications();

		issues.addComment(issue.getId(), mentioning("look", anna, cleo), annas.getId(), ben);
		issues.addComment(issue.getId(), text("and you"), cleos.getId(), ben);

		assertThat(noticesOf(anna)).isEmpty();
		assertThat(noticesOf(cleo)).extracting(Notification::getType)
				.contains(Notification.Type.MENTION, Notification.Type.COMMENT_REPLY);
	}

	@Test
	@DisplayName("an edit of the blocked person reaches the other watcher only, in the bell and in the bundled mail")
	void updatesStop() {
		issues.update(issue.getId(), i -> i.setTitle("Launch the rocket"), ben);

		assertThat(noticesOf(anna)).isEmpty();
		assertThat(noticesOf(cleo)).extracting(Notification::getType).contains(Notification.Type.ISSUE_UPDATED);
		assertThat(mongo.getCollection("issue_mail_digests").countDocuments(new Document("userId", anna.getId())))
				.isZero();
		assertThat(mongo.getCollection("issue_mail_digests").countDocuments(new Document("userId", cleo.getId())))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("a comment asks who blocks its author once, however many it reaches")
	void oneQuestionPerComment() {
		List<String> crowd = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			crowd.add(user("watcher" + i).getId());
		}
		project.getMemberIds().addAll(crowd);
		projects.save(project);
		Issue watched = issueRepository.findById(issue.getId()).orElseThrow();
		watched.getWatcherIds().addAll(crowd);
		issueRepository.save(watched);
		IssueComment cleos = issues.addComment(issue.getId(), text("second"), cleo);
		clearNotifications();
		clearInvocations(blocks);

		issues.addComment(issue.getId(), mentioning("look", anna, cleo), cleos.getId(), ben);

		verify(blocks, times(1)).blockersOf(ben.getId());
		verify(blocks, never()).blocks(any(), any());
		assertThat(mongo.getCollection("notifications").countDocuments(new Document("userId", crowd.getFirst())))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("the weekly summary leaves the blocked person off the board, not their work")
	void theWeeklyBoardLeavesTheBlockedPersonOff() {
		mongo.save(Issue.builder().projectId(project.getId()).title("Done by Ben").readableId("APO-2")
				.numberInProject(2).state("Done").type(Issue.Type.TASK).reporterId(ben.getId())
				.assigneeIds(new ArrayList<>(List.of(ben.getId()))).assigneeId(ben.getId())
				.resolvedAt(java.time.Instant.now()).build());

		WeeklySummaryService.WeeklySummary forAnna = weekly.forUser(anna);
		WeeklySummaryService.WeeklySummary forCleo = weekly.forUser(cleo);

		assertThat(forAnna.team().contributors()).isEmpty();
		assertThat(forAnna.team().completed()).isEqualTo(1);
		assertThat(forCleo.team().contributors()).extracting(WeeklySummaryService.Contributor::userId)
				.containsExactly(ben.getId());
	}

	@Test
	@DisplayName("being assigned by the blocked person still tells the one who blocked them")
	void requiredNoticesStillArrive() {
		issues.update(issue.getId(), i -> i.setAssigneeId(anna.getId()), ben);

		assertThat(noticesOf(anna)).extracting(Notification::getType)
				.containsExactly(Notification.Type.ISSUE_ASSIGNED);
	}

	@Test
	@DisplayName("lifting the block lets everything through again")
	void liftingTheBlockRestoresContact() {
		blocks.unblock(anna, ben.getId());

		issues.addComment(issue.getId(), text("hello again"), ben);

		assertThat(noticesOf(anna)).extracting(Notification::getType)
				.containsExactly(Notification.Type.ISSUE_COMMENTED);
	}

	@Test
	@DisplayName("sharing an issue with the one who blocked you is refused like any account that cannot take part")
	void sharingIsRefusedWithTheSameAnswer() {
		Project secret = projects.save(Project.builder().key("SEC").name("Secret").leadId(ben.getId())
				.leadIds(new ArrayList<>(List.of(ben.getId())))
				.memberIds(new ArrayList<>(List.of(ben.getId())))
				.workflowStates(new ArrayList<>(List.of(Project.WorkflowState.builder().id("s1").name("Open").build())))
				.build());
		Issue theirs = issueRepository.save(Issue.builder().projectId(secret.getId()).title("Plan")
				.readableId("SEC-1").numberInProject(1).state("Open").type(Issue.Type.TASK)
				.reporterId(ben.getId()).build());
		User gone = user("gone");
		gone.setActive(false);
		users.save(gone);

		ApiException toAnna = refusal(() -> participants.add(theirs.getId(), anna.getId(), ben));
		ApiException toGone = refusal(() -> participants.add(theirs.getId(), gone.getId(), ben));

		assertThat(toAnna.getStatus()).isEqualTo(toGone.getStatus());
		assertThat(toAnna.getMessage()).isEqualTo(toGone.getMessage());
		assertThat(toAnna.getMessage()).contains("participantUnavailable");
		// Cleo blocks nobody and is shared with.
		participants.add(theirs.getId(), cleo.getId(), ben);
		assertThat(issueRepository.findById(theirs.getId()).orElseThrow().getParticipantIds())
				.containsExactly(cleo.getId());
	}

	private ApiException refusal(Runnable call) {
		try {
			call.run();
		} catch (ApiException ex) {
			return ex;
		}
		throw new AssertionError("expected a refusal");
	}

	private void clearNotifications() {
		mongo.getCollection("notifications").deleteMany(new Document());
	}

	private List<Notification> noticesOf(User user) {
		return mongo.find(Query.query(Criteria.where("userId").is(user.getId())), Notification.class);
	}

	private static RichText text(String text) {
		return new RichText(null, text, List.of());
	}

	/** A comment mentioning [people], as the editor stores it. */
	private static RichText mentioning(String text, User... people) {
		StringBuilder children = new StringBuilder("{\"type\":\"text\",\"text\":\"" + text + " \"}");
		for (User person : people) {
			children.append(",{\"type\":\"smartlink\",\"kind\":\"user\",\"targetId\":\"").append(person.getId())
					.append("\",\"text\":\"@").append(person.getDisplayName()).append("\"}");
		}
		String doc = "{\"root\":{\"type\":\"root\",\"children\":[{\"type\":\"paragraph\",\"children\":["
				+ children + "]}]}}";
		return new RichText(doc, text, List.of());
	}

	private User user(String name) {
		return users.save(User.builder().username(name).email(name + "@example.org").displayName(name)
				.roles(Set.of(Role.MEMBER)).active(true).locale("en").build());
	}
}
