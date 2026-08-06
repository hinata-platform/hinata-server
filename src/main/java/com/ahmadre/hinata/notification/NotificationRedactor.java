package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.moderation.freeze.FrozenContentService;
import com.ahmadre.hinata.moderation.freeze.FrozenTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Withholds the body of a notification whose source has since been frozen.
 *
 * <h2>Why this exists on the read path and not the write path</h2>
 *
 * <p>{@link NotificationService} persists a verbatim preview of what somebody
 * wrote — {@code actor + ": \"" + preview + "\""} for a mention or a reply, the
 * issue title for an assignment, a comment teaser for a watcher. Those rows are
 * written before anybody reports anything, and they are the one copy of the
 * content the freeze cannot recall: the push and the e-mail have already gone, and
 * no mechanism can unsend them.
 *
 * <p>But the persisted row is served back, indefinitely, from {@code GET
 * /api/v1/notifications} — a controller that is entirely within reach. So the
 * position taken here is the honest one: the notice that already left is gone, and
 * the copy this product keeps serving is not. Every subsequent read of a frozen
 * source's notification returns the title and a withheld body.
 *
 * <h2>How the source is found</h2>
 *
 * <p>From the {@code link}, which is the only pointer a notification carries:
 * {@code /issues/{readableId}} or {@code /issues/{readableId}?comment={id}}. The
 * comment id is a canonical id and matches the registry directly; the readable id
 * is not, so one batched lookup per page resolves the keys on the page to issue
 * ids. That lookup happens <em>only</em> when something is frozen, which is never
 * in a healthy install — so the ordinary notification page pays one set-emptiness
 * test and no query at all.
 *
 * <p>The keys are matched case-sensitively because that is how they are minted and
 * how {@code NotificationService.issueLink} writes them; nothing in between
 * rewrites the case of a link it stored itself.
 *
 * <p>Only the body is withheld. Titles are left alone because they are built from
 * the readable id and the actor's name — "New comment on HIN-42", "X mentioned you
 * in HIN-42" — and carry no authored content; blanking them too would leave a bell
 * entry that says nothing at all where the recipient remembers one that did.
 *
 * <p>Deriving the source rather than storing it on the row is deliberate. A new
 * {@code sourceId} field would be null on every notification written before the
 * migration, which is every notification in every existing install — and those are
 * precisely the rows a freeze raised tomorrow has to cover.
 */
@Service
@RequiredArgsConstructor
public class NotificationRedactor {

	/** {@code /issues/HIN-42} with an optional {@code ?comment=<id>}. */
	private static final Pattern SOURCE =
			Pattern.compile("^/issues/([^/?#]+)(?:\\?comment=([^&#]+))?");

	private final MongoTemplate mongo;
	private final FrozenContentService frozen;
	private final MessageSource messages;

	/**
	 * Replaces the body of every notification in [page] whose source is frozen.
	 *
	 * <p>Mutates the entities in place rather than returning copies, because the
	 * caller maps them straight into a response DTO and the alternative is a second
	 * list of the same objects. They are not saved — the stored row keeps its text,
	 * which matters: a freeze can be released, and a redaction written into the
	 * database would not come back.
	 */
	public void redact(List<Notification> page) {
		if (page == null || page.isEmpty() || !frozen.anythingFrozen()) {
			return;
		}
		Set<String> frozenComments = frozen.frozenIds(FrozenTargetType.COMMENT);
		Set<String> frozenIssues = frozen.frozenIds(FrozenTargetType.ISSUE);
		if (frozenComments.isEmpty() && frozenIssues.isEmpty()) {
			return;
		}
		List<Matcher> sources = new ArrayList<>();
		Set<String> keysOnPage = new HashSet<>();
		for (Notification notification : page) {
			Matcher matcher = matcher(notification.getLink());
			sources.add(matcher);
			if (matcher != null && !frozenIssues.isEmpty()) {
				keysOnPage.add(matcher.group(1));
			}
		}
		Set<String> frozenKeys = frozenKeysAmong(keysOnPage, frozenIssues);
		String withheld = null;
		for (int index = 0; index < page.size(); index++) {
			Notification notification = page.get(index);
			Matcher matcher = sources.get(index);
			if (matcher == null
					|| notification.getBody() == null || notification.getBody().isBlank()) {
				continue;
			}
			String commentId = matcher.group(2);
			boolean hidden = (commentId != null && frozenComments.contains(commentId))
					|| frozenKeys.contains(matcher.group(1));
			if (hidden) {
				if (withheld == null) {
					withheld = withheldBody();
				}
				notification.setBody(withheld);
			}
		}
	}

	/** As {@link #redact(List)}, for one row. */
	public void redact(Notification notification) {
		if (notification != null) {
			redact(List.of(notification));
		}
	}

	private static Matcher matcher(String link) {
		if (link == null || link.isBlank()) {
			return null;
		}
		Matcher matcher = SOURCE.matcher(link);
		return matcher.find() ? matcher : null;
	}

	/**
	 * Which of the readable keys on this page belong to a frozen issue.
	 *
	 * <p>The lookup runs from the page inward rather than from the registry outward,
	 * and that direction is chosen for two reasons. It is bounded by the page (at
	 * most a hundred keys) instead of by the registry's cap; and it filters on
	 * {@code readableId}, a plain string, rather than on {@code _id}, which Mongo
	 * stores as an {@code ObjectId} — an untyped {@code _id $in} of Java strings
	 * matches nothing at all in production while passing happily in a test whose
	 * fixtures use readable ids.
	 *
	 * <p>Read as raw documents so nothing maps an issue body into memory: this is the
	 * one class whose entire job is to stop a body being served.
	 */
	private Set<String> frozenKeysAmong(Set<String> keysOnPage, Set<String> frozenIssueIds) {
		if (keysOnPage.isEmpty() || frozenIssueIds.isEmpty()) {
			return Set.of();
		}
		Query query = new Query(Criteria.where("readableId").in(keysOnPage));
		query.fields().include("readableId");
		Set<String> keys = new HashSet<>();
		for (Document row : mongo.find(query, Document.class, "issues")) {
			Object id = row.get("_id");
			String readableId = row.getString("readableId");
			if (id != null && readableId != null && frozenIssueIds.contains(String.valueOf(id))) {
				keys.add(readableId);
			}
		}
		return keys;
	}

	/**
	 * The replacement text, in the reader's language.
	 *
	 * <p>Resolved against the request locale rather than the language the row was
	 * written in. The bodies are persisted per-recipient in that recipient's
	 * language, so this is the same reader either way; the request locale is simply
	 * the one the product has at read time, and it is what every {@code ApiException}
	 * message already uses.
	 */
	private String withheldBody() {
		return messages.getMessage("notification.bodyWithheld", null,
				"notification.bodyWithheld", LocaleContextHolder.getLocale());
	}
}
