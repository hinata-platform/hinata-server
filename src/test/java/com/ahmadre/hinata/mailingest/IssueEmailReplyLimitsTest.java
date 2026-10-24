package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.FeatureFlags;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueService;
import com.ahmadre.hinata.notification.MailService;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.User;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A reply by e-mail is bounded before it is sent (HIN-26, HIN-167): subject and text by length,
 * and the attachments by count and size, because every file is read into memory for the mail.
 */
class IssueEmailReplyLimitsTest {

	private static final byte[] MEGABYTE = new byte[1024 * 1024];

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
	private final IssueService issues = mock(IssueService.class);
	private final MailService mail = mock(MailService.class);
	private final StorageService storage = mock(StorageService.class);
	private final FeatureFlags featureFlags = mock(FeatureFlags.class);
	private final IssueEmailReplyService replies = new IssueEmailReplyService(issues,
			mock(IngestConnectionRepository.class), mail, storage, featureFlags);
	private final User user = User.builder().id("u1").build();
	private Issue issue;

	@BeforeEach
	void setUp() {
		issue = Issue.builder().reporterEmail("sender@example.org")
				.attachments(new ArrayList<>(List.of(attachment("small", 1), attachment("big", 20)))).build();
		when(featureFlags.enabled(IssueEmailReplyService.FEATURE_FLAG)).thenReturn(true);
		when(issues.getForUser("i1", user)).thenReturn(issue);
		when(mail.sendReply(anyString(), any(), anyString(), anyString(), any(), any()))
				.thenReturn(MailService.SendResult.SENT);
	}

	@Test
	void subjectTextAndAttachmentListAreBoundedAndTheRouteChecksThem() throws Exception {
		List<String> twentyOne = Collections.nCopies(21, "small");
		assertThat(validator.validate(new IssueEmailReplyController.ReplyRequest("s".repeat(301), "text", null)))
				.extracting(v -> v.getPropertyPath().toString()).containsExactly("subject");
		assertThat(validator.validate(new IssueEmailReplyController.ReplyRequest("subject", "t".repeat(30_001), null)))
				.extracting(v -> v.getPropertyPath().toString()).containsExactly("body");
		assertThat(validator.validate(new IssueEmailReplyController.ReplyRequest("subject", "text", twentyOne)))
				.extracting(v -> v.getPropertyPath().toString()).containsExactly("attachmentIds");
		assertThat(validator.validate(new IssueEmailReplyController.ReplyRequest("s".repeat(300), "t".repeat(30_000),
				Collections.nCopies(20, "small")))).isEmpty();
		// Without @Valid on the body none of the limits above would run.
		assertThat(IssueEmailReplyController.class
				.getMethod("reply", String.class, IssueEmailReplyController.ReplyRequest.class)
				.getParameters()[1].isAnnotationPresent(Valid.class)).isTrue();
	}

	@Test
	void anAttachmentNamedTwiceIsReadAndSentOnce() {
		replies.reply("i1", user, "subject", "text", List.of("small", "small", "small"));

		verify(storage, times(1)).getObject("key-small");
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MailService.Attachment>> sent = ArgumentCaptor.forClass(List.class);
		verify(mail).sendReply(eq("sender@example.org"), any(), eq("subject"), anyString(), any(), sent.capture());
		assertThat(sent.getValue()).hasSize(1);
	}

	@Test
	void attachmentsOverTwentyFiveMegabytesTogetherAreRefusedBeforeSending() {
		issue.getAttachments().add(attachment("bigger", 6));

		assertThatThrownBy(() -> replies.reply("i1", user, "subject", "text", List.of("big", "bigger")))
				.isInstanceOf(ApiException.class)
				.hasMessageContaining("attachmentsTooLarge");
		verify(mail, never()).sendReply(anyString(), any(), anyString(), anyString(), any(), any());
		// Twenty-one megabytes go out.
		replies.reply("i1", user, "subject", "text", List.of("big", "small"));
		verify(mail).sendReply(anyString(), any(), anyString(), anyString(), any(), any());
	}

	private Issue.Attachment attachment(String id, int megabytes) {
		byte[] data = new byte[megabytes * MEGABYTE.length];
		when(storage.getObject("key-" + id)).thenReturn(Optional.of(new StorageService.StoredObject(data, "application/pdf")));
		return Issue.Attachment.builder().id(id).fileName(id + ".pdf").contentType("application/pdf")
				.size(data.length).objectKey("key-" + id).build();
	}
}
