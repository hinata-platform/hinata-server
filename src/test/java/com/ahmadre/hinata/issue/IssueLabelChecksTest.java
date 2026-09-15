package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.board.AgileBoardRepository;
import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.timetracking.WorkItemRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Creating an issue and editing one both hold to {@link IssueLabels}, whoever writes it, and the labels an
 * issue brings join its project's vocabulary without rewriting the project.
 */
class IssueLabelChecksTest {

	private IssueRepository issues;
	private ProjectService projects;
	private MongoTemplate mongo;
	private IssueService service;
	private User user;

	@BeforeEach
	void setUp() {
		issues = mock(IssueRepository.class);
		projects = mock(ProjectService.class);
		mongo = mock(MongoTemplate.class);
		user = User.builder().id("u1").email("a@b.c").roles(Set.of(Role.MEMBER)).build();
		when(projects.get("p1")).thenReturn(Project.builder().id("p1").key("HIN").name("Hinata")
				.memberIds(new ArrayList<>(List.of("u1"))).build());
		service = new IssueService(issues, mock(IssueCommentRepository.class), mock(IssueActivityRepository.class),
				mock(IssueLinkRepository.class), mock(IssueLinkEvents.class), mock(CommentEvents.class), projects,
				mock(NotificationService.class), mock(StorageService.class), mock(WorkItemRepository.class),
				mock(AuditService.class), mongo, mock(AgileBoardRepository.class),
				mock(SprintRepository.class), mock(UserRepository.class));
	}

	@Test
	void refusesAnIssueWithMoreLabelsThanItMayCarryBeforeItTakesANumber() {
		Issue issue = Issue.builder().projectId("p1").title("Too many").tags(labels(IssueLabels.MAX_LABELS + 1)).build();

		assertThatThrownBy(() -> service.create(issue, user)).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.getMessageKey()).isEqualTo("error.issue.labels"));
		verify(projects, never()).nextIssueNumber(any());
		verify(issues, never()).save(any());
	}

	@Test
	void refusesAnEditThatGivesAnIssueALabelTooLong() {
		Issue stored = Issue.builder().id("i1").projectId("p1").title("Stored").state("Open")
				.tags(new ArrayList<>(List.of("api"))).build();
		when(issues.findById("i1")).thenReturn(Optional.of(stored));
		String tooLong = "x".repeat(IssueLabels.MAX_LENGTH + 1);

		assertThatThrownBy(() -> service.update("i1", issue -> issue.setTags(new ArrayList<>(List.of("api", tooLong))),
				user)).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.getMessageKey()).isEqualTo("error.issue.labels"));
		verify(issues, never()).save(any());
	}

	@Test
	void pushesEachNewLabelOntoItsProjectWhileItLacksItAndHasRoom() {
		Project project = Project.builder().id("p1").labels(new ArrayList<>(List.of(label("bug")))).build();

		service.mergeProjectLabels(project, List.of("bug", "ux"));

		ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
		verify(mongo).updateFirst(query.capture(), any(UpdateDefinition.class), eq(Project.class));
		assertThat(query.getValue().getQueryObject().toJson())
				.contains("labels.name").contains("labels." + (Project.MAX_LABELS - 1));
		assertThat(project.labelNames()).containsExactly("bug", "ux");
		verify(projects, never()).save(any());
	}

	@Test
	void leavesAFullVocabularyAsItIs() {
		List<Project.Label> full = new ArrayList<>(IntStream.range(0, Project.MAX_LABELS)
				.mapToObj(i -> label("label-" + i)).toList());
		Project project = Project.builder().id("p1").labels(full).build();

		service.mergeProjectLabels(project, List.of("one more"));

		verify(mongo, never()).updateFirst(any(Query.class), any(UpdateDefinition.class), eq(Project.class));
		assertThat(project.getLabels()).hasSize(Project.MAX_LABELS);
	}

	private static Project.Label label(String name) {
		return Project.Label.builder().id(name).name(name).hue(0).build();
	}

	private static List<String> labels(int count) {
		return new ArrayList<>(IntStream.range(0, count).mapToObj(i -> "label-" + i).toList());
	}
}
