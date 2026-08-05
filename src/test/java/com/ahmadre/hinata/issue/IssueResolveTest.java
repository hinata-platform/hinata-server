package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code {{issue:…}}} chips resolve through {@code resolveIssues}. After a
 * cross-project move an issue's readable id changes, so every chip written
 * under the old key would break unless the batch lookup also matches
 * {@link Issue#getFormerReadableIds()} — the same redirect the single-issue
 * lookup performs.
 */
class IssueResolveTest {

	private MongoTemplate mongo;
	private ProjectService projects;
	private IssueService service;
	private User user;

	@BeforeEach
	void setUp() {
		mongo = mock(MongoTemplate.class);
		projects = mock(ProjectService.class);
		user = User.builder().id("u1").email("a@b.c").roles(Set.of(Role.MEMBER)).build();

		Project visible = Project.builder().id("p1").key("MOB").name("Mobile")
				.memberIds(new ArrayList<>(List.of("u1")))
				.build();
		when(projects.visibleTo(user)).thenReturn(List.of(visible));
		when(mongo.find(any(Query.class), eq(Issue.class))).thenReturn(List.of());

		service = new IssueService(
				mock(IssueRepository.class), mock(IssueCommentRepository.class),
				mock(IssueActivityRepository.class), mock(IssueLinkRepository.class),
				mock(IssueLinkEvents.class), mock(CommentEvents.class), projects,
				mock(com.ahmadre.hinata.notification.NotificationService.class),
				mock(com.ahmadre.hinata.storage.StorageService.class),
				mock(com.ahmadre.hinata.timetracking.WorkItemRepository.class),
				mock(com.ahmadre.hinata.audit.AuditService.class), mongo,
				mock(com.ahmadre.hinata.board.AgileBoardRepository.class),
				mock(com.ahmadre.hinata.board.SprintRepository.class),
				mock(com.ahmadre.hinata.user.UserRepository.class),
				mock(com.ahmadre.hinata.moderation.ModerationService.class),
				mock(com.ahmadre.hinata.moderation.ModerationRecorder.class),
				mock(com.ahmadre.hinata.moderation.report.UserBlockService.class));
	}

	private Query capturedQuery() {
		ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
		verify(mongo).find(captor.capture(), eq(Issue.class));
		return captor.getValue();
	}

	@Test
	void resolvesByTheCurrentReadableIdAndByIdsCarriedBeforeAMove() {
		service.resolveIssues(List.of("HIN-10"), user);

		String query = capturedQuery().getQueryObject().toJson();
		assertThat(query).contains("readableId").contains("HIN-10");
		// The redirect: an id the issue only carried before it was moved.
		assertThat(query).contains("formerReadableIds");
	}

	@Test
	void staysScopedToProjectsTheViewerMaySee() {
		service.resolveIssues(List.of("HIN-10"), user);

		// Widening the lookup to former ids must not widen it past the ACL — the
		// project scope stays an AND, never an OR branch.
		assertThat(capturedQuery().getQueryObject().toJson())
				.contains("projectId")
				.contains("p1");
	}

	@Test
	void anEmptyKeyListShortCircuitsWithoutQuerying() {
		assertThat(service.resolveIssues(List.of(), user)).isEmpty();
		verify(mongo, org.mockito.Mockito.never()).find(any(Query.class), eq(Issue.class));
	}
}
