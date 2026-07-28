package com.ahmadre.hinata.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.board.AgileBoardRepository;
import com.ahmadre.hinata.board.SprintRepository;
import com.ahmadre.hinata.notification.NotificationService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.richtext.RichText;
import com.ahmadre.hinata.richtext.RichTextService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import com.ahmadre.hinata.timetracking.WorkItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What {@code snapshot()} carries decides what the rest of an update believes
 * changed. Both consumers now read the <em>document</em>, not its plain-text
 * projection: the notification layer diffs it to find mentions this edit added,
 * and the change history diffs it to notice an edit that changed formatting
 * without changing a word. A field missing from the snapshot reads as null, and
 * null diffs against everything.
 */
class IssueUpdateSnapshotTest {

	private final RichTextService richText = new RichTextService();

	private IssueRepository issues;
	private IssueActivityRepository activities;
	private NotificationService notifications;
	private IssueService service;

	@BeforeEach
	void setUp() {
		issues = mock(IssueRepository.class);
		activities = mock(IssueActivityRepository.class);
		notifications = mock(NotificationService.class);
		ProjectService projects = mock(ProjectService.class);

		Project project = Project.builder().id("p1").key("HIN").name("Hinata")
				.memberIds(new ArrayList<>(List.of("u1")))
				.build();
		when(projects.get("p1")).thenReturn(project);

		service = new IssueService(
				issues,
				mock(IssueCommentRepository.class),
				activities,
				mock(IssueLinkRepository.class),
				mock(IssueLinkEvents.class),
				mock(CommentEvents.class),
				projects,
				notifications,
				mock(StorageService.class),
				mock(WorkItemRepository.class),
				mock(AuditService.class, RETURNS_DEEP_STUBS),
				mock(MongoTemplate.class),
				mock(AgileBoardRepository.class),
				mock(SprintRepository.class),
				mock(UserRepository.class));

		when(issues.save(any(Issue.class))).thenAnswer(call -> call.getArgument(0));
	}

	private User editor() {
		return User.builder().id("u1").email("a@b.c").roles(Set.of(Role.MEMBER)).build();
	}

	/** A stored issue whose description mentions people, as the write path left it. */
	private Issue stored(String markdown) {
		RichText body = richText.fromMarkdown(markdown);
		Issue issue = Issue.builder().id("i1").projectId("p1").readableId("HIN-1")
				.title("Login bug")
				.state("Open")
				.description(body.text())
				.descriptionDoc(body.doc())
				.assigneeIds(new ArrayList<>())
				.tags(new ArrayList<>())
				.watcherIds(new ArrayList<>())
				.build();
		when(issues.findById("i1")).thenReturn(Optional.of(issue));
		return issue;
	}

	@Test
	void anUnrelatedEditSeesTheDescriptionItStartedWith() {
		Issue issue = stored("Betrifft {{user:u2}} und {{user:u3}}.");
		String storedDoc = issue.getDescriptionDoc();

		// Someone drags the issue to another column. The description is untouched.
		service.update("i1", i -> i.setPriority(Issue.Priority.CRITICAL), editor());

		ArgumentCaptor<String> before = ArgumentCaptor.forClass(String.class);
		verify(notifications).notifyNewMentions(any(), any(), before.capture(), eq(storedDoc));
		assertThat(before.getValue())
				.as("the snapshot must carry the document, or every mention reads as new")
				.isEqualTo(storedDoc);
	}

	@Test
	void aFormattingOnlyEditIsStillRecordedInTheChangeHistory() {
		// Same words, different document: bold added. Diffing the derived plain text
		// finds nothing, so the activity feed would show the edit never happened.
		Issue issue = stored("Bitte pruefen.");
		String plainBefore = issue.getDescription();
		RichText bold = richText.fromMarkdown("Bitte **pruefen**.");
		assertThat(bold.text()).isEqualTo(plainBefore);

		service.update("i1", i -> {
			i.setDescription(bold.text());
			i.setDescriptionDoc(bold.doc());
		}, editor());

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<IssueActivity>> saved = ArgumentCaptor.forClass(List.class);
		verify(activities).saveAll(saved.capture());
		assertThat(saved.getValue())
				.extracting(IssueActivity::getField)
				.contains(IssueActivity.Field.DESCRIPTION);
	}

	@Test
	void anEditThatChangesNothingRecordsNothing() {
		stored("Bitte pruefen.");

		service.update("i1", i -> { /* no change at all */ }, editor());

		verify(activities, never()).saveAll(any());
	}
}
