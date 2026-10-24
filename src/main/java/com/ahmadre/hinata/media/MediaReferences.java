package com.ahmadre.hinata.media;

import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.MediaRef;
import com.ahmadre.hinata.storage.ImagePreviewService;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where inline images are referenced. Inline media has no per-entity link, only its URL inside
 * free-form content, and the same image may stand in several documents: a clone shares it, and any
 * member may paste any id. So the one way to know who shows an image is to look at all of them,
 * which the orphan sweep and moderation both do here (HIN-169).
 */
@Component
@RequiredArgsConstructor
public class MediaReferences {

	/** Every {@code /api/v1/media/{uuid}} reference in a chunk of content. */
	private static final Pattern REFERENCE = Pattern.compile("/api/v1/media/([0-9a-fA-F-]{36})");

	/** A collection and the field of it that can embed inline media. */
	public record Source(String collection, String field) {
	}

	/**
	 * Everything that can embed inline media.
	 *
	 * <p>The <em>Doc</em> fields, not the plain ones. Bodies are Lexical documents now, and
	 * {@code description} / {@code text} / {@code content} hold the plain-text projection derived
	 * from them, which is exactly the projection an image contributes nothing to. Scanning those
	 * found no references at all, so every uploaded image looked orphaned and was deleted from the
	 * bucket one grace window after it was embedded. Both are listed: a document written before the
	 * migration still has its markdown in the plain field, and reaping those would be the same data
	 * loss with a longer fuse. The versions of a page count too: an image that only an old version
	 * still shows comes back with that version.
	 */
	static final List<Source> SOURCES = List.of(
			new Source("issues", "descriptionDoc"),
			new Source("issues", "description"),
			new Source("issue_comments", "textDoc"),
			new Source("issue_comments", "text"),
			new Source("articles", "contentDoc"),
			new Source("articles", "content"),
			new Source("article_versions", "contentDoc"));

	private final MongoTemplate mongo;

	/** The media ids [content] references, in the order they first appear. */
	public static Set<String> idsIn(String content) {
		Set<String> ids = new LinkedHashSet<>();
		if (content == null || content.isEmpty()) {
			return ids;
		}
		Matcher matcher = REFERENCE.matcher(content);
		while (matcher.find()) {
			ids.add(matcher.group(1));
		}
		return ids;
	}

	/** The object key an inline image lives under. */
	public static String objectKey(String id) {
		return MediaService.PREFIX + id;
	}

	/** The object key of the generated preview of the image stored under [objectKey]. */
	public static String thumbnailKeyOf(String objectKey) {
		String id = objectKey.startsWith(MediaService.PREFIX) ? objectKey.substring(MediaService.PREFIX.length())
				: objectKey;
		return ImagePreviewService.mediaThumbnailKey(id);
	}

	/** Every media id any document references. */
	Set<String> referencedIds() {
		Set<String> ids = new HashSet<>();
		for (Source source : SOURCES) {
			// Only pull documents that mention the media path at all, and project just that one
			// field, which keeps the scan light on large collections. A literal path, no regex
			// metacharacters, so a plain substring match.
			Query query = new Query(Criteria.where(source.field()).regex("/api/v1/media/"));
			query.fields().include(source.field());
			try (Stream<Document> stream = mongo.stream(query, Document.class, source.collection())) {
				stream.forEach(document -> ids.addAll(idsIn(document.getString(source.field()))));
			}
		}
		return ids;
	}

	/**
	 * The images a removal takes and how (HIN-169): moved where nothing else shows them, copied
	 * where other content still does, so a clone keeps its picture. Sensitive content is moved out
	 * of every document; [otherUses] then names those documents for moderation to look at.
	 */
	public record Split(List<MediaRef> move, List<MediaRef> copy, List<ContentRef> otherUses) {

		public static final Split NONE = new Split(List.of(), List.of(), List.of());
	}

	public Split split(Collection<String> ids, Map<String, Criteria> excluded, boolean sensitive) {
		List<MediaRef> move = new ArrayList<>();
		List<MediaRef> copy = new ArrayList<>();
		List<ContentRef> otherUses = new ArrayList<>();
		for (String id : ids) {
			MediaRef image = MediaRef.of(objectKey(id));
			if (sensitive) {
				move.add(image);
				otherUses.addAll(usesOf(id, excluded));
			}
			else if (usedElsewhere(id, excluded)) {
				copy.add(image);
			}
			else {
				move.add(image);
			}
		}
		return new Split(List.copyOf(move), List.copyOf(copy), List.copyOf(otherUses));
	}

	/** At most this many other uses are named per image; enough for moderation to follow up. */
	static final int USES_MAX = 50;

	/** The content outside [excluded] that shows image [id]. */
	List<ContentRef> usesOf(String id, Map<String, Criteria> excluded) {
		String path = Pattern.quote("/api/v1/media/" + id);
		Set<ContentRef> uses = new LinkedHashSet<>();
		for (Source source : SOURCES) {
			List<Criteria> all = new ArrayList<>();
			all.add(Criteria.where(source.field()).regex(path));
			Criteria skip = excluded.get(source.collection());
			if (skip != null) {
				all.add(new Criteria().norOperator(skip));
			}
			Query query = new Query(new Criteria().andOperator(all)).limit(USES_MAX);
			query.fields().include("_id", "projectId", "issueId", "articleId");
			for (Document found : mongo.find(query, Document.class, source.collection())) {
				uses.add(refOf(source.collection(), found));
			}
		}
		return uses.stream().limit(USES_MAX).toList();
	}

	private static ContentRef refOf(String collection, Document found) {
		String id = String.valueOf(found.get("_id"));
		return switch (collection) {
			case "issues" -> new ContentRef(ContentKind.ISSUE, id, null, found.getString("projectId"));
			case "issue_comments" -> new ContentRef(ContentKind.COMMENT, id, found.getString("issueId"), null);
			case "article_versions" -> new ContentRef(ContentKind.ARTICLE, found.getString("articleId"), null, null);
			default -> new ContentRef(ContentKind.ARTICLE, id, null, found.getString("projectId"));
		};
	}

	/**
	 * Whether a document other than those [excluded] names still shows image [id]. [excluded] maps a
	 * collection to the documents of it that do not count: the content being removed, and the
	 * versions of a page that go with it.
	 */
	public boolean usedElsewhere(String id, Map<String, Criteria> excluded) {
		String path = Pattern.quote("/api/v1/media/" + id);
		for (Source source : SOURCES) {
			List<Criteria> all = new ArrayList<>();
			all.add(Criteria.where(source.field()).regex(path));
			Criteria skip = excluded.get(source.collection());
			if (skip != null) {
				all.add(new Criteria().norOperator(skip));
			}
			Query query = new Query(new Criteria().andOperator(all));
			query.fields().include("_id");
			if (mongo.exists(query, source.collection())) {
				return true;
			}
		}
		return false;
	}
}
