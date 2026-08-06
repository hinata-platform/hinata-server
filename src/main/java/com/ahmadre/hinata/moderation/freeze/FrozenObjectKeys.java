package com.ahmadre.hinata.moderation.freeze;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleRepository;
import com.ahmadre.hinata.issue.Issue;
import com.ahmadre.hinata.issue.IssueComment;
import com.ahmadre.hinata.issue.IssueCommentRepository;
import com.ahmadre.hinata.issue.IssueRepository;
import com.ahmadre.hinata.me.AvatarService;
import com.ahmadre.hinata.media.MediaReferences;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Every stored object a freeze target owns, resolved from the target alone.
 *
 * <h2>Why this is a collaborator and not two copies</h2>
 *
 * <p>There are two ways a freeze is raised — a user's report, and an admin naming
 * a target by hand — and they used to answer this question differently. The report
 * path resolved keys inside {@code ContentReportService.resolve}, where the entity
 * was already in hand; the admin path passed {@code List.of()} and therefore froze
 * nothing but the row. A hand-frozen issue kept every attachment downloadable, and
 * a hand-frozen attachment or account was a complete no-op — the freeze existed in
 * the registry and restricted not one byte.
 *
 * <p>Both now ask here, so the two paths cannot diverge again. The lookups are
 * deliberately <b>ACL-free</b>: the report path has already proven the reporter's
 * access before it gets this far, and the admin path is admin-only by route. More
 * to the point, an ACL check here would break re-freezing — {@code getForUser}
 * answers 404 for something already frozen, which is exactly the target an
 * operator most often needs to widen.
 *
 * <h2>Inline images</h2>
 *
 * <p>{@link FrozenContent#getObjectKeys()} always claimed to hold "an inline image
 * embedded in the frozen body" and nothing extracted them, so a frozen issue whose
 * description embeds {@code /api/v1/media/{id}} left those bytes served to anyone
 * who kept the URL — and the media route takes no viewer at all, so there was
 * nothing else to stop them. The extraction is
 * {@link MediaReferences#objectKeysIn}, shared with the orphan sweep rather than
 * written a second time: two regexes for "what does this body embed" is two
 * answers that drift, and the direction they drift in here is bytes the sweep
 * deletes while the freeze believes it is preserving them.
 *
 * <p>Both the Lexical document and the plain-text field beside it are scanned, for
 * the reason {@code MediaGarbageCollector.SOURCES} gives: a body written before the
 * Lexical migration still carries its markdown in the plain field.
 *
 * <h2>What it does not do</h2>
 *
 * <p>Best-effort, and it never throws. A freeze that cannot enumerate every byte
 * still has to happen — the entity row is the greater part of it, and refusing to
 * freeze at all because one lookup failed would trade a partial restriction for
 * none. Failures are logged at {@code WARN} naming the target and never the
 * content.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrozenObjectKeys {

	private final IssueRepository issues;
	private final IssueCommentRepository comments;
	private final ArticleRepository articles;

	/**
	 * The storage keys [targetId] of [type] owns.
	 *
	 * @param contextId owning issue of an {@link FrozenTargetType#ATTACHMENT},
	 *                  ignored for every other kind — an attachment is a subdocument
	 *                  and cannot be located by id alone
	 */
	public List<String> of(FrozenTargetType type, String targetId, String contextId) {
		if (type == null || targetId == null || targetId.isBlank()) {
			return List.of();
		}
		try {
			return switch (type) {
				case ISSUE -> ofIssue(issues.findById(targetId).orElse(null));
				case COMMENT -> ofComment(comments.findById(targetId).orElse(null));
				case ARTICLE -> ofArticle(articles.findById(targetId).orElse(null));
				case ATTACHMENT -> ofAttachment(targetId, contextId);
				// The avatar key is derived, not stored, so it resolves whether or not
				// the account ever uploaded one — freezing a key with no object behind
				// it costs a row and closes the race where one is uploaded afterwards.
				case USER -> List.of(AvatarService.objectKeyFor(targetId));
				// The key *is* the target, and freezing it already writes that row.
				// There is nothing an object owns beyond itself — that is what makes it
				// the terminal kind.
				case OBJECT -> List.of();
			};
		}
		catch (RuntimeException ex) {
			log.warn("Could not resolve the stored objects of {} {} — the freeze proceeds without "
					+ "them: {}", type, targetId, ex.toString());
			return List.of();
		}
	}

	private static List<String> ofIssue(Issue issue) {
		if (issue == null) {
			return List.of();
		}
		Set<String> keys = new LinkedHashSet<>();
		if (issue.getAttachments() != null) {
			for (Issue.Attachment attachment : issue.getAttachments()) {
				add(keys, attachment.getObjectKey());
			}
		}
		keys.addAll(MediaReferences.objectKeysIn(issue.getDescriptionDoc(), issue.getDescription()));
		return List.copyOf(keys);
	}

	private static List<String> ofComment(IssueComment comment) {
		if (comment == null) {
			return List.of();
		}
		Set<String> keys = new LinkedHashSet<>();
		if (comment.getVoice() != null) {
			add(keys, comment.getVoice().getObjectKey());
		}
		keys.addAll(MediaReferences.objectKeysIn(comment.getTextDoc(), comment.getText()));
		return List.copyOf(keys);
	}

	private static List<String> ofArticle(Article article) {
		if (article == null) {
			return List.of();
		}
		return List.copyOf(
				MediaReferences.objectKeysIn(article.getContentDoc(), article.getContent()));
	}

	/**
	 * The one attachment's bytes.
	 *
	 * <p>Only that attachment, and not the issue's others: an attachment is a target
	 * in its own right precisely so a moderator can restrict one file without taking
	 * the whole ticket — including its unrelated attachments — offline.
	 */
	private List<String> ofAttachment(String attachmentId, String contextId) {
		if (contextId == null || contextId.isBlank()) {
			log.warn("Freezing attachment {} without its owning issue — its bytes stay served. "
					+ "An attachment is a subdocument and cannot be located by id alone.", attachmentId);
			return List.of();
		}
		Optional<Issue> issue = issues.findById(contextId);
		if (issue.isEmpty() || issue.get().getAttachments() == null) {
			return List.of();
		}
		return issue.get().getAttachments().stream()
				.filter(attachment -> attachmentId.equals(attachment.getId()))
				.map(Issue.Attachment::getObjectKey)
				.filter(key -> key != null && !key.isBlank())
				.toList();
	}

	private static void add(Set<String> keys, String key) {
		if (key != null && !key.isBlank()) {
			keys.add(key);
		}
	}
}
