package com.ahmadre.hinata.oauth;

import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Deletes every OAuth grant and unused authorization code of a deleted account (HIN-172,
 * Art. 17 DSGVO): a grant is a refresh token that would otherwise outlive its person.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrantErasure {

	private final OAuthGrantRepository grants;
	private final OAuthAuthorizationCodeRepository codes;

	@EventListener
	public void onUserDeleted(UserService.UserDeletedEvent event) {
		String userId = event.userId();
		try {
			long removed = grants.deleteByUserId(userId) + codes.deleteByUserId(userId);
			if (removed > 0) {
				log.info("[oauth] {} grant(s) and code(s) for deleted user {}", removed, userId);
			}
		}
		catch (RuntimeException ex) {
			// The account is already gone; a failure here must not fail the deletion.
			log.warn("[oauth] could not remove the grants of deleted user {}: {}", userId, ex.toString());
		}
	}
}
