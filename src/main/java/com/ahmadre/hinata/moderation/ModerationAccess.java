package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The one check for the moderation routes (HIN-26): the stored account must hold
 * {@link Role#MODERATOR}. Read from the account, not from the token, so a role taken away a
 * minute ago is gone already, whatever token the person still holds.
 */
@Component
@RequiredArgsConstructor
public class ModerationAccess {

	private final CurrentUser currentUser;
	private final UserRepository users;

	/** The signed-in person if they currently moderate; 403 otherwise. */
	public User require() {
		User user = currentUser.require();
		if (!user.isModerator()) {
			throw ApiException.forbidden("error.moderation.moderatorOnly");
		}
		return user;
	}

	/** The active moderators, for the notices meant for all of them. */
	public List<User> moderators() {
		return users.findByRolesContainingAndActiveIsTrue(Role.MODERATOR);
	}
}
