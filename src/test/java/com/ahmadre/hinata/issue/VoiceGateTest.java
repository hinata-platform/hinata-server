package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.storage.TestImages;
import com.ahmadre.hinata.storage.TestStorage;
import com.ahmadre.hinata.storage.TestUploadGates;
import com.ahmadre.hinata.storage.UploadKind;
import com.ahmadre.hinata.storage.UploadRefusedEvent;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A voice message is bytes from a person like any other (HIN-177): it passes the upload gate, which
 * reads the audio's signature instead of trusting the type the recorder declared.
 */
class VoiceGateTest {

	private final Map<String, StorageService.StoredObject> objects = new ConcurrentHashMap<>();
	private final List<UploadRefusedEvent> refused = new ArrayList<>();
	private IssueCommentRepository comments;
	private IssueService service;
	private final User author = User.builder().id("u1").email("a@b.c").roles(Set.of(Role.MEMBER)).build();

	@BeforeEach
	void setUp() {
		IssueRepository issues = mock(IssueRepository.class);
		when(issues.findById("i1")).thenReturn(Optional.of(Issue.builder().id("i1").projectId("p1").build()));
		comments = mock(IssueCommentRepository.class);
		when(comments.save(any(IssueComment.class))).thenAnswer(call -> call.getArgument(0));
		service = new IssueService(
				issues, comments,
				mock(IssueActivityRepository.class), mock(IssueLinkRepository.class),
				mock(IssueLinkEvents.class), mock(CommentEvents.class), mock(ProjectService.class),
				mock(com.ahmadre.hinata.notification.NotificationService.class),
				TestStorage.inMemory(TestUploadGates.counting(refused), objects),
				mock(com.ahmadre.hinata.timetracking.WorkItemRepository.class),
				mock(com.ahmadre.hinata.audit.AuditService.class), mock(MongoTemplate.class),
				mock(com.ahmadre.hinata.board.AgileBoardRepository.class),
				mock(com.ahmadre.hinata.board.SprintRepository.class),
				mock(com.ahmadre.hinata.user.UserRepository.class),
				mock(IssueDeadlinePolicy.class), IssueReachStubs.everyoneSees(), mock(DeletedContent.class),
				com.ahmadre.hinata.richtext.TestTextChecks.allowAll());
	}

	@Test
	void audioWithTheWrongSignatureIsRefusedAndNothingIsSaved() {
		MockMultipartFile disguised = new MockMultipartFile("file", "voice.m4a", "audio/mp4", TestImages.exe());

		assertThatThrownBy(() -> service.addVoiceComment("i1", disguised, 3000, List.of(10, 80), author))
				.isInstanceOf(ApiException.class)
				.hasMessage("error.upload.refused.signature");

		assertThat(objects).isEmpty();
		verify(comments, never()).save(any(IssueComment.class));
		assertThat(refused).containsExactly(new UploadRefusedEvent(UploadKind.VOICE, "signature"));
	}

	@Test
	void aRecordingIsStoredWithTheTypeItReallyHas() {
		IssueComment saved = service.addVoiceComment("i1",
				new MockMultipartFile("file", "voice.webm", "audio/webm;codecs=opus", TestImages.webm()), 3000,
				List.of(10, 80), author);

		assertThat(saved.getVoice().getContentType()).isEqualTo("audio/webm");
		assertThat(saved.getVoice().getObjectKey()).startsWith("voice/");
		assertThat(objects.get(saved.getVoice().getObjectKey()).data()).isEqualTo(TestImages.webm());
	}
}
