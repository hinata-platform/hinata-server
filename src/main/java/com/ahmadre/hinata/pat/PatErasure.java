package com.ahmadre.hinata.pat;

import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Deletes every access token of a deleted account (HIN-172, Art. 17 DSGVO). Until then a deleted
 * person's tokens stayed, revoked or not, with their id and names.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatErasure {

	private final PersonalAccessTokenRepository tokens;

	@EventListener
	public void onUserDeleted(UserService.UserDeletedEvent event) {
		String userId = event.userId();
		try {
			long removed = tokens.deleteByUserId(userId);
			if (removed > 0) {
				log.info("[pat] {} token(s) for deleted user {}", removed, userId);
			}
		}
		catch (RuntimeException ex) {
			// The account is already gone; a failure here must not fail the deletion.
			log.warn("[pat] could not remove the tokens of deleted user {}: {}", userId, ex.toString());
		}
	}
}
