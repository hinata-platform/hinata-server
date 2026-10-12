package com.ahmadre.hinata.notification;

import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Deletes every device token of a deleted account: push tokens (FCM, WNS) and
 * Live Activity tokens with their content keys alike.
 *
 * <p>Until HIN-103 nothing did, and a deleted person's tokens stayed in
 * {@code device_tokens} with their id — Art. 17 DSGVO left undone. One query,
 * no batches: a person holds a handful of devices and at most
 * {@link DeviceController#MAX_LIVE_ACTIVITIES} activities.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceTokenErasure {

	private final DeviceTokenRepository devices;

	@EventListener
	public void onUserDeleted(UserService.UserDeletedEvent event) {
		String userId = event.userId();
		try {
			long removed = devices.deleteByUserId(userId);
			if (removed > 0) {
				log.info("[devices] {} token(s) for deleted user {}", removed, userId);
			}
		}
		catch (RuntimeException ex) {
			// The account is already gone; a failure here must not turn a completed
			// erasure into a failed request.
			log.warn("[devices] could not remove the tokens of deleted user {}: {}", userId, ex.toString());
		}
	}
}
