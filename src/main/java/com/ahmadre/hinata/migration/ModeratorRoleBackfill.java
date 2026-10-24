package com.ahmadre.hinata.migration;

import com.ahmadre.hinata.admin.AdminUserService;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One-time hand-over when the moderation role arrives (HIN-26): every account that is an
 * administrator at that moment also becomes a moderator.
 *
 * <p>Reports go to the moderators, and an instance whose nobody held the role would let them
 * reach nobody. Handing it to the administrators keeps every report answered from the first
 * day; the notice explains the role and recommends handing it on, because running the platform
 * and deciding about the people on it are better kept apart. Separating them is a decision in
 * Admin → Users, made on purpose.
 *
 * <p>Runs once (a marker in {@code migrations}), never fails the start, and only ever adds the
 * role. Each grant is audited with the reason {@code migration} and the person is told.
 */
@Slf4j
@Component
@Order(44)
@RequiredArgsConstructor
public class ModeratorRoleBackfill implements ApplicationRunner {

	static final String MARKER_ID = "moderator-role-from-admins";

	private final UserRepository users;
	private final MigrationMarkers markers;
	private final AdminUserService adminUsers;

	@Override
	public void run(ApplicationArguments args) {
		if (markers.done(MARKER_ID)) return;
		try {
			int granted = 0;
			for (User user : users.findByRolesContaining(Role.ADMIN)) {
				if (user.isModerator()) continue;
				adminUsers.changeRole(user, Role.MODERATOR, true, null, "migration");
				granted++;
			}
			if (granted > 0) {
				log.info("ModeratorRoleBackfill: {} administrator(s) are now also moderators", granted);
			}
			markers.markDone(MARKER_ID, new Document("granted", granted));
		}
		catch (RuntimeException ex) {
			log.warn("ModeratorRoleBackfill failed; will retry on next start", ex);
		}
	}
}
