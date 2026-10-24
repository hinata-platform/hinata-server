package com.ahmadre.hinata.moderation.quarantine;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.MediaRef;
import com.ahmadre.hinata.content.Quarantine;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.VersionCopy;
import com.ahmadre.hinata.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The quarantine of the moderation module (HIN-169): objects copied under
 * {@code quarantine/<caseId>/}, the removed fields in {@code moderation_quarantine}, the versions of
 * a page in {@code moderation_quarantine_items}.
 *
 * <p>Objects are copied inside the store, never through this application. An original that does
 * not exist is skipped (there is nothing of it to keep); a copy the store refuses stops the removal
 * before anything was rewritten.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MongoQuarantine implements Quarantine {

	/** How often deleting an original is tried before it is left for the follow-up. */
	static final int DELETE_ATTEMPTS = 3;

	/** Versions read and written per round, so a hundred large ones never sit in memory at once. */
	static final int VERSION_BATCH = 10;

	private final MongoTemplate mongo;
	private final StorageService storage;

	@Override
	public Held hold(String caseId, ContentRef ref, RemovalScope scope, Map<String, Object> fields,
			List<MediaRef> move, List<MediaRef> copy) {
		List<HeldObject> held = new ArrayList<>();
		try {
			copyAll(caseId, move, true, held);
			copyAll(caseId, copy, false, held);
			QuarantinedContent row = mongo.insert(QuarantinedContent.builder()
					.caseId(caseId)
					.ref(ref)
					.scope(scope)
					.fields(new LinkedHashMap<>(fields))
					.objects(held.stream().map(MongoQuarantine::toStored).toList())
					.createdAt(Instant.now())
					.build());
			return new Held(row.getId(), caseId, List.copyOf(held));
		}
		catch (RuntimeException ex) {
			// Nothing of a removal that could not be recorded may stay behind.
			held.forEach(object -> storage.delete(object.quarantineKey()));
			throw ex;
		}
	}

	private void copyAll(String caseId, List<MediaRef> objects, boolean move, List<HeldObject> held) {
		Set<String> seen = new LinkedHashSet<>();
		held.forEach(object -> seen.add(object.original().objectKey()));
		for (MediaRef object : objects) {
			String key = object.objectKey();
			if (key == null || key.isBlank() || !seen.add(key) || !storage.isConfigured() || !storage.exists(key)) {
				continue;
			}
			String quarantineKey = Quarantine.keyFor(caseId, key);
			if (!storage.copyObject(key, quarantineKey)) {
				throw new ApiException(HttpStatus.BAD_GATEWAY, "error.storage.unavailable");
			}
			held.add(new HeldObject(object, quarantineKey, move));
		}
	}

	private static QuarantinedObject toStored(HeldObject object) {
		MediaRef original = object.original();
		return new QuarantinedObject(original.objectKey(), object.quarantineKey(), original.fileName(),
				original.contentType(), original.size(), object.move(), false);
	}

	@Override
	public void keepVersions(Held held, List<VersionCopy> versions) {
		if (versions.isEmpty()) {
			return;
		}
		mongo.insertAll(versions.stream()
				.map(version -> QuarantinedVersion.builder()
						.quarantineId(held.id())
						.number(version.number())
						.title(version.title())
						.contentDoc(version.contentDoc())
						.editorId(version.editorId())
						.savedAt(version.savedAt())
						.build())
				.toList());
	}

	@Override
	public void discard(Held held) {
		held.objects().forEach(object -> storage.delete(object.quarantineKey()));
		mongo.remove(Query.query(Criteria.where("quarantineId").is(held.id())), QuarantinedVersion.class);
		mongo.remove(Query.query(Criteria.where("_id").is(held.id())), QuarantinedContent.class);
	}

	@Override
	public List<String> deleteOriginals(Held held) {
		List<String> moved = held.objects().stream()
				.filter(HeldObject::move)
				.map(object -> object.original().objectKey())
				.toList();
		List<String> pending = deleteOriginals(moved);
		QuarantinedContent row = mongo.findById(held.id(), QuarantinedContent.class);
		if (row != null && row.getObjects() != null) {
			row.setObjects(row.getObjects().stream()
					.map(object -> object.move() && !pending.contains(object.originalKey()) ? object.gone() : object)
					.toList());
			mongo.save(row);
		}
		return pending;
	}

	@Override
	public List<String> deleteOriginals(Collection<String> keys) {
		List<String> pending = new ArrayList<>();
		for (String key : keys) {
			if (!deleted(key)) {
				pending.add(key);
			}
		}
		return pending;
	}

	private boolean deleted(String key) {
		if (!storage.isConfigured()) {
			return true;
		}
		for (int attempt = 1; attempt <= DELETE_ATTEMPTS; attempt++) {
			if (storage.deleteVerified(key)) {
				return true;
			}
			log.warn("Original {} still there after delete attempt {}", key, attempt);
		}
		return false;
	}

	@Override
	public boolean restoreObjects(String quarantineId) {
		QuarantinedContent row = mongo.findById(quarantineId, QuarantinedContent.class);
		if (row == null || row.getObjects() == null) {
			return row != null;
		}
		boolean all = true;
		for (QuarantinedObject object : row.getObjects()) {
			// A copied original never left; only a moved one has to come back.
			if (object.move() && !storage.copyObject(object.quarantineKey(), object.originalKey())) {
				all = false;
			}
		}
		return all;
	}

	@Override
	public void forEachVersions(String quarantineId, Consumer<List<VersionCopy>> batch) {
		int after = Integer.MIN_VALUE;
		while (true) {
			List<QuarantinedVersion> page = mongo.find(Query.query(Criteria.where("quarantineId").is(quarantineId)
							.and("number").gt(after))
					.with(Sort.by("number"))
					.limit(VERSION_BATCH), QuarantinedVersion.class);
			if (page.isEmpty()) {
				return;
			}
			batch.accept(page.stream()
					.map(version -> new VersionCopy(version.getNumber(), version.getTitle(), version.getContentDoc(),
							version.getEditorId(), version.getSavedAt()))
					.toList());
			after = page.getLast().getNumber();
		}
	}
}
