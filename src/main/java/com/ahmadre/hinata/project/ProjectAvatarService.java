package com.ahmadre.hinata.project;

import com.ahmadre.hinata.storage.AvatarImages;
import com.ahmadre.hinata.storage.ImagePreviewService;
import com.ahmadre.hinata.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

/**
 * Where a project's avatar is stored and which URL points at it. The picture
 * runs through the shared {@link AvatarImages} pipeline, so a project logo obeys
 * the same limits and is stripped of metadata exactly like a profile photo.
 *
 * <p>Deliberately knows nothing about {@link ProjectService}: who may upload or
 * see a project avatar is the controller's question, and keeping this service
 * down to storage + repository lets the deletion cascade call back into it for
 * orphan cleanup without the two forming a bean cycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectAvatarService {

	/** Bucket "folder" every project avatar lives under (private, proxied). */
	static final String PREFIX = "projects/";

	private final StorageService storage;
	private final ProjectRepository projects;
	private final ImagePreviewService previews;

	/**
	 * Object key for a project's avatar, e.g. {@code projects/{projectId}.jpg}.
	 * Always built from the id of a project actually loaded from Mongo, never
	 * from a raw path variable — a caller can then not steer the key elsewhere.
	 */
	static String objectKey(String projectId) {
		return PREFIX + projectId + ".jpg";
	}

	/** Compresses + stores [file] as [project]'s avatar and returns its URL. */
	public String store(Project project, MultipartFile file) {
		byte[] jpeg = AvatarImages.compress(file);
		// Deterministic key: re-uploads overwrite cleanly, no orphans accrue. The
		// bucket stays private; bytes are served back through the controller's
		// proxy, so storage credentials never leave the server.
		storage.putObject(objectKey(project.getId()), jpeg, AvatarImages.CONTENT_TYPE);

		String url = AvatarImages.withBlurHash(urlFor(project.getId()),
				AvatarImages.blurHashOf(previews, jpeg));
		project.setAvatarUrl(url);
		projects.save(project);
		return url;
	}

	public void remove(Project project) {
		storage.delete(objectKey(project.getId()));
		project.setAvatarUrl(null);
		projects.save(project);
	}

	/**
	 * The stored avatar bytes for [project], or empty when none was ever
	 * uploaded.
	 *
	 * <p>Also fills in a missing BlurHash on the way out, the way user avatars do:
	 * this is the one moment the server holds the bytes anyway, and the refreshed
	 * URL reaches clients with the next response that mentions this project.
	 */
	public Optional<StorageService.StoredObject> load(Project project) {
		if (!storage.isConfigured()) {
			return Optional.empty();
		}
		Optional<StorageService.StoredObject> object =
				storage.getObject(objectKey(project.getId()));
		object.ifPresent(stored -> backfillBlurHash(project, stored.data()));
		return object;
	}

	/**
	 * Drops the stored object without touching the project — for the deletion
	 * cascade, where the project row is about to disappear and its picture would
	 * otherwise linger unreachable. Best-effort on purpose: an unreachable (or
	 * entirely unconfigured) object store must not abort a deletion the user
	 * asked for.
	 */
	public void deleteStoredObject(String projectId) {
		if (projectId == null || !storage.isConfigured()) {
			return;
		}
		try {
			storage.delete(objectKey(projectId));
		}
		catch (RuntimeException ex) {
			log.warn("Deleting avatar of project {} failed: {}", projectId, ex.getMessage());
		}
	}

	private void backfillBlurHash(Project project, byte[] jpeg) {
		String url = project.getAvatarUrl();
		if (url == null || AvatarImages.hasBlurHash(url)) {
			return;
		}
		String hash = AvatarImages.blurHashOf(previews, jpeg);
		if (hash == null) {
			return;
		}
		// The existing URL is kept verbatim apart from the new parameter:
		// re-stamping its cache-buster would make every client refetch a picture
		// that has not changed.
		project.setAvatarUrl(AvatarImages.withBlurHash(url, hash));
		projects.save(project);
	}

	/** A relative, cache-busted URL the client resolves against its API base. */
	private String urlFor(String projectId) {
		return "/api/v1/projects/" + projectId + "/avatar?v=" + System.currentTimeMillis();
	}
}
