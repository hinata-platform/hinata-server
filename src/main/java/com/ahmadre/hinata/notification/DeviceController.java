package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.auth.CurrentUser;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Registration of the calling user's FCM device tokens for push. The app posts
 * its token after login (and on refresh); it deletes it on sign-out.
 */
@RestController
@RequestMapping("/api/v1/me/devices")
@RequiredArgsConstructor
public class DeviceController {

	private final CurrentUser currentUser;
	private final DeviceTokenRepository devices;

	public record RegisterRequest(@NotBlank String token, String platform) {
	}

	/** Upsert the token for the current user (re-registration refreshes lastSeen). */
	@PostMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void register(@RequestBody RegisterRequest request) {
		String userId = currentUser.requireId();
		Instant now = Instant.now();
		DeviceToken device = devices.findByToken(request.token())
				.map(existing -> {
					existing.setUserId(userId);
					existing.setPlatform(request.platform());
					existing.setLastSeenAt(now);
					return existing;
				})
				.orElseGet(() -> DeviceToken.builder()
						.userId(userId)
						.token(request.token())
						.platform(request.platform())
						.createdAt(now)
						.lastSeenAt(now)
						.build());
		devices.save(device);
	}

	public record UnregisterRequest(@NotBlank String token) {
	}

	/**
	 * Drop a token (sign-out / disabled notifications).
	 *
	 * <p>Takes the token in the body because a Windows registration is a WNS
	 * channel URI — an https URL containing {@code :}, {@code /} and {@code ?},
	 * which cannot travel in a path segment. The path variant below stays for
	 * older app builds.
	 */
	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unregister(@RequestBody UnregisterRequest request) {
		delete(request.token());
	}

	/** Legacy path form — FCM tokens only (they are path-safe). */
	@DeleteMapping("/{token}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unregisterByPath(@PathVariable String token) {
		delete(token);
	}

	private void delete(String token) {
		// Only delete if it belongs to the caller, so one user can't evict another's token.
		devices.findByToken(token)
				.filter(d -> d.getUserId().equals(currentUser.requireId()))
				.ifPresent(d -> devices.deleteByToken(token));
	}
}
