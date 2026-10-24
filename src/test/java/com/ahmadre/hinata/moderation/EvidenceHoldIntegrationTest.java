package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.EvidenceHold;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.storage.AttachmentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting what an open case still needs (HIN-169): its files and audio move into that case's
 * quarantine instead of going for good, everything else is deleted as before.
 */
@Import(EvidenceHoldIntegrationTest.HeldCase.class)
class EvidenceHoldIntegrationTest extends RemovalIntegrationSupport {

	/** The content a case holds in this test: by id, attachment, comment or issue alike. */
	static final Set<String> HELD = ConcurrentHashMap.newKeySet();

	@TestConfiguration
	static class HeldCase {

		@Bean
		EvidenceHold evidenceHold() {
			return ref -> HELD.contains(ref.id()) ? Optional.of("case-9") : Optional.empty();
		}
	}

	@Autowired
	private AttachmentStore attachments;
	@Autowired
	private IssueCommentRepository comments;

	@BeforeEach
	void nothingHeld() {
		HELD.clear();
	}

	private Issue issueWithFiles() {
		Issue issue = issue("Stand B12", doc("Aufbau"));
		for (String id : new String[] { "held", "free" }) {
			store.put("object-" + id, "bytes");
			attachments.add(issue.getId(), Issue.Attachment.builder().id(id).fileName(id + ".pdf")
					.contentType("application/pdf").size(5).objectKey("object-" + id).uploadedAt(Instant.now()).build());
		}
		return issue;
	}

	@Test
	void aHeldAttachmentMovesIntoTheCaseWhenItsIssueIsDeleted() {
		Issue issue = issueWithFiles();
		HELD.add("held");

		issues.delete(issue.getId(), owner);

		assertThat(store.has("object-held")).isFalse();
		assertThat(store.has("quarantine/case-9/object-held")).isTrue();
		assertThat(store.has("object-free")).isFalse();
		assertThat(store.has("quarantine/case-9/object-free")).isFalse();
	}

	@Test
	void aHeldIssueKeepsTheFilesOfAllItsAttachments() {
		Issue issue = issueWithFiles();
		HELD.add(issue.getId());

		issues.delete(issue.getId(), owner);

		assertThat(store.has("quarantine/case-9/object-held")).isTrue();
		assertThat(store.has("quarantine/case-9/object-free")).isTrue();
	}

	@Test
	void aHeldVoiceMessageKeepsItsAudioWhenItsAuthorDeletesIt() {
		Issue issue = issue("Stand B12", doc("Aufbau"));
		store.put("voice/held", "audio");
		IssueComment voice = comments.save(IssueComment.builder().issueId(issue.getId()).authorId(reader.getId())
				.type(IssueComment.Type.VOICE)
				.voice(IssueComment.Voice.builder().objectKey("voice/held").durationMs(1).size(5).build()).build());
		HELD.add(voice.getId());

		issues.deleteComment(issue.getId(), voice.getId(), reader);

		assertThat(comments.findById(voice.getId())).isEmpty();
		assertThat(store.has("voice/held")).isFalse();
		assertThat(store.has("quarantine/case-9/voice/held")).isTrue();
	}
}
