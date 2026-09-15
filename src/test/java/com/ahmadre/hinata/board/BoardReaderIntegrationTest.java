package com.ahmadre.hinata.board;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A board read in pages against a real MongoDB.
 *
 * <p>Everything here is a property of queries: which issues a column counts, in which order a page
 * comes, what a search or a filter keeps, and which projects a viewer's read reaches. None of that
 * can be observed against a stubbed template, and a filter that returns nothing would pass every
 * test that only checks what is absent, so each test also checks what must be there.
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
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

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

	private User member;
	private User outsider;
	private User admin;
	private Project hinata;
	private Project secret;
	private AgileBoard board;
	private int number;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "projects", "teams", "users", "agile_boards", "sprints")) {
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

		assertThat(titles(search("LOGIN"))).containsExactly(login.getTitle());
		assertThat(titles(search("mobile"))).containsExactly(labelled.getTitle());
		assertThat(titles(search(login.getReadableId().toLowerCase()))).containsExactly(login.getTitle());
		// The text is matched literally: a dot is a dot, not any character.
		assertThat(titles(search("a.b"))).containsExactly(dotted.getTitle());
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
		assertThat(filtered(BoardQuery.of(null, List.of("NOPE"), null, null, null, null, null, null, null, null))
				.totalElements()).isZero();
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
		assertThat(titles(filtered(BoardQuery.of(null, null, null, null, null, null, null,
				List.of(BoardQuery.NO_SPRINT), null, null)))).containsExactly(waiting.getTitle());

		board.setActiveSprintId(sprint.getId());
		boards.save(board);
		BoardReader.BoardWall wall = reader.wall(board.getId(), null, BoardQuery.ALL, 30, member);
		assertThat(wall.sprintId()).isEqualTo(sprint.getId());
		assertThat(column(wall, "Open").issues()).extracting(BoardCard::title).containsExactly(planned.getTitle());

		assertRefused(() -> reader.cards(board.getId(), new BoardReader.CardSource(null, sprint.getId(), true, null),
				BoardQuery.ALL, 0, 30, false, member), "error.validationFailed");
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
		BoardQuery planning = BoardQuery.of(null, null, null, null, null, null, null, null, null, "planning");

		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource(null, sprint.getId(), false, null),
				planning, 0, 30, false, member)))
				.containsExactlyInAnyOrder("Epic in the sprint", "Story in the sprint", "Sub-task in the sprint");
	}

	@Test
	void resolvesEachCardsEpicPeopleAndSubTasksForThePageAtOnce() {
		Issue epic = issue(hinata, "Epic", i -> i.type(Issue.Type.EPIC));
		Issue story = issue(hinata, "Story", i -> i.type(Issue.Type.STORY).parentId(epic.getId())
				.assigneeId(member.getId()).assigneeIds(new ArrayList<>(List.of(member.getId()))));
		Issue child = issue(hinata, "Child", i -> i.type(Issue.Type.SUBTASK).parentId(story.getId()).state("Done"));
		issue(hinata, "Second child", i -> i.type(Issue.Type.SUBTASK).parentId(story.getId()));
		BoardQuery subtasks = BoardQuery.of(null, null, null, null, null, null, null, null, null, "subtasks");

		BoardReader.BoardCardPage page = reader.cards(board.getId(), new BoardReader.CardSource(null, null, false,
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
		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource(null, null, false, null), underEpic,
				0, 30, false, member))).containsExactlyInAnyOrder("Story", "Child", "Second child");
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
		assertRefused(() -> reader.facets(board.getId(), null, false, outsider), "error.accessDenied");

		hinata.setArchived(true);
		projects.save(hinata);
		assertRefused(() -> reader.wall(board.getId(), null, BoardQuery.ALL, 30, member), "error.accessDenied");
	}

	@Test
	void summarizesEveryCardOfTheQueryByStateAndResolution() {
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
				new BoardReader.StateSummary("Done", true, 1, 5),
				new BoardReader.StateSummary("In Review", false, 1, 0),
				new BoardReader.StateSummary("Open", false, 40, 80));
	}

	@Test
	void gathersTheFacetsOverEveryCardOfTheBoard() {
		issue(hinata, "One", i -> i.assigneeId(member.getId()).assigneeIds(new ArrayList<>(List.of(member.getId())))
				.reporterId(outsider.getId()).tags(new ArrayList<>(List.of("ui", "api"))).type(Issue.Type.BUG));
		issue(hinata, "Two", i -> i.state("Done").priority(Issue.Priority.MINOR).tags(new ArrayList<>(List.of("ui"))));
		Issue epic = issue(hinata, "Epic", i -> i.type(Issue.Type.EPIC));

		BoardReader.BoardFacets facets = reader.facets(board.getId(), null, false, member);

		assertThat(facets.assigneeIds()).containsExactly(member.getId());
		assertThat(facets.reporterIds()).containsExactly(outsider.getId());
		assertThat(facets.labels()).containsExactly("api", "ui");
		assertThat(facets.states()).containsExactly("Done", "Open");
		assertThat(facets.types()).containsExactly("BUG", "TASK");
		assertThat(facets.priorities()).containsExactly("MINOR", "NORMAL");
		assertThat(facets.epics()).extracting(BoardRef::id).containsExactly(epic.getId());
		assertThat(facets.users()).extracting(user -> user.id())
				.containsExactlyInAnyOrder(member.getId(), outsider.getId());
	}

	@Test
	void putsEpicsOnTheTimelineAndSplitsItByDate() {
		issue(hinata, "Later", i -> i.startDate(LocalDate.parse("2026-10-01")));
		issue(hinata, "Sooner epic", i -> i.type(Issue.Type.EPIC).startDate(LocalDate.parse("2026-09-01")));
		issue(hinata, "Undated", i -> { });
		issue(hinata, "Dated sub-task", i -> i.type(Issue.Type.SUBTASK).dueDate(LocalDate.parse("2026-09-10")));
		BoardQuery timeline = BoardQuery.of(null, null, null, null, null, null, null, null, null, "timeline");

		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource(null, null, false, true), timeline, 0,
				30, false, member))).containsExactly("Sooner epic", "Later");
		assertThat(titles(reader.cards(board.getId(), new BoardReader.CardSource(null, null, false, false), timeline,
				0, 30, false, member))).containsExactly("Undated");
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
		assertRefused(() -> reader.cards(board.getId(), new BoardReader.CardSource("Nowhere", null, false, null),
				BoardQuery.ALL, 0, 30, false, member), "error.board.unknownColumn");
		assertRefused(() -> reader.wall("missing", null, BoardQuery.ALL, 30, member), "error.notFound");
	}

	@Test
	void readsAColumnsPageAndCountOffTheBoardIndexes() {
		Sprint sprint = sprints.save(Sprint.builder().boardId(board.getId()).name("Sprint 1").build());
		for (int i = 0; i < 300; i++) {
			int at = i;
			issue(hinata, "Card " + i, b -> b.state(at % 3 == 0 ? "Done" : "Open"));
		}
		for (int i = 0; i < 60; i++) {
			issue(hinata, "Sprint card " + i, b -> b.sprintId(sprint.getId()));
		}
		// The shape BoardReader asks in: the board's projects, active cards, the wall's types.
		List<Document> wall = List.of(
				new Document("projectId", new Document("$in", List.of(hinata.getId()))),
				new Document("archived", false),
				new Document("type", new Document("$nin", List.of("EPIC", "SUBTASK"))));
		Document byRank = new Document("rank", 1).append("_id", 1);

		Document columnPage = mongo.getCollection("issues")
				.find(and(wall, new Document("state", new Document("$in", List.of("Open", "OPEN", "open")))))
				.sort(byRank).limit(30).explain();
		assertThat(indexesIn(columnPage)).containsOnly("board_column");
		assertThat(stagesIn(columnPage)).doesNotContain("SORT", "COLLSCAN");

		Document sprintPage = mongo.getCollection("issues")
				.find(and(wall, new Document("sprintId", sprint.getId())))
				.sort(byRank).limit(30).explain();
		assertThat(indexesIn(sprintPage)).containsOnly("board_sprint");
		assertThat(stagesIn(sprintPage)).doesNotContain("SORT", "COLLSCAN");

		// Counting the columns reads the index alone, not one document.
		Document counts = mongo.getCollection("issues").aggregate(List.of(
				new Document("$match", and(wall)),
				new Document("$group", new Document("_id", "$state").append("count", new Document("$sum", 1)))))
				.explain();
		assertThat(indexesIn(counts)).containsOnly("board_column");
		assertThat(stagesIn(counts)).doesNotContain("FETCH", "COLLSCAN");
	}

	// --- helpers --------------------------------------------------------------------

	private static Document and(List<Document> parts) {
		return new Document("$and", parts);
	}

	private static Document and(List<Document> parts, Document more) {
		List<Document> all = new ArrayList<>(parts);
		all.add(more);
		return and(all);
	}

	/** Every stage name of the winning plan, however the server nests it. */
	private static List<String> stagesIn(Document explained) {
		List<String> stages = new ArrayList<>();
		walkPlan(winningPlan(explained), plan -> {
			if (plan.getString("stage") != null) {
				stages.add(plan.getString("stage"));
			}
		});
		return stages;
	}

	private static List<String> indexesIn(Document explained) {
		List<String> indexes = new ArrayList<>();
		walkPlan(winningPlan(explained), plan -> {
			if (plan.getString("indexName") != null) {
				indexes.add(plan.getString("indexName"));
			}
		});
		return indexes;
	}

	private static Document winningPlan(Document explained) {
		Document planner = explained.get("queryPlanner", Document.class);
		if (planner == null) {
			// An aggregation explains its first stage, where the query runs.
			for (Document stage : explained.getList("stages", Document.class, List.of())) {
				Document cursor = stage.get("$cursor", Document.class);
				if (cursor != null) {
					planner = cursor.get("queryPlanner", Document.class);
					break;
				}
			}
		}
		assertThat(planner).as("query planner in %s", explained.toJson()).isNotNull();
		Document winning = planner.get("winningPlan", Document.class);
		return winning.containsKey("queryPlan") ? winning.get("queryPlan", Document.class) : winning;
	}

	private static void walkPlan(Document plan, Consumer<Document> visit) {
		visit.accept(plan);
		Document input = plan.get("inputStage", Document.class);
		if (input != null) {
			walkPlan(input, visit);
		}
		for (Document each : plan.getList("inputStages", Document.class, List.of())) {
			walkPlan(each, visit);
		}
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

	private BoardReader.BoardCardPage filtered(BoardQuery query) {
		return reader.cards(board.getId(), new BoardReader.CardSource(null, null, false, null), query, 0, 30, false,
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
