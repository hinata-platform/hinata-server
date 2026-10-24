package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.MarkState;
import com.ahmadre.hinata.content.Removal;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.RestoreOutcome;
import com.ahmadre.hinata.content.ScrubReason;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueActivity;
import com.ahmadre.hinata.issue.IssueCloneService;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.notification.FieldChange;
import com.ahmadre.hinata.notification.IssueMailDigest;
import com.ahmadre.hinata.notification.Notification;
import com.ahmadre.hinata.notification.NotificationRepository;
import com.ahmadre.hinata.storage.AttachmentContentService;
import com.ahmadre.hinata.storage.AttachmentStore;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Removing an issue's title and description, and an attachment, in place (HIN-169).
 */
class IssueRemovalIntegrationTest extends RemovalIntegrationSupport {

	private static final String SLUR = "Projekt der Idioten";
	private static final String MEDIA = "0f8fad5b-d9cb-469f-a165-70867728950e";
	private static final String SHARED = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private NotificationRepository notifications;
	@Autowired
	private AttachmentStore attachments;
	@Autowired
	private AttachmentContentService contents;
	@Autowired
	private IssueCloneService clones;

	@Test
	void aRemovedTitleAndDescriptionShowThePlaceholderAndTheirCopiesGo() {
		Issue issue = issue("Erster Entwurf", doc("Die Leute vom Lager sind Idioten"));
		issues.update(issue.getId(), i -> i.setTitle(SLUR), author);
		// Ten issues further, ACME-1 has a neighbour ACME-11 whose links start the same way.
		Issue neighbour = null;
		for (int i = 0; i < 10; i++) {
			neighbour = issue("Nachbar " + i, doc("Nichts"));
		}
		Notification aboutNeighbour = notifications.save(Notification.builder().userId(owner.getId())
				.type(Notification.Type.ISSUE_UPDATED).title(neighbour.getReadableId()).body(SLUR)
				.link("/issues/" + neighbour.getReadableId()).build());
		assertThat(neighbour.getReadableId()).startsWith(issue.getReadableId());
		IssueMailDigest digest = mongo.insert(IssueMailDigest.builder().userId(reader.getId()).issueId(issue.getId())
				.projectId(project.getId())
				.changes(new ArrayList<>(List.of(new FieldChange("title", "Erster Entwurf", SLUR))))
				.firstQueuedAt(Instant.now()).lastQueuedAt(Instant.now()).build());

		Removal removal = remove(ContentKind.ISSUE, issue.getId(), null, RemovalScope.WHOLE, ReportCategory.HARASSMENT);
		followUps.scrubCopies(List.of(removal)).join();

		Issue removed = issueRepository.findById(issue.getId()).orElseThrow();
		assertThat(removed.getTitle()).isEqualTo(placeholders.removedTitle());
		assertThat(removed.getDescription()).isEqualTo(placeholders.removedText());
		assertThat(removed.getDescriptionDoc()).doesNotContain("Idioten");
		assertThat(removed.getModeration().get("title").state()).isEqualTo(MarkState.REMOVED);
		assertThat(removed.getModeration().get("description").state()).isEqualTo(MarkState.REMOVED);
		Document stored = mongo.findOne(Query.query(Criteria.where("_id").is(issue.getId())), Document.class, "issues");
		assertThat(stored.getString("searchText")).doesNotContain("idioten");
		// The history keeps the title before it; the removed one is gone from it.
		List<IssueActivity> titles = mongo.find(Query.query(Criteria.where("issueId").is(issue.getId())
				.and("field").is(IssueActivity.Field.TITLE)), IssueActivity.class);
		assertThat(titles).singleElement().satisfies(change -> {
			assertThat(change.getFromValue()).isEqualTo("Erster Entwurf");
			assertThat(change.getToValue()).isEqualTo(placeholders.removedTitle());
		});
		// The bell about this issue, not about its neighbour.
		assertThat(notifications.findAll()).filteredOn(n -> n.getLink() != null
						&& n.getLink().startsWith("/issues/" + issue.getReadableId())
						&& !n.getLink().startsWith("/issues/" + neighbour().getReadableId()))
				.allSatisfy(n -> assertThat(n.getTitle() + n.getBody()).doesNotContain(SLUR));
		assertThat(notifications.findById(aboutNeighbour.getId()).orElseThrow().getBody()).isEqualTo(SLUR);
		assertThat(mongo.findById(digest.getId(), IssueMailDigest.class).getChanges())
				.containsExactly(new FieldChange("title", "Erster Entwurf", placeholders.removedTitle()));
	}

	private Issue neighbour() {
		return issueRepository.findAll().stream()
				.filter(issue -> issue.getReadableId().equals("ACME-11")).findFirst().orElseThrow();
	}

	@Test
	void aTitleWrittenAnewShowsTheNewWordsAndIsNotRestoredOver() {
		Issue issue = issue(SLUR, doc("Aufbau"));
		Removal removal = remove(ContentKind.ISSUE, issue.getId(), null, RemovalScope.TITLE, ReportCategory.HARASSMENT);

		issues.update(issue.getId(), i -> i.setTitle("Messestand aufbauen"), author);

		Issue rewritten = issueRepository.findById(issue.getId()).orElseThrow();
		assertThat(rewritten.getTitle()).isEqualTo("Messestand aufbauen");
		assertThat(rewritten.getModeration().get("title").state()).isEqualTo(MarkState.REWRITTEN);
		assertThat(surface(ContentKind.ISSUE).restore(removal)).isEqualTo(RestoreOutcome.REWRITTEN_SINCE);
		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getTitle()).isEqualTo("Messestand aufbauen");
	}

	@Test
	void restoringBringsBackWhatWasRemovedWhileNobodyWroteAgain() {
		Issue issue = issue(SLUR, doc("Die Leute vom Lager sind Idioten"));
		Removal removal = remove(ContentKind.ISSUE, issue.getId(), null, RemovalScope.WHOLE, ReportCategory.HARASSMENT);

		assertThat(surface(ContentKind.ISSUE).restore(removal)).isEqualTo(RestoreOutcome.RESTORED);

		Issue back = issueRepository.findById(issue.getId()).orElseThrow();
		assertThat(back.getTitle()).isEqualTo(SLUR);
		assertThat(back.getDescriptionDoc()).contains("Idioten");
		assertThat(back.getModeration()).isNullOrEmpty();
		Document stored = mongo.findOne(Query.query(Criteria.where("_id").is(issue.getId())), Document.class, "issues");
		assertThat(stored.getString("searchText")).contains("idioten");
	}

	@Test
	void aRemovedAttachmentAnswers410EverywhereAndIsLeftOutOfClones() {
		Issue issue = issue("Stand B12", doc("Aufbau"));
		store.put("att-object", "pdf");
		store.put("thumbs/att/a1", "preview");
		attachments.add(issue.getId(), Issue.Attachment.builder().id("a1").fileName("Lageplan.pdf")
				.contentType("application/pdf").size(3).objectKey("att-object").uploaderId(author.getId())
				.uploadedAt(Instant.now()).build());

		Removal removal = remove(ContentKind.ATTACHMENT, "a1", issue.getId(), RemovalScope.WHOLE, ReportCategory.PRIVACY);

		Issue.Attachment removed = issueRepository.findById(issue.getId()).orElseThrow().getAttachments().getFirst();
		assertThat(removed.isRemoved()).isTrue();
		assertThat(removed.getFileName()).isEqualTo(placeholders.removedFile());
		assertThat(store.has("att-object")).isFalse();
		assertThat(store.has("thumbs/att/a1")).isFalse();
		assertThat(store.has("quarantine/" + CASE + "/att-object")).isTrue();
		assertThatThrownBy(() -> contents.render(removed, new AttachmentContentService.Limits(1_000_000, 800, 10_000)))
				.isInstanceOfSatisfying(ApiException.class, gone -> assertThat(gone.getStatus().value()).isEqualTo(410));
		Issue clone = clones.clone(issue.getId(), new IssueCloneService.Options("Stand B12, Kopie", List.of(), true, false, false),
				owner);
		assertThat(clone.getAttachments()).isEmpty();

		// A late preview finds its attachment again (the same array filter, wrong before HIN-169).
		attachments.setBlurHash(issue.getId(), "a1", "LKO2?U%2Tw=w]~RBVZRi};RPxuwH");
		assertThat(issueRepository.findById(issue.getId()).orElseThrow().getAttachments().getFirst().getBlurHash())
				.isEqualTo("LKO2?U%2Tw=w]~RBVZRi};RPxuwH");

		assertThat(surface(ContentKind.ATTACHMENT).restore(removal)).isEqualTo(RestoreOutcome.RESTORED);
		Issue.Attachment back = issueRepository.findById(issue.getId()).orElseThrow().getAttachments().getFirst();
		assertThat(back.getObjectKey()).isEqualTo("att-object");
		assertThat(back.getFileName()).isEqualTo("Lageplan.pdf");
		assertThat(back.getModeration()).isNullOrEmpty();
		assertThat(store.has("att-object")).isTrue();
	}

	@Test
	void anImageAnotherIssueShowsIsCopiedUnlessTheContentIsSensitive() {
		store.put("media/" + MEDIA, "jpeg");
		Issue first = issue("Plan", docWithImage("Erster", MEDIA));
		Issue clone = issue("Plan, Kopie", docWithImage("Zweiter", MEDIA));

		Removal harassment = remove(ContentKind.ISSUE, first.getId(), null, RemovalScope.DESCRIPTION,
				ReportCategory.HARASSMENT);

		// The clone keeps its picture; the quarantine keeps a copy for the case.
		assertThat(store.has("media/" + MEDIA)).isTrue();
		assertThat(store.has("quarantine/" + CASE + "/media/" + MEDIA)).isTrue();
		assertThat(harassment.otherUses()).isEmpty();

		store.put("media/" + SHARED, "jpeg");
		Issue third = issue("Foto", docWithImage("Dritter", SHARED));
		Issue fourth = issue("Foto, Kopie", docWithImage("Vierter", SHARED));
		Removal sexual = remove(ContentKind.ISSUE, third.getId(), null, RemovalScope.DESCRIPTION, ReportCategory.SEXUAL);

		assertThat(store.has("media/" + SHARED)).isFalse();
		assertThat(sexual.otherUses()).extracting(ContentRef::id).containsExactly(fourth.getId());
		assertThat(clone.getId()).isNotEqualTo(fourth.getId());
	}

	@Test
	void deletingIssuesTakesTheirFilesAndScrubsTheBellOnceForTheRequest() {
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Issue issue = issue("Aufgabe " + i, doc("Text " + i));
			store.put("file-" + i, "bytes");
			attachments.add(issue.getId(), Issue.Attachment.builder().id("f" + i).fileName("f.txt")
					.contentType("text/plain").size(5).objectKey("file-" + i).uploadedAt(Instant.now()).build());
			notifications.save(Notification.builder().userId(reader.getId()).type(Notification.Type.ISSUE_UPDATED)
					.title(issue.getReadableId()).body("Aufgabe " + i).link("/issues/" + issue.getReadableId()).build());
			ids.add(issue.getId());
		}

		issues.deleteAll(ids, owner);

		verify(copiesScrub, times(1)).scrub(anyList(), eq(ScrubReason.DELETED), any());
		for (int i = 0; i < 3; i++) {
			// The files went with their issues, which before HIN-169 they did not.
			assertThat(store.has("file-" + i)).isFalse();
		}
		assertThat(notifications.findAll()).filteredOn(n -> n.getUserId().equals(reader.getId()))
				.isNotEmpty()
				.allSatisfy(n -> assertThat(n.getBody()).isEqualTo(placeholders.deletedText()));
	}
}
