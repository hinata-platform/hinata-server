package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.Quarantine;
import com.ahmadre.hinata.content.Removal;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.RestoreOutcome;
import com.ahmadre.hinata.content.ScrubReason;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationRepository;
import com.ahmadre.hinata.richtext.RichTextService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Removing a comment in place (HIN-169): every way it is read shows the placeholder, the copies of
 * its words go with it, the original waits in the quarantine, and nothing of anybody else changes.
 */
class CommentRemovalIntegrationTest extends RemovalIntegrationSupport {

	private static final String INSULT = "Jonas ist zu bloed fuer den Job";

	@Autowired
	private IssueCommentRepository comments;
	@Autowired
	private NotificationRepository notifications;
	@Autowired
	private RichTextService richText;
	@Autowired
	private Quarantine quarantine;

	private IssueComment comment(Issue issue, String text, String replyToId, com.ahmadre.hinata.user.User by) {
		return issues.addComment(issue.getId(), richText.fromLexical(doc(text)), replyToId, by);
	}

	@Test
	void aRemovedCommentShowsThePlaceholderWhereverItIsRead() {
		Issue issue = issue("Stand B12", doc("Aufbau"));
		IssueComment insult = comment(issue, INSULT, null, reader);
		IssueComment reply = comment(issue, "Bitte sachlich bleiben", insult.getId(), author);
		IssueComment neighbour = comment(issue, "Strom kommt morgen", null, author);
		// Written before notices kept their source and replies their target: found by their text.
		Notification older = notifications.save(Notification.builder().userId(owner.getId())
				.type(Notification.Type.ISSUE_COMMENTED).title("ACME-1").body("lena: " + INSULT)
				.link("/issues/" + issue.getReadableId()).build());
		IssueComment olderReply = comments.save(IssueComment.builder().issueId(issue.getId())
				.authorId(owner.getId()).text("Stimmt nicht").textDoc(doc("Stimmt nicht"))
				.replyToId(insult.getId()).replyToAuthorId(reader.getId()).replyToPreview(INSULT).build());

		Removal removal = remove(ContentKind.COMMENT, insult.getId(), issue.getId(), RemovalScope.WHOLE,
				ReportCategory.HARASSMENT);
		followUps.scrubCopies(List.of(removal)).join();

		IssueComment removed = comments.findById(insult.getId()).orElseThrow();
		assertThat(removed.getText()).isEqualTo(placeholders.removedText());
		assertThat(removed.getTextDoc()).doesNotContain(INSULT);
		assertThat(removed.getModeration().get("text").isRemoved()).isTrue();
		// Every copy says the same, the old ones too.
		assertThat(comments.findById(reply.getId()).orElseThrow().getReplyToPreview())
				.isEqualTo(placeholders.removedText());
		assertThat(comments.findById(olderReply.getId()).orElseThrow().getReplyToPreview())
				.isEqualTo(placeholders.removedText());
		assertThat(notifications.findById(older.getId()).orElseThrow().getBody()).isEqualTo(placeholders.removedText());
		List<Notification> about = mongo.find(Query.query(Criteria.where("sourceId").is(insult.getId())),
				Notification.class);
		assertThat(about).isNotEmpty().allSatisfy(n -> assertThat(n.getBody()).isEqualTo(placeholders.removedText()));
		// The thread as every reader gets it.
		assertThat(issues.commentsOf(issue.getId(), 0, 20, owner).getContent())
				.extracting(IssueComment::getText)
				.contains(placeholders.removedText())
				.noneMatch(text -> text.contains(INSULT));
		// Nothing of anybody else changed.
		assertThat(comments.findById(neighbour.getId()).orElseThrow().getText()).isEqualTo("Strom kommt morgen");
		assertThat(comments.findById(reply.getId()).orElseThrow().getText()).isEqualTo("Bitte sachlich bleiben");
		assertThat(mongo.find(Query.query(Criteria.where("sourceId").is(neighbour.getId())), Notification.class))
				.allSatisfy(n -> assertThat(n.getBody()).contains("Strom kommt morgen"));
		// The original waits in the quarantine of the case.
		Document row = mongo.findOne(Query.query(Criteria.where("caseId").is(CASE)), Document.class,
				"moderation_quarantine");
		assertThat(row.get("fields", Document.class).getString("text")).isEqualTo(INSULT);
		assertThat(removal.quarantineId()).isEqualTo(row.get("_id").toString());
	}

	@Test
	void aRemovedCommentCannotBeWrittenAgainButCanBeDeleted() {
		Issue issue = issue("Stand B12", doc("Aufbau"));
		IssueComment insult = comment(issue, INSULT, null, reader);
		remove(ContentKind.COMMENT, insult.getId(), issue.getId(), RemovalScope.WHOLE, ReportCategory.HARASSMENT);

		assertThatThrownBy(() -> issues.editComment(issue.getId(), insult.getId(),
				richText.fromLexical(doc("Nachgebessert")), reader))
				.isInstanceOfSatisfying(ApiException.class, refused -> {
					assertThat(refused.getStatus().value()).isEqualTo(409);
					assertThat(refused.getMessageKey()).isEqualTo("error.moderation.removed");
				});
		issues.deleteComment(issue.getId(), insult.getId(), reader);
		assertThat(comments.findById(insult.getId())).isEmpty();
	}

	@Test
	void aVoiceMessageGivesItsAudioToTheQuarantineAndComesBackWhole() {
		Issue issue = issue("Stand B12", doc("Aufbau"));
		store.put("voice/abc", "audio");
		IssueComment voice = comments.save(IssueComment.builder().issueId(issue.getId()).authorId(reader.getId())
				.type(IssueComment.Type.VOICE)
				.voice(IssueComment.Voice.builder().objectKey("voice/abc").durationMs(4200)
						.peaks(List.of(10, 80, 30)).size(5).contentType("audio/mp4").build())
				.build());

		Removal removal = remove(ContentKind.COMMENT, voice.getId(), issue.getId(), RemovalScope.WHOLE,
				ReportCategory.THREAT);

		IssueComment removed = comments.findById(voice.getId()).orElseThrow();
		// A text comment now, so an app that knows nothing of moderation shows the words.
		assertThat(removed.resolvedType()).isEqualTo(IssueComment.Type.TEXT);
		assertThat(removed.getVoice()).isNull();
		assertThat(removed.getText()).isEqualTo(placeholders.removedText());
		assertThat(store.has("voice/abc")).isFalse();
		assertThat(store.has("quarantine/" + CASE + "/voice/abc")).isTrue();

		assertThat(surface(ContentKind.COMMENT).restore(removal)).isEqualTo(RestoreOutcome.RESTORED);
		IssueComment back = comments.findById(voice.getId()).orElseThrow();
		assertThat(back.resolvedType()).isEqualTo(IssueComment.Type.VOICE);
		assertThat(back.getVoice().getObjectKey()).isEqualTo("voice/abc");
		assertThat(back.getVoice().getPeaks()).containsExactly(10, 80, 30);
		assertThat(back.getModeration()).isNullOrEmpty();
		assertThat(store.has("voice/abc")).isTrue();
	}

	@Test
	void removingTwiceTakesNothingTheSecondTime() {
		Issue issue = issue("Stand B12", doc("Aufbau"));
		IssueComment insult = comment(issue, INSULT, null, reader);
		remove(ContentKind.COMMENT, insult.getId(), issue.getId(), RemovalScope.WHOLE, ReportCategory.HARASSMENT);

		Removal again = remove(ContentKind.COMMENT, insult.getId(), issue.getId(), RemovalScope.WHOLE,
				ReportCategory.HARASSMENT);

		assertThat(again.quarantineId()).isNull();
		assertThat(mongo.count(new Query(), "moderation_quarantine")).isEqualTo(1);
	}

	@Test
	void aCopyTheStoreRefusesLeavesEverythingAsItWas() {
		Issue issue = issue("Stand B12", doc("Aufbau"));
		store.put("voice/abc", "audio");
		store.refuseCopies.add("voice/abc");
		IssueComment voice = comments.save(IssueComment.builder().issueId(issue.getId()).authorId(reader.getId())
				.type(IssueComment.Type.VOICE)
				.voice(IssueComment.Voice.builder().objectKey("voice/abc").durationMs(1).size(5).build()).build());

		assertThatThrownBy(() -> remove(ContentKind.COMMENT, voice.getId(), issue.getId(), RemovalScope.WHOLE,
				ReportCategory.THREAT)).isInstanceOf(ApiException.class);

		assertThat(comments.findById(voice.getId()).orElseThrow().getVoice().getObjectKey()).isEqualTo("voice/abc");
		assertThat(store.has("voice/abc")).isTrue();
		assertThat(mongo.count(new Query(), "moderation_quarantine")).isZero();
	}

	@Test
	void anOriginalTheStoreKeepsIsNamedAndGoesOnTheNextTry() {
		Issue issue = issue("Stand B12", doc("Aufbau"));
		store.put("voice/abc", "audio");
		store.refuseDeletes.add("voice/abc");
		IssueComment voice = comments.save(IssueComment.builder().issueId(issue.getId()).authorId(reader.getId())
				.type(IssueComment.Type.VOICE)
				.voice(IssueComment.Voice.builder().objectKey("voice/abc").durationMs(1).size(5).build()).build());

		Removal removal = remove(ContentKind.COMMENT, voice.getId(), issue.getId(), RemovalScope.WHOLE,
				ReportCategory.THREAT);

		// Nothing points to it any more, and it is named for the follow-up.
		assertThat(removal.complete()).isFalse();
		assertThat(removal.pendingDeletes()).containsExactly("voice/abc");
		assertThat(comments.findById(voice.getId()).orElseThrow().getVoice()).isNull();

		store.refuseDeletes.clear();
		assertThat(quarantine.deleteOriginals(removal.pendingDeletes())).isEmpty();
		assertThat(store.has("voice/abc")).isFalse();
	}

	@Test
	void deletingARootScrubsTheBellForItAndItsRepliesInOneCall() {
		Issue issue = issue("Stand B12", doc("Aufbau"));
		IssueComment root = comment(issue, "Wer bringt die Kabel", null, reader);
		IssueComment answer = comment(issue, "Ich bringe sie", root.getId(), author);
		IssueComment other = comment(issue, "Strom kommt morgen", null, author);

		issues.deleteComment(issue.getId(), root.getId(), reader);

		// The root and its reply, one scrub for the request.
		verify(copiesScrub, times(1)).scrub(anyList(), eq(ScrubReason.DELETED), any());
		for (String gone : List.of(root.getId(), answer.getId())) {
			assertThat(mongo.find(Query.query(Criteria.where("sourceId").is(gone)), Notification.class))
					.allSatisfy(n -> assertThat(n.getBody()).isEqualTo(placeholders.deletedText()));
		}
		assertThat(mongo.find(Query.query(Criteria.where("sourceId").is(root.getId())), Notification.class))
				.isNotEmpty();
		assertThat(mongo.find(Query.query(Criteria.where("sourceId").is(other.getId())), Notification.class))
				.allSatisfy(n -> assertThat(n.getBody()).contains("Strom kommt morgen"));
	}

	@Test
	void deletingAQuotedReplyScrubsTheQuoteOfTheReplyAfterIt() {
		Issue issue = issue("Stand B12", doc("Aufbau"));
		IssueComment root = comment(issue, "Wer bringt die Kabel", null, reader);
		IssueComment answer = comment(issue, "Ich bringe sie", root.getId(), author);
		IssueComment quoting = comment(issue, "Danke", answer.getId(), reader);

		issues.deleteComment(issue.getId(), answer.getId(), author);

		assertThat(comments.findById(quoting.getId()).orElseThrow().getReplyToPreview())
				.isEqualTo(placeholders.deletedText());
		// The root's own quote-free text is untouched.
		assertThat(comments.findById(root.getId()).orElseThrow().getText()).isEqualTo("Wer bringt die Kabel");
	}
}
