package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.content.ContentCopies;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.CopySource;
import com.ahmadre.hinata.content.Placeholders;
import com.ahmadre.hinata.content.ScrubReason;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The bell (HIN-169). A notification copies the words it is about: a teaser of a comment, an
 * issue's title, a line of its description. One written since it kept its source is found by that;
 * an older one by the link to the issue it carries and the text it copied.
 *
 * <p>The notice itself stays, only the copied words go: somebody was told something happened,
 * which is still true. A held mail is rendered from these rows later, so it is scrubbed with them.
 */
@Component
@RequiredArgsConstructor
class NotificationCopies implements ContentCopies {

	/** The notices that quote a comment. */
	private static final Set<Notification.Type> COMMENT_TYPES = EnumSet.of(Notification.Type.ISSUE_COMMENTED,
			Notification.Type.MENTION, Notification.Type.COMMENT_REPLY);

	/** How much of a removed description an old notice is recognised by. */
	private static final int DESCRIPTION_PROBE = 60;

	private static final int BATCH = 500;

	/** Previews one search for older notices looks for at once, and issues one write covers. */
	private static final int PER_QUERY = 50;

	private final MongoTemplate mongo;
	private final NotificationService notifications;

	@Override
	public void scrub(List<CopySource> sources, ScrubReason reason, Placeholders placeholders) {
		List<CopySource> comments = ofKind(sources, ContentKind.COMMENT);
		if (!comments.isEmpty()) {
			scrubComments(comments, placeholders.textFor(reason));
		}
		List<CopySource> issues = ofKind(sources, ContentKind.ISSUE);
		if (reason == ScrubReason.DELETED) {
			scrubDeletedIssues(issues, placeholders.deletedText());
			return;
		}
		issues.forEach(source -> scrubRemovedIssue(source, placeholders));
	}

	private static List<CopySource> ofKind(List<CopySource> sources, ContentKind kind) {
		return sources.stream().filter(source -> source.ref().kind() == kind).toList();
	}

	/**
	 * The notices quoting [comments]: one write for those that kept their source, and per issue
	 * one for the older ones, found by the very previews they quote.
	 */
	private void scrubComments(List<CopySource> comments, String placeholder) {
		Update body = Update.update("body", placeholder);
		mongo.updateMulti(Query.query(Criteria.where("sourceId").in(comments.stream().map(source -> source.ref().id())
				.toList())), body, Notification.class);
		Map<List<String>, List<String>> previewsByIssue = new LinkedHashMap<>();
		for (CopySource source : comments) {
			if (source.textDoc() == null || source.issueKeys().isEmpty()) {
				continue;
			}
			String preview = notifications.preview(source.textDoc());
			if (!preview.isBlank()) {
				previewsByIssue.computeIfAbsent(source.issueKeys(), keys -> new ArrayList<>()).add(Pattern.quote(preview));
			}
		}
		previewsByIssue.forEach((keys, previews) -> {
			for (List<String> some : chunks(previews)) {
				mongo.updateMulti(Query.query(new Criteria().andOperator(linkTo(keys),
						Criteria.where("type").in(COMMENT_TYPES)
								.and("sourceId").exists(false)
								.and("body").regex(String.join("|", some)))), body, Notification.class);
			}
		});
	}

	/** Nothing of a deleted issue stays readable on the bell; that something happened does. */
	private void scrubDeletedIssues(List<CopySource> issues, String deletedText) {
		List<CopySource> linked = issues.stream().filter(source -> !source.issueKeys().isEmpty()).toList();
		for (List<CopySource> some : chunks(linked)) {
			List<String> keys = some.stream().flatMap(source -> source.issueKeys().stream()).toList();
			mongo.updateMulti(Query.query(new Criteria().andOperator(linkTo(keys),
					Criteria.where("body").ne(deletedText))), Update.update("body", deletedText), Notification.class);
		}
	}

	private void scrubRemovedIssue(CopySource source, Placeholders placeholders) {
		if (source.issueKeys().isEmpty()) {
			return;
		}
		Criteria about = linkTo(source.issueKeys());
		if (source.title() != null && !source.title().isBlank()) {
			replaceIn(about, source.title(), placeholders.removedTitle());
		}
		if (source.textDoc() != null) {
			List<Criteria> quoting = new ArrayList<>();
			quoting.add(Criteria.where("sourceId").is(source.ref().id()).and("type").is(Notification.Type.MENTION));
			String preview = notifications.preview(source.textDoc());
			if (!preview.isBlank()) {
				String probe = preview.length() > DESCRIPTION_PROBE ? preview.substring(0, DESCRIPTION_PROBE) : preview;
				quoting.add(Criteria.where("body").regex(Pattern.quote(probe)));
			}
			mongo.updateMulti(Query.query(new Criteria().andOperator(about,
							new Criteria().orOperator(quoting))),
					Update.update("body", placeholders.removedText()), Notification.class);
		}
	}

	private static <T> List<List<T>> chunks(List<T> items) {
		List<List<T>> chunks = new ArrayList<>();
		for (int from = 0; from < items.size(); from += PER_QUERY) {
			chunks.add(items.subList(from, Math.min(items.size(), from + PER_QUERY)));
		}
		return chunks;
	}

	/** Puts [placeholder] wherever a notice about the issue spells [words], in its title or body. */
	private void replaceIn(Criteria about, String words, String placeholder) {
		String quoted = Pattern.quote(words);
		Query query = Query.query(new Criteria().andOperator(about, new Criteria().orOperator(
				Criteria.where("title").regex(quoted), Criteria.where("body").regex(quoted))));
		query.fields().include("title", "body");
		BulkOperations bulk = null;
		int pending = 0;
		try (Stream<Document> rows = mongo.stream(query, Document.class, "notifications")) {
			for (Document row : (Iterable<Document>) rows::iterator) {
				if (bulk == null) {
					bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, Notification.class);
				}
				Update update = new Update();
				for (String field : List.of("title", "body")) {
					String value = row.getString(field);
					if (value != null && value.contains(words)) {
						update.set(field, value.replace(words, placeholder));
					}
				}
				bulk.updateOne(Query.query(Criteria.where("_id").is(row.get("_id"))), update);
				if (++pending == BATCH) {
					bulk.execute();
					bulk = null;
					pending = 0;
				}
			}
		}
		if (bulk != null) {
			bulk.execute();
		}
	}

	/**
	 * Notices that link to the issue under any key it went by: the plain link, or the link with a
	 * query such as a comment. {@code ACME-4} never reaches {@code ACME-40}.
	 */
	private static Criteria linkTo(List<String> keys) {
		List<Criteria> links = new ArrayList<>();
		for (String key : keys) {
			String link = "/issues/" + key;
			links.add(Criteria.where("link").is(link));
			links.add(Criteria.where("link").regex("^" + Pattern.quote(link + "?")));
		}
		return new Criteria().orOperator(links);
	}
}
