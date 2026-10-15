package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.timetracking.TimeTrackingService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HIN-136 against a real MongoDB: an issue shared with somebody outside its project.
 *
 * <p>The participant reads and comments on exactly that issue and edits nothing of it; the
 * project's members keep every right they had. The rights matrix of the spec, row by row.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ParticipantAccessIntegrationTest {

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
	private IssueCloneService cloning;
	@Autowired
	private IssueMoveService moving;
	@Autowired
	private IssueLinkService linking;
	@Autowired
	private TimeTrackingService time;
	@Autowired
	private IssueParticipantService participants;
	@Autowired
	private IssueActivityRepository activities;
	@Autowired
	private com.ahmadre.hinata.user.UserService userService;
	@Autowired
	private com.ahmadre.hinata.project.ProjectService projectService;
	@Autowired
	private com.ahmadre.hinata.search.SearchService search;
	@Autowired
	private IssueWatchService watching;
	@Autowired
	private IssueWatcherCleanup watcherCleanup;
	@Autowired
	private com.ahmadre.hinata.notification.IssueDigestService digestService;

	private User lead;
	private User member;
	private User participant;
	private User outsider;
	private Project project;
	private Project otherProject;
	private Issue issue;

	@BeforeEach
	void seed() {
		for (String collection : List.of("users", "projects", "teams", "issues", "issue_comments",
				"issue_activities", "issue_links", "work_items", "notifications", "audit_log",
				"issue_mail_digests")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		lead = user("lead");
		member = user("member");
		participant = user("participant");
		outsider = user("outsider");
		project = projects.save(Project.builder().key("SEC").name("Secret plans")
				.leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId())))
				.workflowStates(new ArrayList<>(List.of(
						Project.WorkflowState.builder().id("s1").name("Open").build(),
						Project.WorkflowState.builder().id("s2").name("Done").build())))
				.resolvedStates(new ArrayList<>(List.of("Done")))
				.build());
		otherProject = projects.save(Project.builder().key("OTH").name("Other")
				.leadId(lead.getId())
				.leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId(), participant.getId())))
				.build());
		issue = issue("Get an eSIM", participant.getId());
	}

	// --- reading and commenting ----------------------------------------------

	@Test
	void aParticipantReadsTheIssueCommentsActivityAndHierarchy() {
		issues.addComment(issue.getId(), text("hello"), member);

		assertThat(issues.getForUser(issue.getReadableId(), participant).getId()).isEqualTo(issue.getId());
		assertThat(issues.commentsOf(issue.getId(), 0, 20, participant).getContent()).hasSize(1);
		assertThat(issues.activityOf(issue.getId(), 0, 20, participant)).isNotNull();
		assertThat(issues.hierarchyOf(issue.getId(), participant)).isNotNull();
		assertThat(issues.canAccess(issue, participant)).isTrue();
	}

	@Test
	void aParticipantCommentsRepliesAndReacts() {
		IssueComment root = issues.addComment(issue.getId(), text("Which carrier?"), member);

		IssueComment reply = issues.addComment(issue.getId(), text("Telekom"), root.getId(), participant);
		issues.reactToComment(issue.getId(), root.getId(), "👍", participant);

		assertThat(reply.getAuthorId()).isEqualTo(participant.getId());
		assertThat(issues.commentsOf(issue.getId(), 0, 20, member).getContent()).isNotEmpty();
	}

	@Test
	void anOutsiderStillGets403() {
		assertThatThrownBy(() -> issues.getForUser(issue.getId(), outsider))
				.isInstanceOf(ApiException.class).hasMessageContaining("notMember");
		assertThatThrownBy(() -> issues.addComment(issue.getId(), text("hi"), outsider))
				.isInstanceOf(ApiException.class);
	}

	// --- editing stays with the project -------------------------------------

	@Test
	void aParticipantCannotChangeFieldsArchiveOrDelete() {
		assertReadOnly(() -> issues.update(issue.getId(), i -> i.setTitle("Hijacked"), participant));
		assertReadOnly(() -> issues.setArchived(issue.getId(), true, participant));
		assertReadOnly(() -> issues.delete(issue.getId(), participant));

		Issue stored = issueRepository.findById(issue.getId()).orElseThrow();
		assertThat(stored.getTitle()).isEqualTo("Get an eSIM");
		assertThat(stored.isArchived()).isFalse();
	}

	@Test
	void aParticipantCannotMoveTheIssueEvenIntoAProjectTheyBelongTo() {
		assertReadOnly(() -> moving.move(List.of(issue.getId()), otherProject.getId(), Map.of(), false, false,
				participant));

		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getProjectId()).isEqualTo(project.getId());
	}

	@Test
	void aParticipantCannotLogTimeClonePinOrLink() {
		Issue other = issue("Another one");
		IssueComment comment = issues.addComment(issue.getId(), text("pin me"), member);

		assertReadOnly(() -> time.create(new TimeTrackingService.NewEntry(null, issue.getId(), 30,
				LocalDate.now(), null, "work", null, null, null, null), com.ahmadre.hinata.timetracking.WorkItem.Source.APP,
				participant));
		assertReadOnly(() -> time.list(issue.getId(), participant));
		assertReadOnly(() -> cloning.clone(issue.getId(),
				new IssueCloneService.Options("Copy", List.of(), false, false, false), participant));
		assertReadOnly(() -> issues.setPinned(issue.getId(), comment.getId(), true, participant));
		assertReadOnly(() -> linking.addLinks(issue.getId(), IssueLinkType.RELATES, true,
				List.of(other.getReadableId()), participant));
	}

	@Test
	void aParticipantMayNotDeleteSomeoneElsesComment() {
		IssueComment comment = issues.addComment(issue.getId(), text("mine"), member);

		assertThatThrownBy(() -> issues.deleteComment(issue.getId(), comment.getId(), participant))
				.isInstanceOf(ApiException.class);
	}

	// --- the detail view ----------------------------------------------------

	@Test
	void aParticipantGetsATrimmedProjectNoSprintsAndNoTime() {
		IssueService.IssueDetail detail = issues.detail(issue.getId(), 30, "newest", 30, participant);

		assertThat(detail.project().getId()).isEqualTo(project.getId());
		assertThat(detail.project().getKey()).isEqualTo("SEC");
		assertThat(detail.project().getWorkflowStates()).hasSize(2);
		assertThat(detail.project().getMemberIds()).isEmpty();
		assertThat(detail.project().getLeadIds()).isEmpty();
		assertThat(detail.project().getLeadId()).isNull();
		assertThat(detail.sprints()).isEmpty();
		assertThat(detail.workItems()).isEmpty();
		assertThat(detail.workItemsTotal()).isZero();
		assertThat(detail.canDelete()).isFalse();
		assertThat(detail.participation().role()).isEqualTo(IssueService.Participation.Role.PARTICIPANT);
		assertThat(detail.participation().canManageParticipants()).isFalse();
	}

	@Test
	void aMemberGetsTheFullProjectAndMayManage() {
		IssueService.IssueDetail detail = issues.detail(issue.getId(), 30, "newest", 30, member);

		assertThat(detail.project().getMemberIds()).contains(member.getId());
		assertThat(detail.participation().role()).isEqualTo(IssueService.Participation.Role.MEMBER);
		assertThat(detail.participation().canManageParticipants()).isTrue();
	}

	@Test
	void detailUsersSayWhoCanSeeTheIssue() {
		issue.setReporterId(outsider.getId());
		issue.setAssigneeIds(new ArrayList<>(List.of(member.getId())));
		issueRepository.save(issue);
		issues.addComment(issue.getId(), text("hi"), participant);

		Map<String, IssueService.DetailUser> byId = issues.detail(issue.getId(), 30, "newest", 30, member)
				.users().stream().collect(Collectors.toMap(IssueService.DetailUser::id, Function.identity()));

		assertThat(byId.get(outsider.getId()).canSeeIssue()).isFalse();
		assertThat(byId.get(member.getId()).canSeeIssue()).isTrue();
		assertThat(byId.get(participant.getId()).canSeeIssue()).isTrue();
	}

	@Test
	void permissionsSayAParticipantCannotDelete() {
		assertThat(issues.permissionsOf(issue.getId(), participant).canDelete()).isFalse();
		assertThat(issues.permissionsOf(issue.getId(), lead).canDelete()).isTrue();
	}

	// --- adding and removing participants -----------------------------------

	@Test
	void aMemberAddsAParticipantWhoThenWatches() {
		Issue plain = issue("Plain");

		participants.add(plain.getId(), outsider.getId(), member);

		Issue stored = issueRepository.findById(plain.getId()).orElseThrow();
		assertThat(stored.getParticipantIds()).containsExactly(outsider.getId());
		assertThat(stored.getWatcherIds()).contains(outsider.getId());
		assertThat(stored.getParticipantMeta()).singleElement()
				.satisfies(meta -> assertThat(meta.addedById()).isEqualTo(member.getId()));
		assertThat(activitiesOf(plain, IssueActivity.Field.PARTICIPANT_ADDED)).singleElement()
				.satisfies(a -> assertThat(a.getToValue()).isEqualTo(outsider.getId()));
		assertThat(audits(com.ahmadre.hinata.audit.AuditAction.ISSUE_PARTICIPANT_ADDED)).isEqualTo(1);
		assertThat(issues.canAccess(stored, outsider)).isTrue();
	}

	@Test
	void addingIsIdempotentAndAuditsOnce() {
		Issue plain = issue("Plain");

		participants.add(plain.getId(), outsider.getId(), member);
		participants.add(plain.getId(), outsider.getId(), lead);

		Issue stored = issueRepository.findById(plain.getId()).orElseThrow();
		assertThat(stored.getParticipantIds()).containsExactly(outsider.getId());
		assertThat(stored.getParticipantMeta()).hasSize(1);
		assertThat(audits(com.ahmadre.hinata.audit.AuditAction.ISSUE_PARTICIPANT_ADDED)).isEqualTo(1);
		assertThat(activitiesOf(plain, IssueActivity.Field.PARTICIPANT_ADDED)).hasSize(1);
	}

	@Test
	void aParticipantMayNotAddOthersButMayLeave() {
		participants.add(issue.getId(), participant.getId(), member); // meta + watch

		assertReadOnly(() -> participants.add(issue.getId(), outsider.getId(), participant));
		assertReadOnly(() -> participants.list(issue.getId(), participant));
		participants.remove(issue.getId(), participant.getId(), participant);

		Issue stored = issueRepository.findById(issue.getId()).orElseThrow();
		assertThat(stored.getParticipantIds()).isEmpty();
		assertThat(stored.getParticipantMeta()).isEmpty();
		assertThat(stored.getWatcherIds()).doesNotContain(participant.getId());
		assertThat(issues.canAccess(stored, participant)).isFalse();
	}

	@Test
	void aParticipantMayNotRemoveSomebodyElse() {
		Issue shared = issue("Two guests", participant.getId(), outsider.getId());

		assertReadOnly(() -> participants.remove(shared.getId(), outsider.getId(), participant));

		assertThat(issueRepository.findById(shared.getId()).orElseThrow().getParticipantIds())
				.contains(outsider.getId());
	}

	@Test
	void aMemberRemovesAParticipant() {
		participants.add(issue.getId(), outsider.getId(), member);

		participants.remove(issue.getId(), outsider.getId(), lead);

		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getParticipantIds())
				.doesNotContain(outsider.getId());
		assertThat(activitiesOf(issue, IssueActivity.Field.PARTICIPANT_REMOVED)).singleElement()
				.satisfies(a -> assertThat(a.getFromValue()).isEqualTo(outsider.getId()));
		assertThat(audits(com.ahmadre.hinata.audit.AuditAction.ISSUE_PARTICIPANT_REMOVED)).isEqualTo(1);
	}

	@Test
	void theTwentySixthIsRefused() {
		List<String> crowd = new ArrayList<>();
		for (int i = 0; i < IssueParticipantService.MAX_PARTICIPANTS; i++) {
			crowd.add(user("guest" + i).getId());
		}
		Issue full = issue("Full", crowd.toArray(String[]::new));

		assertThatThrownBy(() -> participants.add(full.getId(), outsider.getId(), member))
				.isInstanceOf(ApiException.class).hasMessageContaining("participantsFull");
		assertThat(issueRepository.findById(full.getId()).orElseThrow().getParticipantIds())
				.hasSize(IssueParticipantService.MAX_PARTICIPANTS);
	}

	@Test
	void anInactiveAccountIsRefused() {
		outsider.setActive(false);
		users.save(outsider);

		assertThatThrownBy(() -> participants.add(issue.getId(), outsider.getId(), member))
				.isInstanceOf(ApiException.class).hasMessageContaining("participantInactive");
	}

	@Test
	void anUnknownUserIs400() {
		assertThatThrownBy(() -> participants.add(issue.getId(), "000000000000000000000000", member))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(400));
	}

	@Test
	void anInvisibleIssueIs404ForAnOutsider() {
		assertThatThrownBy(() -> participants.add(issue.getId(), member.getId(), outsider))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
		assertThatThrownBy(() -> participants.list(issue.getId(), outsider))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
		assertThatThrownBy(() -> participants.remove(issue.getId(), participant.getId(), outsider))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
	}

	@Test
	void listingLeavesOutProjectMembers() {
		Issue plain = issue("Plain");
		participants.add(plain.getId(), participant.getId(), member);
		participants.add(plain.getId(), outsider.getId(), member);
		// A member on the list (shared before they joined, or by a direct write) is not a participant.
		mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("_id").is(plain.getId())),
				new org.springframework.data.mongodb.core.query.Update().addToSet("participantIds", member.getId()),
				Issue.class);

		List<IssueParticipantService.ParticipantView> listed = participants.list(plain.getId(), lead);

		assertThat(listed).extracting(IssueParticipantService.ParticipantView::id)
				.containsExactlyInAnyOrder(participant.getId(), outsider.getId());
		assertThat(listed).allSatisfy(view -> assertThat(view.addedById()).isEqualTo(member.getId()));
	}

	@Test
	void removingAnAssigneeParticipantKeepsTheAssignment() {
		Issue assigned = issue("Assigned", participant.getId());
		assigned.setAssigneeIds(new ArrayList<>(List.of(participant.getId())));
		issueRepository.save(assigned);

		participants.remove(assigned.getId(), participant.getId(), member);

		assertThat(issueRepository.findById(assigned.getId()).orElseThrow().getAssigneeIds())
				.containsExactly(participant.getId());
	}

	@Test
	void aDeletedAccountLeavesEveryParticipantList() {
		Issue second = issue("Second", participant.getId());

		userService.delete(participant);

		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getParticipantIds()).isEmpty();
		assertThat(issueRepository.findById(second.getId()).orElseThrow().getParticipantIds()).isEmpty();
	}

	@Test
	void reachTellsAMemberWhichMentionedPeopleCannotSeeTheIssue() {
		assertThat(participants.blocked(issue.getId(), List.of(member.getId(), participant.getId(),
				outsider.getId(), "nobody"), member)).containsExactly(outsider.getId());
		assertReadOnly(() -> participants.blocked(issue.getId(), List.of(outsider.getId()), participant));
	}

	private List<IssueActivity> activitiesOf(Issue of, IssueActivity.Field field) {
		return activities.findAll().stream()
				.filter(a -> a.getIssueId().equals(of.getId()) && a.getField() == field).toList();
	}

	private long audits(com.ahmadre.hinata.audit.AuditAction action) {
		return mongo.getCollection("audit_log").countDocuments(new Document("action", action.name()));
	}

	// --- lists, search and the watched page --------------------------------

	@Test
	void aParticipantFindsTheIssueInTheListSearchAndWatchedPage() {
		Issue unshared = issue("Get a new laptop");
		participants.add(issue.getId(), outsider.getId(), member);

		assertThat(issues.search(null, null, null, null, null, null, false, 0, 50, outsider).getContent())
				.extracting(Issue::getId).containsExactly(issue.getId());
		assertThat(issues.search(null, null, null, null, null, "Get", false, 0, 50, outsider).getContent())
				.extracting(Issue::getId).containsExactly(issue.getId()).doesNotContain(unshared.getId());
		assertThat(hits(search.search(outsider, "Get", "ISSUES", false))).isEqualTo(1);
		assertThat(watching.watchedBy(outsider, 0, 20).getContent())
				.extracting(Issue::getId).containsExactly(issue.getId());
		// Even after unwatching, the share keeps it on the page.
		watching.unwatch(issue.getId(), outsider);
		assertThat(watching.watchedBy(outsider, 0, 20).getContent())
				.extracting(Issue::getId).containsExactly(issue.getId());
	}

	@Test
	void aMemberStillListsEveryIssueAndCombinedFiltersStillWork() {
		issue("Get a new laptop");

		assertThat(issues.search(null, null, null, null, null, null, false, 0, 50, member).getContent()).hasSize(2);
		IssueService.SearchParams combined = new IssueService.SearchParams(null, null, List.of("OPEN"), null,
				List.of(member.getId(), lead.getId()), null, null, null, null, "Get", false, false, null, null,
				null, null, null, 0, 50);
		assertThat(issues.search(combined, member).getContent()).isEmpty();
	}

	@Test
	void aParticipantDoesNotSeeTheProjectBoardsOrPages() {
		participants.add(issue.getId(), outsider.getId(), member);

		assertThat(projectService.visibleTo(outsider)).isEmpty();
		assertThat(hits(search.search(outsider, "Secret", "PROJECTS", false))).isZero();
		assertThatThrownBy(() -> projectService.assertMember(project, outsider)).isInstanceOf(ApiException.class);
		assertThat(issues.search(project.getId(), null, null, null, null, null, false, 0, 50, outsider)
				.getContent()).isEmpty();
	}

	@Test
	void losingProjectAccessKeepsTheParticipation() {
		participants.add(issue.getId(), outsider.getId(), member);
		watching.watch(issue.getId(), member);

		projectService.applyUpdate(project.getId(), new com.ahmadre.hinata.project.ProjectUpdateRequest(null, null,
				null, null, null, List.of(lead.getId()), null, null, null, null, null, null, null, null), lead);

		Issue stored = issueRepository.findById(issue.getId()).orElseThrow();
		assertThat(issues.canAccess(stored, outsider)).isTrue();
		assertThat(issues.canAccess(stored, member)).isFalse();
		assertThat(stored.getWatcherIds()).contains(outsider.getId()).doesNotContain(member.getId());
	}

	@Test
	void watcherCleanupKeepsAParticipantsSubscription() {
		participants.add(issue.getId(), outsider.getId(), member);

		watcherCleanup.removeFromProject(project.getId(), List.of(outsider.getId()));

		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getWatcherIds()).contains(outsider.getId());
	}

	@Test
	void aParticipantWhoJoinsTheProjectIsNoLongerListed() {
		participants.add(issue.getId(), outsider.getId(), member);
		project.getMemberIds().add(outsider.getId());
		projects.save(project);

		assertThat(participants.list(issue.getId(), member)).extracting(IssueParticipantService.ParticipantView::id)
				.doesNotContain(outsider.getId());
		assertThat(issues.detail(issue.getId(), 30, "newest", 30, outsider).participation().role())
				.isEqualTo(IssueService.Participation.Role.MEMBER);
	}

	@Test
	void moveKeepsParticipantsAndTheirWatch() {
		participants.add(issue.getId(), outsider.getId(), member);

		moving.move(List.of(issue.getId()), otherProject.getId(), Map.of(), false, false, lead);

		Issue moved = issueRepository.findById(issue.getId()).orElseThrow();
		assertThat(moved.getProjectId()).isEqualTo(otherProject.getId());
		assertThat(moved.getParticipantIds()).contains(outsider.getId());
		assertThat(moved.getWatcherIds()).contains(outsider.getId());
		assertThat(issues.canAccess(moved, outsider)).isTrue();
	}

	private static int hits(com.ahmadre.hinata.search.SearchResponse response) {
		return response.groups().stream().mapToInt(group -> group.items().size()).sum();
	}

	// --- notifications, links and the hierarchy -----------------------------

	@Test
	void aNewParticipantIsToldOnce() {
		participants.add(issue.getId(), outsider.getId(), member);
		participants.add(issue.getId(), outsider.getId(), member);

		List<Document> told = notificationsOf(outsider, "ISSUE_SHARED");
		assertThat(told).singleElement().satisfies(row -> {
			assertThat(row.getString("link")).isEqualTo("/issues/" + issue.getReadableId());
			assertThat(row.getString("title")).contains("member").contains(issue.getReadableId());
		});
	}

	@Test
	void aParticipantHearsCommentsAndChangesLikeAWatcher() {
		participants.add(issue.getId(), outsider.getId(), member);
		User stranger = user("stranger");
		mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("_id").is(issue.getId())),
				new org.springframework.data.mongodb.core.query.Update().addToSet("watcherIds", stranger.getId()),
				Issue.class);

		issues.addComment(issue.getId(), text("Ordered"), member);
		issues.update(issue.getId(), i -> i.setTitle("Get an eSIM today"), lead);

		assertThat(notificationsOf(outsider, "ISSUE_COMMENTED")).singleElement()
				.satisfies(row -> assertThat(row.getString("link")).isEqualTo("/issues/" + issue.getReadableId()));
		assertThat(notificationsOf(outsider, "ISSUE_UPDATED")).isNotEmpty()
				.allSatisfy(row -> assertThat(row.getString("link")).isNotNull());
		assertThat(notificationsOf(stranger, null)).isEmpty();
	}

	@Test
	void aMentionOfSomebodyWithoutAccessStaysSilent() {
		String doc = """
				{"root":{"type":"root","children":[{"type":"paragraph","children":[\
				{"type":"smartlink","kind":"user","targetId":"%s","text":"@outsider"},\
				{"type":"smartlink","kind":"user","targetId":"%s","text":"@participant"}]}]}}"""
				.formatted(outsider.getId(), participant.getId());

		issues.addComment(issue.getId(), new RichText(doc, "@outsider @participant", List.of()), member);

		assertThat(notificationsOf(outsider, "MENTION")).isEmpty();
		assertThat(notificationsOf(participant, "MENTION")).singleElement()
				.satisfies(row -> assertThat(row.getString("link")).isNotNull());
	}

	@Test
	void aParticipantSeesOnlyVisibleLinksAndAncestors() {
		Issue epic = issueRepository.save(Issue.builder().projectId(project.getId()).title("Phones")
				.readableId("SEC-90").numberInProject(90).state("Open").type(Issue.Type.EPIC).build());
		issue.setParentId(epic.getId());
		issueRepository.save(issue);
		Issue hidden = issue("Hidden neighbour");
		linking.addLinks(issue.getId(), IssueLinkType.RELATES, true, List.of(hidden.getReadableId()), member);
		Issue sharedToo = issue("Shared neighbour", participant.getId());
		linking.addLinks(issue.getId(), IssueLinkType.BLOCKS, true, List.of(sharedToo.getReadableId()), member);

		IssueService.IssueDetail detail = issues.detail(issue.getId(), 30, "newest", 30, participant);

		assertThat(detail.hierarchy().ancestors()).isEmpty();
		assertThat(linking.linksOf(issue.getId(), participant)).extracting(view -> view.issue().getId())
				.containsExactly(sharedToo.getId());
		assertThat(linking.linksOf(issue.getId(), member)).hasSize(2);
	}

	@Test
	void theDigestKeepsParticipants() {
		participants.add(issue.getId(), outsider.getId(), member);
		// Watchers' change mails are bundled; the participant is one of them now.
		issues.update(issue.getId(), i -> i.setTitle("Changed"), lead);

		assertThat(mongo.getCollection("issue_mail_digests")
				.countDocuments(new Document("userId", outsider.getId()))).isEqualTo(1);
		java.util.Date past = java.util.Date.from(java.time.Instant.now().minusSeconds(3600));
		mongo.getCollection("issue_mail_digests").updateMany(new Document("userId", outsider.getId()),
				new Document("$set", new Document("softDueAt", past).append("hardDueAt", past)));
		assertThat(digestService.sweep(50)).isEqualTo(1);
	}

	private List<Document> notificationsOf(User user, String type) {
		Document filter = new Document("userId", user.getId());
		if (type != null) filter.append("type", type);
		return mongo.getCollection("notifications").find(filter).into(new ArrayList<>());
	}

	// --- helpers ------------------------------------------------------------

	private static void assertReadOnly(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOf(ApiException.class)
				.satisfies(ex -> assertThat(((ApiException) ex).getMessageKey())
						.isEqualTo("error.issue.participantReadOnly"));
	}

	private Issue issue(String title, String... participantIds) {
		long number = issueRepository.count() + 1;
		return issueRepository.save(Issue.builder().projectId(project.getId()).title(title)
				.readableId("SEC-" + number).numberInProject(number).state("Open")
				.type(Issue.Type.TASK).reporterId(lead.getId())
				.participantIds(new ArrayList<>(List.of(participantIds)))
				.build());
	}

	private static RichText text(String text) {
		return new RichText(null, text, List.of());
	}

	private User user(String name) {
		return users.save(User.builder().username(name).email(name + "@example.org")
				.displayName(name).roles(Set.of(Role.MEMBER)).active(true).build());
	}
}
