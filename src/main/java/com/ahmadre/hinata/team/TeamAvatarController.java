package com.ahmadre.hinata.team;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Map;

/**
 * A team's avatar. Kept out of {@link TeamController} so the image concern stays
 * legible on its own, but under the same authority: uploading or removing needs
 * manage rights (platform admin or Team-Admin), reading needs team visibility.
 *
 * <p>Unlike a user's avatar, the read endpoint is <b>authenticated</b>. Profile
 * pictures show up in public contexts and are permit-listed for that reason; a
 * team is internal, and an anonymous byte proxy would tell the whole internet
 * which teams exist and what their crests look like. The bytes are cached
 * {@code private} for the same reason — a shared proxy must not keep a copy.
 */
@Tag(name = "Teams")
@RestController
@RequestMapping("/api/v1/teams/{id}/avatar")
@RequiredArgsConstructor
public class TeamAvatarController {

	private final TeamService teamService;
	private final TeamAvatarService avatars;
	private final CurrentUser currentUser;

	@Operation(summary = "Upload a team avatar (compressed server-side)")
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, String> upload(@PathVariable String id,
			@RequestParam("file") MultipartFile file) {
		User user = currentUser.require();
		Team team = teamService.get(id);
		teamService.assertManage(team, user);
		return Map.of("avatarUrl", avatars.store(team, file));
	}

	@Operation(summary = "Remove a team's avatar")
	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@PathVariable String id) {
		User user = currentUser.require();
		Team team = teamService.get(id);
		teamService.assertManage(team, user);
		avatars.remove(team);
	}

	/**
	 * Streams the stored avatar back. Visibility is asserted <em>before</em> the
	 * object is looked up, so the 404 for "no avatar uploaded" can never be used
	 * to probe teams the caller may not see — they get 403 either way.
	 */
	@Operation(summary = "Fetch a team's avatar")
	@GetMapping
	public ResponseEntity<byte[]> get(@PathVariable String id) {
		User user = currentUser.require();
		Team team = teamService.get(id);
		teamService.assertVisible(team, user);
		StorageService.StoredObject avatar = avatars.load(team)
				.orElseThrow(() -> ApiException.notFound("avatar"));
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(avatar.contentType()))
				.cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
				.body(avatar.data());
	}
}
