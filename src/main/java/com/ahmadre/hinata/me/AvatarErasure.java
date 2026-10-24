package com.ahmadre.hinata.me;

import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Deletes the uploaded picture of a deleted account (HIN-172, Art. 17 DSGVO). It lies under a
 * key made of the person's id, so nothing else ever finds it again to delete it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AvatarErasure {

	private final StorageService storage;

	@EventListener
	public void onUserDeleted(UserService.UserDeletedEvent event) {
		String userId = event.userId();
		if (!storage.isConfigured()) {
			return;
		}
		try {
			storage.delete(AvatarService.objectKey(userId));
		}
		catch (RuntimeException ex) {
			// The account is already gone; a failure here must not fail the deletion.
			log.warn("[avatar] could not remove the picture of deleted user {}: {}", userId, ex.toString());
		}
	}
}
