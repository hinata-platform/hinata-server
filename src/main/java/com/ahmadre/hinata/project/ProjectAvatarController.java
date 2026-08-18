package com.ahmadre.hinata.project;

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
 * A project's avatar. Kept out of {@link ProjectController} so the image concern
 * stays legible on its own, but under the same authority: uploading or removing
 * is a settings change (platform admin or project lead), reading only needs the
 * visibility every member already has.
 *
 * <p>Unlike a user's avatar, the read endpoint is <b>authenticated</b>. Profile
 * pictures show up in public contexts and are permit-listed for that reason; a
 * project is internal, and an anonymous byte proxy would tell the whole internet
 * which projects exist and what their logos look like. The bytes are cached
 * {@code private} for the same reason — a shared proxy must not keep a copy.
 */
@Tag(name = "Projects")
@RestController
@RequestMapping("/api/v1/projects/{id}/avatar")
@RequiredArgsConstructor
public class ProjectAvatarController {

	private final ProjectService projectService;
	private final ProjectAvatarService avatars;
	private final CurrentUser currentUser;

	@Operation(summary = "Upload a project avatar (compressed server-side)")
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, String> upload(@PathVariable String id,
			@RequestParam("file") MultipartFile file) {
		User user = currentUser.require();
		Project project = projectService.get(id);
		projectService.assertLeadOrAdmin(project, user);
		return Map.of("avatarUrl", avatars.store(project, file));
	}

	@Operation(summary = "Remove a project's avatar")
	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@PathVariable String id) {
		User user = currentUser.require();
		Project project = projectService.get(id);
		projectService.assertLeadOrAdmin(project, user);
		avatars.remove(project);
	}

	/**
	 * Streams the stored avatar back. Reach is asserted <em>before</em> the object
	 * is looked up (via {@code assertMember}, i.e. {@link ProjectReach#canSee}), so
	 * the 404 for "no avatar uploaded" can never be used to probe projects the
	 * caller may not see — they get 403 either way.
	 */
	@Operation(summary = "Fetch a project's avatar")
	@GetMapping
	public ResponseEntity<byte[]> get(@PathVariable String id) {
		User user = currentUser.require();
		Project project = projectService.get(id);
		projectService.assertMember(project, user);
		StorageService.StoredObject avatar = avatars.load(project)
				.orElseThrow(() -> ApiException.notFound("avatar"));
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(avatar.contentType()))
				.cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
				.body(avatar.data());
	}
}
