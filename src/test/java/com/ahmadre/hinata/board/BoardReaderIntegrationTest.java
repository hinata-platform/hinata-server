package com.ahmadre.hinata.board;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.TestMongo;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueLink;
import com.ahmadre.hinata.issue.IssueLinkGraphService;
import com.ahmadre.hinata.issue.IssueLinkRepository;
import com.ahmadre.hinata.issue.IssueLinkType;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * A board read in pages against a real MongoDB.
 *
 * <p>Everything here is a property of queries: which issues a column counts, in which order a page
 * comes, what a search or a filter keeps, which projects a viewer's read reaches, and which index the
 * database reads it off. None of that can be observed against a stubbed template, and a filter that
 * returns nothing would pass every test that only checks what is absent, so each test also checks
 * what must be there.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class BoardReaderIntegrationTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(TestMongo.IMAGE));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private BoardReader reader;
	@Autowired
	private UserRepository users;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private IssueRepository issues;
	@Autowired
	private AgileBoardRepository boards;
	@Autowired
	private SprintRepository sprints;
	@Autowired
	private IssueLinkRepository links;
	@Autowired
	private BoardIssueReads issueReads;
	@Autowired
	private BoardController controller;
	@MockitoBean
	private CurrentUser currentUser;

	private User member;
	private User outsider;
	private User admin;
	private Project hinata;
	private Project secret;
	private AgileBoard board;
	private int number;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "projects", "teams", "users", "agile_boards", "sprints", "issue_links")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		member = users.save(User.builder().email("member@example.org").username("member").displayName("Member")
				.roles(Set.of(Role.MEMBER)).active(true).build());
		outsider = users.save(User.builder().email("outsider@example.org").username("outsider")
				.displayName("Outsider").roles(Set.of(Role.MEMBER)).active(true).build());
		admin = users.save(User.builder().email("admin@example.org").username("admin").displayName("Admin")
				.roles(Set.of(Role.ADMIN)).active(true).build());
		hinata = projects.save(Project.builder().key("HIN").name("Hinata").leadId(member.getId())
				.leadIds(new ArrayList<>(List.of(member.getId())))
				.memberIds(new ArrayList<>(List.of(member.getId()))).build());
		secret = projects.save(Project.builder().key("SEC").name("Secret").leadId(admin.getId())
				.leadIds(new ArrayList<>(List.of(admin.getId())))
				.memberIds(new ArrayList<>(List.of(admin.getId()))).build());
		board = boards.save(AgileBoard.builder().name("Hinata board")
				.projectIds(new ArrayList<>(List.of(hinata.getId()))).build());
		number = 0;
	}

	@Test
	void countsEveryCardOfAColumnAndHandsOverTheFirstPageInBoardOrder() {
		for (int rank = 35; rank >= 1; rank--) {
			int at = rank;
			issue(hinata, "Open " + rank, i -> i.rank(at));
		}
		issue(hinata, "Done one", i -> i.state("Done"));
		issue(hinata, "Done two", i -> i.state("Done"));
		issue(hinata, "Archived", i -> i.archived(true));
		Issue epic = issue(hinata, "Epic", i -> i.type(Issue.Type.EPIC));
		issue(hinata, "Sub-task", i -> i.type(Issue.Type.SUBTASK).parentId(epic.getId()));

		BoardReader.BoardWall wall = reader.wall(board.getId(), null, BoardQuery.ALL, 30, member);

		BoardReader.WallColumn open = column(wall, "Open");
		assertThat(open.total()).isEqualTo(35);
		assertThat(open.issues()).hasSize(30);
		assertThat(open.issues()).extracting(BoardCard::title).startsWith("Open 1", "Open 2", "Open 3");
		assertThat(column(wall, "Done").total()).isEqualTo(2);
		assertThat(column(wall, "In Review").total()).isZero();
		assertThat(column(wall, "In Review").issues()).isEmpty();
		assertThat(wall.columns()).extracting(BoardReader.WallColumn::name)
				.containsExactly("Open", "In Progress", "In Parking", "In Review", "Done");

		BoardReader.BoardCardPage next = reader.cards(board.getId(),
				new BoardReader.CardSource("open", null, false, null), BoardQuery.ALL, 1, 30, false, member);
		assertThat(next.totalElements()).isEqualTo(35);
		assertThat(next.content()).extracting(BoardCard::title)
				.containsExactly("Open 31", "Open 32", "Open 33", "Open 34", "Open 35");
	}

	@Test
	void searchesTheKeyTheTitleAndTheLabelsIgnoringCase() {
		Issue login = issue(hinata, "Fix the Login screen", i -> { });
		Issue labelled = issue(hinata, "Release notes", i -> i.tags(new ArrayList<>(List.of("Mobile"))));
		Issue dotted = issue(hinata, "a.b rename", i -> { });
		issue(hinata, "axb rename", i -> { });
		Issue umlauts = issue(hinata, "Übersicht öffnen", i -> { });

		assertThat(titles(search("LOGIN"))).containsExactly(login.getTitle());
		assertThat(titles(search("mobile"))).containsExactly(labelled.getTitle());
		assertThat(titles(search(login.getReadableId().toLowerCase()))).containsExactly(login.getTitle());
		// The text is matched literally: a dot is a dot, not any character.
		assertThat(titles(search("a.b"))).containsExactly(dotted.getTitle());
		assertThat(titles(search("ÜBERSICHT"))).containsExactly(umlauts.getTitle());
	}

	@Test
	void filtersEveryFacetOnTheServer() {
		Issue bug = issue(hinata, "Bug in progress", i -> i.type(Issue.Type.BUG).state("In Progress")
				.priority(Issue.Priority.MAJOR).reporterId(member.getId()));
		Issue shared = issue(hinata, "Shared work", i -> i.assigneeId("first")
				.assigneeIds(new ArrayList<>(List.of("first", "second"))).tags(new ArrayList<>(List.of("ui"))));
		issue(hinata, "Plain task", i -> { });

		assertThat(titles(filtered(BoardQuery.of(null, List.of("IN PROGRESS"), null, null, null, null, null, null,
				null, null)))).containsExactly(bug.getTitle());
		assertThat(titles(filtered(BoardQuery.of(null, null, List.of("bug"), List.of("MAJOR"), null,
				List.of(member.getId()), null, null, null, null)))).containsExactly(bug.getTitle());
		// A further assignee counts, not only the first.
		assertThat(titles(filtered(BoardQuery.of(null, null, null, null, List.of("second"), null, List.of("ui"),
				null, null, null)))).containsExactly(shared.getTitle());
		// A state no project of the board has keeps nothing, and costs no query for cards.
		BoardQuery nowhere = BoardQuery.of(null, List.of("NOPE"), null, null, null, null, null, null, null, null);
		assertThat(filtered(nowhere).totalElements()).isZero();
		assertThat(reader.wall(board.getId(), null, nowhere, 30, member).columns())
				.isNotEmpty().allSatisfy(column -> assertThat(column.total()).isZero());
	}

	@Test
	void readsASprintTheBacklogAndTheActiveSprintByDefault() {
		Sprint sprint = sprints.save(Sprint.builder().boardId(board.getId()).name("Sprint 1").build());
		Issue planned = issue(hinata, "In the sprint", i -> i.sprintId(sprint.getId()));
		Issue waiting = issue(hinata, "In the backlog", i -> { });

		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource(null, sprint.getId(), false, null),
				BoardQuery.ALL, 0, 30, false, member))).containsExactly(planned.getTitle());
		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource(null, null, true, null),
				BoardQuery.ALL, 0, 30, false, member))).containsExactly(waiting.getTitle());
		// A column of a sprint wall reads its next page within the sprint.
		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource("Open", sprint.getId(), false,
				null), BoardQuery.ALL, 0, 30, false, member))).containsExactly(planned.getTitle());
		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource("Open", null, false, null),
				BoardQuery.of(null, null, null, null, null, null, null, List.of(BoardQuery.NO_SPRINT), null, null),
				0, 30, false, member))).containsExactly(waiting.getTitle());

		board.setActiveSprintId(sprint.getId());
		boards.save(board);
		BoardReader.BoardWall wall = reader.wall(board.getId(), null, BoardQuery.ALL, 30, member);
		assertThat(wall.sprintId()).isEqualTo(sprint.getId());
		assertThat(column(wall, "Open").issues()).extracting(BoardCard::title).containsExactly(planned.getTitle());

		assertRefused(() -> reader.cards(board.getId(), new BoardReader.CardSource(null, sprint.getId(), true, null),
				BoardQuery.ALL, 0, 30, false, member), "error.validationFailed");
	}

	@Test
	void showsAScrumBoardBetweenTwoSprintsWithoutCards() {
		board.setType(AgileBoard.Type.SCRUM);
		boards.save(board);
		issue(hinata, "Waiting for a sprint", i -> { });

		BoardReader.BoardWall wall = reader.wall(board.getId(), null, BoardQuery.ALL, 30, member);

		assertThat(wall.sprintId()).isNull();
		assertThat(wall.columns()).isNotEmpty().allSatisfy(column -> {
			assertThat(column.total()).isNull();
			assertThat(column.issues()).isEmpty();
		});
	}

	@Test
	void takesInTheSubTasksOfASprintsWorkWhenGroupedBySubTask() {
		Sprint sprint = sprints.save(Sprint.builder().boardId(board.getId()).name("Sprint 1").build());
		Issue story = issue(hinata, "Story", i -> i.type(Issue.Type.STORY).sprintId(sprint.getId()));
		Issue child = issue(hinata, "Child", i -> i.type(Issue.Type.SUBTASK).parentId(story.getId()));
		issue(hinata, "Child of backlog work", i -> i.type(Issue.Type.SUBTASK)
				.parentId(issue(hinata, "Backlog story", s -> s.type(Issue.Type.STORY)).getId()));
		BoardQuery subtasks = BoardQuery.of(null, null, null, null, null, null, null, null, null, "subtasks");

		BoardReader.BoardCardPage page = reader.cards(board.getId(),
				new BoardReader.CardSource(null, sprint.getId(), false, null), subtasks, 0, 30, false, member);

		assertThat(titles(page)).containsExactlyInAnyOrder(story.getTitle(), child.getTitle());
		// Without the grouping a sub-task is no card of its own.
		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource(null, sprint.getId(), false, null),
				BoardQuery.ALL, 0, 30, false, member))).containsExactly(story.getTitle());
	}

	@Test
	void listsEveryIssueTypeForThePlanning() {
		Sprint sprint = sprints.save(Sprint.builder().boardId(board.getId()).name("Sprint 1").build());
		Issue epic = issue(hinata, "Epic in the sprint", i -> i.type(Issue.Type.EPIC).sprintId(sprint.getId()));
		issue(hinata, "Story in the sprint", i -> i.type(Issue.Type.STORY).sprintId(sprint.getId()));
		issue(hinata, "Sub-task in the sprint", i -> i.type(Issue.Type.SUBTASK).parentId(epic.getId())
				.sprintId(sprint.getId()));
		Issue backlogEpic = issue(hinata, "Epic in the backlog", i -> i.type(Issue.Type.EPIC));
		issue(hinata, "Sub-task in the backlog", i -> i.type(Issue.Type.SUBTASK).parentId(backlogEpic.getId()));
		BoardQuery planning = BoardQuery.of(null, null, null, null, null, null, null, null, null, "planning");

		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource(null, sprint.getId(), false, null),
				planning, 0, 30, false, member)))
				.containsExactlyInAnyOrder("Epic in the sprint", "Story in the sprint", "Sub-task in the sprint");
		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource(null, null, true, null),
				planning, 0, 30, false, member)))
				.containsExactlyInAnyOrder("Epic in the backlog", "Sub-task in the backlog");
	}

	@Test
	void resolvesEachCardsEpicPeopleAndSubTasksForThePageAtOnce() {
		Issue epic = issue(hinata, "Epic", i -> i.type(Issue.Type.EPIC));
		Issue story = issue(hinata, "Story", i -> i.type(Issue.Type.STORY).parentId(epic.getId())
				.assigneeId(member.getId()).assigneeIds(new ArrayList<>(List.of(member.getId()))));
		Issue child = issue(hinata, "Child", i -> i.type(Issue.Type.SUBTASK).parentId(story.getId()).state("Done"));
		issue(hinata, "Second child", i -> i.type(Issue.Type.SUBTASK).parentId(story.getId()));
		BoardQuery subtasks = BoardQuery.of(null, null, null, null, null, null, null, null, null, "subtasks");

		BoardReader.BoardCardPage page = reader.cards(board.getId(), new BoardReader.CardSource(null, null, true,
				null), subtasks, 0, 30, false, member);

		BoardCard storyCard = card(page, story);
		assertThat(storyCard.epicId()).isEqualTo(epic.getId());
		assertThat(storyCard.subtaskCount()).isEqualTo(2);
		assertThat(storyCard.subtaskDoneCount()).isEqualTo(1);
		// A sub-task rolls up to the epic of its parent.
		assertThat(card(page, child).epicId()).isEqualTo(epic.getId());
		assertThat(page.refs()).extracting(BoardRef::id).contains(epic.getId(), story.getId());
		assertThat(page.users()).extracting(user -> user.displayName()).containsExactly("Member");
		// The epic filter reaches the sub-task through its parent too.
		BoardQuery underEpic = BoardQuery.of(null, null, null, null, null, null, null, null, List.of(epic.getId()),
				"subtasks");
		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource(null, null, true, null), underEpic,
				0, 30, false, member))).containsExactlyInAnyOrder("Story", "Child", "Second child");
	}

	@Test
	void countsOnlyTheActiveSubTasksOfTheParentsOwnProject() {
		Issue story = issue(hinata, "Story", i -> i.type(Issue.Type.STORY));
		issue(hinata, "Own child", i -> i.type(Issue.Type.SUBTASK).parentId(story.getId()).state("Done"));
		issue(hinata, "Archived child", i -> i.type(Issue.Type.SUBTASK).parentId(story.getId()).archived(true));
		// Left behind in another project when its parent moved: a viewer of the story may not see it.
		issue(secret, "Stayed behind", i -> i.type(Issue.Type.SUBTASK).parentId(story.getId()));

		BoardCard card = card(filtered(BoardQuery.ALL), story);

		assertThat(card.subtaskCount()).isEqualTo(1);
		assertThat(card.subtaskDoneCount()).isEqualTo(1);
	}

	@Test
	void offersTheTypesAShapeListsAndEveryPriority() {
		issue(hinata, "Task", i -> { });

		BoardFacets wall = reader.facets(board.getId(), BoardQuery.Shape.WALL, member);
		BoardFacets timeline = reader.facets(board.getId(), BoardQuery.Shape.TIMELINE, member);
		BoardFacets planning = reader.facets(board.getId(), BoardQuery.Shape.PLANNING, member);

		assertThat(wall.types()).containsExactly("TASK", "BUG", "FEATURE", "STORY");
		assertThat(timeline.types()).containsExactly("TASK", "BUG", "FEATURE", "STORY", "EPIC");
		assertThat(planning.types()).containsExactly("TASK", "BUG", "FEATURE", "STORY", "EPIC", "SUBTASK");
		assertThat(wall.priorities()).containsExactly("SHOWSTOPPER", "CRITICAL", "MAJOR", "NORMAL", "MINOR", "TRIVIAL");
	}

	@Test
	void readsOnlyTheProjectsOfTheBoardTheViewerMaySee() {
		board.setProjectIds(new ArrayList<>(List.of(hinata.getId(), secret.getId())));
		boards.save(board);
		Issue secretEpic = issue(secret, "Secret epic", i -> i.type(Issue.Type.EPIC));
		issue(secret, "Secret work", i -> i.parentId(secretEpic.getId()));
		// Linked across projects: the reference must not carry the other project's epic along.
		Issue own = issue(hinata, "Own work", i -> i.parentId(secretEpic.getId()));

		BoardReader.BoardWall memberWall = reader.wall(board.getId(), null, BoardQuery.ALL, 30, member);
		assertThat(column(memberWall, "Open").issues()).extracting(BoardCard::title).containsExactly(own.getTitle());
		assertThat(memberWall.refs()).isEmpty();
		assertThat(column(memberWall, "Open").issues().getFirst().epicId()).isNull();

		BoardReader.BoardWall adminWall = reader.wall(board.getId(), null, BoardQuery.ALL, 30, admin);
		assertThat(column(adminWall, "Open").issues()).extracting(BoardCard::title)
				.containsExactlyInAnyOrder("Own work", "Secret work");

		assertRefused(() -> reader.wall(board.getId(), null, BoardQuery.ALL, 30, outsider), "error.accessDenied");
		assertRefused(() -> reader.facets(board.getId(), BoardQuery.Shape.WALL, outsider), "error.accessDenied");

		board.setProjectIds(new ArrayList<>(List.of(hinata.getId())));
		boards.save(board);
		hinata.setArchived(true);
		projects.save(hinata);
		assertRefused(() -> reader.wall(board.getId(), null, BoardQuery.ALL, 30, member), "error.accessDenied");
		// An admin reads a board of projects nobody may see as empty.
		assertThat(reader.wall(board.getId(), null, BoardQuery.ALL, 30, admin).columns())
				.allSatisfy(column -> assertThat(column.issues()).isEmpty());
	}

	@Test
	void readsABoardSpanningMoreProjectsThanOneLookupNames() {
		List<String> spanned = new ArrayList<>(IntStream.range(0, 120).mapToObj(i -> String.format("%024x", i)).toList());
		spanned.add(hinata.getId());
		board.setProjectIds(spanned);
		boards.save(board);
		Issue own = issue(hinata, "Own work", i -> { });

		BoardReader.BoardWall wall = reader.wall(board.getId(), null, BoardQuery.ALL, 30, member);

		assertThat(column(wall, "Open").issues()).extracting(BoardCard::title).containsExactly(own.getTitle());
	}

	@Test
	void summarizesEveryCardOfTheSprintByStateAndResolution() {
		Sprint sprint = sprints.save(Sprint.builder().boardId(board.getId()).name("Sprint 1").build());
		for (int i = 0; i < 40; i++) {
			issue(hinata, "Open " + i, b -> b.sprintId(sprint.getId()).storyPoints(2));
		}
		issue(hinata, "Done", b -> b.sprintId(sprint.getId()).state("Done").storyPoints(5)
				.resolvedAt(Instant.parse("2026-09-01T10:00:00Z")));
		issue(hinata, "Unestimated", b -> b.sprintId(sprint.getId()).state("In Review"));

		BoardReader.BoardCardPage page = reader.cards(board.getId(),
				new BoardReader.CardSource(null, sprint.getId(), false, null), BoardQuery.ALL, 0, 10, true, member);

		assertThat(page.content()).hasSize(10);
		assertThat(page.totalElements()).isEqualTo(42);
		assertThat(page.summary()).containsExactly(
				new BoardStateSummary("Done", true, 1, 5),
				new BoardStateSummary("In Review", false, 1, 0),
				new BoardStateSummary("Open", false, 40, 80));
		// Only a sprint has a head to count for.
		assertRefused(() -> reader.cards(board.getId(), new BoardReader.CardSource(null, null, true, null),
				BoardQuery.ALL, 0, 10, true, member), "error.validationFailed");
	}

	@Test
	void gathersThePeopleAndLabelsOfEveryActiveCardOfTheBoard() {
		Sprint sprint = sprints.save(Sprint.builder().boardId(board.getId()).name("Sprint 1").build());
		issue(hinata, "One", i -> i.assigneeId(member.getId())
				.assigneeIds(new ArrayList<>(List.of(member.getId(), admin.getId())))
				.reporterId(outsider.getId()).tags(new ArrayList<>(List.of("ui", "api"))).type(Issue.Type.BUG));
		issue(hinata, "Two", i -> i.state("Done").priority(Issue.Priority.MINOR).tags(new ArrayList<>(List.of("ui"))));
		issue(hinata, "In the sprint", i -> i.sprintId(sprint.getId()).tags(new ArrayList<>(List.of("sprint-only"))));
		issue(hinata, "Archived", i -> i.archived(true).assigneeId(outsider.getId())
				.assigneeIds(new ArrayList<>(List.of(outsider.getId()))).tags(new ArrayList<>(List.of("gone"))));
		issue(secret, "Elsewhere", i -> i.reporterId(admin.getId()).tags(new ArrayList<>(List.of("secret"))));
		Issue epic = issue(hinata, "Epic", i -> i.type(Issue.Type.EPIC));

		BoardFacets facets = reader.facets(board.getId(), BoardQuery.Shape.WALL, member);

		// A further assignee counts as well as the first.
		assertThat(facets.assigneeIds()).containsExactlyInAnyOrder(member.getId(), admin.getId());
		assertThat(facets.reporterIds()).containsExactly(outsider.getId());
		assertThat(facets.labels()).containsExactly("api", "sprint-only", "ui");
		assertThat(facets.states()).containsExactly("Open", "In Progress", "In Parking", "In Review", "Done");
		assertThat(facets.epics()).extracting(BoardRef::id).containsExactly(epic.getId());
		assertThat(facets.users()).extracting(user -> user.id())
				.containsExactlyInAnyOrder(member.getId(), admin.getId(), outsider.getId());
	}

	@Test
	void gathersTheFacetsOffTheIndexesReadingNoCardButTheEpics() {
		issue(hinata, "Epic", i -> i.type(Issue.Type.EPIC));
		for (int i = 0; i < 60; i++) {
			User person = i % 2 == 0 ? member : admin;
			String label = "label-" + i % 3;
			issue(hinata, "Card " + i, b -> b.assigneeId(person.getId())
					.assigneeIds(new ArrayList<>(List.of(person.getId()))).reporterId(outsider.getId())
					.tags(new ArrayList<>(List.of(label))));
		}

		List<Document> gathered = profiled(() -> reader.facets(board.getId(), BoardQuery.Shape.WALL, member));

		assertThat(gathered).isNotEmpty().allSatisfy(op -> {
			assertThat(examined(op)).isLessThanOrEqualTo(1);
			assertThat(limited(op)).as("time limit of %s", sent(op)).isTrue();
		});
		// One step through the keys per person and per label, and one that finds no further one.
		assertThat(hinted(gathered, "find", "board_by_assignee")).hasSize(3);
		assertThat(hinted(gathered, "find", "board_by_label")).hasSize(4);
		assertThat(hinted(gathered, "distinct", "board_by_reporter")).hasSize(1);
		assertThat(hinted(gathered, "find", "board_epics")).singleElement().satisfies(epics -> {
			assertThat(examined(epics)).isEqualTo(1);
			assertThat(epics.getBoolean("hasSortStage", false)).isFalse();
		});
	}

	@Test
	void putsACardInTheColumnOfItsStateHoweverTheStateIsSpelled() {
		issue(hinata, "Spelled otherwise", i -> i.state("in PROGRESS"));
		issue(hinata, "Spelled as named", i -> i.state("In Progress"));

		BoardReader.WallColumn inProgress = column(reader.wall(board.getId(), null, BoardQuery.ALL, 30, member),
				"In Progress");

		assertThat(inProgress.total()).isEqualTo(2);
		assertThat(inProgress.issues()).extracting(BoardCard::title)
				.containsExactlyInAnyOrder("Spelled otherwise", "Spelled as named");
		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource("In Progress", null, false, null),
				BoardQuery.ALL, 0, 30, false, member))).containsExactlyInAnyOrder("Spelled otherwise", "Spelled as named");
		assertThat(titles(filtered(BoardQuery.of(null, List.of("in progress"), null, null, null, null, null, null, null,
				null)))).containsExactlyInAnyOrder("Spelled otherwise", "Spelled as named");
	}

	@Test
	void theOldBoardViewPutsACardInTheColumnOfItsStateHoweverTheStateIsSpelled() {
		issue(hinata, "Spelled otherwise", i -> i.state("in PROGRESS"));
		when(currentUser.require()).thenReturn(member);

		List<BoardController.BoardView> views = new ArrayList<>();
		List<Document> read = profiled(() -> views.add(controller.view(board.getId(), null)));

		assertThat(views.getFirst().columns()).filteredOn(column -> column.name().equals("In Progress")).singleElement()
				.satisfies(column -> assertThat(column.issues()).extracting(Issue::getTitle)
						.containsExactly("Spelled otherwise"));
		// The old view reads within the time of its request too, each project's cards off a board index.
		assertThat(read).isNotEmpty()
				.allSatisfy(op -> assertThat(limited(op)).as("time limit of %s", sent(op)).isTrue());
		assertThat(hinted(read, "find", "board_by_state")).isNotEmpty();
	}

	@Test
	void readsTheValuesOffTheIssuesOnceTheStepsRunOut() {
		for (String label : List.of("api", "ui", "web")) {
			issue(hinata, "Tagged " + label, i -> i.tags(new ArrayList<>(List.of(label))));
		}

		List<Document> read = profiled(() -> assertThat(issueReads.keysOf("tags", List.of(hinata.getId()),
				"board_by_label", 500, new BoardIssueReads.Steps(2))).containsExactly("api", "ui", "web"));

		assertThat(hinted(read, "find", "board_by_label")).hasSize(2);
		// Then one read takes the values off the issues of the board's projects, no more of them than asked.
		assertThat(hinted(read, "aggregate", "board_by_state")).singleElement();
		assertThat(issueReads.keysOf("tags", List.of(hinata.getId()), "board_by_label", 2,
				new BoardIssueReads.Steps(0))).containsExactly("api", "ui");
	}

	@Test
	void readsTheSubTasksOfASprintWithoutTheBoardsOtherCardsInTheirState() {
		Sprint sprint = sprints.save(Sprint.builder().boardId(board.getId()).name("Sprint 1").build());
		for (int i = 0; i < 200; i++) {
			issue(hinata, "Done elsewhere " + i, b -> b.state("Done"));
		}
		Issue story = issue(hinata, "Story", b -> b.type(Issue.Type.STORY).sprintId(sprint.getId()).state("Done"));
		for (int i = 0; i < 5; i++) {
			issue(hinata, "Child " + i, b -> b.type(Issue.Type.SUBTASK).parentId(story.getId()).state("Done"));
		}
		BoardQuery subtasks = BoardQuery.of(null, null, null, null, null, null, null, null, null, "subtasks");

		List<Document> read = profiled(() -> assertThat(column(reader.wall(board.getId(), sprint.getId(), subtasks, 30,
				member), "Done").issues()).hasSize(6));

		// The planner combines the sprint's work and the sub-tasks under it: no read walks the two
		// hundred other cards in the same state.
		assertThat(read).isNotEmpty().allSatisfy(op -> assertThat(examined(op)).isLessThanOrEqualTo(12));
	}

	@Test
	void drawsTheConnectorsBetweenTheCardsAViewHoldsOfProjectsTheViewerMaySee() {
		board.setProjectIds(new ArrayList<>(List.of(hinata.getId(), secret.getId())));
		boards.save(board);
		Issue blocker = issue(hinata, "Blocker", i -> { });
		Issue blocked = issue(hinata, "Blocked", i -> { });
		Issue hidden = issue(secret, "Hidden", i -> { });
		links.save(IssueLink.builder().type(IssueLinkType.BLOCKS).sourceId(blocker.getId()).targetId(blocked.getId())
				.build());
		links.save(IssueLink.builder().type(IssueLinkType.BLOCKS).sourceId(blocker.getId()).targetId(hidden.getId())
				.build());

		List<IssueLinkGraphService.LinkEdge> edges = new ArrayList<>();
		List<Document> read = profiled(() -> edges.addAll(reader.links(board.getId(),
				List.of(blocker.getId(), blocked.getId(), hidden.getId()), member)));

		assertThat(edges).extracting(IssueLinkGraphService.LinkEdge::targetId).containsExactly(blocked.getId());
		// The cards come off their ids, not off an index of the board's projects, and the cards and their
		// links are read within the request's time.
		assertThat(hinted(read, "find", BoardCriteria.BY_ID)).singleElement()
				.satisfies(find -> assertThat(examined(find)).isEqualTo(3));
		assertThat(read).anySatisfy(op -> assertThat(op.getString("ns")).endsWith(".issue_links"))
				.allSatisfy(op -> assertThat(limited(op)).as("time limit of %s", sent(op)).isTrue());
		assertRefused(() -> reader.links(board.getId(), IntStream.range(0, BoardReader.MAX_LINK_CARDS + 1)
				.mapToObj(String::valueOf).toList(), member), "error.validationFailed");
	}

	@Test
	void putsEpicsOnTheTimelineAndSplitsItByDate() {
		issue(hinata, "Later", i -> i.startDate(LocalDate.parse("2026-10-01")));
		issue(hinata, "Sooner epic", i -> i.type(Issue.Type.EPIC).startDate(LocalDate.parse("2026-09-01")));
		issue(hinata, "Deadline", i -> i.dueDate(LocalDate.parse("2026-09-20")));
		issue(hinata, "Undated", i -> { });
		issue(hinata, "Dated sub-task", i -> i.type(Issue.Type.SUBTASK).dueDate(LocalDate.parse("2026-09-10")));
		BoardQuery timeline = BoardQuery.of(null, null, null, null, null, null, null, null, null, "timeline");

		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource(null, null, false, true), timeline, 0,
				30, false, member))).containsExactly("Deadline", "Sooner epic", "Later");
		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource(null, null, false, false), timeline,
				0, 30, false, member))).containsExactly("Undated");
		// Page by page the order holds across the cards with a due date alone and those with a start date.
		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource(null, null, false, true), timeline, 0,
				2, false, member))).containsExactly("Deadline", "Sooner epic");
		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource(null, null, false, true), timeline, 1,
				2, false, member))).containsExactly("Later");
	}

	@Test
	void keepsPagesWithinTheirLimits() {
		for (int i = 0; i < 120; i++) {
			issue(hinata, "Card " + i, b -> { });
		}

		assertThat(reader.cards(board.getId(), new BoardReader.CardSource("Open", null, false, null), BoardQuery.ALL,
				0, 500, false, member).content()).hasSize(BoardReader.MAX_PAGE_SIZE);
		BoardReader.BoardCardPage farAway = reader.cards(board.getId(),
				new BoardReader.CardSource("Open", null, false, null), BoardQuery.ALL, 10_001, 1, false, member);
		assertThat(farAway.content()).isEmpty();
		assertThat(farAway.totalElements()).isEqualTo(120);

		BoardReader.BoardWall bare = reader.wall(board.getId(), null, BoardQuery.ALL, 0, member);
		assertThat(bare.columns()).allSatisfy(column -> {
			assertThat(column.total()).isNull();
			assertThat(column.issues()).isEmpty();
		});

		assertRefused(() -> reader.cards(board.getId(), new BoardReader.CardSource("Open", null, false, null),
				BoardQuery.ALL, 0, -1, false, member), "error.validationFailed");
		// A read names where its cards come from: the whole board in board order is no read.
		assertRefused(() -> reader.cards(board.getId(), new BoardReader.CardSource(null, null, false, null),
				BoardQuery.ALL, 0, 30, false, member), "error.validationFailed");
		assertRefused(() -> reader.cards(board.getId(), new BoardReader.CardSource("Nowhere", null, false, null),
				BoardQuery.ALL, 0, 30, false, member), "error.board.unknownColumn");
		// The timeline has no columns.
		assertRefused(() -> reader.cards(board.getId(), new BoardReader.CardSource("Open", null, false, true),
				BoardQuery.ALL, 0, 30, false, member), "error.validationFailed");
		assertRefused(() -> reader.wall("missing", null, BoardQuery.ALL, 30, member), "error.notFound");
	}

	@Test
	void readsWithoutTheIndexesTheDatabaseLacks() {
		issue(hinata, "Still there", i -> i.state("in PROGRESS").assigneeId(member.getId())
				.assigneeIds(new ArrayList<>(List.of(member.getId()))).tags(new ArrayList<>(List.of("ui"))));
		// Read once with every index, so the reads know the indexes before they go missing.
		reader.facets(board.getId(), BoardQuery.Shape.WALL, member);
		List<String> dropped = List.of("board_by_state", "board_by_assignee", "board_by_label");
		Map<String, Document> keys = new HashMap<>();
		for (Document index : mongo.getCollection("issues").listIndexes()) {
			if (dropped.contains(index.getString("name"))) {
				keys.put(index.getString("name"), index.get("key", Document.class));
			}
		}
		dropped.forEach(name -> mongo.getCollection("issues").dropIndex(name));
		try {
			BoardReader.BoardWall wall = reader.wall(board.getId(), null, BoardQuery.ALL, 30, member);
			assertThat(column(wall, "In Progress").issues()).extracting(BoardCard::title).containsExactly("Still there");
			assertThat(titles(filtered(BoardQuery.of(null, List.of("In Progress"), null, null, List.of(member.getId()),
					null, null, null, null, null)))).containsExactly("Still there");

			BoardFacets facets = reader.facets(board.getId(), BoardQuery.Shape.WALL, member);
			assertThat(facets.assigneeIds()).containsExactly(member.getId());
			assertThat(facets.labels()).containsExactly("ui");
		}
		finally {
			keys.forEach((name, key) -> mongo.getCollection("issues").createIndex(key, new IndexOptions().name(name)));
		}
	}

	@Test
	void readsEveryPageAndCountOffTheIndexItNames() {
		// Two projects: a page in board order merges one scan per project instead of sorting.
		Project other = projects.save(Project.builder().key("OTH").name("Other").leadId(member.getId())
				.leadIds(new ArrayList<>(List.of(member.getId())))
				.memberIds(new ArrayList<>(List.of(member.getId()))).build());
		board.setProjectIds(new ArrayList<>(List.of(hinata.getId(), other.getId())));
		boards.save(board);
		Sprint sprint = sprints.save(Sprint.builder().boardId(board.getId()).name("Sprint 1").build());
		for (int i = 0; i < 90; i++) {
			int at = i;
			LocalDate day = LocalDate.parse("2026-09-01").plusDays(at);
			issue(at / 2 % 2 == 0 ? hinata : other, "Card " + i, b -> b.state(at % 3 == 0 ? "Done" : "Open")
					.startDate(at % 2 == 0 ? day : null)
					.dueDate(at % 2 == 1 && at % 3 == 1 ? day : null));
		}
		for (int i = 0; i < 30; i++) {
			issue(i % 2 == 0 ? hinata : other, "Sprint card " + i, b -> b.sprintId(sprint.getId()));
		}
		issue(hinata, "Kryptonite in the title", b -> { });

		List<Document> wall = profiled(() -> reader.wall(board.getId(), null, BoardQuery.ALL, 30, member));
		// The columns are counted off the index keys alone, and each page comes off the index in board order.
		assertThat(hinted(wall, "aggregate", "board_by_state")).singleElement()
				.satisfies(count -> assertThat(examined(count)).isZero());
		assertThat(hinted(wall, "find", "board_by_state")).hasSize(2).allSatisfy(page -> {
			assertThat(page.getString("planSummary")).contains("state: 1, rank: 1");
			assertThat(page.getBoolean("hasSortStage", false)).isFalse();
		});

		List<Document> search = profiled(() -> reader.wall(board.getId(), null,
				BoardQuery.of("KRYPTONITE", null, null, null, null, null, null, null, null, null), 30, member));
		// A search reads the index keys and fetches the one card it keeps.
		assertThat(hinted(search, "aggregate", "board_by_state")).singleElement()
				.satisfies(count -> assertThat(examined(count)).isZero());
		assertThat(hinted(search, "find", "board_by_state")).singleElement()
				.satisfies(page -> assertThat(examined(page)).isEqualTo(1));

		List<Document> sprintColumn = profiled(() -> reader.cards(board.getId(),
				new BoardReader.CardSource("Open", sprint.getId(), false, null), BoardQuery.ALL, 0, 10, false, member));
		// A sprint's column is counted off the keys too, which hold the state after the sprint.
		assertThat(hinted(sprintColumn, "aggregate", "board_by_sprint")).singleElement()
				.satisfies(count -> assertThat(examined(count)).isZero());
		assertThat(hinted(sprintColumn, "find", "board_by_sprint")).singleElement().satisfies(page -> {
			assertThat(page.getString("planSummary")).contains("sprintId: 1, rank: 1");
			assertThat(page.getBoolean("hasSortStage", false)).isFalse();
		});

		List<Document> backlog = profiled(() -> reader.cards(board.getId(),
				new BoardReader.CardSource(null, null, true, null), BoardQuery.ALL, 0, 10, false, member));
		assertThat(hinted(backlog, "aggregate", "board_by_sprint")).singleElement()
				.satisfies(count -> assertThat(examined(count)).isZero());

		List<Document> timeline = profiled(() -> reader.cards(board.getId(),
				new BoardReader.CardSource(null, null, false, true), BoardQuery.all(BoardQuery.Shape.TIMELINE), 0, 10,
				false, member));
		// The cards with a due date alone and those with a start date are counted off the keys, and each
		// part comes off the index in its order, merged across both projects without a sort.
		assertThat(hinted(timeline, "aggregate", "board_by_dates")).hasSize(2)
				.allSatisfy(count -> assertThat(examined(count)).isZero());
		assertThat(hinted(timeline, "find", "board_by_dates")).isNotEmpty().allSatisfy(page -> {
			assertThat(page.getString("planSummary")).contains("startDate: 1, dueDate: 1");
			assertThat(page.getBoolean("hasSortStage", false)).isFalse();
			assertThat(examined(page)).isLessThanOrEqualTo(10);
		});

		List<Document> undated = profiled(() -> reader.cards(board.getId(),
				new BoardReader.CardSource(null, null, false, false), BoardQuery.all(BoardQuery.Shape.TIMELINE), 0, 10,
				false, member));
		assertThat(hinted(undated, "find", "board_by_dates")).singleElement().satisfies(page -> {
			assertThat(page.getBoolean("hasSortStage", false)).isFalse();
			assertThat(examined(page)).isLessThanOrEqualTo(10);
		});
		// The timeline draws neither sub-tasks nor epics nor people, so its reads take its cards and nothing else.
		for (List<Document> read : List.of(timeline, undated)) {
			assertThat(read).allSatisfy(op -> assertThat(sent(op).get("hint")).isEqualTo("board_by_dates"));
		}

		// Every read of a request runs within the request's time, the parents and sub-tasks of its cards included.
		for (List<Document> read : List.of(wall, search, sprintColumn, backlog, timeline, undated)) {
			assertThat(read).isNotEmpty()
					.allSatisfy(op -> assertThat(limited(op)).as("time limit of %s", sent(op)).isTrue());
		}
	}

	@Test
	void readsAFilteredBoardOffTheIndexOfItsFilter() {
		Sprint sprint = sprints.save(Sprint.builder().boardId(board.getId()).name("Sprint 1").build());
		Issue epic = issue(hinata, "Epic", i -> i.type(Issue.Type.EPIC));
		for (int i = 0; i < 90; i++) {
			int at = i;
			String assignee = at % 10 == 0 ? member.getId() : admin.getId();
			issue(hinata, "Card " + i, b -> b.state(at % 3 == 0 ? "Done" : "Open")
					.assigneeId(assignee).assigneeIds(new ArrayList<>(List.of(assignee)))
					.reporterId(at % 9 == 0 ? member.getId() : admin.getId())
					.tags(new ArrayList<>(List.of(at % 15 == 0 ? "rare" : "common")))
					.parentId(at % 30 == 0 ? epic.getId() : null)
					.sprintId(at % 6 == 0 ? sprint.getId() : null)
					.startDate(at % 2 == 0 ? LocalDate.parse("2026-09-01").plusDays(at) : null));
		}

		// One person's cards are counted off the keys, and each column's page comes in board order
		// having read that person's cards alone.
		List<Document> byPerson = profiled(() -> reader.wall(board.getId(), null,
				BoardQuery.of(null, null, null, null, List.of(member.getId()), null, null, null, null, null), 30, member));
		assertThat(hinted(byPerson, "aggregate", "board_by_assignee")).singleElement()
				.satisfies(count -> assertThat(examined(count)).isZero());
		assertThat(hinted(byPerson, "find", "board_by_assignee")).hasSize(2).allSatisfy(page -> {
			assertThat(page.getBoolean("hasSortStage", false)).isFalse();
			assertThat(examined(page)).isLessThanOrEqualTo(9);
		});

		// An epic's children come off the parent index: its three cards are read, not the board's.
		List<Document> byEpic = profiled(() -> reader.wall(board.getId(), null,
				BoardQuery.of(null, null, null, null, null, null, null, null, List.of(epic.getId()), null), 30, member));
		assertThat(hinted(byEpic, "aggregate", "parent_number")).singleElement()
				.satisfies(count -> assertThat(examined(count)).isEqualTo(3));

		List<Document> byReporter = profiled(() -> reader.wall(board.getId(), null,
				BoardQuery.of(null, null, null, null, null, List.of(member.getId()), null, null, null, null), 30, member));
		assertThat(hinted(byReporter, "aggregate", "board_by_reporter")).singleElement()
				.satisfies(count -> assertThat(examined(count)).isZero());

		List<Document> byLabel = profiled(() -> reader.wall(board.getId(), null,
				BoardQuery.of(null, null, null, null, null, null, List.of("rare"), null, null, null), 30, member));
		assertThat(hinted(byLabel, "aggregate", "board_by_label")).singleElement()
				.satisfies(count -> assertThat(examined(count)).isZero());

		List<Document> bySprint = profiled(() -> reader.wall(board.getId(), null,
				BoardQuery.of(null, null, null, null, null, null, null, List.of(sprint.getId()), null, null), 30, member));
		assertThat(hinted(bySprint, "aggregate", "board_by_sprint")).singleElement()
				.satisfies(count -> assertThat(examined(count)).isZero());

		// A sprint's timeline reads the sprint's cards and sorts them, not every dated card of the board.
		List<Document> sprintTimeline = profiled(() -> reader.cards(board.getId(),
				new BoardReader.CardSource(null, sprint.getId(), false, true), BoardQuery.all(BoardQuery.Shape.TIMELINE),
				0, 10, false, member));
		assertThat(hinted(sprintTimeline, "find", "board_by_sprint")).singleElement()
				.satisfies(page -> assertThat(examined(page)).isLessThanOrEqualTo(15));
	}

	// --- helpers --------------------------------------------------------------------

	/** What the database did for [read]: every operation on the issues and their links, as its profiler saw it. */
	private List<Document> profiled(Runnable read) {
		MongoDatabase database = mongo.getDb();
		database.runCommand(new Document("profile", 0));
		database.getCollection("system.profile").drop();
		database.runCommand(new Document("profile", 2));
		try {
			read.run();
		}
		finally {
			database.runCommand(new Document("profile", 0));
		}
		List<String> namespaces = List.of(database.getName() + ".issues", database.getName() + ".issue_links");
		return database.getCollection("system.profile").find(new Document("ns", new Document("$in", namespaces)))
				.into(new ArrayList<>());
	}

	/** Whether [op] ran within a time limit; a getMore runs within the limit of the read it continues. */
	private static boolean limited(Document op) {
		Document continued = op.get("originatingCommand", Document.class);
		return (continued != null ? continued : sent(op)).containsKey("maxTimeMS");
	}

	/** The operations of [command] that were sent with the hint [index]. */
	private static List<Document> hinted(List<Document> profile, String command, String index) {
		return profile.stream()
				.filter(op -> sent(op).containsKey(command) && index.equals(String.valueOf(sent(op).get("hint"))))
				.toList();
	}

	private static Document sent(Document op) {
		Document command = op.get("command", Document.class);
		return command == null ? new Document() : command;
	}

	private static long examined(Document op) {
		return ((Number) op.get("docsExamined")).longValue();
	}

	private Issue issue(Project project, String title, Consumer<Issue.IssueBuilder> more) {
		number++;
		Issue.IssueBuilder builder = Issue.builder().projectId(project.getId())
				.readableId(project.getKey() + "-" + number).numberInProject(number).title(title).state("Open")
				.type(Issue.Type.TASK).priority(Issue.Priority.NORMAL).rank(number)
				.assigneeIds(new ArrayList<>()).tags(new ArrayList<>()).watcherIds(new ArrayList<>())
				.dependsOnIds(new ArrayList<>());
		more.accept(builder);
		return issues.save(builder.build());
	}

	private BoardReader.BoardCardPage search(String text) {
		return filtered(BoardQuery.of(text, null, null, null, null, null, null, null, null, null));
	}

	/** The cards [query] keeps of a board whose cards are all in no sprint: its backlog. */
	private BoardReader.BoardCardPage filtered(BoardQuery query) {
		return reader.cards(board.getId(), new BoardReader.CardSource(null, null, true, null), query, 0, 30, false,
				member);
	}

	private static BoardReader.WallColumn column(BoardReader.BoardWall wall, String name) {
		return wall.columns().stream().filter(column -> column.name().equals(name)).findFirst().orElseThrow();
	}

	private static BoardCard card(BoardReader.BoardCardPage page, Issue issue) {
		return page.content().stream().filter(card -> card.id().equals(issue.getId())).findFirst().orElseThrow();
	}

	private static List<String> titles(BoardReader.BoardCardPage page) {
		return page.content().stream().map(BoardCard::title).toList();
	}

	private static void assertRefused(ThrowingCallable read, String messageKey) {
		assertThatThrownBy(read).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.getMessageKey()).isEqualTo(messageKey));
	}
}
