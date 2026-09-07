package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueMoveService;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.report.ReportService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.user.UserService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What happens to logged hours when the things around them change: the issue
 * moves to another project, the issue is deleted, the person who booked them
 * closes their account.
 *
 * <p>The rule under test is that hours are never collateral damage. They were
 * worked; the project's record keeps them. That makes {@code issueId} and
 * {@code userId} both optional at read time, so every path that touches an
 * entry has to survive their absence — which is the other half of this class.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class WorkItemLifecycleIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private TimeTrackingService timeTracking;
	@Autowired
	private WorkItemRepository workItems;
	@Autowired
	private ReportService reports;
	@Autowired
	private IssueService issues;
	@Autowired
	private IssueMoveService moves;
	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;
	@Autowired
	private UserService userService;

	private User lead;
	private User member;
	private User admin;
	private Project project;
	private Project target;
	private Issue issue;

	private static final LocalDate DAY = LocalDate.now().minusDays(1);

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "projects", "teams", "users", "work_items",
				"notifications", "issue_activities", "issue_comments", "git_dev_info",
				"audit_log")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		lead = user("lead", Role.MEMBER);
		member = user("member", Role.MEMBER);
		admin = user("admin", Role.ADMIN);
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadId(lead.getId()).leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId())))
				.build());
		target = projects.save(Project.builder().key("OPS").name("Operations")
				.leadId(lead.getId()).leadIds(new ArrayList<>(List.of(lead.getId())))
				.memberIds(new ArrayList<>(List.of(lead.getId(), member.getId())))
				.build());
		issue = issueRepository.save(Issue.builder().projectId(project.getId()).readableId("HIN-1")
				.numberInProject(1).title("Login bug").state("Open").spentMinutes(0)
				.watcherIds(new ArrayList<>()).assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>()).dependsOnIds(new ArrayList<>()).build());
	}

	private User user(String name, Role role) {
		return users.save(User.builder().email(name + "@example.org").username(name)
				.displayName(name).roles(Set.of(role)).active(true).timezone("UTC").build());
	}

	private WorkItem log(int minutes) {
		return timeTracking.add(issue.getId(),
				new TimeTrackingService.NewWorkItem(minutes, DAY, "Development", "work", null, null,
						null, null),
				WorkItem.Source.APP, member);
	}

	// --- the issue moves ---------------------------------------------------------

	/**
	 * {@code projectId} on an entry is denormalized so the timesheet and the
	 * reports do not have to join through the issue. Left behind on a move, every
	 * hour ever logged would keep counting towards the project the issue left.
	 */
	@Test
	void movingAnIssueTakesItsLoggedTimeAlong() {
		log(30);
		log(45);
		assertThat(reports.timePerProject(DAY.minusDays(2), DAY.plusDays(2), lead))
				.containsEntry(project.getId(), 75);

		moves.move(List.of(issue.getId()), target.getId(), Map.of(), false, false, lead);

		assertThat(workItems.findAll()).allSatisfy(item ->
				assertThat(item.getProjectId()).isEqualTo(target.getId()));
		assertThat(reports.timePerProject(DAY.minusDays(2), DAY.plusDays(2), lead))
				.as("the hours count where the issue now lives, and only there")
				.containsExactly(org.assertj.core.api.Assertions.entry(target.getId(), 75));
		assertThat(timeTracking.timesheet(DAY.minusDays(2), DAY.plusDays(2), null,
				target.getId(), member)).singleElement()
				.satisfies(row -> assertThat(row.totalMinutes()).isEqualTo(75));
	}

	// --- the issue is deleted -----------------------------------------------------

	/**
	 * Deleting an issue used to delete its entries. It now cuts them loose: the
	 * issue reference goes, the project stays, the hours stay.
	 */
	@Test
	void deletingAnIssueDetachesItsEntriesRatherThanDeletingThem() {
		WorkItem entry = log(30);

		issues.delete(issue.getId(), lead);

		WorkItem after = workItems.findById(entry.getId()).orElseThrow();
		assertThat(after.getIssueId()).as("cut loose").isNull();
		assertThat(after.getProjectId()).as("but still the project's time")
				.isEqualTo(project.getId());
		assertThat(after.getDurationMinutes()).isEqualTo(30);
		assertThat(reports.timePerProject(DAY.minusDays(2), DAY.plusDays(2), lead))
				.containsEntry(project.getId(), 30);
	}

	/** A detached entry is still the owner's to correct or remove — and that must not 500. */
	@Test
	void aDetachedEntryCanStillBeEditedAndDeleted() {
		WorkItem entry = log(30);
		issues.delete(issue.getId(), lead);

		WorkItem patched = timeTracking.update(entry.getId(),
				new TimeTrackingService.WorkItemPatch(60, null, null, null, false, null, false,
						null, null, null),
				member);
		assertThat(patched.getDurationMinutes()).isEqualTo(60);

		assertThatCode(() -> timeTracking.delete(entry.getId(), member)).doesNotThrowAnyException();
		assertThat(workItems.findById(entry.getId())).isEmpty();
	}

	/** An entry with neither issue nor project still groups, and never lands in a report as null. */
	@Test
	void anEntryWithoutAProjectGroupsOnItsOwnAndIsLeftOutOfTheProjectReport() {
		log(30);
		workItems.save(WorkItem.builder().userId(member.getId()).date(DAY).durationMinutes(15)
				.activityType("Meeting").build());

		List<TimeTrackingService.TimesheetRow> rows = timeTracking.timesheet(DAY.minusDays(2),
				DAY.plusDays(2), member.getId(), null, member);

		assertThat(rows).hasSize(2);
		assertThat(rows).anySatisfy(row -> {
			assertThat(row.projectId()).isNull();
			assertThat(row.totalMinutes()).isEqualTo(15);
		});
		assertThat(reports.timePerProject(DAY.minusDays(2), DAY.plusDays(2), admin))
				.as("no null key — it has no name to show and no JSON to serialize to")
				.containsExactly(org.assertj.core.api.Assertions.entry(project.getId(), 30));
	}

	// --- the issue detail ----------------------------------------------------------

	@Test
	void theIssueDetailCarriesTheNewestFiftyEntriesAndTheTotal() {
		for (int i = 0; i < 55; i++) {
			workItems.save(WorkItem.builder().issueId(issue.getId()).projectId(project.getId())
					.userId(member.getId()).date(DAY.minusDays(i % 20)).durationMinutes(10)
					.activityType("Development").build());
		}

		IssueService.IssueDetail detail = issues.detail(issue.getId(), 10, "newest", 10, member);

		assertThat(detail.workItems()).hasSize(IssueService.DETAIL_WORK_ITEMS);
		assertThat(detail.workItemsTotal()).isEqualTo(55);
		assertThat(detail.workItems()).isSortedAccordingTo(
				java.util.Comparator.comparing(WorkItem::getDate).reversed());
	}

	@Test
	void theCappedListAndThePagedRouteAgreeOnTheOrder() {
		for (int i = 0; i < 25; i++) {
			workItems.save(WorkItem.builder().issueId(issue.getId()).projectId(project.getId())
					.userId(member.getId()).date(DAY.minusDays(i)).durationMinutes(10).build());
		}

		List<String> listed = timeTracking.list(issue.getId(), member).stream()
				.map(WorkItem::getId).limit(10).toList();
		List<String> firstPage = timeTracking.page(issue.getId(), 0, 10, member).getContent()
				.stream().map(WorkItem::getId).toList();
		List<String> secondPage = timeTracking.page(issue.getId(), 1, 10, member).getContent()
				.stream().map(WorkItem::getId).toList();

		assertThat(firstPage).isEqualTo(listed);
		assertThat(secondPage).doesNotContainAnyElementsOf(firstPage);
	}

	/**
	 * The array-shaped route has no page parameter, so its only defence against
	 * an issue with years of entries on it is the cap. The paged route is where
	 * the rest lives.
	 */
	@Test
	void theArrayShapedListStopsAtTheCap() {
		List<WorkItem> many = new ArrayList<>();
		for (int i = 0; i < TimeTrackingService.LIST_CAP + 5; i++) {
			many.add(WorkItem.builder().issueId(issue.getId()).projectId(project.getId())
					.userId(member.getId()).date(DAY.minusDays(i % 30)).durationMinutes(10).build());
		}
		workItems.saveAll(many);

		assertThat(timeTracking.list(issue.getId(), member))
				.hasSize(TimeTrackingService.LIST_CAP);
		assertThat(timeTracking.page(issue.getId(), 0, 10, member).getTotalElements())
				.as("the paged route still knows about all of them")
				.isEqualTo(TimeTrackingService.LIST_CAP + 5);
	}

	/** A page size beyond the ceiling is clamped rather than honoured. */
	@Test
	void thePageSizeIsClampedToTheCeiling() {
		for (int i = 0; i < 3; i++) {
			log(10);
		}

		assertThat(timeTracking.page(issue.getId(), 0, 5000, member).getSize())
				.isEqualTo(TimeTrackingService.PAGE_MAX);
	}

	// --- the person leaves ----------------------------------------------------------

	/**
	 * {@code work_items.userId} is kept on account deletion, the same pseudonym
	 * convention as comment authors: the hours stay in the project's record under
	 * an id that no longer resolves to a person. Everything that reads them has to
	 * keep working.
	 */
	@Test
	void deletingAnAccountLeavesItsHoursReadable() {
		WorkItem entry = log(30);
		String goneId = member.getId();

		userService.delete(member);

		assertThat(users.findById(goneId)).isEmpty();
		WorkItem after = workItems.findById(entry.getId()).orElseThrow();
		assertThat(after.getUserId()).isEqualTo(goneId);

		assertThat(timeTracking.list(issue.getId(), lead)).hasSize(1);
		assertThat(timeTracking.timesheet(DAY.minusDays(2), DAY.plusDays(2), goneId, null, admin))
				.singleElement()
				.satisfies(row -> assertThat(row.totalMinutes()).isEqualTo(30));
		assertThat(reports.timePerProject(DAY.minusDays(2), DAY.plusDays(2), lead))
				.containsEntry(project.getId(), 30);
		assertThat(reports.timePerActivity(project.getId(), DAY.minusDays(2), DAY.plusDays(2), lead))
				.containsEntry("Development", 30);
	}

	/** A lead can still clean up an orphaned entry, and the counter follows. */
	@Test
	void anOrphanedEntryCanStillBeRemovedByTheLead() {
		WorkItem entry = log(30);
		userService.delete(member);

		timeTracking.delete(entry.getId(), lead);

		assertThat(workItems.findById(entry.getId())).isEmpty();
		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getSpentMinutes()).isZero();
	}

	/** An entry that never had an owner (a LEGACY remainder) is not silently anyone's. */
	@Test
	void anEntryWithoutAnOwnerBelongsToNobodyAndOnlyALeadOrAdminMayTouchIt() {
		WorkItem ownerless = workItems.save(WorkItem.builder().issueId(issue.getId())
				.projectId(project.getId()).userId(null).date(DAY).durationMinutes(20)
				.source(WorkItem.Source.LEGACY).build());

		assertThat(timeTracking.canManageForeign(ownerless, member)).isFalse();
		assertThat(timeTracking.canManageForeign(ownerless, lead)).isTrue();
		assertThat(timeTracking.canManageForeign(ownerless, admin)).isTrue();

		timeTracking.delete(ownerless.getId(), lead);
		assertThat(workItems.findById(ownerless.getId())).isEmpty();
	}
}
