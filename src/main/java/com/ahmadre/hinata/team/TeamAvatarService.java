package com.ahmadre.hinata.team;

import com.ahmadre.hinata.storage.AvatarImages;
import com.ahmadre.hinata.storage.ImagePreviewService;
import com.ahmadre.hinata.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

/**
 * Where a team's avatar is stored and which URL points at it. The picture runs
 * through the shared {@link AvatarImages} pipeline, so a team crest obeys the
 * same limits and is stripped of metadata exactly like a profile photo.
 *
 * <p>Deliberately knows nothing about {@link TeamService}: who may upload or see
 * a team avatar is the controller's question, and keeping this service down to
 * storage + repository lets {@code TeamService.delete} call back into it for
 * orphan cleanup without the two forming a bean cycle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamAvatarService {

	/** Bucket "folder" every team avatar lives under (private, server-proxied). */
	static final String PREFIX = "teams/";

	private final StorageService storage;
	private final TeamRepository teams;
	private final ImagePreviewService previews;

	/**
	 * Object key for a team's avatar, e.g. {@code teams/{teamId}.jpg}. Always
	 * built from the id of a team actually loaded from Mongo, never from a raw
	 * path variable — a caller can then not steer the key anywhere else.
	 */
	static String objectKey(String teamId) {
		return PREFIX + teamId + ".jpg";
	}

	/** Compresses + stores [file] as [team]'s avatar and returns its URL. */
	public String store(Team team, MultipartFile file) {
		byte[] jpeg = AvatarImages.compress(file);
		// Deterministic key: re-uploads overwrite cleanly, no orphans accrue. The
		// bucket stays private; bytes are served back through the controller's
		// proxy, so storage credentials never leave the server.
		storage.putObject(objectKey(team.getId()), jpeg, AvatarImages.CONTENT_TYPE);

		String url = AvatarImages.withBlurHash(urlFor(team.getId()),
				AvatarImages.blurHashOf(previews, jpeg));
		team.setAvatarUrl(url);
		teams.save(team);
		return url;
	}

	public void remove(Team team) {
		storage.delete(objectKey(team.getId()));
		team.setAvatarUrl(null);
		teams.save(team);
	}

	/**
	 * The stored avatar bytes for [team], or empty when none was ever uploaded.
	 *
	 * <p>Also fills in a missing BlurHash on the way out, the way user avatars do:
	 * this is the one moment the server holds the bytes anyway, and the refreshed
	 * URL reaches clients with the next response that mentions this team.
	 */
	public Optional<StorageService.StoredObject> load(Team team) {
		if (!storage.isConfigured()) {
			return Optional.empty();
		}
		Optional<StorageService.StoredObject> object = storage.getObject(objectKey(team.getId()));
		object.ifPresent(stored -> backfillBlurHash(team, stored.data()));
		return object;
	}

	/**
	 * Drops the stored object without touching the team — for the deletion paths,
	 * where the team row is about to disappear and its picture would otherwise
	 * linger unreachable. Best-effort on purpose: an unreachable (or entirely
	 * unconfigured) object store must not abort a deletion the user asked for.
	 */
	public void deleteStoredObject(String teamId) {
		if (teamId == null || !storage.isConfigured()) {
			return;
		}
		try {
			storage.delete(objectKey(teamId));
		}
		catch (RuntimeException ex) {
			log.warn("Deleting avatar of team {} failed: {}", teamId, ex.getMessage());
		}
	}

	private void backfillBlurHash(Team team, byte[] jpeg) {
		String url = team.getAvatarUrl();
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
		team.setAvatarUrl(AvatarImages.withBlurHash(url, hash));
		teams.save(team);
	}

	/** A relative, cache-busted URL the client resolves against its API base. */
	private String urlFor(String teamId) {
		return "/api/v1/teams/" + teamId + "/avatar?v=" + System.currentTimeMillis();
	}
}
