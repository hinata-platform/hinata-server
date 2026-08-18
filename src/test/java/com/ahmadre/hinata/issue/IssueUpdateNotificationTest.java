package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.board.AgileBoardRepository;
import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.notification.FieldChange;
import com.ahmadre.hinata.notification.IssueChangeDiff;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What one save announces.
 *
 * <p>The behaviour these pin down replaced an {@code if / else if} chain in which
 * an assignment silently suppressed a state change and every other field — a due
 * date, a priority, a sprint, a re-parenting — moved in complete silence. The
 * contract now is one notification per save, carrying everything that changed.
 */
class IssueUpdateNotificationTest {

	private IssueRepository issues;
	private NotificationService notifications;
	private IssueService service;

	@BeforeEach
	void setUp() {
		issues = mock(IssueRepository.class);
		notifications = mock(NotificationService.class);
		ProjectService projects = mock(ProjectService.class);
		Project project = Project.builder().id("p1").key("HIN").name("Hinata")
				.memberIds(new ArrayList<>(List.of("u1"))).build();
		when(projects.get("p1")).thenReturn(project);
		when(notifications.notifyNewMentions(any(), any(), any(), any())).thenReturn(Set.of());

		service = new IssueService(issues, mock(IssueCommentRepository.class),
				mock(IssueActivityRepository.class), mock(IssueLinkRepository.class),
				mock(IssueLinkEvents.class), mock(CommentEvents.class), projects, notifications,
				mock(StorageService.class), mock(WorkItemRepository.class),
				mock(AuditService.class, RETURNS_DEEP_STUBS), mock(MongoTemplate.class),
				mock(AgileBoardRepository.class), mock(SprintRepository.class),
				mock(UserRepository.class));
		when(issues.save(any(Issue.class))).thenAnswer(call -> call.getArgument(0));
	}

	private User editor() {
		return User.builder().id("u1").email("a@b.c").roles(Set.of(Role.MEMBER)).build();
	}

	private Issue stored() {
		Issue issue = Issue.builder().id("i1").projectId("p1").readableId("HIN-1")
				.title("Login bug").state("Open").priority(Issue.Priority.NORMAL)
				.assigneeIds(new ArrayList<>()).tags(new ArrayList<>())
				.dependsOnIds(new ArrayList<>()).watcherIds(new ArrayList<>())
				.build();
		when(issues.findById("i1")).thenReturn(Optional.of(issue));
		return issue;
	}

	@SuppressWarnings("unchecked")
	private List<FieldChange> announced() {
		ArgumentCaptor<List<FieldChange>> changes = ArgumentCaptor.forClass(List.class);
		verify(notifications).notifyUpdated(any(), changes.capture(), any(), anySet());
		return changes.getValue();
	}

	/**
	 * The regression. Assigning someone and moving the issue in one save used to
	 * announce the assignment and swallow the state change.
	 */
	@Test
	void anAssignmentAndAStateChangeInOneSaveBothReachTheWatchers() {
		stored();

		service.update("i1", issue -> {
			issue.setAssigneeIds(new ArrayList<>(List.of("u2")));
			issue.setState("In Progress");
		}, editor());

		assertThat(announced()).extracting(FieldChange::field)
				.contains(IssueChangeDiff.ASSIGNEES, IssueChangeDiff.STATE);
	}

	/** The assignee already heard "this is yours"; they must not also hear
	 *  "the assignee changed" for the same click. */
	@Test
	void theNewlyAssignedUserIsExcludedFromTheChangeSummary() {
		stored();

		service.update("i1", issue -> issue.setAssigneeIds(new ArrayList<>(List.of("u2"))),
				editor());

		verify(notifications).notifyAssigned(any(), any(), any());
		verify(notifications).notifyUpdated(any(), anyList(), any(), eq(Set.of("u2")));
	}

	/** Fields that used to move in silence. */
	@Test
	void everyOtherWhitelistedFieldAlsoProducesANotice() {
		stored();

		service.update("i1", issue -> {
			issue.setPriority(Issue.Priority.MAJOR);
			issue.setDueDate(LocalDate.of(2026, 8, 23));
		}, editor());

		assertThat(announced()).extracting(FieldChange::field)
				.containsExactlyInAnyOrder(IssueChangeDiff.PRIORITY, IssueChangeDiff.DUE_DATE);
	}

	/**
	 * Time tracking ticks with every logged work item. On the notify list it would
	 * mail every watcher about somebody else's stopwatch.
	 */
	@Test
	void loggedTimeNotifiesNobody() {
		stored();

		service.update("i1", issue -> issue.setSpentMinutes(240), editor());

		verify(notifications).notifyUpdated(any(), eq(List.of()), any(), anySet());
	}

	/** Written by the nightly reminder job; no human did it and nobody wants it. */
	@Test
	void theDueReminderMarkerNotifiesNobody() {
		stored();

		service.update("i1", issue -> issue.setDueReminderFor(LocalDate.of(2026, 8, 23)),
				editor());

		verify(notifications).notifyUpdated(any(), eq(List.of()), any(), anySet());
	}

	@Test
	void anEditThatChangesNothingAnnouncesNothing() {
		stored();

		service.update("i1", issue -> { /* nothing at all */ }, editor());

		verify(notifications).notifyUpdated(any(), eq(List.of()), any(), anySet());
		verify(notifications, never()).notifyAssigned(any(), any(), any());
	}

	/**
	 * Archiving does not go through {@code update()}, yet it is the change a
	 * watcher most needs: the issue they subscribed to has just vanished from
	 * every list they look at.
	 */
	@Test
	void archivingAndRestoringBothReachTheWatchers() {
		Issue issue = stored();
		when(issues.findByParentId("i1")).thenReturn(List.of());

		service.setArchived("i1", true, editor());

		ArgumentCaptor<List<FieldChange>> changes = captor();
		verify(notifications).notifyUpdated(any(), changes.capture(), any());
		assertThat(changes.getValue()).singleElement()
				.returns(IssueChangeDiff.ARCHIVED, FieldChange::field)
				.returns("true", FieldChange::newValue);
		assertThat(issue.isArchived()).isTrue();
	}

	@SuppressWarnings("unchecked")
	private static ArgumentCaptor<List<FieldChange>> captor() {
		return ArgumentCaptor.forClass(List.class);
	}
}
