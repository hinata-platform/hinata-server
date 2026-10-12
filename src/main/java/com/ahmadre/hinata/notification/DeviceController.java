package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.FeatureFlags;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Registration of the calling user's device tokens. The app posts its push
 * token after login (and on refresh) and deletes it on sign-out.
 *
 * <p>Since HIN-103 the same route registers the token of a Live Activity, with
 * {@code kind: liveActivityUpdate}, the app's reference of the activity, the
 * device's FCM token and the activity's content key. A request without
 * {@code kind} is a push token, exactly as before.
 */
@RestController
@RequestMapping("/api/v1/me/devices")
@RequiredArgsConstructor
public class DeviceController {

	/** The flag of the extended time tracking, which owns the timer and so its Live Activity. */
	static final String TIME_FLAG = "advanced_time_tracking";

	/** Live Activity tokens kept per person; the oldest give way. */
	static final int MAX_LIVE_ACTIVITIES = 20;

	/**
	 * How long a Live Activity token lives before its first update tightens it:
	 * the longest a timer is shown anywhere plus the grace after it.
	 */
	static final Duration LIVE_ACTIVITY_TTL = Duration.ofHours(28);

	private static final Pattern ACTIVITY_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

	private final CurrentUser currentUser;
	private final DeviceTokenRepository devices;
	private final LiveActivityKeys keys;
	private final FeatureFlags flags;

	public record RegisterRequest(@NotBlank @Size(max = 4096) String token, @Size(max = 32) String platform,
			@Size(max = 32) String kind, @Size(max = 64) String activityId, @Size(max = 128) String contentKey,
			@Size(max = 4096) String fcmToken) {
	}

	/** Upsert the token for the current user (re-registration refreshes lastSeen). */
	@PostMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void register(@Valid @RequestBody RegisterRequest request) {
		DeviceToken.Kind kind = kindOf(request.kind());
		if (kind == DeviceToken.Kind.LIVE_ACTIVITY_UPDATE) {
			registerLiveActivity(request);
			return;
		}
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

	/**
	 * Registers the token of one Live Activity. Refused while the time module is
	 * off, and while the server has no secret to keep the content key under — a
	 * key stored in clear would protect nothing.
	 */
	private void registerLiveActivity(RegisterRequest request) {
		if (!flags.enabled(TIME_FLAG)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "error.feature.disabled");
		}
		if (!keys.available()) {
			throw ApiException.conflict("error.device.liveActivityUnavailable");
		}
		String activityId = request.activityId();
		byte[] key = contentKeyOf(request.contentKey());
		if (activityId == null || !ACTIVITY_ID.matcher(activityId).matches() || key == null
				|| request.fcmToken() == null || request.fcmToken().isBlank()) {
			throw ApiException.badRequest("error.device.invalid");
		}
		String userId = currentUser.requireId();
		Instant now = Instant.now();
		List<DeviceToken> held = devices.findByUserIdAndKindOrderByCreatedAtAsc(userId,
				DeviceToken.Kind.LIVE_ACTIVITY_UPDATE);
		// The same activity under a rotated token goes; of the others, the oldest
		// give way so that this one makes at most MAX_LIVE_ACTIVITIES.
		List<DeviceToken> others = new ArrayList<>();
		for (DeviceToken old : held) {
			if (old.getToken().equals(request.token())) continue;
			if (activityId.equals(old.getActivityId())) {
				devices.deleteByToken(old.getToken());
			}
			else {
				others.add(old);
			}
		}
		int excess = others.size() - (MAX_LIVE_ACTIVITIES - 1);
		for (int i = 0; i < excess; i++) {
			devices.deleteByToken(others.get(i).getToken());
		}
		// A push token that happens to equal this one is replaced, never merged:
		// the two kinds must not share a document.
		devices.findByToken(request.token()).ifPresent(existing -> devices.deleteByToken(existing.getToken()));
		devices.save(DeviceToken.builder()
				.userId(userId)
				.token(request.token())
				.platform(request.platform())
				.kind(DeviceToken.Kind.LIVE_ACTIVITY_UPDATE)
				.activityId(activityId)
				.fcmToken(request.fcmToken())
				.contentKey(keys.seal(key, userId, activityId))
				.createdAt(now)
				.lastSeenAt(now)
				.expiresAt(now.plus(LIVE_ACTIVITY_TTL))
				.build());
	}

	private static DeviceToken.Kind kindOf(String kind) {
		if (kind == null || kind.isBlank() || "push".equals(kind)) return DeviceToken.Kind.PUSH;
		if ("liveActivityUpdate".equals(kind)) return DeviceToken.Kind.LIVE_ACTIVITY_UPDATE;
		throw ApiException.badRequest("error.device.invalid");
	}

	/** The 32 raw bytes of a Base64 key, or null for anything else. */
	private static byte[] contentKeyOf(String contentKey) {
		if (contentKey == null) return null;
		try {
			byte[] key = Base64.getDecoder().decode(contentKey);
			return key.length == LiveActivityKeys.KEY_BYTES ? key : null;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	public record UnregisterRequest(@NotBlank String token) {
	}

	/**
	 * Drop a token (sign-out / disabled notifications / an ended Live Activity /
	 * background updates switched off).
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
