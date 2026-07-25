package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.board.AgileBoard;
import com.ahmadre.hinata.board.AgileBoardRepository;
import com.ahmadre.hinata.board.Sprint;
import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the parts of a cross-project move that would silently corrupt data if
 * they regressed: re-keying, the status mapping, and what detaches on the way.
 */
class IssueMoveServiceTest {

	private static final String VOR = "p-vorstand";
	private static final String ERSTI = "p-ersti";

	private IssueRepository issues;
	private IssueService issueService;
	private ProjectService projects;
	private AgileBoardRepository boards;
	private SprintRepository sprints;
	private IssueMoveService service;

	private final Map<String, Issue> store = new HashMap<>();
	private final AtomicLong erstiCounter = new AtomicLong(6);

	private User user;

	@BeforeEach
	void setUp() {
		issues = mock(IssueRepository.class);
		issueService = mock(IssueService.class);
		projects = mock(ProjectService.class);
		boards = mock(AgileBoardRepository.class);
		sprints = mock(SprintRepository.class);
		IssueActivityRepository activities = mock(IssueActivityRepository.class);
		AuditService audit = mock(AuditService.class, RETURNS_DEEP_STUBS);
		MongoTemplate mongo = mock(MongoTemplate.class);

		user = User.builder().id("u1").email("a@b.c").roles(java.util.Set.of(Role.MEMBER)).build();

		when(projects.get(VOR)).thenReturn(vorstand());
		when(projects.get(ERSTI)).thenReturn(ersti());
		when(projects.nextIssueNumber(ERSTI)).thenAnswer(i -> erstiCounter.incrementAndGet());
		when(issueService.get(anyString())).thenAnswer(i -> store.get(i.getArgument(0, String.class)));
		when(issueService.findOrNull(anyString()))
				.thenAnswer(i -> store.get(i.getArgument(0, String.class)));
		when(issues.findByParentId(anyString())).thenReturn(List.of());
		when(issues.save(any(Issue.class))).thenAnswer(i -> i.getArgument(0));
		when(sprints.findById(anyString())).thenReturn(Optional.empty());

		service = new IssueMoveService(issues, activities, issueService, projects, boards,
				sprints, audit, mongo);
	}

	private static Project project(String id, String key, List<String> states, List<Integer> hues,
			List<String> resolved) {
		List<Project.WorkflowState> workflow = new ArrayList<>();
		for (int i = 0; i < states.size(); i++) {
			workflow.add(Project.WorkflowState.builder()
					.id(id + i).name(states.get(i)).hue(hues.get(i)).build());
		}
		return Project.builder()
				.id(id).key(key).name(key)
				.workflowStates(workflow)
				.resolvedStates(new ArrayList<>(resolved))
				.memberIds(new ArrayList<>(List.of("u1")))
				.labels(new ArrayList<>())
				.issueCounter(6)
				.build();
	}

	private static Project vorstand() {
		return project(VOR, "VOR", List.of("Open", "In Progress", "Done"),
				List.of(250, 70, 155), List.of("Done"));
	}

	/** Same workflow, translated — so the hue rung of the mapping is exercised. */
	private static Project ersti() {
		return project(ERSTI, "ERSTI", List.of("Neu", "In Arbeit", "Fertig"),
				List.of(250, 70, 155), List.of("Fertig"));
	}

	private Issue issue(String id, String state) {
		Issue built = Issue.builder()
				.id(id)
				.projectId(VOR)
				.numberInProject(42)
				.readableId("VOR-42")
				.title("Raum buchen")
				.type(Issue.Type.TASK)
				.state(state)
				.tags(new ArrayList<>())
				.assigneeIds(new ArrayList<>())
				.formerReadableIds(new ArrayList<>())
				.build();
		store.put(id, built);
		return built;
	}

	// --- preflight -----------------------------------------------------------

	@Test
	void preflightSuggestsTheTranslatedStatusAndPreviewsTheNewId() {
		issue("i1", "In Progress");

		IssueMoveService.Preflight preflight =
				service.preflight(List.of("i1"), ERSTI, false, user);

		assertThat(preflight.stateMappings()).hasSize(1);
		IssueMoveService.StateMapping mapping = preflight.stateMappings().get(0);
		assertThat(mapping.fromState()).isEqualTo("In Progress");
		assertThat(mapping.suggestedTo()).isEqualTo("In Arbeit");
		assertThat(mapping.existsInTarget()).isFalse();
		assertThat(mapping.issueCount()).isEqualTo(1);

		assertThat(preflight.issues()).singleElement()
				.satisfies(preview -> {
					assertThat(preview.readableId()).isEqualTo("VOR-42");
					assertThat(preview.nextReadableId()).isEqualTo("ERSTI-7");
					assertThat(preview.pulledIn()).isFalse();
				});
	}

	@Test
	void preflightCollapsesTheMappingToOneRowPerDistinctStatus() {
		issue("i1", "In Progress");
		issue("i2", "In Progress");
		issue("i3", "Open");

		IssueMoveService.Preflight preflight =
				service.preflight(List.of("i1", "i2", "i3"), ERSTI, false, user);

		assertThat(preflight.stateMappings()).hasSize(2);
		assertThat(preflight.stateMappings())
				.filteredOn(m -> m.fromState().equals("In Progress"))
				.singleElement()
				.satisfies(m -> assertThat(m.issueCount()).isEqualTo(2));
	}

	@Test
	void preflightWarnsThatAnEpicsChildrenStayBehind() {
		Issue epic = issue("e1", "Open");
		epic.setType(Issue.Type.EPIC);
		Issue child = Issue.builder().id("c1").projectId(VOR).parentId("e1")
				.readableId("VOR-43").state("Open").type(Issue.Type.STORY)
				.tags(new ArrayList<>()).assigneeIds(new ArrayList<>()).build();
		store.put("c1", child);
		when(issues.findByParentId("e1")).thenReturn(List.of(child));

		IssueMoveService.Preflight preflight =
				service.preflight(List.of("e1"), ERSTI, false, user);

		assertThat(preflight.warnings())
				.anySatisfy(w -> assertThat(w.code())
						.isEqualTo(IssueMoveService.WarningCode.EPIC_CHILDREN_STAY));
		// Not opted in → the child does not travel.
		assertThat(preflight.issues()).extracting(IssueMoveService.MovePreview::issueId)
				.containsExactly("e1");
	}

	@Test
	void optingInPullsTheEpicsChildrenAlongAndMarksThemAsCarried() {
		Issue epic = issue("e1", "Open");
		epic.setType(Issue.Type.EPIC);
		Issue child = Issue.builder().id("c1").projectId(VOR).parentId("e1")
				.readableId("VOR-43").state("Done").type(Issue.Type.STORY)
				.tags(new ArrayList<>()).assigneeIds(new ArrayList<>()).build();
		store.put("c1", child);
		when(issues.findByParentId("e1")).thenReturn(List.of(child));

		IssueMoveService.Preflight preflight =
				service.preflight(List.of("e1"), ERSTI, true, user);

		assertThat(preflight.issues()).extracting(IssueMoveService.MovePreview::issueId)
				.containsExactly("e1", "c1");
		assertThat(preflight.issues().get(1).pulledIn()).isTrue();
		assertThat(preflight.warnings())
				.noneSatisfy(w -> assertThat(w.code())
						.isEqualTo(IssueMoveService.WarningCode.EPIC_CHILDREN_STAY));
	}

	@Test
	void aSubtaskCannotBeMovedWithoutItsParent() {
		Issue subtask = issue("s1", "Open");
		subtask.setType(Issue.Type.SUBTASK);
		subtask.setParentId("p1");

		assertThatThrownBy(() -> service.preflight(List.of("s1"), ERSTI, false, user))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.issue.moveSubtaskNeedsParent");
	}

	@Test
	void movingIntoTheProjectItAlreadyLivesInIsRejected() {
		issue("i1", "Open");

		assertThatThrownBy(() -> service.preflight(List.of("i1"), VOR, false, user))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.issue.moveSameProject");
	}

	@Test
	void anEmptySelectionIsRejected() {
		assertThatThrownBy(() -> service.preflight(List.of(), ERSTI, false, user))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.issue.moveNoIssues");
	}

	// --- move ----------------------------------------------------------------

	@Test
	void moveReKeysTheIssueAndRemembersTheOldIdForRedirects() {
		issue("i1", "In Progress");

		List<Issue> moved = service.move(List.of("i1"), ERSTI,
				Map.of("In Progress", "In Arbeit"), false, true, user);

		assertThat(moved).singleElement().satisfies(issue -> {
			assertThat(issue.getProjectId()).isEqualTo(ERSTI);
			assertThat(issue.getReadableId()).isEqualTo("ERSTI-7");
			assertThat(issue.getNumberInProject()).isEqualTo(7);
			assertThat(issue.getState()).isEqualTo("In Arbeit");
			assertThat(issue.getFormerReadableIds()).containsExactly("VOR-42");
		});
	}

	@Test
	void anUnmappedStatusFallsBackToTheSuggestionRatherThanFailing() {
		issue("i1", "In Progress");

		List<Issue> moved = service.move(List.of("i1"), ERSTI, Map.of(), false, true, user);

		assertThat(moved.get(0).getState()).isEqualTo("In Arbeit");
	}

	@Test
	void aStatusOutsideTheTargetWorkflowIsRejectedRatherThanWritten() {
		issue("i1", "In Progress");

		assertThatThrownBy(() -> service.move(List.of("i1"), ERSTI,
				Map.of("In Progress", "Nonsense"), false, true, user))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.issue.moveStateUnmapped");
	}

	@Test
	void landingInAResolvedStatusStampsResolvedAt() {
		issue("i1", "Open");

		List<Issue> moved = service.move(List.of("i1"), ERSTI,
				Map.of("Open", "Fertig"), false, true, user);

		assertThat(moved.get(0).getResolvedAt()).isNotNull();
	}

	@Test
	void leavingAResolvedStatusClearsResolvedAt() {
		Issue open = issue("i1", "Done");
		open.setResolvedAt(java.time.Instant.now());

		List<Issue> moved = service.move(List.of("i1"), ERSTI,
				Map.of("Done", "Neu"), false, true, user);

		assertThat(moved.get(0).getResolvedAt()).isNull();
	}

	@Test
	void theSprintIsDroppedWhenItsBoardDoesNotSpanTheTarget() {
		Issue planned = issue("i1", "Open");
		planned.setSprintId("sp1");
		when(sprints.findById("sp1")).thenReturn(Optional.of(
				Sprint.builder().id("sp1").boardId("b1").name("Sprint 3").build()));
		when(boards.findById("b1")).thenReturn(Optional.of(
				AgileBoard.builder().id("b1").name("Vorstand")
						.projectIds(new ArrayList<>(List.of(VOR))).build()));

		List<Issue> moved = service.move(List.of("i1"), ERSTI,
				Map.of("Open", "Neu"), false, true, user);

		assertThat(moved.get(0).getSprintId()).isNull();
	}

	@Test
	void theSprintSurvivesWhenItsBoardAlsoSpansTheTarget() {
		Issue planned = issue("i1", "Open");
		planned.setSprintId("sp1");
		when(sprints.findById("sp1")).thenReturn(Optional.of(
				Sprint.builder().id("sp1").boardId("b1").name("Gemeinsam").build()));
		when(boards.findById("b1")).thenReturn(Optional.of(
				AgileBoard.builder().id("b1").name("Vorstand + Ersti")
						.projectIds(new ArrayList<>(List.of(VOR, ERSTI))).build()));

		List<Issue> moved = service.move(List.of("i1"), ERSTI,
				Map.of("Open", "Neu"), false, true, user);

		assertThat(moved.get(0).getSprintId()).isEqualTo("sp1");
	}

	@Test
	void aParentLinkIsCutWhenTheParentStaysBehind() {
		Issue child = issue("i1", "Open");
		child.setParentId("epic-elsewhere");
		store.put("epic-elsewhere", Issue.builder().id("epic-elsewhere").projectId(VOR)
				.readableId("VOR-1").state("Open").type(Issue.Type.EPIC)
				.tags(new ArrayList<>()).assigneeIds(new ArrayList<>()).build());

		List<Issue> moved = service.move(List.of("i1"), ERSTI,
				Map.of("Open", "Neu"), false, true, user);

		assertThat(moved.get(0).getParentId()).isNull();
	}

	@Test
	void movingMoreThanTheBatchCeilingIsRejected() {
		List<String> tooMany = new ArrayList<>();
		for (int i = 0; i <= IssueMoveService.MAX_BATCH; i++) tooMany.add("i" + i);

		assertThatThrownBy(() -> service.preflight(tooMany, ERSTI, false, user))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("error.issue.moveTooMany");
	}
}
