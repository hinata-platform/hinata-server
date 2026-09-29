package com.ahmadre.hinata.user;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;

/** The one check for the organisation's routes: the stored account must hold {@link Role#ORG_ADMIN}. */
public final class OrgAdmins {

	private OrgAdmins() {
	}

	/**
	 * The caller, if they are an organisation admin; 403 otherwise. Read from the
	 * stored account, so a role granted a minute ago counts already.
	 */
	public static User require(CurrentUser currentUser) {
		User user = currentUser.require();
		if (!user.isOrgAdmin()) {
			throw ApiException.forbidden("error.org.adminOnly");
		}
		return user;
	}
}
