package com.ahmadre.hinata.block;

import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Deletes the blocks a deleted account made and the ones made against it (Art. 17 DSGVO). */
@Slf4j
@Component
@RequiredArgsConstructor
class BlockErasure {

	private final BlockService blocks;

	@EventListener
	public void onUserDeleted(UserService.UserDeletedEvent event) {
		try {
			long removed = blocks.eraseEveryBlockOf(event.userId());
			if (removed > 0) {
				log.info("[blocks] {} block(s) of deleted user {}", removed, event.userId());
			}
		} catch (RuntimeException ex) {
			// The account is already gone; a failure here must not fail the deletion.
			log.warn("[blocks] could not remove the blocks of deleted user {}: {}", event.userId(), ex.toString());
		}
	}
}
