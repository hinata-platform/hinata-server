package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentRef;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting what an open case still needs (HIN-169): its files and audio move into that case's
 * quarantine instead of going for good, everything else is deleted as before.
 */
@Import(EvidenceHoldIntegrationTest.HeldCase.class)
class EvidenceHoldIntegrationTest extends RemovalIntegrationSupport {

	/** The content a case holds in this test: by id, attachment, comment or issue alike. */
	static final Set<String> HELD = ConcurrentHashMap.newKeySet();

	/** How often the holds were asked for many things at once. */
	static final AtomicInteger ASKED = new AtomicInteger();

	@TestConfiguration
	static class HeldCase {

		@Bean
		EvidenceHold evidenceHold() {
			return new EvidenceHold() {
				@Override
				public Optional<String> holdingCaseId(ContentRef ref) {
					return HELD.contains(ref.id()) ? Optional.of("case-9") : Optional.empty();
				}

				@Override
				public Map<ContentRef, String> holdingCaseIds(Collection<ContentRef> refs) {
					ASKED.incrementAndGet();
					return EvidenceHold.super.holdingCaseIds(refs);
				}
			};
		}
	}

	@Autowired
	private AttachmentStore attachments;
	@Autowired
	private IssueCommentRepository comments;

	@Autowired
	private CaseEvidenceHold caseHolds;

	@BeforeEach
	void nothingHeld() {
		HELD.clear();
		ASKED.set(0);
	}

	private Issue issueWithFiles() {
		return issueWithFiles("held", "free");
	}

	private Issue issueWithFiles(String... ids) {
		Issue issue = issue("Stand B12", doc("Aufbau"));
		for (String id : ids) {
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
	@Test
	void deletingIssuesReadsTheHoldsOfAllTheirFilesAtOnce() {
		Issue first = issueWithFiles("held", "free");
		Issue second = issueWithFiles("second-a", "second-b");
		HELD.add("held");

		issues.deleteAll(List.of(first.getId(), second.getId()), owner);

		assertThat(ASKED).as("one read for the whole request").hasValue(1);
		assertThat(store.has("quarantine/case-9/object-held")).isTrue();
		for (String gone : List.of("object-held", "object-free", "object-second-a", "object-second-b")) {
			assertThat(store.has(gone)).as(gone).isFalse();
		}
	}

	/** An open case holds what it is about; any case under a legal hold does, of the same kind only. */
	@Test
	void theCasesHoldingSeveralThingsAreFoundTogether() {
		ContentRef comment = new ContentRef(ContentKind.COMMENT, "c-1", "i-1", "p-1");
		ContentRef attachment = new ContentRef(ContentKind.ATTACHMENT, "a-1", "i-1", "p-1");
		ContentRef issue = new ContentRef(ContentKind.ISSUE, "i-1", null, "p-1");
		ContentRef sameIdOtherKind = new ContentRef(ContentKind.ISSUE, "a-1", null, "p-1");
		ModerationCase open = mongo.insert(ModerationCase.builder().target(comment)
				.targetKey(ModerationCase.keyOf(comment)).status(CaseStatus.OPEN).build());
		ModerationCase heldByLaw = mongo.insert(ModerationCase.builder().target(attachment)
				.targetKey(ModerationCase.keyOf(attachment)).status(CaseStatus.DECIDED)
				.legalHoldUntil(Instant.now().plusSeconds(86400)).build());
		mongo.insert(ModerationCase.builder().target(issue).targetKey(ModerationCase.keyOf(issue))
				.status(CaseStatus.DECIDED).build());

		assertThat(caseHolds.holdingCaseIds(List.of(comment, attachment, issue, sameIdOtherKind)))
				.containsOnly(Map.entry(comment, open.getId()), Map.entry(attachment, heldByLaw.getId()));
		assertThat(caseHolds.holdingCaseId(issue)).isEmpty();
	}
}
